package com.haojing.battlepass.server.egg;

import com.haojing.battlepass.common.data.EggCategory;

/**
 * 用途：单个彩蛋的定义（需求文档 §6、§18 的 eggs.json 样例）。
 *
 * <p>字段与 §18 样例一一对应（id / name / category / enabled / xp / title / condition），
 * 仅额外增加 {@code desc}（管理面板展示用）与 {@code announce}（是否全服公告）。
 *
 * <p>为什么 {@code desc} 存在配置里却**不能发给未解锁的玩家**：§6 明确要求
 * "不在玩家 GUI 展示未解锁彩蛋的描述" —— 彩蛋的乐趣就在于隐藏。
 * 因此描述只出现在管理面板与日志里，玩家侧只有解锁后才能看到名字与描述。
 */
public class EggDefinition {

    /** 彩蛋 ID，池内唯一；也是进度与收藏册的键。 */
    public String id = "";

    /** 彩蛋名（内置彩蛋的名字由 zh_cn.json 提供译文；此字段用于日志与管理面板）。 */
    public String name = "";

    /** 描述。**只给管理员看**（§6：不在玩家 GUI 展示未解锁彩蛋的描述）。 */
    public String desc = "";

    /** 分类名，取值见 {@link EggCategory}。 */
    public String category = EggCategory.GLOBAL.name();

    /** 是否启用。关闭后不再判定，但配置内容保留。 */
    public boolean enabled = true;

    /** 触发奖励的经验。§6：长夜专属彩蛋零经验，配置写了也会被校验修正为 0。 */
    public int xp = 0;

    /** 触发奖励的称号 ID；留空表示不给称号。 */
    public String title = "";

    /** 触发时是否全服公告（默认 true：彩蛋是稀有的，值得让全服看到）。 */
    public boolean announce = true;

    /** 判定条件。 */
    public EggCondition condition = new EggCondition();

    /** 触发后文案的 TranslationKey；留空时按 id 推导（见 {@link #messageKey()}）。 */
    public String messageKey = "";

    /** @return 强类型分类。 */
    public EggCategory categoryOrGlobal() {
        return EggCategory.fromName(category);
    }

    /**
     * @return 触发公告用的翻译键。
     *
     * <p>默认按 {@code haojing_battlepass.egg.<id>} 推导，这样内置彩蛋只要在
     * zh_cn.json 里按同一规律加一条译文即可；管理员新建的彩蛋若没配 messageKey，
     * 客户端找不到译文时会显示这个键名 —— 比默默不发公告更容易发现配置漏了。
     */
    public String messageKey() {
        if (messageKey != null && !messageKey.isBlank()) {
            return messageKey.trim();
        }

        return "haojing_battlepass.egg." + (id == null ? "" : id);
    }

    /** @return 该彩蛋是否要求玩家处于长夜时段（由条件类型推断）。 */
    public boolean requiresLongNight() {
        EggConditionType type = condition == null ? null : condition.typeOrNull();
        return type != null && type.isLongNightOnly();
    }
}
