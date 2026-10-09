package com.haojing.battlepass.server.longnight;

import com.haojing.battlepass.server.config.ConfigManager;
import com.haojing.battlepass.server.config.LongNightConfig;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * 用途：长夜对玩家的实际影响。需求文档 §7：在线玩家获得【长夜疲劳】，
 * 直接使用原版 SLOWNESS + MINING_FATIGUE，**不自定义状态效果**（避免双端注册与同步问题）。
 *
 * <p>为什么用"短时长 + 周期性刷新"而不是"施加一个无限时长的效果，结束时再移除"：
 * 后者需要额外维护"我给哪些玩家加过效果"的状态，一旦服务端中途重启或配置热重载，
 * 这份状态就丢了，效果会永久留在玩家身上。而短时长方案是自愈的 ——
 * 长夜结束、玩家被加入白名单、甚至模组被卸掉，效果都会在 {@link #FATIGUE_DURATION_TICKS}
 * 对应的时间内自然消失，**不需要任何移除逻辑**。
 *
 * <p>为什么时长不取 1 tick 而是 5 秒：轮询是每 20 tick（1 秒）一次，若效果只持续 1 秒，
 * 网络抖动导致的一次延迟就会让效果闪烁、客户端图标一闪一闪。取 5 秒既留出余量，
 * 又保证失效足够快（不会让玩家在长夜结束后还顶着疲劳很久）。
 */
public final class LongNightEffects {

    /** 疲劳效果每次施加的持续时间（tick）。5 秒 = 100 tick。 */
    static final int FATIGUE_DURATION_TICKS = 100;

    /**
     * 给长夜中的在线玩家施加疲劳。白名单玩家会被跳过。
     *
     * @param server        服务端实例
     * @param config        长夜配置
     * @param configManager 用于查询管理员白名单
     */
    public void applyFatigue(MinecraftServer server, LongNightConfig config, ConfigManager configManager) {
        if (config == null || !config.fatigueEnabled) {
            return;
        }

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            // 需求文档 §7：管理员白名单（UUID 列表）不受长夜全部效果影响，用于夜间维护。
            // 这里只是"不再续期"，效果会在 5 秒内自然过期，因此中途改白名单也能立即生效。
            if (configManager.isAdminWhitelisted(player.getUuid())) {
                continue;
            }

            addEffect(player, StatusEffects.SLOWNESS, config.fatigueSlownessAmplifier);
            addEffect(player, StatusEffects.MINING_FATIGUE, config.fatigueMiningFatigueAmplifier);

            if (config.fatigueHungerAcceleration) {
                addEffect(player, StatusEffects.HUNGER, 0);
            }
        }
    }

    private void addEffect(ServerPlayerEntity player, RegistryEntry<StatusEffect> effect, int amplifier) {
        // 1.21 起 LivingEntity#addStatusEffect 需要显式传入"效果来源实体"，传玩家自身语义最自然。
        player.addStatusEffect(new StatusEffectInstance(effect, FATIGUE_DURATION_TICKS, amplifier), player);
    }
}
