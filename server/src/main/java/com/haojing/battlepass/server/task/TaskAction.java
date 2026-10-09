package com.haojing.battlepass.server.task;

/**
 * 用途：任务的"触发动作"类型。它决定这个任务挂在哪个游戏事件上、以及进度从哪来。
 *
 * <p><b>为什么要新增这个字段（需求文档 §18 的 schema 缺了它）</b>：
 * §18 的任务样例只有 {@code filter}，但过滤器里的 {@code BLOCK_ID} 只能表达"涉及某个方块"，
 * **无法区分「放置石头」与「破坏石头」**——两者的过滤条件完全相同。
 * 而 §5.6 要求分别监听放置与破坏，§18 的样例也因此无法表达像"放置 64 个石头"这样的任务。
 * 因此这里补一个显式的 action 字段，并在 docs/需求偏差记录.md 的 D-8 中登记。
 */
public enum TaskAction {

    /** 破坏方块。挂在 PlayerBlockBreakEvents.AFTER 上。 */
    BREAK_BLOCK,

    /** 放置方块。Fabric 无现成事件，挂在 Mixin ServerPlayerInteractionManager 上。 */
    PLACE_BLOCK,

    /** 击杀实体。挂在 ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY 上。 */
    KILL_ENTITY,

    /** 钓鱼成功。Fabric 无现成事件，挂在 Mixin FishingBobberEntity 上。 */
    FISH,

    /** 村民交易。Fabric 无现成事件，挂在 Mixin 交易处理上。 */
    TRADE,

    /** 进入/处于某群系。无服务端事件，由每 20 tick 的轮询比对实现（§5.6 的规定做法）。 */
    ENTER_BIOME,

    /** 进入某维度。同上，由轮询比对实现。 */
    ENTER_DIMENSION,

    /**
     * 抵达某个位置条件（例如"抵达 Y≤-40 的深度"）。
     * 由轮询比对玩家坐标/所在群系/维度实现。需求文档 §18 的样例任务即属于此类。
     */
    REACH_POSITION;

    /**
     * 宽容解析动作名。
     *
     * <p>为什么返回 null 而不是给一个兜底枚举：动作决定任务挂在哪个事件上，
     * 猜错会让任务统计到完全错误的玩家行为上（例如把"放置"当"破坏"）。
     * 因此无法识别的动作由配置校验阶段直接丢弃该任务并告警，绝不猜。
     *
     * @param name 配置里的动作名
     * @return 对应枚举；无法识别时返回 null
     */
    public static TaskAction fromName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }

        for (TaskAction action : values()) {
            if (action.name().equalsIgnoreCase(name.trim())) {
                return action;
            }
        }

        return null;
    }
}
