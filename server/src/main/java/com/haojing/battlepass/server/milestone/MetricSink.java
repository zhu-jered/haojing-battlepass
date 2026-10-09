package com.haojing.battlepass.server.milestone;

/**
 * 用途：统计埋点的统一出口。任何"想给里程碑贡献数字"的模块都通过它上报，
 * 而不必直接依赖 {@code MilestoneService}。
 *
 * <p>为什么要这个接口：埋点散落在任务事件、战令发经验、彩蛋解锁等好几个模块里。
 * 若它们各自直接引用 MilestoneService，就会出现"里程碑没加载时整条业务链崩掉"
 * （例如管理员把 milestones.json 删坏了）这种本不该发生的事故。
 * 有了接口 + {@link #NOOP}，里程碑不可用时业务照常，只是不计数。
 */
@FunctionalInterface
public interface MetricSink {

    /** 里程碑不可用时使用的空实现。 */
    MetricSink NOOP = (metric, amount) -> {
    };

    /**
     * 累加一个指标。
     *
     * @param metric 指标
     * @param amount 增量（通常为 1；长夜在线时长会是秒数）
     */
    void add(MilestoneMetric metric, long amount);
}
