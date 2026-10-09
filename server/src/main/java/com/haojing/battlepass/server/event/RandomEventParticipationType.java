package com.haojing.battlepass.server.event;

/**
 * 用途：随机事件的"参与判定"类型（需求文档 §10：需定义参与判定条件）。
 *
 * <p>为什么把参与判定做成可配的类型 + 目标次数：§10 要求"参与判定条件"必须被定义，
 * 而不同事件天然有不同的参与方式 —— 怪物潮看击杀、双倍经验看在线时长、
 * 商队集结看交易。做成枚举后，管理员既能选现成的口径，
 * 也能通过"目标次数"控制门槛（例如"击杀 3 只才算参与"）。
 */
public enum RandomEventParticipationType {

    /** 事件开始时在线即算参与。 */
    ONLINE("开始时在线"),

    /** 事件期间击杀指定数量的生物。 */
    KILL_ENTITY("事件期间击杀"),

    /** 事件期间破坏指定数量的方块。 */
    BREAK_BLOCK("事件期间破坏方块"),

    /** 事件期间放置指定数量的方块。 */
    PLACE_BLOCK("事件期间放置方块"),

    /** 事件期间钓鱼成功指定次数。 */
    FISH("事件期间钓鱼成功"),

    /** 事件期间完成指定次数的村民交易。 */
    TRADE("事件期间交易");

    private final String label;

    RandomEventParticipationType(String label) {
        this.label = label;
    }

    /** @return 中文标签，用于日志与管理面板。 */
    public String label() {
        return label;
    }

    /**
     * 宽容地把字符串转成枚举。
     *
     * @param name 配置里的类型名
     * @return 对应枚举；无法识别或为空时返回 {@link #ONLINE}（最宽松的口径，不会让玩家白参与）
     */
    public static RandomEventParticipationType fromName(String name) {
        if (name == null || name.isBlank()) {
            return ONLINE;
        }

        String trimmed = name.trim();

        for (RandomEventParticipationType type : values()) {
            if (type.name().equalsIgnoreCase(trimmed)) {
                return type;
            }
        }

        return ONLINE;
    }
}
