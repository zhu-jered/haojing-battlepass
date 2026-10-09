package com.haojing.battlepass.server.event;

import com.haojing.battlepass.common.data.Reward;

import java.util.ArrayList;
import java.util.List;

/**
 * 用途：一个世界随机事件的定义（需求文档 §10、§9"世界随机事件"可编辑）。
 *
 * <p>§10 要求的五件事在这里都能配：事件类型（{@code type}）、触发概率（在
 * {@link RandomEventConfig} 里统一配）、最小间隔（同上）、持续时长（{@code durationMinutes}）、
 * 参与判定（{@code participation}）、奖励表（{@code rewards}），外加效果（{@code effects}）。
 *
 * <p>公告文案走 TranslationKey（§1），键名默认按 id 推导
 * （{@code haojing_battlepass.event.<id>.start} / {@code .end}）。
 * 管理员新建的事件在 zh_cn.json 里没有译文时，用 {@code startMessage} / {@code endMessage}
 * 作为字面兜底 —— 这一点登记在 docs/需求偏差记录.md。
 */
public class RandomEventDefinition {

    /** 事件 ID，配置内唯一。 */
    public String id = "";

    /** 事件名（日志与管理面板）。 */
    public String name = "";

    /** 事件类型名，取值见 {@link RandomEventType}。 */
    public String type = "";

    /** 是否启用。 */
    public boolean enabled = true;

    /** 持续分钟数（§10 要求的"持续时长"）。 */
    public int durationMinutes = 10;

    /** 随机权重（同类候选之间按权重抽）。 */
    public int weight = 1;

    /** 参与判定条件。 */
    public RandomEventParticipation participation = new RandomEventParticipation();

    /** 事件期间/结束时生效的效果列表。 */
    public List<EventEffect> effects = new ArrayList<>();

    /** 参与者奖励表（事件结束时发放）。 */
    public List<Reward> rewards = new ArrayList<>();

    /** 开始公告文案翻译键；留空按 id 推导。 */
    public String startMessageKey = "";

    /** 结束公告文案翻译键；留空按 id 推导。 */
    public String endMessageKey = "";

    /** 开始公告的字面兜底文案（没配译文时使用）。 */
    public String startMessage = "";

    /** 结束公告的字面兜底文案。 */
    public String endMessage = "";

    /** @return 解析后的事件类型；无法识别时为 null。 */
    public RandomEventType typeOrNull() {
        return RandomEventType.fromName(type);
    }

    /** @return 开始公告翻译键。 */
    public String startKey() {
        return startMessageKey != null && !startMessageKey.isBlank()
                ? startMessageKey.trim()
                : "haojing_battlepass.event." + id + ".start";
    }

    /** @return 结束公告翻译键。 */
    public String endKey() {
        return endMessageKey != null && !endMessageKey.isBlank()
                ? endMessageKey.trim()
                : "haojing_battlepass.event." + id + ".end";
    }

    /** @return 长夜期间允许触发的说明（§10：全天可触发，长夜期间正常运行）。 */
    public boolean allowsDuringLongNight() {
        // §10 明确要求长夜期间正常运行，因此这里恒为 true；
        // 保留这个方法是为了在配置校验里给出显式依据，将来若要加开关只改这一处。
        return true;
    }

    /** 就地规范化。 */
    public void normalize() {
        id = id == null ? "" : id.trim();
        name = name == null ? "" : name.trim();
        type = type == null ? "" : type.trim();

        if (participation == null) {
            participation = new RandomEventParticipation();
        }

        participation.normalize();

        if (effects == null) {
            effects = new ArrayList<>();
        }

        if (rewards == null) {
            rewards = new ArrayList<>();
        }
    }
}
