package com.haojing.battlepass.server.egg;

/**
 * 用途：一次彩蛋判定的结果。
 *
 * @param triggered 是否达成了触发条件（调用方据此发放奖励并记入收藏册）
 * @param changed   本次判定是否修改了进度（调用方据此决定要不要标脏落盘）
 */
public record EggOutcome(boolean triggered, boolean changed) {

    /** 什么都没发生。 */
    public static final EggOutcome NONE = new EggOutcome(false, false);

    /** 进度有变化但还没达成。 */
    public static final EggOutcome PROGRESS = new EggOutcome(false, true);

    /** 达成。 */
    public static final EggOutcome TRIGGERED = new EggOutcome(true, true);

    /**
     * @param changed 是否发生了变化
     * @return 变化时为 {@link #PROGRESS}，否则为 {@link #NONE}
     */
    public static EggOutcome of(boolean changed) {
        return changed ? PROGRESS : NONE;
    }
}
