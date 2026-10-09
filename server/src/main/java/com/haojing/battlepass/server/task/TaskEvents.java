package com.haojing.battlepass.server.task;

import com.haojing.battlepass.common.ModConstants;
import com.haojing.battlepass.server.event.RandomEventParticipationType;
import com.haojing.battlepass.server.milestone.MilestoneMetric;
import com.haojing.battlepass.server.time.TimeUtil;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityCombatEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用途：把 Minecraft 的游戏行为翻译成任务可以判定的"上下文"，并交给 {@link TaskProgressTracker}。
 * 覆盖需求文档 §5.6 的事件清单。
 *
 * <p>各行为的接入方式（均经实测确认，未凭印象）：
 * <ul>
 *   <li><b>破坏方块</b>：Fabric 事件 {@code PlayerBlockBreakEvents.AFTER}（有现成事件，不写 Mixin）</li>
 *   <li><b>击杀实体</b>：Fabric 事件 {@code ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY}。
 *       该事件给出的实体是"直接击杀者"，因此宠物/傀儡杀人时传进来的是那只狼本身而不是主人，
 *       §5.8「只统计玩家本人行为」天然成立，无需额外判断。</li>
 *   <li><b>放置方块 / 钓鱼 / 交易</b>：Fabric 没有对应事件，由三个 Mixin 回调进来
 *       （注入点与判据见各 Mixin 类注释）</li>
 *   <li><b>群系 / 维度 / 坐标</b>：无服务端事件，按 §5.6 的规定用每 20 tick 轮询比对</li>
 * </ul>
 *
 * <p>为什么用静态持有 tracker 而不是实例：Mixin 回调是没有实例上下文的静态调用点，
 * 只能通过一个受控的静态入口把事件送进来。这里用 volatile 保证初始化后对所有线程可见。
 */
public final class TaskEvents {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModConstants.MOD_ID_SERVER);

    /** 需求文档 §13：轮询类检测统一每 20 tick 一次。 */
    private static final int POLL_INTERVAL_TICKS = 20;

    private static volatile TaskProgressTracker tracker;

    /** 里程碑统计出口（阶段 6）；null 表示不统计。 */
    private static volatile com.haojing.battlepass.server.milestone.MetricSink metricSink;

    /** 随机事件（阶段 6）；null 表示不做参与判定。 */
    private static volatile com.haojing.battlepass.server.event.RandomEventService eventService;

    private static int pollCounter;

    /**
     * 每个玩家上一轮轮询中"已被位置条件满足"的任务 ID 集合。
     *
     * <p>为什么需要它：位置类任务必须按"条件由假变真"计数 —— 站在 Y≥120 的地方一小时
     * 也只算抵达一次，而不是每 20 tick 加一次。没有这个记忆就只能反复计数。
     */
    private static final Map<UUID, Set<String>> latestSatisfied = new ConcurrentHashMap<>();

    private TaskEvents() {
    }

    /** 注册全部监听。应在服务端初始化时调用一次。 */
    public static void register(TaskProgressTracker progressTracker) {
        register(progressTracker, null, null);
    }

    /**
     * 注册全部监听，并把行为同时上报给里程碑统计与随机事件的参与判定
     * （需求文档 §10：里程碑统计口径、随机事件参与条件；§13：自增计数器）。
     *
     * <p>为什么把这两个上报放在同一处 dispatch：每种行为（破坏、放置、击杀、钓鱼、交易）
     * 在服务端只有这一个接入点。如果让里程碑与随机事件各自再注册一遍事件监听，
     * 就会出现"同一个行为被统计两次"或"某处漏了某个行为"的经典分叉。
     *
     * @param progressTracker 任务进度
     * @param metrics         里程碑统计出口；null 表示不统计
     * @param randomEvents    随机事件；null 表示不参与判定
     */
    public static void register(TaskProgressTracker progressTracker,
                               com.haojing.battlepass.server.milestone.MetricSink metrics,
                               com.haojing.battlepass.server.event.RandomEventService randomEvents) {
        tracker = progressTracker;
        metricSink = metrics;
        eventService = randomEvents;

        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            if (!(player instanceof ServerPlayerEntity serverPlayer) || world.isClient()) {
                return;
            }

            String blockId = Registries.BLOCK.getId(state.getBlock()).toString();
            report(serverPlayer, MilestoneMetric.BLOCKS_BROKEN, RandomEventParticipationType.BREAK_BLOCK);
            dispatch(serverPlayer, TaskAction.BREAK_BLOCK,
                    TaskFilterContext.block(blockId, dimensionId(world), pos.getY(), nowTime()),
                    pos.toShortString());
        });

        ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY.register((world, entity, killed, damageSource) -> {
            if (!(entity instanceof ServerPlayerEntity serverPlayer)) {
                return;
            }

            String entityId = Registries.ENTITY_TYPE.getId(killed.getType()).toString();
            report(serverPlayer, MilestoneMetric.MOBS_KILLED, RandomEventParticipationType.KILL_ENTITY);
            dispatch(serverPlayer, TaskAction.KILL_ENTITY,
                    TaskFilterContext.entity(entityId, dimensionId(world), killed.getBlockPos().getY(), nowTime()),
                    killed.getUuid().toString());
        });

        ServerTickEvents.END_SERVER_TICK.register(TaskEvents::poll);

        // 玩家退出时清掉轮询记忆，避免内存随"见过的玩家数"增长。
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                latestSatisfied.remove(handler.getPlayer().getUuid()));
    }

    /** 上报一次行为给里程碑与随机事件。 */
    private static void report(ServerPlayerEntity player,
                               MilestoneMetric metric,
                               com.haojing.battlepass.server.event.RandomEventParticipationType eventType) {
        com.haojing.battlepass.server.milestone.MetricSink metrics = metricSink;

        if (metrics != null && metric != null) {
            metrics.add(metric, 1L);
        }

        com.haojing.battlepass.server.event.RandomEventService events = eventService;

        if (events != null && eventType != null) {
            events.onAction(player.getUuid(), eventType);
        }
    }

    /** 由 Mixin 调用：玩家放置了一个方块。 */
    public static void onBlockPlaced(ServerPlayerEntity player, BlockState placedState, BlockPos pos, World world) {
        if (player == null || placedState == null || world == null || world.isClient()) {
            return;
        }

        String blockId = Registries.BLOCK.getId(placedState.getBlock()).toString();
        report(player, MilestoneMetric.BLOCKS_PLACED, RandomEventParticipationType.PLACE_BLOCK);
        dispatch(player, TaskAction.PLACE_BLOCK,
                TaskFilterContext.block(blockId, dimensionId(world), pos.getY(), nowTime()),
                pos.toShortString());
    }

    /** 由 Mixin 调用：玩家钓鱼成功。 */
    public static void onFishingSuccess(ServerPlayerEntity player, World world) {
        if (player == null || world == null) {
            return;
        }

        BlockPos pos = player.getBlockPos();
        report(player, MilestoneMetric.FISH_CAUGHT, RandomEventParticipationType.FISH);
        dispatch(player, TaskAction.FISH,
                TaskFilterContext.generic(biomeId(world, pos), dimensionId(world), pos.getY(), nowTime()),
                null);
    }

    /** 由 Mixin 调用：玩家与村民完成了一次交易。 */
    public static void onTradeCompleted(ServerPlayerEntity player) {
        if (player == null) {
            return;
        }

        World world = player.getEntityWorld();
        BlockPos pos = player.getBlockPos();
        report(player, MilestoneMetric.TRADES, RandomEventParticipationType.TRADE);
        dispatch(player, TaskAction.TRADE,
                TaskFilterContext.generic(biomeId(world, pos), dimensionId(world), pos.getY(), nowTime()),
                null);
    }

    private static void dispatch(ServerPlayerEntity player, TaskAction action, TaskFilterContext context,
                                String discriminator) {
        TaskProgressTracker current = tracker;

        if (current == null) {
            return;
        }

        current.advance(player.getUuid(), player.getName().getString(), action, context, discriminator,
                currentTick(player));

        // 任务刚完成时在物品栏上方弹一条提示，不用点进界面才知道。
        List<String> completed = current.drainJustCompleted();
        if (!completed.isEmpty()) {
            net.minecraft.text.MutableText msg = net.minecraft.text.Text.literal("§a§l任务完成：§r§e")
                    .append(String.join("§r§7、§r§e", completed));
            player.sendMessage(msg, true); // true = action bar（物品栏上方）
        }
    }

    /** 每 20 tick 一次的位置类判定（§5.6：群系切换用每 20 tick 比对实现）。 */
    private static void poll(MinecraftServer server) {
        TaskProgressTracker current = tracker;

        if (current == null) {
            return;
        }

        if (++pollCounter < POLL_INTERVAL_TICKS) {
            return;
        }

        pollCounter = 0;

        LocalTime time = nowTime();

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            UUID uuid = player.getUuid();
            World world = player.getEntityWorld();
            BlockPos pos = player.getBlockPos();

            TaskFilterContext context = TaskFilterContext.position(
                    biomeId(world, pos), dimensionId(world), pos.getY(), time);

            Set<String> before = latestSatisfied.getOrDefault(uuid, Set.of());
            Set<String> after = new HashSet<>();

            for (TaskDefinition definition : current.positionTasks(uuid)) {
                if (!TaskFilterEvaluator.matches(definition.filter, context)) {
                    continue;
                }

                after.add(definition.id);

                // 只有"上一轮还不满足、这一轮满足"才算一次抵达/进入。
                if (!before.contains(definition.id)) {
                    current.advanceTask(uuid, player.getName().getString(), definition.id, context, currentTick(player));

                    List<String> completed = current.drainJustCompleted();
                    if (!completed.isEmpty()) {
                        net.minecraft.text.MutableText msg = net.minecraft.text.Text.literal("§a§l任务完成：§r§e")
                                .append(String.join("§r§7、§r§e", completed));
                        player.sendMessage(msg, true);
                    }
                }
            }

            latestSatisfied.put(uuid, after);
        }
    }

    private static LocalTime nowTime() {
        try {
            return TimeUtil.now().toLocalTime();
        } catch (RuntimeException e) {
            // 时间获取异常写 WARN（§7），并返回 null 让时段条件判为不满足（保守方向）。
            LOGGER.warn("{} 获取北京时间失败，本次任务判定将忽略时段条件：{}", ModConstants.LOG_PREFIX, e.toString());
            return null;
        }
    }

    private static int currentTick(ServerPlayerEntity player) {
        // 用 world.getServer() 而不是 player.getServer()：后者在 1.21.11 的 Yarn 映射里已不存在，
        // 而 world.getServer() 是原版自己也在用的取法（见 FishingBobberEntity 反编译源码）。
        MinecraftServer server = player.getEntityWorld().getServer();
        return server == null ? 0 : server.getTicks();
    }

    private static String dimensionId(World world) {
        return world.getRegistryKey().getValue().toString();
    }

    private static String biomeId(World world, BlockPos pos) {
        // 群系键需要走 RegistryEntry 才能拿到命名空间形式（minecraft:river），
        // 直接取 toString() 会得到不可比较的实现细节。
        return world.getBiome(pos).getKey().map(key -> key.getValue().toString()).orElse("");
    }
}
