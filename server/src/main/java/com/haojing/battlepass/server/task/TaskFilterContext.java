package com.haojing.battlepass.server.task;

import java.time.LocalTime;

/**
 * 用途：一次待判定行为所携带的上下文，供 {@link TaskFilterEvaluator} 判断是否命中某个任务。
 *
 * <p>为什么用"一次性把所有维度都塞进来"的扁平结构，而不是每种行为一个子类：
 * 过滤器是配置驱动的、任意组合的，运行时并不知道（也不该知道）"这个任务到底在看什么字段"。
 * 扁平上下文让"事件 → 过滤器"的匹配变成一次纯粹的值比较，不需要任何类型分发。
 * 用不到的字段留 null，相应的条件会被判为不匹配（保守方向），不会误计。
 *
 * @param blockId     涉及的方块 ID；非方块类行为为 null
 * @param entityId    涉及的实体 ID；非击杀类行为为 null
 * @param biomeId     玩家所在群系 ID；非位置类行为可为 null
 * @param dimensionId 玩家所在维度 ID
 * @param y           玩家 Y 坐标；非位置类行为可为 null
 * @param time        行为发生时的北京时间
 */
public record TaskFilterContext(String blockId, String entityId, String biomeId,
                                String dimensionId, Integer y, LocalTime time) {

    /** 破坏/放置方块时的上下文。 */
    public static TaskFilterContext block(String blockId, String dimensionId, Integer y, LocalTime time) {
        return new TaskFilterContext(blockId, null, null, dimensionId, y, time);
    }

    /** 击杀实体时的上下文。 */
    public static TaskFilterContext entity(String entityId, String dimensionId, Integer y, LocalTime time) {
        return new TaskFilterContext(null, entityId, null, dimensionId, y, time);
    }

    /** 与具体实体/方块无关的行为（钓鱼、交易）。 */
    public static TaskFilterContext generic(String biomeId, String dimensionId, Integer y, LocalTime time) {
        return new TaskFilterContext(null, null, biomeId, dimensionId, y, time);
    }

    /** 位置类判定（群系切换、进入维度、抵达坐标）。 */
    public static TaskFilterContext position(String biomeId, String dimensionId, Integer y, LocalTime time) {
        return new TaskFilterContext(null, null, biomeId, dimensionId, y, time);
    }
}
