package com.haojing.battlepass.server.battlepass;

/**
 * 用途：经验来源标记。需求文档 §4 列出的经验来源为「每日任务、每周挑战、全局/赛季限定彩蛋、
 * 世界随机事件、里程碑」，加上阶段 5 新增的等级奖励与管理员数据维护。
 *
 * <p>为什么需要这个枚举而不是只传一个数字：日志与排障时要能回答
 * "这个玩家的经验是从哪来的"。§13 要求给出可测量的性能说明，§16 又有多条
 * 与经验发放有关的验收用例；带来源的日志是唯一能事后核对的手段。
 *
 * <p>为什么不在这里做任何业务判断（例如"彩蛋经验不受长夜减半影响"）：
 * 那条规则属于彩蛋系统（阶段 6），阶段 5 只提供来源标记，
 * 具体哪个来源走不走减半由调用方在阶段 6 决定。
 */
public enum XpSource {

    /** 每日任务领取。 */
    DAILY_TASK("每日任务"),

    /** 每周挑战领取。 */
    WEEKLY_TASK("每周挑战"),

    /** 等级奖励表里的 BATTLEPASS_XP。 */
    LEVEL_REWARD("等级奖励"),

    /** 商店兑换里的 BATTLEPASS_XP。 */
    SHOP("商店兑换"),

    /** 彩蛋（阶段 6）。 */
    EGG("彩蛋"),

    /** 世界随机事件（阶段 6）。 */
    RANDOM_EVENT("随机事件"),

    /** 全服里程碑（阶段 6）。 */
    MILESTONE("里程碑"),

    /** 管理员手动发放（阶段 7 的数据维护）。 */
    ADMIN("管理员发放");

    private final String label;

    XpSource(String label) {
        this.label = label;
    }

    /** @return 中文标签，仅用于日志。 */
    public String label() {
        return label;
    }
}
