package com.haojing.battlepass.server.event;

/**
 * 用途：随机事件效果的种类（需求文档 §10：事件需要"持续时长、参与判定条件、奖励表"，
 * 效果本身也必须能被管理员配置，否则新增一种事件就得改代码）。
 *
 * <p>为什么效果不做成任意脚本：COMMAND 效果已经能覆盖"高级自定义"，
 * 而任意脚本会让配置变成事实上的代码，既无法校验也无法在管理面板里安全编辑。
 * 这里只提供 5 种可校验的效果类型，需要更复杂的行为时用 COMMAND（走白名单）。
 */
public enum EventEffectKind {

    /** 给在线玩家施加状态效果（{@code value} = 效果 ID，{@code amount} = 等级）。 */
    POTION("状态效果"),

    /** 在在线玩家附近刷出实体（{@code value} = 实体 ID，{@code amount} = 数量）。 */
    SPAWN("刷出实体"),

    /** 直接发物品（{@code value} = 物品 ID，{@code amount} = 数量；事件结束时发给参与者）。 */
    ITEM("发放物品"),

    /** 执行一条管理员命令（{@code value} = 命令，必须命中命令白名单）。 */
    COMMAND("执行命令"),

    /** 事件期间的经验倍率（{@code multiplier}，例如 2.0 表示双倍）。 */
    XP_MULTIPLIER("经验倍率");

    private final String label;

    EventEffectKind(String label) {
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
     * @return 对应枚举；无法识别时为 null（该效果会在校验时被丢弃）
     */
    public static EventEffectKind fromName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }

        String trimmed = name.trim();

        for (EventEffectKind kind : values()) {
            if (kind.name().equalsIgnoreCase(trimmed)) {
                return kind;
            }
        }

        return null;
    }
}
