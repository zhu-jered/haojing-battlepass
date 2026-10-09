package com.haojing.battlepass.server.config;

import com.haojing.battlepass.server.time.TimeUtil;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 用途：赛季总配置，对应 config/haojing_battlepass/season.json（需求文档 §18）。
 *
 * <p>为什么把时间字符串的解析做成方法而不是在字段里直接存 LocalTime：
 * 配置是给人编辑的，"06:00" 比序列化后的时间对象可读得多；而 LocalTime 无法表达
 * "写错了"这一状态。存字符串、用时再宽容解析（配合 TimeUtil.parseTime 的 WARN 与兜底），
 * 可以在配置写错时降级运行而不是让模组加载失败。
 */
public class SeasonConfig {

    /** 当前配置结构版本。需求文档 §12 要求所有 JSON 含 schemaVersion 字段。 */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    /** 配置结构版本。将来字段发生不兼容变更时递增，并在 ConfigManager 里做迁移。 */
    public int schemaVersion = CURRENT_SCHEMA_VERSION;

    /** 赛季 ID。用于存档归属判断与赛季归档文件名（data/history/season_&lt;id&gt;.json）。 */
    public String seasonId = "S1";

    /** 赛季主题名，仅用于展示（需求文档 §18 样例为「镐京初章」）。 */
    public String themeName = "镐京初章";

    /** 赛季总开关。需求文档 §4：赛季开关可由管理员编辑。 */
    public boolean enabled = true;

    /** 赛季时长（天）。需求文档 §4 默认 30 天。 */
    public int durationDays = 30;

    /** 等级上限。需求文档 §4 规定等级范围 1~30。 */
    public int maxLevel = 30;

    /** 分支解锁等级。需求文档 §4：10 级时弹窗二选一。 */
    public int branchUnlockLevel = 10;

    /**
     * 每日刷新时刻，HH:mm。需求文档 §5.2：所有「日」边界统一以该时刻为界，
     * 且与长夜起止**解耦**（长夜改时间不影响任务刷新）。
     */
    public String dailyRefreshTime = "06:00";

    /** 每日经验上限（按经验值计）。需求文档 §5.10 默认 500。 */
    public int dailyXpCap = 500;

    /**
     * devMode：跳过客户端强制校验（需求文档 §2 明确要求提供这个开关，默认 false）。
     *
     * <p>用途是本地测试：开着它时原版客户端也能进服，便于在没有安装本 Mod 的
     * 开发环境里验证服务端行为。**正式服必须保持 false** —— 校验本身不是反作弊
     * （见 {@code HandshakeValidator} 的说明），但它决定了玩家进服后能不能看到界面。
     */
    public boolean devMode = false;

    /**
     * 每级所需经验的基础值。需求文档通篇未给经验曲线数值（§4 只给等级范围 1~30），
     * 因此这条曲线由实现方拟定并做成可调参数，取值理由见 docs/需求偏差记录.md（D-14）：
     * 第 N 级升到 N+1 级需要 {@code xpPerLevelBase + xpPerLevelStep × (N-1)} 点经验。
     */
    public int xpPerLevelBase = 50;

    /** 每级所需经验的递增量（见 {@link #xpPerLevelBase}）。 */
    public int xpPerLevelStep = 10;

    /** 每升 1 级发放的星币数。需求文档 §4：每升 1 级 +10 星币。 */
    public int starCoinPerLevel = 10;

    /** 豁免卡持有上限。需求文档 §5.9：每人持有上限默认 3。 */
    public int exemptCardMax = 3;

    /**
     * COMMAND 类奖励允许执行的命令根白名单。需求文档 §9：COMMAND 必须走管理员命令白名单，
     * 禁止将 GUI 输入直接作为命令执行。
     *
     * <p>为什么默认只放这几条：它们都是"发东西/放声音/给效果"这类不会破坏世界的命令。
     * 越是危险的命令（kill / fill / setblock / op …）越应该由管理员显式加进白名单，
     * 而不是由模组默认放开。
     */
    public List<String> commandWhitelist = new ArrayList<>(List.of(
            "give", "title", "tellraw", "playsound", "particle", "effect", "xp", "summon"));

    /** 每日「重 roll 本组」次数上限。需求文档 §5.1：默认每日 1 次，管理员可调。 */
    public int dailyRerollLimit = 1;

    /**
     * 假人（bot）的名字前缀。需求文档 §5.8 要求假人的行为不计入任务进度。
     *
     * <p>为什么用名字前缀而不是别的判据：假人在服务端就是一个正常的 ServerPlayerEntity，
     * 没有任何字段能区分"这是机器人"。用户告知其服务器假人统一以 {@code bot_} 开头，
     * 因此这里按前缀识别；默认值即用户给定的 {@code bot_}，改成空串表示不启用该过滤。
     */
    public String fakePlayerNamePrefix = "bot_";

    /** 长夜时段配置。 */
    public LongNightConfig longNight = new LongNightConfig();

    /**
     * 客户端 GUI 视觉配置（颜色、社团链接等）。
     *
     * <p>这些值只影响客户端渲染，管理员在 season.json 里改完无需重启，
     * 下一次同步（玩家打开战令界面）即生效。非法值会被 {@link ConfigManager} 修正并回退默认。
     */
    public GuiStyleConfig gui = new GuiStyleConfig();

    /** @return 每日刷新时刻；配置非法时回退为 06:00 并已由 TimeUtil 记 WARN。 */
    public LocalTime dailyRefresh() {
        return TimeUtil.parseTime(dailyRefreshTime, LocalTime.of(6, 0));
    }

    /** @return 长夜开始时刻；配置非法时回退为 00:00。 */
    public LocalTime longNightStart() {
        return TimeUtil.parseTime(longNight == null ? null : longNight.start, LocalTime.of(0, 0));
    }

    /** @return 长夜结束时刻；配置非法时回退为 06:00。 */
    public LocalTime longNightEnd() {
        return TimeUtil.parseTime(longNight == null ? null : longNight.end, LocalTime.of(6, 0));
    }

    /** @return 长夜是否处于启用状态（总开关 + 子对象存在）。 */
    public boolean longNightEnabled() {
        return enabled && longNight != null && longNight.enabled;
    }

    /**
     * 客户端 GUI 视觉配置，对应 season.json 的 {@code gui} 节点。
     *
     * <p>为什么颜色用字符串（§ 代码）而不是数字：管理员手改 JSON 时，
     * "§a" 或 "a" 比 "0xFF55FF55" 直观得多；服务端在下发前会解析成 ARGB int，
     * 解析失败的项自动回退默认色并在控制台 WARN，不会让客户端崩。
     *
     * <p>每个字段前都有 {@code _comment_*} 字段，Gson 会把它们原样写进 JSON，
     * 管理员打开 season.json 就能看到中文说明与填写示例。
     */
    public static class GuiStyleConfig {

        /** 配置文件里的中文说明（Gson 会序列化出来；客户端忽略）。 */
        public String _comment = "GUI 外观配置：颜色均填原版 § 代码（如 §a、§6）或颜色名（如 gold、red），留空回退默认；透明度 0~255。";

        /** 任务条目选中框颜色。示例：§c（红色）、§e（金色）。默认 §f（白色）。 */
        public String selectionBorderColor = "§f";

        /** 选中条目背景加深的不透明度（0 全透明 ~ 255 全黑）。默认 96。 */
        public int selectionDimAlpha = 96;

        /** 当前激活 Tab 标签的文字颜色。示例：§e（黄色）。 */
        public String tabActiveTextColor = "§e";

        /** 当前激活 Tab 底色加深的不透明度（0~255）。默认 64。 */
        public int tabActiveOverlayAlpha = 64;

        /** 首页右下角常驻的社团文字。 */
        public String communityText = "支持社团：镐京方块协会";

        /** 社团文字颜色（§ 代码）。默认 §7（浅灰）。 */
        public String communityColor = "§7";

        /** 点击社团文字后在玩家本地浏览器打开的链接；留空则点击无反应。示例：https://qm.qq.com/xxxx */
        public String communityUrl = "";

        /**
         * 商店商品分组 → 文字颜色。键是商品配置里的 group 字段（如 titles / consumable / decor），
         * 值是 § 颜色代码；未配置的分组回退白色。
         */
        public java.util.Map<String, String> shopGroupColors = new java.util.LinkedHashMap<>();

        /** 商店价格数字的颜色（独立于分组颜色）。默认 §6（金色）。 */
        public String shopPriceColor = "§6";
    }
}
