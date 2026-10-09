package com.haojing.battlepass.server.task;

/**
 * 用途：单个任务的定义，对应需求文档 §18 里任务池的一项。
 *
 * <p>字段与 §18 样例一一对应，仅额外增加 {@code action} 字段，原因见 {@link TaskAction} 的说明：
 * 原样例的过滤器无法区分"放置"与"破坏"，而 §5.6 要求分别监听。
 *
 * <p>为什么这些"可调默认值"不作为常量写死在代码里：需求文档 §5.1 要求池规模
 * 每组 12 个且同日/跨日不重复，具体任务内容必须可被管理员通过管理面板增删改（§9）。
 * 因此任务定义全部来自 config/haojing_battlepass/daily_tasks.json，代码只负责读与校验。
 */
public class TaskDefinition {

    /** 任务 ID，池内唯一。玩家存档里记录的进度就是按它索引的。 */
    public String id = "";

    /** 触发动作名，取值见 {@link TaskAction}。 */
    public String action = "";

    /** 任务名（管理面板展示用；玩家界面文案后续走 TranslationKey）。 */
    public String name = "";

    /** 任务描述。 */
    public String desc = "";

    /** 目标进度值，例如 64 表示"64 个方块"。 */
    public int target = 1;

    /** 完成奖励的经验。 */
    public int xp = 0;

    /** 完成奖励的星币。需求文档 §4：星币主要来自升级，任务可少量给。 */
    public int starCoin = 0;

    /** 过滤器；null 表示无条件（任何同类行为都计入）。 */
    public FilterNode filter;

    /** @return 解析后的动作；无法识别时为 null（该任务会在池校验阶段被丢弃）。 */
    public TaskAction actionOrNull() {
        return TaskAction.fromName(action);
    }

    /** @return 目标进度是否合法（必须为正数）。 */
    public boolean hasValidTarget() {
        return target > 0;
    }
}
