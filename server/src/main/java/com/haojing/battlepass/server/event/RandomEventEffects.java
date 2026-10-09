package com.haojing.battlepass.server.event;

import com.haojing.battlepass.common.ModConstants;
import com.haojing.battlepass.server.reward.CommandWhitelist;
import com.haojing.battlepass.server.reward.RewardSink;
import com.haojing.battlepass.common.data.Reward;
import com.haojing.battlepass.common.data.RewardType;
import com.haojing.battlepass.server.config.SeasonConfig;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
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

    public RandomEventEffects(RewardSink rewardSink, Supplier<MinecraftServer> serverSupplier,
                              Supplier<SeasonConfig> configSupplier) {
        this.rewardSink = rewardSink;
        this.serverSupplier = serverSupplier;
        this.configSupplier = configSupplier;
    }

    /**
     * 事件开始时施放"立即类"效果：状态效果、刷怪、命令。
     *
     * <p>ITEM 与 XP_MULTIPLIER 不在这里：前者在结束时发给参与者（见 {@link #applyEnd}），
     * 后者由 {@link RandomEventService#xpMultiplier()} 在整个事件期间持续生效。
     *
     * @param effects  效果列表
     * @param duration 事件时长（秒），用于状态效果缺省持续时间
     * @param online   当前在线玩家
     */
    public void applyStart(List<EventEffect> effects, int duration, List<UUID> online) {
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
                    applyPotion(effect, duration, online);
                    break;
                case SPAWN:
                    applySpawn(effect, online);
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

    private void applyPotion(EventEffect effect, int duration, List<UUID> online) {
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

    private void applySpawn(EventEffect effect, List<UUID> online) {
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

        int perPlayer = Math.max(1, effect.amount);
        int spawned = 0;

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            World world = player.getEntityWorld();

            if (!(world instanceof net.minecraft.server.world.ServerWorld serverWorld)) {
                continue;
            }

            Vec3d base = player.getEntityPos();

            for (int i = 0; i < perPlayer; i++) {
                // 在玩家周围 8 格内随机偏移刷出：太近会卡在玩家身上，太远就看不到。
                double offsetX = (world.random.nextDouble() - 0.5D) * 16.0D;
                double offsetZ = (world.random.nextDouble() - 0.5D) * 16.0D;

                Entity entity = type.create(serverWorld, SpawnReason.EVENT);

                if (entity == null) {
                    continue;
                }

                entity.refreshPositionAndAngles(base.x + offsetX, base.y, base.z + offsetZ,
                        world.random.nextFloat() * 360.0F, 0.0F);

                if (serverWorld.spawnEntity(entity)) {
                    spawned++;
                }
            }
        }

        LOGGER.info("{} 随机事件效果：刷出 {} 个 {}", ModConstants.LOG_PREFIX, spawned, effect.value);
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
