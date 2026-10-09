package com.haojing.battlepass.server.milestone;

/**
 * 用途：全服里程碑的统计指标（需求文档 §10：需定义统计口径）。
 *
 * <p>为什么指标是枚举而不是自由字符串：里程碑的达成判定必须"有一个明确的计数器"，
 * 而不是让管理员写一个表达式。枚举的价值在于：每个指标都**必然**有一个真实的埋点
 * （见 {@code MilestoneService} 的接入点清单），管理员只能在这些已经埋好的口径里选，
 * 不会配出一个永远为 0 的死指标。
 *
 * <p>为什么所有指标都是"单调递增的累计量"：§10 要求"达成判定"，
 * 累计量天然满足"一旦达成永久保留"，不需要额外的状态机；
 * 也正好符合 §13 要求的"自增计数器"这种可测量的实现方式。
 */
public enum MilestoneMetric {

    /** 全服累计破坏方块数。 */
    BLOCKS_BROKEN("累计破坏方块"),

    /** 全服累计放置方块数。 */
    BLOCKS_PLACED("累计放置方块"),

    /** 全服累计击杀生物数。 */
    MOBS_KILLED("累计击杀生物"),

    /** 全服累计钓鱼成功次数。 */
    FISH_CAUGHT("累计钓鱼成功"),

    /** 全服累计村民交易次数。 */
    TRADES("累计村民交易"),

    /** 全服累计领取任务奖励次数。 */
    TASKS_CLAIMED("累计领取任务"),

    /** 全服累计发放的战令经验（§5.10 上限内的实际发放量）。 */
    BATTLEPASS_XP("累计战令经验"),

    /** 全服累计处于长夜时段的"人·秒"（在线人数 × 秒数）。 */
    LONG_NIGHT_SECONDS("长夜在线人秒"),

    /** 全服累计解锁彩蛋次数。 */
    EGGS_UNLOCKED("累计解锁彩蛋");

    private final String label;

    MilestoneMetric(String label) {
        this.label = label;
    }

    /** @return 中文标签，用于日志与管理面板。 */
    public String label() {
        return label;
    }

    /**
     * 宽容地把字符串转成枚举。
     *
     * @param name 配置里的指标名
     * @return 对应枚举；无法识别时为 null（该里程碑会在校验时被丢弃）
     */
    public static MilestoneMetric fromName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }

        String trimmed = name.trim();

        for (MilestoneMetric metric : values()) {
            if (metric.name().equalsIgnoreCase(trimmed)) {
                return metric;
            }
        }

        return null;
    }
}
