package com.haojing.battlepass.server.bonus;

import com.haojing.battlepass.server.config.SeasonConfig;
import com.haojing.battlepass.server.time.TimeUtil;

import java.time.ZonedDateTime;

/**
 * 用途：经验加成时段的纯时间排程逻辑 —— 判断"此刻是否处于加成窗口"、"倍率是多少"、
 * "距离结束还有多少分钟"。
 *
 * <p>为什么把这段逻辑从运行时管理器里抽出来（与 {@code LongNightSchedule} 同一取舍）：
 * 时间判断一旦和 tick、发标题、加效果混在一起，就只能等到晚上七点上线实测。
 * 抽成不依赖任何 Minecraft 类型的纯函数后，跨零点窗口、开关关闭、倍率回退这些边界
 * 都能用单元测试秒级覆盖。
 *
 * <p>叠加规则（见 docs/需求偏差记录.md）：加成倍率与长夜倍率、随机事件倍率**三者相乘**。
 * 例如长夜 0.5 × 加成 2.0 = 1.0，两者恰好抵消；本类只负责给出"加成这一段"的倍率，
 * 乘积在 {@code BattlePassService} 的倍率提供方里完成。
 */
public final class BonusTimeSchedule {

    private BonusTimeSchedule() {
    }

    /**
     * @param now    当前时刻
     * @param config 赛季配置
     * @return 此刻是否处于加成时段（已同时考虑赛季总开关与加成子开关）
     */
    public static boolean isActive(ZonedDateTime now, SeasonConfig config) {
        if (config == null || !config.bonusTimeEnabled()) {
            return false;
        }

        return TimeUtil.isWithin(now, config.bonusTimeStart(), config.bonusTimeEnd());
    }

    /**
     * 计算加成倍率。
     *
     * <p>为什么不做管理员白名单豁免：加成是面向全员的正向福利，长夜白名单只豁免长夜的
     * 负向效果（减速 + 减半），不应当连加成一起豁免；管理员在加成时段做管理事务同样享受
     * 加成，不会破坏平衡（加成只是经验提速，不发放任何物品或属性）。
     *
     * @param active 此刻是否处于加成时段
     * @param config 赛季配置
     * @return 经验倍率；非加成时段恒为 1.0
     */
    public static double resolveXpMultiplier(boolean active, SeasonConfig config) {
        if (!active || config == null || config.bonusTime == null) {
            return 1.0D;
        }

        return config.bonusTime.xpMultiplier;
    }

    /**
     * 计算距离加成窗口结束的分钟数（供首页卡片倒计时显示）。
     *
     * @param now    当前时刻
     * @param config 赛季配置
     * @param active 当前是否处于加成时段
     * @return 剩余分钟；非加成时段返回 0
     */
    public static long minutesRemaining(ZonedDateTime now, SeasonConfig config, boolean active) {
        if (!active || config == null) {
            return 0L;
        }

        return TimeUtil.minutesUntil(now, config.bonusTimeEnd());
    }
}
