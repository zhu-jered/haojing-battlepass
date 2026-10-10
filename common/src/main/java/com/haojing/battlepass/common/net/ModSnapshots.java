package com.haojing.battlepass.common.net;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用途：服务端下发给客户端的状态快照（需求文档 §8 各分页要显示的内容）。
 *
 * <p>为什么快照是"扁平 DTO 的集合"而不是直接把服务端的业务对象发过去：
 * 业务对象里带着过滤器、条件树、内部标记等**玩家不该看到的东西**
 * （§6 甚至明确要求未解锁彩蛋的描述不能给玩家看）。
 * 快照是一次显式的"允许客户端知道什么"的清单，漏项是编译期可见的，
 * 而"把整个对象发过去"很容易在不经意间泄漏内部数据。
 *
 * <p>全部字段用 public + Gson（与本项目其它 DTO 一致），
 * 并且都带默认值 —— 老客户端缺少某个字段时不会 NPE。
 */
public final class ModSnapshots {

    private ModSnapshots() {
    }

    /** 首页：玩家自身状态（§8：等级、经验进度条、今日剩余可获取经验、服务器状态与倒计时）。 */
    public static class Player {

        public String seasonId = "";
        public String themeName = "";
        public int level = 1;
        public int maxLevel = 30;
        public int xp = 0;
        public int xpForNext = 0;
        /** -1 表示"不限制"（配置里 dailyXpCap = 0）。 */
        public int remainingDailyXp = 0;
        public int starCoin = 0;
        public int exemptCards = 0;
        public int rerollUsed = 0;
        public int rerollLimit = 1;
        public String branch = "NONE";
        public int branchUnlockLevel = 10;
        /** 是否应当弹出分支选择界面（等级已到、尚未选择）。 */
        public boolean branchPrompt = false;
        public boolean longNight = false;
        public int longNightMinutesRemaining = 0;
        /** 赛季滚动后尚未播报的公告内容（由服务端拼好的可读文本），空串表示没有。 */
        public String pendingSeasonNotice = "";

        /** GUI 视觉配置（颜色、社团链接等）。字段自带默认值，老客户端缺字段也不崩。 */
        public GuiStyle guiStyle = new GuiStyle();

        /** 客户端开关：是否在聊天中显示称号前缀（服务端持久化，仅影响自己的渲染/广播）。 */
        public boolean chatTitleVisible = true;

        /** 客户端开关：是否在玩家头顶渲染称号前缀（仅本机观看其他玩家时生效）。 */
        public boolean nametagTitleVisible = true;

        // ── 随机事件状态（首页展示） ──
        /** 是否有事件进行中。 */
        public boolean eventActive = false;
        /** 进行中事件的 id（无事件时为空串）。 */
        public String eventId = "";
        /** 进行中事件名称。 */
        public String eventName = "";
        /** 进行中事件类型标签（怪物潮/天降补给/双倍经验/...）。 */
        public String eventTypeLabel = "";
        /** 进行中事件剩余秒数。 */
        public int eventRemainingSeconds = 0;
        /** 参与判定类型（ONLINE/KILL_ENTITY/TRADE 等）。 */
        public String eventParticipationType = "";
        /** 参与目标数。 */
        public int eventParticipationTarget = 1;
        /** 玩家当前参与进度。 */
        public int eventPlayerProgress = 0;
        /** 玩家是否已参与（ONLINE 类型开始时即算参与）。 */
        public boolean eventPlayerParticipated = false;
        /** 奖励摘要文字。 */
        public String eventRewardSummary = "";
        /** 无事件时距下次可触发的剩余秒数（冷却中）；有事件时为 0。 */
        public int eventCooldownSeconds = 0;
    }

    /** 一条任务（每日或每周）在玩家身上的进度（§8：名称/描述/进度/状态/奖励）。 */
    public static class TaskLine {

        public String id = "";
        public String name = "";
        public String desc = "";
        /** 状态名：NOT_ACTIVE / IN_PROGRESS / COMPLETED / CLAIMED。 */
        public String status = "NOT_ACTIVE";
        public int progress = 0;
        public int target = 1;
        public int xp = 0;
        public int starCoin = 0;
        public boolean weekly = false;
    }

    /**
     * 客户端 GUI 视觉配置（由服务端 season.json 的 gui 节点下发，管理员可热改）。
     *
     * <p>为什么放在玩家快照里一起发：它只在客户端渲染时使用，不增加服务端计算；
     * 所有字段都带默认值，老客户端缺字段时 Gson 会留默认值，不会崩。
     * 服务端在下发前已经把非法颜色/透明度修正过一遍，客户端这里只是"照着画"。
     */
    public static class GuiStyle {

        /** 任务条目选中框颜色（ARGB，已带 alpha）。 */
        public int selectionBorderColor = 0xFFFF5555;

        /** 任务条目选中时背景加深的 ARGB（alpha + 黑色）。 */
        public int selectionDimArgb = 0x60000000;

        /** 当前激活 Tab 的文字高亮颜色（ARGB）。 */
        public int tabActiveTextColor = 0xFFFFFF55;

        /** 当前激活 Tab 底色加深的 ARGB。 */
        public int tabActiveOverlayArgb = 0x40FFFFFF;

        /** 首页右下角社团文字（原样显示）。 */
        public String communityText = "支持社团：镐京方块协会";

        /** 社团文字颜色代码（形如 "§7"）。 */
        public String communityColor = "§7";

        /** 社团文字点击后在本地浏览器打开的链接；空串表示点击无反应。 */
        public String communityUrl = "";

        /** 商店每个分组的文字颜色（group → "§?"）；未配置的分组回退白色。 */
        public Map<String, String> shopGroupColors = new HashMap<>();

        /** 商店价格数字颜色（形如 "§6"），独立于分组颜色。 */
        public String shopPriceColor = "§6";

        /** 首页顶部欢迎语文案（{player} 替换为玩家名）。 */
        public String welcomeText = "欢迎您，{player}";

        /** 欢迎语中文字部分颜色代码（形如 "§7"）。 */
        public String welcomeColor = "§7";

        /** 欢迎语中玩家名颜色代码（形如 "§f"）。 */
        public String welcomePlayerColor = "§f";
    }

    /** 任务快照：三组每日 + 每周列表。 */
    public static class Tasks {

        public List<TaskLine> explore = new ArrayList<>();
        public List<TaskLine> build = new ArrayList<>();
        public List<TaskLine> general = new ArrayList<>();
        public List<TaskLine> weekly = new ArrayList<>();

        /** 玩家本日已选定的每日任务组；空串表示尚未选择（其它组显示"选择本组"按钮）。 */
        public String chosenGroup = "";

        /** @return 该组对应的列表；组名非法时返回空列表。 */
        public List<TaskLine> group(String name) {
            if ("explore".equalsIgnoreCase(name)) {
                return explore;
            }

            if ("build".equalsIgnoreCase(name)) {
                return build;
            }

            if ("general".equalsIgnoreCase(name)) {
                return general;
            }

            return weekly;
        }
    }

    /** 一件商店商品（§8：战令商店）。 */
    public static class ShopLine {

        public String id = "";
        public String nameKey = "";
        public String descKey = "";
        public int price = 0;
        public int limitPerPlayer = 0;
        public int purchased = 0;
        public boolean enabled = true;
        /** 商品分组键（如 "titles" / "consumable" / "decor"），用于按配置着色；空串回退默认白色。 */
        public String group = "";
        /** 奖励摘要（服务端生成的可读文本），便于界面直接显示。 */
        public String rewardSummary = "";
    }

    /** 商店快照。 */
    public static class Shop {

        public int starCoin = 0;
        public List<ShopLine> items = new ArrayList<>();
    }

    /** 称号库（§8：已解锁列表、佩戴、预览切换）。 */
    public static class Titles {

        public String equipped = "";
        public List<String> unlocked = new ArrayList<>();
        /** 全服全部称号定义（含未解锁），用于 GUI 悬浮提示与未解锁灰显。 */
        public List<TitleDef> definitions = new ArrayList<>();
    }

    /** 一条称号的展示定义（从 titles.json 下发）。纯装饰，不含任何属性。 */
    public static class TitleDef {

        public String id = "";
        /** 显示名；空串时客户端回退到 lang 译文。 */
        public String name = "";
        public String description = "";
        public String acquireHint = "";
        /** 颜色代码（形如 "§e"）。 */
        public String color = "§f";
        /** 聊天/头顶包裹符号。 */
        public String wrapPrefix = "【";
        public String wrapSuffix = "】";
    }

    /** 收藏册（§8：本赛季 + 历史赛季档案，仅显示已解锁项）。 */
    public static class Collection {

        public String seasonId = "";
        /** 本周赛季解锁的彩蛋 ID。 */
        public List<String> seasonEggs = new ArrayList<>();
        /** 历史留档（全部已解锁彩蛋 ID）。 */
        public List<String> allEggs = new ArrayList<>();
        /** 历史赛季归档文件名（来自 data/history/）。 */
        public List<String> historySeasons = new ArrayList<>();
        /** 已达成的全服里程碑 ID。 */
        public List<String> milestones = new ArrayList<>();
    }

    /** 管理面板快照（§9：九类可编辑项 + 状态概览）。 */
    public static class Admin {

        public String seasonId = "";
        public String themeName = "";
        public boolean seasonEnabled = true;
        public int durationDays = 30;
        public int maxLevel = 30;
        public int branchUnlockLevel = 10;
        public String dailyRefreshTime = "06:00";
        public int dailyXpCap = 500;
        public int dailyRerollLimit = 1;
        public int xpPerLevelBase = 50;
        public int xpPerLevelStep = 10;
        public int starCoinPerLevel = 10;
        public int exemptCardMax = 3;
        public boolean devMode = false;
        public boolean longNightEnabled = true;
        public String longNightStart = "00:00";
        public String longNightEnd = "06:00";
        public double longNightXpMultiplier = 0.5D;
        public List<String> commandWhitelist = new ArrayList<>();

        /** 各模块的条目数，用于面板首页概览。 */
        public int taskCount = 0;
        public int rewardLevelCount = 0;
        public int shopCount = 0;
        public int eggCount = 0;
        public int codeCount = 0;
        public int eventCount = 0;
        public int milestoneCount = 0;

        /** 运行状态。 */
        public int onlinePlayers = 0;
        public int storedPlayers = 0;

        /** 配置文件绝对路径列表（便于管理员去编辑大块内容）。 */
        public List<String> configFiles = new ArrayList<>();

        /** 最近一次玩家查询结果（可读文本，多行）。 */
        public String queryResult = "";
    }
}
