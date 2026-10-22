package com.haojing.battlepass.server.event;

import com.haojing.battlepass.common.ModConstants;
import com.haojing.battlepass.server.reward.CommandWhitelist;
import com.haojing.battlepass.server.reward.RewardSink;
import com.haojing.battlepass.common.data.Reward;
import com.haojing.battlepass.common.data.RewardType;
import com.haojing.battlepass.server.config.SeasonConfig;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 用途：把随机事件的配置效果真正作用到世界上（需求文档 §10 的"类型/持续时长"落地）。
 *
 * <p>与 {@link RandomEventService} 的分工：那边是纯逻辑调度（什么时候开始、谁算参与、发什么奖励），
 * 这边是 Minecraft 侧的动作（加状态效果、刷怪、执行白名单命令、发物品）。
 * 分开之后，调度规则可以单元测试，而这边只剩"解析 ID → 调一次 API"。
 *
 * <p>为什么物品效果走 {@link RewardSink} 而不是自己塞背包：奖励发放（含离线、幂等、
 * 命令白名单）已经在阶段 5 统一收口在奖励出口里，这里再写一遍必然出现两套规则。
 */
public final class RandomEventEffects {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModConstants.MOD_ID_SERVER);

    private final RewardSink rewardSink;
    private final Supplier<MinecraftServer> serverSupplier;
    private final Supplier<SeasonConfig> configSupplier;
    private final Supplier<RandomEventConfig> eventConfigSupplier;

    public RandomEventEffects(RewardSink rewardSink, Supplier<MinecraftServer> serverSupplier,
                              Supplier<SeasonConfig> configSupplier,
                              Supplier<RandomEventConfig> eventConfigSupplier) {
        this.rewardSink = rewardSink;
        this.serverSupplier = serverSupplier;
        this.configSupplier = configSupplier;
        // 注入空供应商时退化为读不到配置，选点逻辑走内置默认值，避免事件被构造失败拖垮。
        this.eventConfigSupplier = eventConfigSupplier == null ? () -> null : eventConfigSupplier;
    }

    /**
     * 事件开始时施放"立即类"效果：状态效果、刷怪、命令。
     *
     * <p>ITEM 与 XP_MULTIPLIER 不在这里：前者在结束时发给参与者（见 {@link #applyEnd}），
     * 后者由 {@link RandomEventService#xpMultiplier()} 在整个事件期间持续生效。
     *
     * <p>为什么不接收参与者名单：事件是全服事件，POTION/SPAWN 的施加对象就是"当前所有在线玩家"，
     * 与参与判定无关。这里直接遍历 {@code server.getPlayerManager().getPlayerList()}，
     * 传入 UUID 名单反而是误导（拿不到实体引用，最后还是得再查一遍）。
     *
     * @param effects  效果列表
     * @param duration 事件时长（秒），用于状态效果缺省持续时间
     */
    public void applyStart(List<EventEffect> effects, int duration) {
        if (effects == null || effects.isEmpty()) {
            return;
        }

        for (EventEffect effect : effects) {
            EventEffectKind kind = effect == null ? null : effect.kindOrNull();

            if (kind == null) {
                continue;
            }

            switch (kind) {
                case POTION:
                    applyPotion(effect, duration);
                    break;
                case SPAWN:
                    applySpawn(effect);
                    break;
                case COMMAND:
                    runCommand(effect);
                    break;
                default:
                    // ITEM / XP_MULTIPLIER 不在这里处理，见方法注释。
                    break;
            }
        }
    }

    /**
     * 事件结束时把 ITEM 效果发给参与者（奖励表由 {@link RandomEventService} 自己发）。
     *
     * @param effects      效果列表
     * @param participants 参与者
     */
    public void applyEnd(List<EventEffect> effects, List<UUID> participants) {
        if (effects == null || effects.isEmpty() || participants == null || participants.isEmpty()) {
            return;
        }

        for (EventEffect effect : effects) {
            if (effect == null || effect.kindOrNull() != EventEffectKind.ITEM) {
                continue;
            }

            Reward reward = new Reward(RewardType.ITEM, Math.max(1, effect.amount));
            reward.itemId = effect.value;

            for (UUID uuid : participants) {
                rewardSink.grant(uuid, reward, "随机事件物品效果", com.haojing.battlepass.server.battlepass.XpSource.RANDOM_EVENT);
            }
        }
    }

    private void applyPotion(EventEffect effect, int duration) {
        Identifier id = Identifier.tryParse(effect.value);

        if (id == null) {
            LOGGER.warn("{} 随机事件的状态效果 ID 非法：{}", ModConstants.LOG_PREFIX, effect.value);
            return;
        }

        Optional<RegistryEntry.Reference<StatusEffect>> entry = Registries.STATUS_EFFECT.getEntry(id);

        if (entry.isEmpty()) {
            LOGGER.warn("{} 随机事件的状态效果不存在：{}", ModConstants.LOG_PREFIX, effect.value);
            return;
        }

        int seconds = effect.durationSeconds > 0 ? effect.durationSeconds : Math.max(1, duration);
        StatusEffectInstance instance = new StatusEffectInstance(entry.get(), seconds * 20, Math.max(0, effect.amount));

        MinecraftServer server = serverSupplier.get();

        if (server == null) {
            return;
        }

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            player.addStatusEffect(new StatusEffectInstance(instance));
        }

        LOGGER.info("{} 随机事件效果：给 {} 名在线玩家施加 {}（{} 秒，等级 {}）",
                ModConstants.LOG_PREFIX, server.getPlayerManager().getPlayerList().size(),
                effect.value, seconds, effect.amount);
    }

    /**
     * 在玩家附近刷出一批实体（SPAWN 效果）。
     *
     * <p>2026-10 修复（玩家反馈：无视亮度直接生成会损坏生电机器）：旧实现直接在玩家脚下随机偏移刷怪，
     * 不看亮度、不找地面，白天室内、火把照亮的基地与生电机房都会刷出怪物。新版每只怪物都要先找到一个
     * {@link #findSpawnSpot 满足亮度与空间条件的暗点}；找不到就放弃这一只，绝不为凑数量硬刷到亮处。
     */
    private void applySpawn(EventEffect effect) {
        MinecraftServer server = serverSupplier.get();
        Identifier id = Identifier.tryParse(effect.value);

        if (server == null || id == null) {
            LOGGER.warn("{} 随机事件的刷怪 ID 非法或服务端不可用：{}", ModConstants.LOG_PREFIX, effect.value);
            return;
        }

        EntityType<?> type = Registries.ENTITY_TYPE.get(id);

        if (type == null) {
            LOGGER.warn("{} 随机事件的实体类型不存在：{}", ModConstants.LOG_PREFIX, effect.value);
            return;
        }

        RandomEventConfig policy = eventConfigSupplier.get();
        final int maxLight;
        final int radius;
        final int attempts;
        final int verticalRange;

        if (policy == null) {
            // 配置尚未加载时走内置默认值，口径与 default_events.json 一致。
            maxLight = 7;
            radius = 8;
            attempts = 12;
            verticalRange = 10;
        } else {
            maxLight = policy.effectiveSpawnMaxLightLevel();
            radius = policy.effectiveSpawnRadius();
            attempts = policy.effectiveSpawnMaxAttempts();
            verticalRange = policy.effectiveSpawnVerticalRange();
        }

        int perPlayer = Math.max(1, effect.amount);
        int spawned = 0;
        int skipped = 0;

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            World world = player.getEntityWorld();

            if (!(world instanceof ServerWorld serverWorld)) {
                continue;
            }

            Vec3d base = player.getEntityPos();

            for (int i = 0; i < perPlayer; i++) {
                BlockPos spot = findSpawnSpot(serverWorld, base, maxLight, radius, attempts, verticalRange);

                if (spot == null) {
                    // 尝试次数内找不到合法暗点：放弃这一只，不硬刷到亮处。
                    skipped++;
                    continue;
                }

                Entity entity = type.create(serverWorld, SpawnReason.EVENT);

                if (entity == null) {
                    continue;
                }

                entity.refreshPositionAndAngles(spot.getX() + 0.5D, spot.getY(), spot.getZ() + 0.5D,
                        world.random.nextFloat() * 360.0F, 0.0F);

                if (serverWorld.spawnEntity(entity)) {
                    spawned++;
                }
            }
        }

        LOGGER.info("{} 随机事件效果：刷出 {} 个 {}（跳过 {} 个未找到暗点的候选）",
                ModConstants.LOG_PREFIX, spawned, effect.value, skipped);
    }

    /**
     * 在玩家周围找一个合法的怪物刷怪点：脚下实心、脚位与头顶是空气、亮度不超过阈值。
     *
     * <p>为什么自己做垂直探测而不是直接取高度图：旧实现把脚位直接取玩家 Y，会刷进方块或空中；
     * 高度图只反映地表，室内屋顶、地下洞穴两类典型生电场景都不准。这里在玩家周围随机取列，
     * 再在垂直窗口内自上而下找第一处"实心地面 + 上方两格空气"，最后复核亮度。
     *
     * @param serverWorld   当前世界
     * @param base          玩家位置
     * @param maxLight      允许刷怪的最高亮度（含）
     * @param radius        水平半径（格）
     * @param maxAttempts   最大候选列数
     * @param verticalRange  与玩家的最大垂直高差（格）
     * @return 合法刷怪点；找不到时返回 {@code null}
     */
    private BlockPos findSpawnSpot(ServerWorld serverWorld, Vec3d base, int maxLight,
                                   int radius, int maxAttempts, int verticalRange) {
        BlockPos origin = BlockPos.ofFloored(base);
        int worldBottom = serverWorld.getBottomY();
        int worldTop = worldBottom + serverWorld.getHeight() - 1;

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            int dx = serverWorld.random.nextInt(radius * 2 + 1) - radius;
            int dz = serverWorld.random.nextInt(radius * 2 + 1) - radius;

            // 离玩家水平两格以内容易卡在身上，直接换一个候选列。
            if (Math.abs(dx) < 2 && Math.abs(dz) < 2) {
                continue;
            }

            int x = origin.getX() + dx;
            int z = origin.getZ() + dz;
            int topY = Math.min(origin.getY() + verticalRange, worldTop);
            int bottomY = Math.max(origin.getY() - verticalRange, worldBottom);

            for (int y = topY; y >= bottomY; y--) {
                BlockPos feet = new BlockPos(x, y, z);
                BlockPos ground = feet.down();
                BlockPos head = feet.up();

                BlockState groundState = serverWorld.getBlockState(ground);
                BlockState feetState = serverWorld.getBlockState(feet);
                BlockState headState = serverWorld.getBlockState(head);

                // 脚下必须是实心方块，脚位与头顶必须都是空气（与原版怪物生成口径一致）。
                if (!groundState.isSolidBlock(serverWorld, ground)
                        || !feetState.isAir() || !headState.isAir()) {
                    continue;
                }

                // 亮度闸：白天露天、火把/灯照亮的基地与生电机房一律不刷。
                if (serverWorld.getLightLevel(feet) > maxLight) {
                    continue;
                }

                return feet;
            }
        }

        return null;
    }

    private void runCommand(EventEffect effect) {
        MinecraftServer server = serverSupplier.get();
        SeasonConfig config = configSupplier.get();
        List<String> whitelist = config == null ? List.of() : config.commandWhitelist;

        // 需求文档 §9：COMMAND 必须走管理员命令白名单 —— 事件效果同样适用这条安全边界。
        if (!CommandWhitelist.isAllowed(effect.value, whitelist)) {
            LOGGER.warn("{} 随机事件的命令「{}」不在白名单内，已拒绝执行（§9）",
                    ModConstants.LOG_PREFIX, effect.value);
            return;
        }

        if (server == null) {
            return;
        }

        try {
            server.getCommandManager().parseAndExecute(server.getCommandSource().withSilent(), effect.value);
            LOGGER.info("{} 随机事件效果：已执行命令 /{}", ModConstants.LOG_PREFIX, effect.value);
        } catch (RuntimeException e) {
            LOGGER.error("{} 随机事件命令 /{} 执行异常", ModConstants.LOG_PREFIX, effect.value, e);
        }
    }

    /** @return 便捷方法：判断效果是否需要"事件结束时"处理。 */
    static boolean isEndEffect(EventEffect effect) {
        return effect != null && effect.kindOrNull() == EventEffectKind.ITEM;
    }
}
