package com.haojing.battlepass.server.event;

/**
 * 用途：随机事件的参与判定条件（需求文档 §10：需定义参与判定条件）。
 *
 * <p>{@code target} 的含义随类型变化：ONLINE 类型忽略它；其余类型表示
 * "事件期间至少要完成的动作次数"（例如击杀 3 只生物才算参与）。
 */
public class RandomEventParticipation {

    /** 判定类型名，取值见 {@link RandomEventParticipationType}。 */
    public String type = RandomEventParticipationType.ONLINE.name();

    /** 目标次数（ONLINE 类型忽略）。 */
    public int target = 1;

    /** @return 解析后的判定类型。 */
    public RandomEventParticipationType typeOrDefault() {
        return RandomEventParticipationType.fromName(type);
    }

    /** 就地规范化。 */
    public void normalize() {
        type = type == null ? "" : type.trim();

        if (target < 1) {
            target = 1;
        }

        if (type.isEmpty()) {
            type = RandomEventParticipationType.ONLINE.name();
        }
    }
}
