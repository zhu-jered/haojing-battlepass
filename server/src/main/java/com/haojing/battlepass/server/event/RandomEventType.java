package com.haojing.battlepass.server.event;

/**
 * 用途：随机事件的类型枚举（需求文档 §10 明确要求"需定义事件类型枚举"）。
 *
 * <p>为什么类型只是"分类标签"而不决定具体效果：效果由配置里的
 * {@link EventEffect} 列表描述（药水、刷怪、物品、命令、经验倍率）。
 * 这样管理员新增一种事件时**不需要改代码**，而 §9 又要求世界随机事件能在管理面板里编辑。
 * 类型本身仍然有用：它决定公告文案的组织方式、参与判定的默认值，以及日志里的归类。
 *
 * <p>为什么默认给出这 5 种：它们是"全天可触发、长夜也能跑"（§10）且不需要额外美术资源的
 * 常见玩法。类型名与默认内容都是可调默认值，已登记在 docs/需求偏差记录.md。
 */
public enum RandomEventType {

    /** 怪物潮：短时间内刷出一批怪物，考验防守。 */
    MOB_SURGE("怪物潮"),

    /** 天降补给：给在线玩家发一批物资。 */
    SUPPLY_DROP("天降补给"),

    /** 双倍经验：事件期间战令经验翻倍。 */
    DOUBLE_XP("双倍经验"),

    /** 陨石雨：在玩家附近刷出危险实体/特效的限时挑战。 */
    METEOR_SHOWER("陨石雨"),

    /** 商队集结：给玩家临时增益与交易奖励。 */
    CARAVAN("商队集结");

    private final String label;

    RandomEventType(String label) {
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
     * @return 对应枚举；无法识别时为 null（该事件会在校验时被丢弃）
     */
    public static RandomEventType fromName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }

        String trimmed = name.trim();

        for (RandomEventType type : values()) {
            if (type.name().equalsIgnoreCase(trimmed)) {
                return type;
            }
        }

        return null;
    }
}
