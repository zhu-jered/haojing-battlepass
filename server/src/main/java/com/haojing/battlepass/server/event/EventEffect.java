package com.haojing.battlepass.server.event;

/**
 * 用途：随机事件的一条效果（需求文档 §10 的"类型/持续时长"落到可配置的效果列表）。
 *
 * <p>字段是各类效果的并集，哪种类型读哪些字段见 {@link EventEffectKind} 与 {@code RandomEventEffects}：
 * <ul>
 *   <li>{@code POTION}：value = 效果 ID，amount = 等级（0 为 I 级），durationSeconds 缺省时用事件时长</li>
 *   <li>{@code SPAWN}：value = 实体 ID，amount = 数量</li>
 *   <li>{@code ITEM}：value = 物品 ID，amount = 数量（事件结束时发给参与者）</li>
 *   <li>{@code COMMAND}：value = 命令（不带斜杠，必须命中命令白名单）</li>
 *   <li>{@code XP_MULTIPLIER}：multiplier = 倍率（例如 2.0）</li>
 * </ul>
 */
public class EventEffect {

    /** 效果类型名，取值见 {@link EventEffectKind}。 */
    public String kind = "";

    /** 通用取值：效果 ID / 实体 ID / 物品 ID / 命令。 */
    public String value = "";

    /** 数量或等级。 */
    public int amount = 1;

    /** 持续秒数（0 表示与事件时长一致；仅对 POTION 有意义）。 */
    public int durationSeconds = 0;

    /** 倍率（仅对 XP_MULTIPLIER 有意义）。 */
    public double multiplier = 1.0D;

    /** 就地规范化。 */
    public void normalize() {
        kind = kind == null ? "" : kind.trim();
        value = value == null ? "" : value.trim();

        while (value.startsWith("/")) {
            value = value.substring(1).trim();
        }

        if (amount < 0) {
            amount = 0;
        }

        if (durationSeconds < 0) {
            durationSeconds = 0;
        }

        if (multiplier < 0) {
            multiplier = 0.0D;
        }
    }

    /** @return 效果类型；无法识别时为 null。 */
    public EventEffectKind kindOrNull() {
        return EventEffectKind.fromName(kind);
    }

    /**
     * @return 不合法时的中文原因；合法时为 null。
     */
    public String validateError() {
        EventEffectKind resolved = kindOrNull();

        if (resolved == null) {
            return "无法识别的效果类型：" + kind;
        }

        switch (resolved) {
            case POTION:
            case SPAWN:
            case ITEM:
                return value.isBlank() ? resolved.label() + " 缺少 value（ID）" : null;
            case COMMAND:
                return value.isBlank() ? "COMMAND 效果缺少命令" : null;
            case XP_MULTIPLIER:
                return multiplier <= 0 ? "XP_MULTIPLIER 的 multiplier 必须为正数" : null;
            default:
                return null;
        }
    }
}
