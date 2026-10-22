package com.haojing.battlepass.server.bonus;

import com.haojing.battlepass.common.ModConstants;
import com.haojing.battlepass.server.config.ConfigManager;
import com.haojing.battlepass.server.config.SeasonConfig;
import com.haojing.battlepass.server.time.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;

/**
 * 用途：经验加成时段的运行时状态机。
 *
 * <p>与 {@code LongNightManager} 同构的取舍：状态用"每次轮询重新计算，只在跃变时记日志"，
 * 而不是一堆定时器 —— 配置热重载（改起止时间）后下一轮轮询自动纠正，不会漏掉旧任务。
 *
 * <p>刻意比长夜管理器更轻：加成时段只改经验倍率，**不**发标题、**不**加状态效果、
 * **不**做开始/结束预告广播（正向福利不需要仪式感式弹窗，避免每晚刷屏）；
 * 也不提供管理员白名单（全员同倍率）。
 *
 * <p>本类不引用任何 Minecraft 类型：状态判定与倍率计算全部落在纯函数
 * {@link BonusTimeSchedule} 里，这里只做配置检测、状态跃变记录与对外只读访问，
 * 因此可以在不启动服务端的单元测试里装配使用。
 */
public final class BonusTimeManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModConstants.MOD_ID_SERVER);

    /** 配置变更检测间隔（轮询次数）：服务端维护循环每 20 tick 调一次本类，这里 5 次 ≈ 5 秒。 */
    private static final int CONFIG_CHECK_INTERVAL_TICKS = 5;

    private final ConfigManager configManager;

    /** 当前是否处于加成时段。用 volatile：读它的倍率链在业务线程，写它在维护线程。 */
    private volatile boolean active;

    private int configCheckCounter;

    public BonusTimeManager(ConfigManager configManager) {
        this.configManager = configManager;
    }

    /** @return 当前是否处于加成时段。 */
    public boolean isActive() {
        return active;
    }

    /** @return 当前加成倍率；非加成时段恒为 1.0（与长夜/事件倍率相乘前的这一段）。 */
    public double multiplier() {
        SeasonConfig config = configManager == null ? null : configManager.config();
        return BonusTimeSchedule.resolveXpMultiplier(active, config);
    }

    /** @return 距离加成结束的剩余分钟；非加成时段为 0。 */
    public long minutesRemaining() {
        SeasonConfig config = configManager == null ? null : configManager.config();

        if (!active || config == null) {
            return 0L;
        }

        ZonedDateTime now;

        try {
            now = TimeUtil.now();
        } catch (RuntimeException e) {
            LOGGER.warn("{} 获取北京时间失败，加成剩余分钟本轮按 0 处理：{}",
                    ModConstants.LOG_PREFIX, e.toString());
            return 0L;
        }

        return BonusTimeSchedule.minutesRemaining(now, config, active);
    }

    /**
     * 由服务端维护循环（每 20 tick）调用一次；内部自行做配置变更节流。
     */
    public void tick() {
        SeasonConfig config = configManager == null ? null : configManager.config();

        if (config == null) {
            return;
        }

        if (++configCheckCounter >= CONFIG_CHECK_INTERVAL_TICKS) {
            configCheckCounter = 0;

            // 与长夜管理器一样：管理员手改 season.json 后也要能在几秒内生效。
            if (configManager.reloadIfChanged()) {
                config = configManager.config();
            }
        }

        if (config == null) {
            return;
        }

        ZonedDateTime now;

        try {
            now = TimeUtil.now();
        } catch (RuntimeException e) {
            LOGGER.warn("{} 获取北京时间失败，本轮加成时段判定已跳过：{}",
                    ModConstants.LOG_PREFIX, e.toString());
            return;
        }

        boolean shouldBeActive = BonusTimeSchedule.isActive(now, config);

        if (shouldBeActive != active) {
            active = shouldBeActive;

            if (shouldBeActive) {
                LOGGER.info("{} 经验加成时段开始：北京时间={} 时段={}~{} 经验倍率={}",
                        ModConstants.LOG_PREFIX, TimeUtil.stamp(now),
                        config.bonusTimeStart(), config.bonusTimeEnd(), config.bonusTime.xpMultiplier);
            } else {
                LOGGER.info("{} 经验加成时段结束：北京时间={}", ModConstants.LOG_PREFIX, TimeUtil.stamp(now));
            }
        }
    }
}
