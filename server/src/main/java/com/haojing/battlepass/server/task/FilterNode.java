package com.haojing.battlepass.server.task;

import java.util.List;

/**
 * 用途：任务过滤器的节点模型，对应需求文档 §5.7「过滤器系统」与 §18 样例里的 {@code filter} 字段。
 *
 * <p>同一个类同时表达两种节点，因为 §18 的 JSON 就是这么写的：
 * <pre>
 * // 组合节点（有 op）
 * { "op": "AND", "conditions": [ {...}, {...} ] }
 * // 叶子条件（有 type）
 * { "type": "Y_RANGE", "min": -64, "max": -40 }
 * </pre>
 * 组合节点还可以继续嵌套组合节点，因此 §5.7 要求的「支持 AND/OR 嵌套组合」天然成立。
 *
 * <p>为什么叶子条件的取值支持 {@code value} 与 {@code values} 两种写法：
 * §5.7 只写了"方块 ID、实体 ID"这类单数措辞，但现实任务常常是"任意一种原木/任意一种鱼"。
 * 用 {@code value} 写单个、{@code values} 写一组，两者取"或"，避免为了表达一组而硬套出嵌套 OR。
 */
public class FilterNode {

    /** 叶子条件的类型。 */
    public enum ConditionType {
        /** 方块 ID，如 minecraft:stone。 */
        BLOCK_ID,
        /** 实体 ID，如 minecraft:zombie。 */
        ENTITY_ID,
        /** 群系 ID，如 minecraft:cherry_grove。 */
        BIOME,
        /** 维度 ID，如 minecraft:overworld。 */
        DIMENSION,
        /** Y 高度区间（闭区间），用 min/max 表达，缺省一侧表示不设限。 */
        Y_RANGE,
        /** 北京时间时段，用 from/to 表达，支持跨零点（如 22:00 ~ 06:00）。 */
        TIME_RANGE;

        /** @param name 配置里的类型名 @return 对应枚举；无法识别时返回 null。 */
        public static ConditionType fromName(String name) {
            if (name == null || name.isBlank()) {
                return null;
            }

            for (ConditionType type : values()) {
                if (type.name().equalsIgnoreCase(name.trim())) {
                    return type;
                }
            }

            return null;
        }
    }

    /** 组合运算符，取值为 AND / OR。仅组合节点使用。 */
    public String op;

    /** 子节点列表。仅组合节点使用。 */
    public List<FilterNode> conditions;

    /** 叶子条件类型。仅叶子节点使用。 */
    public String type;

    /** 单值条件（方块 ID / 实体 ID / 群系 / 维度）。 */
    public String value;

    /** 多值条件，与 {@link #value} 取"或"。 */
    public List<String> values;

    /** Y 区间下界（含）。 */
    public Integer min;

    /** Y 区间上界（含）。 */
    public Integer max;

    /** 时段起点，HH:mm。 */
    public String from;

    /** 时段终点，HH:mm。 */
    public String to;

    /** @return 是否为组合节点（AND/OR）。 */
    public boolean isGroup() {
        return op != null && !op.isBlank();
    }

    /** @return 是否可识别的叶子条件。 */
    public boolean isLeaf() {
        return !isGroup() && ConditionType.fromName(type) != null;
    }
}
