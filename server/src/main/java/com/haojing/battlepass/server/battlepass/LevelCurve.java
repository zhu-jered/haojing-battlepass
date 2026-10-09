package com.haojing.battlepass.server.battlepass;

import com.haojing.battlepass.server.config.SeasonConfig;

/**
 * 用途：战令等级与经验的换算规则（需求文档 §4：等级范围 1~30）。
 *
 * <p>为什么把曲线单独抽成一个纯函数类：需求文档**通篇没有给出经验曲线数值**
 * （§4 只规定等级范围，§18 的样例里也没有），这条曲线由实现方拟定并做成可调参数
 * （见 docs/需求偏差记录.md 的 D-14）。既然数值将来一定会被调整，就必须让
 * "升级判定"在一处、可被单元测试直接覆盖，而不是散落在升级逻辑的循环里。
 *
 * <p>曲线定义：从 N 级升到 N+1 级需要 {@code base + step × (N-1)} 点经验。
 * 默认 base=50、step=10，即 1→2 级 50 点、2→3 级 60 点……29→30 级 330 点，
 * 从 1 级练满 30 级共需 5510 点经验。
 *
 * <p>为什么是"递增"而不是固定值：§4 把等级上限设为 30 但没给时长约束；
 * 递增曲线能让前期升级快、后期有目标感，同时把总量控制在
 * "约 110 个每日任务"的量级，与 §5.10（每日经验上限 500）配合后不会出现
 * 一天连升数级的失衡情况。
 */
public final class LevelCurve {

    /** 兜底曲线参数：配置缺失或非法时使用（与 {@link SeasonConfig} 的默认值一致）。 */
    private static final int FALLBACK_BASE = 50;

    private static final int FALLBACK_STEP = 10;

    private LevelCurve() {
    }

    /**
     * 计算从 {@code level} 级升到下一级所需经验。
     *
     * @param config 赛季配置；为 null 时使用兜底曲线
     * @param level  当前等级（1 起）
     * @return 所需经验；等级已到上限或参数异常时返回 0（调用方据此判断"无法再升级"）
     */
    public static int xpForLevel(SeasonConfig config, int level) {
        if (level < 1) {
            return 0;
        }

        int maxLevel = config == null ? 30 : config.maxLevel;

        if (level >= maxLevel) {
            // 满级之后不需要经验，返回 0 让升级循环自然停下。
            return 0;
        }

        int base = config == null ? FALLBACK_BASE : config.xpPerLevelBase;
        int step = config == null ? FALLBACK_STEP : config.xpPerLevelStep;

        if (base < 0 || step < 0) {
            // 配置层已把负数修正为 0，这里再兜一层：负经验会让 while 循环的行为变得不可预测。
            base = FALLBACK_BASE;
            step = FALLBACK_STEP;
        }

        long need = (long) base + (long) step * (level - 1L);

        // 用 long 计算再夹紧：step 被管理员写得很大时（例如 100000），int 乘法会溢出成负数。
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, need));
    }

    /**
     * 计算从 1 级累计到 {@code level} 级所需的总经验。供管理面板（阶段 7）显示与数据核对使用。
     *
     * @param config 赛季配置
     * @param level  目标等级
     * @return 总经验；level ≤ 1 时为 0
     */
    public static long totalXpToReach(SeasonConfig config, int level) {
        long total = 0L;

        for (int current = 1; current < level; current++) {
            total += xpForLevel(config, current);
        }

        return total;
    }
}
