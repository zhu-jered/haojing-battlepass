package com.haojing.battlepass.server.milestone;

import com.haojing.battlepass.common.data.Reward;

import java.util.ArrayList;
import java.util.List;

/**
 * 用途：全服里程碑的定义（需求文档 §10：需定义统计口径、达成判定、奖励发放对象）。
 */
public class MilestoneDefinition {

    /** 统计口径。 */
    public enum Scope {
        /** 全服累计：直接比计数器。 */
        TOTAL,
        /**
         * 人均：计数器 ÷ 协会玩家总数。
         *
         * <p>为什么要提供人均：§10 明确要求"统计口径（全服累计 / 人均）"两种都能定义。
         * 人均口径能让"人少的小服"和"人多的大服"用同一条阈值，
         * 但它的分母需要遍历玩家目录，因此分母会被缓存（见 {@code MilestoneService}）。
         */
        PER_CAPITA
    }

    /** 奖励发放对象。 */
    public enum Recipients {
        /** 全服：所有有存档的玩家（含离线，离线只能收到数据类奖励）。 */
        ALL,
        /** 参与者：达成那一刻在线的玩家。 */
        PARTICIPANTS
    }

    /** 里程碑 ID，配置内唯一。 */
    public String id = "";

    /** 名称（管理面板与公告用；公告文案默认按 id 推导 TranslationKey）。 */
    public String name = "";

    /** 指标名，取值见 {@link MilestoneMetric}。 */
    public String metric = "";

    /** 统计口径名，取值见 {@link Scope}。 */
    public String scope = Scope.TOTAL.name();

    /** 达成阈值（对人均口径而言是人均值）。 */
    public long threshold = 1L;

    /** 发放对象名，取值见 {@link Recipients}。 */
    public String recipients = Recipients.ALL.name();

    /** 是否启用。 */
    public boolean enabled = true;

    /** 达成后是否全服公告。 */
    public boolean announce = true;

    /** 达成奖励（可多条）。 */
    public List<Reward> rewards = new ArrayList<>();

    /** @return 解析后的指标；无法识别时为 null。 */
    public MilestoneMetric metricOrNull() {
        return MilestoneMetric.fromName(metric);
    }

    /** @return 解析后的口径；无法识别时按全服累计处理。 */
    public Scope scopeOrDefault() {
        return parseScope(scope);
    }

    /** @return 解析后的发放对象；无法识别时按全服处理。 */
    public Recipients recipientsOrDefault() {
        return parseRecipients(recipients);
    }

    /** @return 公告文案翻译键（默认按 id 推导）。 */
    public String messageKey() {
        return "haojing_battlepass.milestone." + (id == null ? "" : id);
    }

    /** 宽容解析口径。 */
    public static Scope parseScope(String name) {
        if (name != null) {
            for (Scope scope : Scope.values()) {
                if (scope.name().equalsIgnoreCase(name.trim())) {
                    return scope;
                }
            }
        }

        return Scope.TOTAL;
    }

    /** 宽容解析发放对象。 */
    public static Recipients parseRecipients(String name) {
        if (name != null) {
            for (Recipients recipients : Recipients.values()) {
                if (recipients.name().equalsIgnoreCase(name.trim())) {
                    return recipients;
                }
            }
        }

        return Recipients.ALL;
    }

    /** 就地规范化。 */
    public void normalize() {
        id = id == null ? "" : id.trim();
        name = name == null ? "" : name.trim();
        metric = metric == null ? "" : metric.trim();
        scope = scope == null ? "" : scope.trim();
        recipients = recipients == null ? "" : recipients.trim();

        if (rewards == null) {
            rewards = new ArrayList<>();
        }
    }
}
