package com.haojing.battlepass.server.egg;

import java.util.ArrayList;
import java.util.List;

/**
 * 用途：§6 末尾那三条"轻量趣味彩蛋"的配置（聊天关键词广播、生日祝贺、节日问候）。
 *
 * <p>为什么放在 {@code eggs.json} 里而不是再开一个文件：它们与彩蛋同属"趣味内容"，
 * 管理面板也是同一处维护；多一个文件就多一份加载/校验/热重载/文档的重复。
 *
 * <p>为什么节日问候允许直接写**字面文案**（{@code message}）而不强制 TranslationKey：
 * §1 要求界面文案走 TranslationKey，但节日是管理员随时新增的内容，
 * 客户端的 zh_cn.json 不可能预知管理员会加什么节日。因此这里支持两种写法：
 * 配了 {@code messageKey} 就走译文（内置节日用），否则用字面文案。
 * 这一点已登记在 docs/需求偏差记录.md。
 */
public class LightEggConfig {

    /** 聊天关键词广播总开关。 */
    public boolean chatKeywordsEnabled = true;

    /** 聊天关键词规则。 */
    public List<ChatKeywordRule> chatKeywords = new ArrayList<>();

    /** 生日祝贺总开关（生日日期由管理员在数据维护里录入，见 {@code GlobalData#birthday}）。 */
    public boolean birthdayEnabled = true;

    /** 生日祝贺文案翻译键。 */
    public String birthdayMessageKey = "haojing_battlepass.light.birthday";

    /** 节日问候列表。 */
    public List<FestivalRule> festivals = new ArrayList<>();

    /** 校验并规范化，非法规则丢弃 + 告警（由 {@link EggPool#validate()} 调用）。 */
    public void normalizeAndDrop(java.util.function.BiConsumer<String, String> warn) {
        if (chatKeywords == null) {
            chatKeywords = new ArrayList<>();
        }

        List<ChatKeywordRule> keptKeywords = new ArrayList<>();

        for (ChatKeywordRule rule : chatKeywords) {
            if (rule == null || rule.keyword == null || rule.keyword.isBlank()) {
                warn.accept("聊天关键词规则缺少 keyword", "已丢弃");
                continue;
            }

            rule.keyword = rule.keyword.trim();

            if (rule.sloganKey == null) {
                rule.sloganKey = "";
            }

            if (rule.slogan == null) {
                rule.slogan = "";
            }

            if (rule.sloganKey.isBlank() && rule.slogan.isBlank()) {
                warn.accept("聊天关键词 " + rule.keyword + " 既没有 sloganKey 也没有 slogan", "已丢弃");
                continue;
            }

            if (rule.cooldownSeconds < 0) {
                rule.cooldownSeconds = 0;
            }

            keptKeywords.add(rule);
        }

        chatKeywords = keptKeywords;

        if (festivals == null) {
            festivals = new ArrayList<>();
        }

        List<FestivalRule> keptFestivals = new ArrayList<>();
        java.util.Set<String> seenIds = new java.util.LinkedHashSet<>();

        for (FestivalRule festival : festivals) {
            if (festival == null || festival.id == null || festival.id.isBlank()) {
                warn.accept("节日缺少 id", "已丢弃");
                continue;
            }

            festival.id = festival.id.trim();

            if (!seenIds.add(festival.id)) {
                warn.accept("节日 id 重复：" + festival.id, "只保留第一次出现的");
                continue;
            }

            if (festival.messageKey == null) {
                festival.messageKey = "";
            }

            if (festival.message == null) {
                festival.message = "";
            }

            if (festival.messageKey.isBlank() && festival.message.isBlank()) {
                warn.accept("节日 " + festival.id + " 既没有 messageKey 也没有 message", "已丢弃");
                continue;
            }

            // 日期用 MM-dd（每年循环），空串表示该端不设限。
            festival.startDate = normalizeMonthDay(festival.startDate);
            festival.endDate = normalizeMonthDay(festival.endDate);

            keptFestivals.add(festival);
        }

        festivals = keptFestivals;
    }

    private static String normalizeMonthDay(String value) {
        // 容忍管理员写成 2026-10-01 这种完整日期：只取月-日部分，保证"每年循环"的语义。
        return com.haojing.battlepass.server.time.MonthDayWindow.normalize(value);
    }

    /** 聊天关键词规则。 */
    public static class ChatKeywordRule {

        /** 关键词（忽略大小写匹配，包含即命中）。 */
        public String keyword = "";

        /** 广播文案翻译键（内置词组用）。 */
        public String sloganKey = "";

        /** 广播文案字面文本（管理员自定义时用）。 */
        public String slogan = "";

        /** 同一玩家的冷却秒数，避免刷屏（0 表示不限）。 */
        public int cooldownSeconds = 60;
    }

    /** 节日问候规则。 */
    public static class FestivalRule {

        /** 节日 ID（同一天只问候一次的去重键）。 */
        public String id = "";

        /** 节日名（日志与管理面板展示）。 */
        public String name = "";

        /** 起始日期 MM-dd（空表示不限）。 */
        public String startDate = "";

        /** 结束日期 MM-dd（空表示不限）。 */
        public String endDate = "";

        /** 问候文案翻译键。 */
        public String messageKey = "";

        /** 问候文案字面文本（与 messageKey 二选一）。 */
        public String message = "";
    }
}
