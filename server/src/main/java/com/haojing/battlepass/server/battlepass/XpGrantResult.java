package com.haojing.battlepass.server.battlepass;

/**
 * 用途：一次经验发放的产出，供调用方（事件、任务领取、商店、管理指令）判断发生了什么。
 *
 * <p>为什么返回一个结构而不是 boolean：需求文档 §8 的 GUI 要显示"今日剩余可获取经验"、
 * 升级弹窗与星币到账提示（阶段 7），而 §5.10 又规定"超限后进度照涨但不发经验"——
 * 这些都是"发经验"这一个动作的不同结果，用一个结果对象把它们一次说清，
 * 好过让每个调用方自己去猜"没发到经验是因为满级、因为超限、还是因为倍率取整取没了"。
 *
 * @param requested          请求发放的原始经验（未乘倍率、未受上限约束）
 * @param granted            实际计入进度条的经验
 * @param multiplier         本次生效的经验倍率（§5.11 长夜 0.5；白名单管理员为 1.0）
 * @param cappedByDailyLimit 是否被 §5.10 的每日经验上限截断
 * @param maxLevelReached    是否因为已经满级而完全没有发放
 * @param levelBefore        发放前等级
 * @param levelAfter         发放后等级
 * @param starCoinGained     本次因升级获得的星币（§4：每升 1 级 +10）
 * @param dailyXpEarnedAfter 发放后"本业务日已获得经验"
 */
public record XpGrantResult(int requested, int granted, double multiplier, boolean cappedByDailyLimit,
                            boolean maxLevelReached, int levelBefore, int levelAfter,
                            int starCoinGained, int dailyXpEarnedAfter) {

    /** @return 本次是否发生了升级。 */
    public boolean leveledUp() {
        return levelAfter > levelBefore;
    }

    /** @return 本次是否什么都没发出去（满级、经验不足取整为 0、或已被上限卡死）。 */
    public boolean grantedNothing() {
        return granted <= 0;
    }

    /** @return 一次未产生任何效果的发放结果（用于参数非法等短路场景）。 */
    public static XpGrantResult none(int level, int dailyXpEarned) {
        return new XpGrantResult(0, 0, 1.0D, false, false, level, level, 0, dailyXpEarned);
    }
}
