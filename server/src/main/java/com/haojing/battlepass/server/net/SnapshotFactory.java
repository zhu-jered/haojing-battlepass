package com.haojing.battlepass.server.net;

import com.haojing.battlepass.common.ModConstants;
import com.haojing.battlepass.common.data.EggProgress;
import com.haojing.battlepass.common.data.GlobalData;
import com.haojing.battlepass.common.data.Reward;
import com.haojing.battlepass.common.data.SeasonData;
import com.haojing.battlepass.common.data.TaskProgress;
import com.haojing.battlepass.common.net.ModSnapshots;
import com.haojing.battlepass.server.battlepass.BattlePassService;
import com.haojing.battlepass.server.battlepass.LevelCurve;
import com.haojing.battlepass.server.battlepass.LevelRewardManager;
import com.haojing.battlepass.server.config.ConfigManager;
import com.haojing.battlepass.server.config.SeasonConfig;
import com.haojing.battlepass.server.data.PlayerDataManager;
import com.haojing.battlepass.server.egg.EggManager;
import com.haojing.battlepass.server.event.RandomEventManager;
import com.haojing.battlepass.server.milestone.MilestoneManager;
import com.haojing.battlepass.server.milestone.MilestoneService;
import com.haojing.battlepass.server.redeem.RedeemCodeManager;
import com.haojing.battlepass.server.shop.ShopItem;
import com.haojing.battlepass.server.shop.ShopManager;
import com.haojing.battlepass.server.storage.StoragePaths;
import com.haojing.battlepass.server.task.TaskDefinition;
import com.haojing.battlepass.server.task.TaskPool;
import com.haojing.battlepass.server.task.TaskPoolManager;
import com.haojing.battlepass.server.time.TimeUtil;
import com.haojing.battlepass.server.title.TitleDefinition;
import com.haojing.battlepass.server.title.TitleManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

/**
 * 用途：把服务端的业务状态**翻译成给客户端的快照**（需求文档 §8 六个分页、§9 管理面板）。
 *
 * <p>为什么单独抽一个类：快照是"客户端能看到什么"的唯一清单。把它从网络层里分出来后，
 * 它就不需要任何 Minecraft 类型（只读玩家数据与配置），
 * 于是"等级/经验/剩余经验/任务状态/商店限购/称号/收藏册"这些显示逻辑可以直接单元测试 ——
 * 而它们在真机上往往要凑齐"升到 10 级 + 买了限购商品 + 解锁了彩蛋"才能验证一次。
 *
 * <p>为什么长夜状态用一个 {@link BooleanSupplier} 传入而不是引用长夜管理器：
 * 后者引用 Minecraft 类型，会让本类失去可测试性（同一个取舍见
 * {@code BattlePassService} 用 {@code XpMultiplierProvider} 隔开倍率来源）。
 */
public final class SnapshotFactory {

    private final PlayerDataManager dataManager;
    private final ConfigManager configManager;
    private final StoragePaths paths;
    private final TaskPoolManager taskPoolManager;
    private final LevelRewardManager levelRewardManager;
    private final ShopManager shopManager;
    private final EggManager eggManager;
    private final RedeemCodeManager redeemCodeManager;
    private final RandomEventManager randomEventManager;
    private final MilestoneManager milestoneManager;
    private final MilestoneService milestoneService;
    private final BattlePassService battlePassService;
    private final BooleanSupplier longNightActive;
    private final TitleManager titleManager;

    /** 随机事件运行时服务（可选，由装配处注入）。 */
    private com.haojing.battlepass.server.event.RandomEventService randomEventService;

    public SnapshotFactory(PlayerDataManager dataManager, ConfigManager configManager, StoragePaths paths,
                           TaskPoolManager taskPoolManager, LevelRewardManager levelRewardManager,
                           ShopManager shopManager, EggManager eggManager, RedeemCodeManager redeemCodeManager,
                           RandomEventManager randomEventManager, MilestoneManager milestoneManager,
                           MilestoneService milestoneService, BattlePassService battlePassService,
                           BooleanSupplier longNightActive) {
        this(dataManager, configManager, paths, taskPoolManager, levelRewardManager, shopManager, eggManager,
                redeemCodeManager, randomEventManager, milestoneManager, milestoneService, battlePassService,
                longNightActive, null);
    }

    public SnapshotFactory(PlayerDataManager dataManager, ConfigManager configManager, StoragePaths paths,
                           TaskPoolManager taskPoolManager, LevelRewardManager levelRewardManager,
                           ShopManager shopManager, EggManager eggManager, RedeemCodeManager redeemCodeManager,
                           RandomEventManager randomEventManager, MilestoneManager milestoneManager,
                           MilestoneService milestoneService, BattlePassService battlePassService,
                           BooleanSupplier longNightActive, TitleManager titleManager) {
        this.dataManager = dataManager;
        this.configManager = configManager;
        this.paths = paths;
        this.taskPoolManager = taskPoolManager;
        this.levelRewardManager = levelRewardManager;
        this.shopManager = shopManager;
        this.eggManager = eggManager;
        this.redeemCodeManager = redeemCodeManager;
        this.randomEventManager = randomEventManager;
        this.milestoneManager = milestoneManager;
        this.milestoneService = milestoneService;
        this.battlePassService = battlePassService;
        this.longNightActive = longNightActive == null ? () -> false : longNightActive;
        this.titleManager = titleManager;
    }

    /** 注入随机事件运行时服务（首页展示事件状态用）。 */
    public void setRandomEventService(com.haojing.battlepass.server.event.RandomEventService service) {
        this.randomEventService = service;
    }

    /**
     * 首页快照（§8：等级、经验进度条、今日剩余可获取经验、服务器状态与倒计时）。
     *
     * @param playerUuid 玩家
     * @return 快照
     */
    public ModSnapshots.Player player(UUID playerUuid) {
        ModSnapshots.Player snapshot = new ModSnapshots.Player();
        SeasonConfig config = configManager == null ? null : configManager.config();
        SeasonData season = dataManager.season(playerUuid);
        GlobalData global = dataManager.global(playerUuid);

        if (config != null) {
            snapshot.seasonId = config.seasonId;
            snapshot.themeName = config.themeName;
            snapshot.maxLevel = config.maxLevel;
            snapshot.branchUnlockLevel = config.branchUnlockLevel;
            snapshot.rerollLimit = config.dailyRerollLimit;
            snapshot.xpForNext = LevelCurve.xpForLevel(config, season.level);
            applyGuiStyle(snapshot.guiStyle, config.gui);

            // 等级轨道奖励摘要
            snapshot.levelRewards.clear();
            if (levelRewardManager != null && levelRewardManager.table() != null) {
                int maxLv = config.maxLevel;
                for (int lv = 1; lv <= maxLv; lv++) {
                    java.util.List<com.haojing.battlepass.common.data.Reward> rewards =
                            levelRewardManager.table().rewardsFor(lv, null);
                    if (rewards == null || rewards.isEmpty()) continue;
                    StringBuilder sb = new StringBuilder();
                    for (com.haojing.battlepass.common.data.Reward r : rewards) {
                        if (sb.length() > 0) sb.append(" ");
                        com.haojing.battlepass.common.data.RewardType rt = r.typeOrNull();
                        if (rt == null) continue;
                        if (rt == com.haojing.battlepass.common.data.RewardType.TITLE) {
                            sb.append("称号");
                        } else if (rt == com.haojing.battlepass.common.data.RewardType.STAR_COIN) {
                            sb.append(r.amount).append("京币");
                        } else if (rt == com.haojing.battlepass.common.data.RewardType.EXEMPT_CARD) {
                            sb.append("任务卡");
                        } else {
                            sb.append(rt);
                        }
                    }
                    snapshot.levelRewards.put(lv, sb.toString());
                }
            }
        }

        snapshot.level = season.level;
        snapshot.xp = season.xp;
        snapshot.remainingDailyXp = battlePassService == null ? 0 : battlePassService.remainingDailyXp(playerUuid);
        snapshot.starCoin = global.starCoin;
        snapshot.exemptCards = season.exemptCards;
        snapshot.rerollUsed = season.dailyRerollUsed;
        snapshot.branch = season.branch().name();

        // 称号显示开关（客户端偏好，服务端持久化）。
        snapshot.chatTitleVisible = global.chatTitleVisible;
        snapshot.nametagTitleVisible = global.nametagTitleVisible;

        // §8：10 级触发分支选择弹窗 —— 条件由服务端给出，客户端只负责显示。
        snapshot.branchPrompt = config != null
                && season.level >= config.branchUnlockLevel
                && !season.hasChosenBranch();

        boolean active = longNightActive.getAsBoolean();
        snapshot.longNight = active;

        if (active && config != null) {
            // 倒计时按"距离长夜结束还有多少分钟"。跨零点时段由 TimeUtil.minutesUntil 处理。
            snapshot.longNightMinutesRemaining = (int) TimeUtil.minutesUntil(TimeUtil.now(), config.longNightEnd());
        }

        // ── 随机事件状态 ──
        if (randomEventService != null) {
            long nowMs = System.currentTimeMillis();
            var evt = randomEventService.activeEvent();

            if (evt != null && randomEventService.isActive()) {
                snapshot.eventActive = true;
                snapshot.eventId = evt.id;
                snapshot.eventName = evt.name;
                var type = evt.typeOrNull();
                snapshot.eventTypeLabel = type == null ? evt.type : type.label();
                snapshot.eventRemainingSeconds = (int) randomEventService.remainingSeconds(nowMs);
                snapshot.eventParticipationType = evt.participation.typeOrDefault().name();
                snapshot.eventParticipationTarget = Math.max(1, evt.participation.target);
                snapshot.eventPlayerProgress = randomEventService.playerProgress(playerUuid);
                snapshot.eventPlayerParticipated = randomEventService.participants().contains(playerUuid);
                snapshot.eventRewardSummary = summarizeEventRewards(evt);
            } else {
                snapshot.eventCooldownSeconds = (int) randomEventService.cooldownSeconds(nowMs);
            }
        }

        return snapshot;
    }

    /**
     * 任务快照（§8：每日三组 + 每周挑战）。
     *
     * @param playerUuid 玩家
     * @return 快照
     */
    public ModSnapshots.Tasks tasks(UUID playerUuid) {
        ModSnapshots.Tasks snapshot = new ModSnapshots.Tasks();
        SeasonData season = dataManager.season(playerUuid);
        TaskPool pool = taskPoolManager == null ? null : taskPoolManager.pool();

        snapshot.chosenGroup = season.chosenDailyGroup == null ? "" : season.chosenDailyGroup;

        if (pool == null) {
            return snapshot;
        }

        for (String group : TaskPool.DAILY_GROUPS) {
            TaskProgress progress = season.dailyTasks == null ? null : season.dailyTasks.get(group);

            if (progress == null) {
                continue;
            }

            TaskDefinition definition = pool.indexById().get(progress.taskId);
            snapshot.group(group).add(line(definition, progress, false));
        }

        if (season.weeklyTasks != null) {
            for (TaskProgress progress : season.weeklyTasks.values()) {
                if (progress == null) {
                    continue;
                }

                snapshot.weekly.add(line(pool.indexById().get(progress.taskId), progress, true));
            }
        }

        return snapshot;
    }

    /**
     * 商店快照（§8：战令商店）。
     *
     * @param playerUuid 玩家
     * @return 快照
     */
    public ModSnapshots.Shop shop(UUID playerUuid) {
        ModSnapshots.Shop snapshot = new ModSnapshots.Shop();
        GlobalData global = dataManager.global(playerUuid);
        snapshot.starCoin = global.starCoin;

        if (shopManager == null || shopManager.catalog() == null) {
            return snapshot;
        }

        for (ShopItem item : shopManager.catalog().enabledItems()) {
            ModSnapshots.ShopLine line = new ModSnapshots.ShopLine();
            line.id = item.id;
            line.nameKey = item.nameKey;
            line.descKey = item.descKey;
            line.price = item.price;
            line.limitPerPlayer = item.limitPerPlayer;
            line.purchased = global.purchaseCount(item.id);
            line.enabled = item.enabled;
            line.group = item.group == null ? "" : item.group;
            line.rewardSummary = summarize(item.reward);
            snapshot.items.add(line);
        }

        return snapshot;
    }

    /**
     * 称号快照（§8：称号库）。
     *
     * @param playerUuid 玩家
     * @return 快照
     */
    public ModSnapshots.Titles titles(UUID playerUuid) {
        ModSnapshots.Titles snapshot = new ModSnapshots.Titles();
        GlobalData global = dataManager.global(playerUuid);

        snapshot.equipped = global.equippedTitle == null ? "" : global.equippedTitle;

        if (global.unlockedTitles != null) {
            snapshot.unlocked.addAll(global.unlockedTitles);
            snapshot.unlocked.sort(Comparator.naturalOrder());
        }

        // 全服全部称号定义（含未解锁），供客户端悬浮提示与未解锁灰显。
        if (titleManager != null && titleManager.catalog() != null) {
            for (TitleDefinition def : titleManager.catalog().all()) {
                ModSnapshots.TitleDef out = new ModSnapshots.TitleDef();
                out.id = def.id;
                out.name = def.normalizedName();
                out.description = def.description;
                out.acquireHint = def.acquireHint;
                out.color = def.color;
                out.wrapPrefix = def.wrapPrefix;
                out.wrapSuffix = def.wrapSuffix;
                snapshot.definitions.add(out);
            }
        }

        return snapshot;
    }

    /**
     * 收藏册快照（§8：本赛季 + 历史赛季档案，仅显示已解锁项）。
     *
     * <p>"本赛季解锁的彩蛋"用 {已解锁} ∩ {本赛季有进度} 判定：
     * 已解锁记录是永久的（历史册），而进度会随赛季清空，
     * 因此两者的交集恰好是"这一季解锁的"。
     *
     * @param playerUuid 玩家
     * @return 快照
     */
    public ModSnapshots.Collection collection(UUID playerUuid) {
        ModSnapshots.Collection snapshot = new ModSnapshots.Collection();
        GlobalData global = dataManager.global(playerUuid);
        SeasonData season = dataManager.season(playerUuid);
        SeasonConfig config = configManager == null ? null : configManager.config();

        snapshot.seasonId = config == null ? season.seasonId : config.seasonId;

        if (global.collection != null) {
            snapshot.allEggs.addAll(global.collection);
            snapshot.allEggs.sort(Comparator.naturalOrder());
        }

        if (season.eggProgress != null) {
            for (String eggId : season.eggProgress.keySet()) {
                if (global.collection != null && global.collection.contains(eggId)) {
                    snapshot.seasonEggs.add(eggId);
                }
            }

            snapshot.seasonEggs.sort(Comparator.naturalOrder());
        }

        snapshot.historySeasons.addAll(historyFiles());

        if (milestoneService != null) {
            snapshot.milestones.addAll(milestoneService.reachedMilestones());
            snapshot.milestones.sort(Comparator.naturalOrder());
        }

        return snapshot;
    }

    /**
     * 管理面板快照（§9：九类可编辑项 + 状态概览 + 数据维护）。
     *
     * @param onlinePlayers 当前在线人数
     * @param storedPlayers 有存档的玩家数
     * @param queryResult   最近一次玩家查询的可读结果（可空）
     * @return 快照
     */
    public ModSnapshots.Admin admin(int onlinePlayers, int storedPlayers, String queryResult) {
        ModSnapshots.Admin snapshot = new ModSnapshots.Admin();
        SeasonConfig config = configManager == null ? null : configManager.config();

        if (config != null) {
            snapshot.seasonId = config.seasonId;
            snapshot.themeName = config.themeName;
            snapshot.seasonEnabled = config.enabled;
            snapshot.durationDays = config.durationDays;
            snapshot.maxLevel = config.maxLevel;
            snapshot.branchUnlockLevel = config.branchUnlockLevel;
            snapshot.dailyRefreshTime = config.dailyRefreshTime;
            snapshot.dailyXpCap = config.dailyXpCap;
            snapshot.dailyRerollLimit = config.dailyRerollLimit;
            snapshot.xpPerLevelBase = config.xpPerLevelBase;
            snapshot.xpPerLevelStep = config.xpPerLevelStep;
            snapshot.starCoinPerLevel = config.starCoinPerLevel;
            snapshot.exemptCardMax = config.exemptCardMax;
            snapshot.devMode = config.devMode;
            snapshot.longNightEnabled = config.longNightEnabled();
            snapshot.longNightStart = config.longNight == null ? "" : config.longNight.start;
            snapshot.longNightEnd = config.longNight == null ? "" : config.longNight.end;
            snapshot.longNightXpMultiplier = config.longNight == null ? 1.0D : config.longNight.xpMultiplier;
            snapshot.commandWhitelist.addAll(config.commandWhitelist);
        }

        snapshot.taskCount = taskPoolManager == null || taskPoolManager.pool() == null
                ? 0 : taskPoolManager.pool().totalSize();
        snapshot.rewardLevelCount = levelRewardManager == null || levelRewardManager.table() == null
                ? 0 : levelRewardManager.table().configuredLevelCount();
        snapshot.shopCount = shopManager == null || shopManager.catalog() == null
                ? 0 : shopManager.catalog().size();
        snapshot.eggCount = eggManager == null || eggManager.pool() == null ? 0 : eggManager.pool().size();
        snapshot.codeCount = redeemCodeManager == null || redeemCodeManager.pool() == null
                ? 0 : redeemCodeManager.pool().size();
        snapshot.eventCount = randomEventManager == null || randomEventManager.config() == null
                ? 0 : randomEventManager.config().size();
        snapshot.milestoneCount = milestoneManager == null || milestoneManager.table() == null
                ? 0 : milestoneManager.table().size();

        snapshot.onlinePlayers = onlinePlayers;
        snapshot.storedPlayers = storedPlayers;

        if (paths != null) {
            snapshot.configFiles.add(paths.seasonConfigFile().toAbsolutePath().toString());
            snapshot.configFiles.add(paths.dailyTasksConfigFile().toAbsolutePath().toString());
            snapshot.configFiles.add(paths.rewardsConfigFile().toAbsolutePath().toString());
            snapshot.configFiles.add(paths.shopConfigFile().toAbsolutePath().toString());
            snapshot.configFiles.add(paths.eggsConfigFile().toAbsolutePath().toString());
            snapshot.configFiles.add(paths.codesConfigFile().toAbsolutePath().toString());
            snapshot.configFiles.add(paths.eventsConfigFile().toAbsolutePath().toString());
            snapshot.configFiles.add(paths.milestonesConfigFile().toAbsolutePath().toString());
            snapshot.configFiles.add(paths.titlesConfigFile().toAbsolutePath().toString());
            snapshot.configFiles.add(paths.stateFile().toAbsolutePath().toString());
        }

        snapshot.queryResult = queryResult == null ? "" : queryResult;
        return snapshot;
    }

    /**
     * 把 season.json 里的 gui 节点翻译成客户端直接能用的 ARGB int 与 § 字符串。
     *
     * <p>服务端 ConfigManager 已经把非法值修正过一遍，这里再做一次防御性回退，
     * 保证客户端拿到的字段永远是合法值（即使配置被绕过校验直接改坏）。
     */
    private void applyGuiStyle(ModSnapshots.GuiStyle out, com.haojing.battlepass.server.config.SeasonConfig.GuiStyleConfig cfg) {
        if (out == null || cfg == null) {
            return;
        }

        Integer border = com.haojing.battlepass.common.gui.GuiColors.parseSectionColor(cfg.selectionBorderColor);
        out.selectionBorderColor = border == null ? 0xFFFFFFFF : border;

        int dimAlpha = Math.max(0, Math.min(255, cfg.selectionDimAlpha));
        out.selectionDimArgb = (dimAlpha << 24);

        Integer tabText = com.haojing.battlepass.common.gui.GuiColors.parseSectionColor(cfg.tabActiveTextColor);
        out.tabActiveTextColor = tabText == null ? 0xFFFFFF55 : tabText;

        int tabOverlayAlpha = Math.max(0, Math.min(255, cfg.tabActiveOverlayAlpha));
        out.tabActiveOverlayArgb = (tabOverlayAlpha << 24) | 0x00FFFFFF;

        out.communityText = cfg.communityText == null || cfg.communityText.isBlank()
                ? "支持社团：镐京方块协会" : cfg.communityText;
        out.communityColor = com.haojing.battlepass.common.gui.GuiColors.normalizeSection(cfg.communityColor, "§7");
        out.communityUrl = cfg.communityUrl == null ? "" : cfg.communityUrl.trim();
        out.shopPriceColor = com.haojing.battlepass.common.gui.GuiColors.normalizeSection(cfg.shopPriceColor, "§6");

        out.welcomeText = cfg.welcomeText == null || cfg.welcomeText.isBlank()
                ? "欢迎您，{player}" : cfg.welcomeText;
        out.welcomeColor = com.haojing.battlepass.common.gui.GuiColors.normalizeSection(cfg.welcomeColor, "§7");
        out.welcomePlayerColor = com.haojing.battlepass.common.gui.GuiColors.normalizeSection(cfg.welcomePlayerColor, "§f");

        out.showLevelAxis = cfg.showLevelAxis;
        out.levelAxisCompleted = com.haojing.battlepass.common.gui.GuiColors.normalizeSection(cfg.levelAxisCompleted, "§a");
        out.levelAxisCurrent = com.haojing.battlepass.common.gui.GuiColors.normalizeSection(cfg.levelAxisCurrent, "§e");
        out.levelAxisLocked = com.haojing.battlepass.common.gui.GuiColors.normalizeSection(cfg.levelAxisLocked, "§8");
        out.levelAxisLine = com.haojing.battlepass.common.gui.GuiColors.normalizeSection(cfg.levelAxisLine, "§7");
        out.levelAxisReward = com.haojing.battlepass.common.gui.GuiColors.normalizeSection(cfg.levelAxisReward, "§6");

        out.shopGroupColors.clear();

        if (cfg.shopGroupColors != null) {
            for (java.util.Map.Entry<String, String> e : cfg.shopGroupColors.entrySet()) {
                if (e.getKey() == null) {
                    continue;
                }

                String normalized = com.haojing.battlepass.common.gui.GuiColors.normalizeSection(e.getValue(), null);

                if (normalized != null) {
                    out.shopGroupColors.put(e.getKey().trim().toLowerCase(java.util.Locale.ROOT), normalized);
                }
            }
        }
    }

    /** 把一条任务定义与进度合成快照行。 */
    private ModSnapshots.TaskLine line(TaskDefinition definition, TaskProgress progress, boolean weekly) {        ModSnapshots.TaskLine line = new ModSnapshots.TaskLine();

        if (progress != null) {
            line.id = progress.taskId;
            line.progress = progress.progress;
            line.status = progress.status().name();
        }

        if (definition != null) {
            line.name = definition.name;
            line.desc = definition.desc;
            line.target = definition.target;
            line.xp = definition.xp;
            line.starCoin = definition.starCoin;
        }

        line.weekly = weekly;
        return line;
    }

    /**
     * 把奖励压成机器可读的摘要（{@code 类型|取值|数量}）。
     *
     * <p>为什么不在这里拼中文：§1 要求界面文案走 TranslationKey，
     * 而奖励是管理员配置的内容，服务端拼出来的中文无法被客户端翻译。
     * 客户端拿到 {@code TITLE|haojing_scout|1} 后自行按语言渲染。
     */
    private String summarize(Reward reward) {
        if (reward == null) {
            return "";
        }

        String type = reward.type == null ? "" : reward.type;
        String value = reward.titleId != null && !reward.titleId.isBlank() ? reward.titleId
                : (reward.itemId != null && !reward.itemId.isBlank() ? reward.itemId
                : (reward.command != null && !reward.command.isBlank() ? reward.command : ""));
        return type + "|" + value + "|" + reward.amount;
    }

    /** 把事件奖励列表拼成中文摘要，如"60经验 + 5京币"。 */
    private String summarizeEventRewards(com.haojing.battlepass.server.event.RandomEventDefinition evt) {
        if (evt == null || evt.rewards == null || evt.rewards.isEmpty()) {
            return "";
        }

        List<String> parts = new ArrayList<>();
        for (Reward r : evt.rewards) {
            if (r == null || r.amount <= 0) {
                continue;
            }
            if ("BATTLEPASS_XP".equals(r.type)) {
                parts.add(r.amount + "经验");
            } else if ("STAR_COIN".equals(r.type)) {
                parts.add(r.amount + "京币");
            } else if ("ITEM".equals(r.type)) {
                parts.add("物品");
            } else if ("TITLE".equals(r.type)) {
                parts.add("称号");
            }
        }
        return String.join(" + ", parts);
    }

    /** @return data/history 下的赛季归档文件名（升序）。 */
    private List<String> historyFiles() {
        List<String> names = new ArrayList<>();

        if (paths == null) {
            return names;
        }

        Path dir = paths.historyDir();

        if (!Files.isDirectory(dir)) {
            return names;
        }

        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith("season_"))
                    .sorted()
                    .forEach(names::add);
        } catch (IOException e) {
            // 归档目录读不出来时返回空列表：界面少一栏比崩掉好（§14）。
            return names;
        }

        return names;
    }
}
