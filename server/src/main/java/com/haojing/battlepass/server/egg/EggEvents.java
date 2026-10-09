package com.haojing.battlepass.server.egg;

import com.haojing.battlepass.common.ModConstants;
import com.haojing.battlepass.server.longnight.LongNightManager;
import com.haojing.battlepass.server.time.TimeUtil;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.MoonPhase;
import net.minecraft.world.World;
import net.minecraft.world.attribute.EnvironmentAttributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用途：把 Minecraft 的世界状态翻译成 {@link EggContext}，并驱动 {@link EggService} 判定。
 * 覆盖需求文档 §6 的全部 13 个彩蛋的数据来源。
 *
 * <p>采样策略（§13 的性能预算与 §6 的量化定义冲突时以 §6 为准）：
 * <ul>
 *   <li><b>每 20 tick（1 秒）</b>：位置、群系、月相、天气、户外、同伴、敌对生物、装备、手持。
 *       这是一次"完整快照"，绝大多数彩蛋都在这个频率下判定。</li>
 *   <li><b>每 tick</b>：只对"长夜中 + 静听彩蛋尚未解锁"的玩家做一次浮点位移比较 ——
 *       §6 明确写了"每 tick 位移 &lt; 0.01"，而这个检查就是一次减法 + 比较，
 *       30 人同时在线的开销可以忽略。</li>
 *   <li><b>事件驱动</b>：放置方块、钓鱼成功、进入群系、树苗长成、长夜窗口结束。</li>
 * </ul>
 *
 * <p>为什么月相要走环境属性：1.21.11 已经**没有** {@code World#getMoonPhase()} 了
 * （§6 的措辞基于旧版本），改用 {@code EnvironmentAttributes.MOON_PHASE_VISUAL}，
 * 判据仍按 §6 取满月。这条 API 变更已登记在 docs/需求偏差记录.md（D-19）。
 */
public final class EggEvents {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModConstants.MOD_ID_SERVER);

    private static final int POLL_INTERVAL_TICKS = 20;

    /** 每个玩家同时跟踪的树苗坐标上限（避免长时间运行后内存增长）。 */
    private static final int MAX_TRACKED_SAPLINGS = 8;

    private static volatile EggService eggService;
    private static volatile LongNightManager longNightManager;

    private static int tickCounter;
    private static boolean lastLongNightActive;
    private static String lastPeriodKey = "";

    /** 上一 tick 的位置（算位移）。 */
    private static final Map<UUID, Vec3d> lastPositions = new ConcurrentHashMap<>();

    /** 一个采样周期内累计的位移。 */
    private static final Map<UUID, Double> movedDistance = new ConcurrentHashMap<>();

    /** 一个采样周期内是否有破坏/放置/攻击行为。 */
    private static final Set<UUID> actedSinceSample = ConcurrentHashMap.newKeySet();

    /** 上一轮看到的群系（用于"进入新群系"）。 */
    private static final Map<UUID, String> lastBiome = new ConcurrentHashMap<>();

    /** 待观察的树苗坐标。 */
    private static final Map<UUID, List<TrackedSapling>> trackedSaplings = new ConcurrentHashMap<>();

    private EggEvents() {
    }

    /** 被跟踪的树苗坐标。 */
    private record TrackedSapling(String dimension, BlockPos pos) {
    }

    /**
     * 注册全部监听与轮询。应在服务端初始化时调用一次。
     *
     * @param service 彩蛋业务
     * @param nights  长夜管理器（提供"是否长夜"与窗口键）
     */
    public static void register(EggService service, LongNightManager nights) {
        eggService = service;
        longNightManager = nights;

        // 破坏方块：既算"有行为"（静听会重置），也可能触发长夜类彩蛋的上下文刷新。
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            if (player instanceof ServerPlayerEntity serverPlayer && !world.isClient()) {
                actedSinceSample.add(serverPlayer.getUuid());
            }
        });

        // 攻击行为：被伤害者不是玩家、而伤害来源是某玩家 → 该玩家发生了攻击。
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            markAttacker(source);
            return true;
        });

        // 玩家死亡：§6 晨归要求"保持存活至 06:00"。
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> onDeath(entity));

        ServerTickEvents.END_SERVER_TICK.register(EggEvents::onTick);

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> onJoin(handler.getPlayer()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> forget(handler.getPlayer().getUuid()));

        // 聊天关键词（§6 轻量趣味彩蛋：聊天关键词触发社团标语广播）。
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) ->
                onChat(sender, message.getSignedContent()));
    }

    /** 由放置方块的 Mixin 调用。 */
    public static void onBlockPlaced(ServerPlayerEntity player, BlockState placedState, BlockPos pos, World world) {
        EggService service = eggService;

        if (service == null || player == null || placedState == null || world == null || world.isClient()) {
            return;
        }

        UUID uuid = player.getUuid();
        actedSinceSample.add(uuid);

        String blockId = Registries.BLOCK.getId(placedState.getBlock()).toString();

        if (blockId.contains("sapling")) {
            trackSapling(player, world, pos);
        }

        EggContext context = baseContext(player, world, 1);
        context.placedBlockId = blockId;

        service.evaluate(uuid, player.getName().getString(), EggTrigger.PLACE_BLOCK, context);
    }

    /** 由钓鱼成功的 Mixin 调用。 */
    public static void onFishingSuccess(ServerPlayerEntity player, World world) {
        EggService service = eggService;

        if (service == null || player == null || world == null) {
            return;
        }

        EggContext context = baseContext(player, world, 1);
        service.evaluate(player.getUuid(), player.getName().getString(), EggTrigger.FISH, context);
    }

    private static void onJoin(ServerPlayerEntity player) {
        EggService service = eggService;

        if (service == null || player == null) {
            return;
        }

        ZonedDateTime now = TimeUtil.now();
        String dateKey = TimeUtil.dateKey(now);

        String birthdayKey = service.birthdayGreetingIfDue(player.getUuid(), dateKey);

        if (birthdayKey != null) {
            // 走 TranslationKey（§1）；译文在客户端 zh_cn.json。
            player.sendMessage(Text.translatable(birthdayKey, player.getName().getString()), false);
        }

        LightEggConfig.FestivalRule festival = service.festivalGreetingIfDue(player.getUuid(), dateKey);

        if (festival != null) {
            player.sendMessage(resolveText(festival.messageKey, festival.message), false);
        }
    }

    private static void onChat(ServerPlayerEntity player, String content) {
        EggService service = eggService;

        if (service == null || player == null || content == null) {
            return;
        }

        LightEggConfig.ChatKeywordRule rule = service.matchChatKeyword(player.getUuid(), content);

        if (rule == null) {
            return;
        }

        MinecraftServer server = player.getEntityWorld().getServer();

        if (server == null) {
            return;
        }

        // §6：聊天关键词触发社团标语广播 —— 全服可见，因此用 false（进聊天框而不是动作栏）。
        server.getPlayerManager().broadcast(resolveText(rule.sloganKey, rule.slogan), false);
        LOGGER.info("{} 聊天关键词「{}」由玩家 {} 触发，已广播社团标语",
                ModConstants.LOG_PREFIX, rule.keyword, player.getName().getString());
    }

    /**
     * 把"翻译键优先、字面兜底"的文案解析成 Text。
     *
     * <p>为什么要兜底：节日与聊天关键词都能被管理员随时新增，
     * 客户端的 zh_cn.json 不可能预知管理员写了什么（需求文档 §1 要求文案走 TranslationKey，
     * 但这一条只对"模组自带文案"成立）。{@code translatableWithFallback} 正好表达这个语义：
     * 有译文用译文，没有就用管理员填的字面文本。
     */
    private static Text resolveText(String translationKey, String literal) {
        if (translationKey == null || translationKey.isBlank()) {
            return Text.literal(literal == null ? "" : literal);
        }

        return Text.translatableWithFallback(translationKey.trim(), literal == null ? "" : literal);
    }

    private static void onDeath(LivingEntity entity) {
        EggService service = eggService;
        LongNightManager nights = longNightManager;

        if (service == null || nights == null || !(entity instanceof ServerPlayerEntity player) || !nights.isActive()) {
            return;
        }

        // 只在长夜期间记录：§6 晨归的判据是"长夜内保持存活至 06:00"。
        service.markDeathDuringLongNight(player.getUuid(), nights.windowKey());
    }

    private static void markAttacker(DamageSource source) {
        Entity attacker = source == null ? null : source.getAttacker();

        if (attacker instanceof ServerPlayerEntity player) {
            actedSinceSample.add(player.getUuid());
        }
    }

    /** 每 tick 的主循环。 */
    private static void onTick(MinecraftServer server) {
        EggService service = eggService;

        if (service == null || server == null) {
            return;
        }

        LongNightManager nights = longNightManager;
        boolean longNight = nights != null && nights.isActive();
        String periodKey = longNight ? nights.windowKey() : "";

        // 长夜窗口结束：只对"此刻仍在线的玩家"派发（§6 守夜人/晨归都要求"在线至 06:00"）。
        if (lastLongNightActive && !longNight) {
            dispatchWindowEnd(server, service, lastPeriodKey);
        }

        lastLongNightActive = longNight;
        lastPeriodKey = periodKey;

        // 每 tick 的位移采样：只对"长夜中 + 静听未解锁"的玩家做一次浮点比较（见类注释）。
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            UUID uuid = player.getUuid();
            Vec3d position = player.getEntityPos();
            Vec3d previous = lastPositions.put(uuid, position);
            double moved = previous == null ? 0.0D : position.distanceTo(previous);

            movedDistance.merge(uuid, moved, Double::sum);

            if (longNight && needsPerTick(service, uuid)) {
                EggContext context = baseContext(player, player.getEntityWorld(), 1);
                context.longNight = true;
                context.periodKey = periodKey;
                context.movedDistance = moved;
                context.acted = actedSinceSample.remove(uuid);

                service.evaluate(uuid, player.getName().getString(), EggTrigger.POLL, context);
            }
        }

        if (++tickCounter < POLL_INTERVAL_TICKS) {
            return;
        }

        tickCounter = 0;

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            pollPlayer(server, service, player, periodKey);
        }
    }

    /** 每 20 tick 的完整判定。 */
    private static void pollPlayer(MinecraftServer server, EggService service,
                                   ServerPlayerEntity player, String periodKey) {
        UUID uuid = player.getUuid();
        World world = player.getEntityWorld();
        EggContext context = baseContext(player, world, POLL_INTERVAL_TICKS);

        context.longNight = longNightManager != null && longNightManager.isActive();
        context.periodKey = periodKey;
        context.movedDistance = movedDistance.getOrDefault(uuid, 0.0D);
        context.acted = actedSinceSample.remove(uuid);

        movedDistance.put(uuid, 0.0D);

        // 进入新群系（§6 大地勘探者：本赛季内分别进入三种洞穴群系）。
        String previousBiome = lastBiome.put(uuid, context.biome);

        if (previousBiome != null && !previousBiome.equals(context.biome)) {
            service.evaluate(uuid, player.getName().getString(), EggTrigger.BIOME_ENTER, context);
        }

        // 树苗是否长成（§6 樱落归镐）。
        if (checkSaplingGrown(server, uuid)) {
            context.saplingGrown = true;
            service.evaluate(uuid, player.getName().getString(), EggTrigger.SAPLING_GROWN, context);
        }

        service.evaluate(uuid, player.getName().getString(), EggTrigger.POLL, context);
    }

    /** 长夜窗口结束：对仍在线且户外的玩家派发（§6 守夜人、晨归）。 */
    private static void dispatchWindowEnd(MinecraftServer server, EggService service, String periodKey) {
        if (periodKey == null || periodKey.isEmpty()) {
            return;
        }

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            EggContext context = baseContext(player, player.getEntityWorld(), 1);
            context.longNight = false;
            context.periodKey = periodKey;
            context.onlineAtWindowEnd = true;

            service.evaluate(player.getUuid(), player.getName().getString(), EggTrigger.NIGHT_WINDOW_END, context);
        }

        LOGGER.info("{} 长夜窗口 {} 已结束，已派发守夜人/晨归判定（在线玩家 {} 人）",
                ModConstants.LOG_PREFIX, periodKey, server.getPlayerManager().getPlayerList().size());
    }

    private static boolean needsPerTick(EggService service, UUID uuid) {
        for (EggDefinition egg : service.eggsFor(uuid, EggTrigger.POLL)) {
            if (EggService.needsPerTickSampling(egg)) {
                return true;
            }
        }

        return false;
    }

    /**
     * 构造上下文。每 tick 的轻量调用只需要极少字段，
     * 但为了代码统一（以及避免"某些字段忘了填"），这里一次性都填上 ——
     * 单个玩家一次快照的开销是常数级的几次查询。
     */
    private static EggContext baseContext(ServerPlayerEntity player, World world, int deltaTicks) {
        EggContext context = new EggContext();

        ZonedDateTime now = TimeUtil.now();
        context.time = now.toLocalTime();
        context.dateKey = TimeUtil.dateKey(now);
        context.nowMillis = System.currentTimeMillis();
        context.deltaTicks = Math.max(1, deltaTicks);

        BlockPos pos = player.getBlockPos();
        context.y = pos.getY();
        context.outdoor = world.isSkyVisible(pos);
        context.biome = biomeId(world, pos);
        context.dimension = world.getRegistryKey().getValue().toString();
        context.moonPhase = moonPhaseName(world, pos);
        context.night = world.isNight();
        context.raining = world.isRaining();
        context.thundering = world.isThundering();
        context.nearbyPlayers = countNearbyPlayers(player, world, pos);
        context.nearbyHostile = countNearbyHostile(player, world, pos);
        context.armorEmpty = hasEmptyArmor(player);
        context.holdingTorch = isHoldingTorch(player);

        return context;
    }

    /**
     * 统计半径 32 格内、**同群系**的玩家数（含自己）。
     *
     * <p>§6 社团同游的原文是"半径 32 格内 ≥3 名协会玩家，同群系连续停留 5 分钟"，
     * 因此同伴必须同时满足"距离内"与"同群系"两个条件 ——
     * 只数人数会让站在洞穴口和站在地表的两名玩家也算"同游"。
     */
    private static int countNearbyPlayers(ServerPlayerEntity player, World world, BlockPos pos) {
        MinecraftServer server = world.getServer();

        if (server == null) {
            return 1;
        }

        String biome = biomeId(world, pos);
        Vec3d center = player.getEntityPos();
        double radius = 32.0D;
        int count = 1;

        for (ServerPlayerEntity other : server.getPlayerManager().getPlayerList()) {
            if (other == player || other.getEntityWorld() != world) {
                continue;
            }

            if (other.getEntityPos().distanceTo(center) > radius) {
                continue;
            }

            if (biome.equals(biomeId(world, other.getBlockPos()))) {
                count++;
            }
        }

        return count;
    }

    /** 统计半径内的敌对生物数量（§6 月下筑者的"触发瞬间半径 16 格内无敌对生物"）。 */
    private static int countNearbyHostile(ServerPlayerEntity player, World world, BlockPos pos) {
        Box box = new Box(pos).expand(16.0D);

        return world.getOtherEntities(player, box, entity -> entity instanceof HostileEntity && entity.isAlive()).size();
    }

    /** @return 四件防具位是否全空（§6 长夜微光）。 */
    private static boolean hasEmptyArmor(ServerPlayerEntity player) {
        EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

        for (EquipmentSlot slot : slots) {
            ItemStack stack = player.getEquippedStack(slot);

            if (stack != null && !stack.isEmpty()) {
                return false;
            }
        }

        return true;
    }

    /** @return 主手是否拿着火把（§6 长夜微光）。 */
    private static boolean isHoldingTorch(ServerPlayerEntity player) {
        ItemStack main = player.getMainHandStack();
        return main != null && main.isOf(Items.TORCH);
    }

    /**
     * 取月相名。
     *
     * <p>1.21.11 的原版接口是环境属性 {@code EnvironmentAttributes.MOON_PHASE_VISUAL}
     * （见类注释）；取不到时返回空串，判定方按"条件不满足"处理（保守方向）。
     */
    private static String moonPhaseName(World world, BlockPos pos) {
        try {
            MoonPhase phase = world.getEnvironmentAttributes()
                    .getAttributeValue(EnvironmentAttributes.MOON_PHASE_VISUAL, pos);

            return phase == null ? "" : phase.name();
        } catch (RuntimeException e) {
            LOGGER.warn("{} 读取月相失败，本次判定按「非满月」处理：{}", ModConstants.LOG_PREFIX, e.toString());
            return "";
        }
    }

    private static String biomeId(World world, BlockPos pos) {
        return world.getBiome(pos).getKey().map(key -> key.getValue().toString()).orElse("");
    }

    private static void trackSapling(ServerPlayerEntity player, World world, BlockPos pos) {
        List<TrackedSapling> list = trackedSaplings.computeIfAbsent(player.getUuid(), key -> new ArrayList<>());
        String dimension = world.getRegistryKey().getValue().toString();

        synchronized (list) {
            if (list.size() >= MAX_TRACKED_SAPLINGS) {
                list.remove(0);
            }

            list.add(new TrackedSapling(dimension, pos.toImmutable()));
        }
    }

    /**
     * 检查该玩家跟踪的树苗是否有一株长成了树。
     *
     * @return 是否检测到"长成"
     */
    private static boolean checkSaplingGrown(MinecraftServer server, UUID uuid) {
        List<TrackedSapling> list = trackedSaplings.get(uuid);

        if (list == null) {
            return false;
        }

        boolean grown = false;

        synchronized (list) {
            var iterator = list.iterator();

            while (iterator.hasNext()) {
                TrackedSapling tracked = iterator.next();
                World world = resolveWorld(server, tracked.dimension());

                if (world == null) {
                    continue;
                }

                BlockState state = world.getBlockState(tracked.pos());
                String blockId = Registries.BLOCK.getId(state.getBlock()).toString().toLowerCase(Locale.ROOT);

                if (blockId.contains("_log") || blockId.contains("_wood") || blockId.contains("leaves")) {
                    // 树苗位置已经变成原木/树叶 → §6 的"成功生长"成立。
                    iterator.remove();
                    grown = true;
                }
            }
        }

        return grown;
    }

    private static World resolveWorld(MinecraftServer server, String dimension) {
        Identifier id = Identifier.tryParse(dimension);

        if (id == null) {
            return null;
        }

        ServerWorld world = server.getWorld(RegistryKey.of(RegistryKeys.WORLD, id));

        if (world == null) {
            return null;
        }

        return world;
    }

    /** 玩家退出：清掉全部按玩家跟踪的内存状态，避免随"见过的玩家数"增长。 */
    private static void forget(UUID uuid) {
        lastPositions.remove(uuid);
        movedDistance.remove(uuid);
        actedSinceSample.remove(uuid);
        lastBiome.remove(uuid);
        trackedSaplings.remove(uuid);
    }
}
