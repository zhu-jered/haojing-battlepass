package com.haojing.battlepass.server.net;

import com.haojing.battlepass.common.ModConstants;
import com.haojing.battlepass.common.data.Branch;
import com.haojing.battlepass.common.data.GlobalData;
import com.haojing.battlepass.common.data.SeasonData;
import com.haojing.battlepass.common.net.NetActions;
import com.haojing.battlepass.server.battlepass.BattlePassService;
import com.haojing.battlepass.server.battlepass.LevelRewardManager;
import com.haojing.battlepass.server.config.ConfigManager;
import com.haojing.battlepass.server.config.SeasonConfig;
import com.haojing.battlepass.server.data.PlayerDataManager;
import com.haojing.battlepass.server.data.ServerStateManager;
import com.haojing.battlepass.server.egg.EggManager;
import com.haojing.battlepass.server.event.RandomEventManager;
import com.haojing.battlepass.server.event.RandomEventService;
import com.haojing.battlepass.server.milestone.MilestoneManager;
import com.haojing.battlepass.server.redeem.RedeemCodeManager;
import com.haojing.battlepass.server.season.SeasonResetService;
import com.haojing.battlepass.server.shop.ShopManager;
import com.haojing.battlepass.server.storage.StoragePaths;
import com.haojing.battlepass.server.task.TaskPoolManager;
import com.haojing.battlepass.server.time.TimeUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 用途：执行管理面板动作（需求文档 §9）。
 *
 * <p>权限与校验的分工（§9 要求"需 OP 权限 + 服务端二次校验"）：
 * <ol>
 *   <li>权限在 {@link ModNetworking} 里用原版权限判据检查一次（非 OP 连动作都到不了这里）；</li>
 *   <li>字段合法性在 {@code C2SGuard} 里检查一次（长度/动作名/频率）；</li>
 *   <li>**本类再做一次业务校验**：数值范围、目标玩家是否存在、分支名是否合法 ——
 *       这一层是"永远不信任客户端"的落点，即使前两层被绕过，也不会把非法值写进存档。</li>
 * </ol>
 *
 * <p>所有动作都只通过既有服务改数据（不直接写文件），因此 §2 的
 * "全部业务逻辑只在服务端"与 §12 的"写盘一律走 JsonStore/节流层"自动成立。
 */
public final class ServerAdminActions {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModConstants.MOD_ID_SERVER);

    private static final DateTimeFormatter BACKUP_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /**
     * 执行结果。
     *
     * @param success    是否成功
     * @param messageKey 展示给管理员的文案翻译键
     */
    public record Result(boolean success, String messageKey) {

        static Result ok(String messageKey) {
            return new Result(true, messageKey);
        }

        static Result fail(String messageKey) {
            return new Result(false, messageKey);
        }
    }

    private final StoragePaths paths;
    private final ConfigManager configManager;
    private final PlayerDataManager dataManager;
    private final ServerStateManager stateManager;
    private final BattlePassService battlePassService;
    private final SeasonResetService seasonResetService;
    private final TaskPoolManager taskPoolManager;
    private final LevelRewardManager levelRewardManager;
    private final ShopManager shopManager;
    private final EggManager eggManager;
    private final RedeemCodeManager redeemCodeManager;
    private final RandomEventManager randomEventManager;
    private final RandomEventService randomEventService;
    private final MilestoneManager milestoneManager;
    private final PlayerSyncService syncService;
    private final com.haojing.battlepass.server.task.TaskAssignmentService taskAssignmentService;
    private final com.haojing.battlepass.server.task.TaskProgressTracker tracker;

    public ServerAdminActions(StoragePaths paths, ConfigManager configManager, PlayerDataManager dataManager,
                              ServerStateManager stateManager, BattlePassService battlePassService,
                              SeasonResetService seasonResetService, TaskPoolManager taskPoolManager,
                              LevelRewardManager levelRewardManager, ShopManager shopManager, EggManager eggManager,
                              RedeemCodeManager redeemCodeManager, RandomEventManager randomEventManager,
                              RandomEventService randomEventService, MilestoneManager milestoneManager,
                              PlayerSyncService syncService,
                              com.haojing.battlepass.server.task.TaskAssignmentService taskAssignmentService,
                              com.haojing.battlepass.server.task.TaskProgressTracker tracker) {
        this.paths = paths;
        this.configManager = configManager;
        this.dataManager = dataManager;
        this.stateManager = stateManager;
        this.battlePassService = battlePassService;
        this.seasonResetService = seasonResetService;
        this.taskPoolManager = taskPoolManager;
        this.levelRewardManager = levelRewardManager;
        this.shopManager = shopManager;
        this.eggManager = eggManager;
        this.redeemCodeManager = redeemCodeManager;
        this.randomEventManager = randomEventManager;
        this.randomEventService = randomEventService;
        this.milestoneManager = milestoneManager;
        this.syncService = syncService;
        this.taskAssignmentService = taskAssignmentService;
        this.tracker = tracker;
    }

    /**
     * 执行一个管理动作。
     *
     * @param actor  操作者（已确认是 OP）
     * @param server 服务端
     * @param action 动作
     * @param arg    参数
     * @param value  取值
     * @return 结果
     */
    public Result execute(ServerPlayerEntity actor, MinecraftServer server, NetActions.AdminAction action,
                          String arg, String value) {
        if (action == null) {
            return Result.fail("haojing_battlepass.admin.result.bad_action");
        }

        try {
            switch (action) {
                case RELOAD:
                    return reloadAll(server);
                case QUERY:
                    return query(server, arg);
                case SET_LEVEL:
                    return setLevel(server, arg, value);
                case SET_XP:
                    return setXp(server, arg, value);
                case SET_STAR_COIN:
                    return setStarCoin(server, arg, value);
                case SET_BRANCH:
                    return setBranch(server, arg, value);
                case SET_SEASON_ENABLED:
                    return setSeasonEnabled(server, value);
                case SET_DAILY_XP_CAP:
                    return setDailyXpCap(server, value);
                case SET_DAILY_REFRESH:
                    return setDailyRefresh(server, value);
                case SET_MAX_LEVEL:
                    return setMaxLevel(server, value);
                case SET_BRANCH_UNLOCK:
                    return setBranchUnlock(server, value);
                case SET_XP_CURVE:
                    return setXpCurve(server, arg, value);
                case SET_LONG_NIGHT:
                    return setLongNight(server, arg, value);
                case RESET_SEASON:
                    return resetSeason(server);
                case EXPORT_CONFIG:
                    return exportConfig();
                case IMPORT_CONFIG:
                    return importConfig(server, value);
                case START_EVENT:
                    return startEvent(server, arg);
                case RESET_EGG_PROGRESS:
                    return resetEggProgress(server, arg);
                case OPEN_PANEL:
                    syncService.pushAdminNow(actor);
                    syncService.openPanel(actor, com.haojing.battlepass.common.net.SyncChannels.Panel.ADMIN);
                    return Result.ok("haojing_battlepass.admin.result.opened");
                case SYNC_ADMIN:
                    // 只推数据、不回"打开面板"：避免与客户端的面板重建形成死循环。
                    syncService.pushAdminNow(actor);
                    return Result.ok("haojing_battlepass.admin.result.synced");
                case OPEN_PLAYER_PANEL:
                    // 管理员自测：一键切到玩家视角（面板里看不到战令本身长什么样时用）。
                    syncService.openPanel(actor, com.haojing.battlepass.common.net.SyncChannels.Panel.PLAYER);
                    return Result.ok("haojing_battlepass.admin.result.player_panel");
                case ADD_XP:
                    return addXp(server, actor, arg, value);
                case ADD_STAR_COIN:
                    return addStarCoin(server, actor, arg, value);
                case GIVE_EXEMPT_CARD:
                    return giveExemptCard(server, actor, arg, value);
                case FORCE_TASK_REFRESH:
                    return forceTaskRefresh(server, actor, arg);
                case RESET_SELF:
                    return resetSelf(actor);
                default:
                    return Result.fail("haojing_battlepass.admin.result.unsupported");
            }
        } catch (RuntimeException e) {
            // §14：任何异常都要被收敛，绝不能让一个畸形包把服务端打断。
            LOGGER.error("{} 管理动作 {} 执行异常（arg={} value={}）",
                    ModConstants.LOG_PREFIX, action, arg, value, e);
            return Result.fail("haojing_battlepass.admin.result.error");
        }
    }

    // ------------------------------------------------------------------
    // 热重载与查询
    // ------------------------------------------------------------------

    private Result reloadAll(MinecraftServer server) {
        configManager.reload();
        taskPoolManager.reload();
        levelRewardManager.reload();
        shopManager.reload();
        eggManager.reload();
        redeemCodeManager.reload();
        randomEventManager.reload();
        milestoneManager.reload();

        if (syncService != null) {
            syncService.markAllPlayersDirty(server);
            syncService.setPopulation(server == null ? 0 : server.getPlayerManager().getPlayerList().size(),
                    dataManager.allStoredPlayerIds().size());
            if (syncService != null) {
                syncService.setLastQuery("配置已全部热重载：" + TimeUtil.stamp(TimeUtil.now()));
            }
        }

        LOGGER.info("{} 管理员触发了全量配置热重载", ModConstants.LOG_PREFIX);
        return Result.ok("haojing_battlepass.admin.result.reloaded");
    }

    private Result query(MinecraftServer server, String target) {
        UUID uuid = resolvePlayer(target);

        if (uuid == null) {
            return Result.fail("haojing_battlepass.admin.result.player_not_found");
        }

        SeasonData season = dataManager.season(uuid);
        GlobalData global = dataManager.global(uuid);

        List<String> lines = new ArrayList<>();
        lines.add("玩家：" + (global.lastKnownName == null || global.lastKnownName.isBlank() ? uuid : global.lastKnownName));
        lines.add("UUID：" + uuid);
        lines.add("赛季：" + season.seasonId + " 等级：" + season.level + " 经验：" + season.xp);
        lines.add("分支：" + season.branch().name() + " 任务卡：" + season.exemptCards);
        lines.add("京币：" + global.starCoin + " 称号数：" + (global.unlockedTitles == null ? 0 : global.unlockedTitles.size())
                + " 彩蛋数：" + (global.collection == null ? 0 : global.collection.size()));
        lines.add("今日已获经验：" + season.dailyXpEarned + " 刷新任务已用：" + season.dailyRerollUsed);
        lines.add("每日任务数：" + (season.dailyTasks == null ? 0 : season.dailyTasks.size())
                + " 每周任务数：" + (season.weeklyTasks == null ? 0 : season.weeklyTasks.size()));
        lines.add("上次每日刷新：" + season.lastDailyRefreshDate);

        String text = String.join("\n", lines);

        if (syncService != null) {
            syncService.setLastQuery(text);
            syncService.pushAdminNow(server == null ? null : findOnline(server, uuid));
        }

        return Result.ok("haojing_battlepass.admin.result.queried");
    }

    // ------------------------------------------------------------------
    // 改玩家数据（§9 数据维护）
    // ------------------------------------------------------------------

    private Result setLevel(MinecraftServer server, String target, String value) {
        UUID uuid = resolvePlayer(target);
        Integer level = parseInt(value);

        if (uuid == null) {
            return Result.fail("haojing_battlepass.admin.result.player_not_found");
        }

        if (level == null) {
            return Result.fail("haojing_battlepass.admin.result.bad_value");
        }

        SeasonConfig config = configManager.config();
        int max = config == null ? 30 : config.maxLevel;
        SeasonData season = dataManager.season(uuid);
        season.level = Math.max(1, Math.min(level, max));
        season.xp = Math.min(season.xp, Math.max(0, LevelCurveNeed(config, season.level) - 1));
        // 每级星币是按"升级"发的，这里不补发（管理员改等级属于数据修正，不是让玩家升级）；
        // 但等级奖励会补结算（否则玩家会永远拿不到那一级的奖励）。
        dataManager.markSeasonDirty(uuid);

        if (battlePassService != null) {
            battlePassService.grantDueLevelRewards(uuid);
        }

        syncAll(server, uuid);
        LOGGER.info("{} 管理员把玩家 {} 的等级改为 {}", ModConstants.LOG_PREFIX, uuid, season.level);
        return Result.ok("haojing_battlepass.admin.result.changed");
    }

    private Result setXp(MinecraftServer server, String target, String value) {
        UUID uuid = resolvePlayer(target);
        Integer xp = parseInt(value);

        if (uuid == null) {
            return Result.fail("haojing_battlepass.admin.result.player_not_found");
        }

        if (xp == null) {
            return Result.fail("haojing_battlepass.admin.result.bad_value");
        }

        dataManager.season(uuid).xp = Math.max(0, xp);
        dataManager.markSeasonDirty(uuid);
        syncAll(server, uuid);
        return Result.ok("haojing_battlepass.admin.result.changed");
    }

    private Result setStarCoin(MinecraftServer server, String target, String value) {
        UUID uuid = resolvePlayer(target);
        Integer coins = parseInt(value);

        if (uuid == null) {
            return Result.fail("haojing_battlepass.admin.result.player_not_found");
        }

        if (coins == null) {
            return Result.fail("haojing_battlepass.admin.result.bad_value");
        }

        dataManager.global(uuid).starCoin = Math.max(0, coins);
        dataManager.markGlobalDirty(uuid);
        syncAll(server, uuid);
        return Result.ok("haojing_battlepass.admin.result.changed");
    }

    private Result setBranch(MinecraftServer server, String target, String value) {
        UUID uuid = resolvePlayer(target);

        if (uuid == null) {
            return Result.fail("haojing_battlepass.admin.result.player_not_found");
        }

        Branch branch = Branch.fromName(value);

        if (battlePassService == null || !battlePassService.adminSetBranch(uuid, branch)) {
            return Result.fail("haojing_battlepass.admin.result.bad_value");
        }

        syncAll(server, uuid);
        return Result.ok("haojing_battlepass.admin.result.changed");
    }

    private Result resetEggProgress(MinecraftServer server, String target) {
        UUID uuid = resolvePlayer(target);

        if (uuid == null) {
            return Result.fail("haojing_battlepass.admin.result.player_not_found");
        }

        dataManager.season(uuid).eggProgress = new java.util.LinkedHashMap<>();
        dataManager.markSeasonDirty(uuid);
        syncAll(server, uuid);
        LOGGER.info("{} 管理员重置了玩家 {} 的彩蛋进度（不影响已解锁记录）", ModConstants.LOG_PREFIX, uuid);
        return Result.ok("haojing_battlepass.admin.result.changed");
    }

    // ------------------------------------------------------------------
    // 自测用动作（阶段 7：管理员没有这些手段就无法重复测试战令链路）
    // ------------------------------------------------------------------

    /**
     * 给玩家"像做任务一样"加经验（会升级、发星币、结算等级奖励）。
     *
     * <p>与 {@link #setXp} 的区别见 {@code NetActions.AdminAction#ADD_XP} 的说明：
     * 这是测试升级链路用的，因此走 {@link BattlePassService#addXp}，
     * 长夜倍率与每日经验上限都会照常生效（这样测出来的行为与真实玩法一致）。
     */
    private Result addXp(MinecraftServer server, ServerPlayerEntity actor, String target, String value) {
        UUID uuid = resolveTargetOrSelf(actor, target);

        if (uuid == null) {
            return Result.fail("haojing_battlepass.admin.result.player_not_found");
        }

        Integer amount = parseInt(value);

        if (amount == null || amount <= 0 || battlePassService == null) {
            return Result.fail("haojing_battlepass.admin.result.bad_value");
        }

        var result = battlePassService.addXp(uuid, amount,
                com.haojing.battlepass.server.battlepass.XpSource.ADMIN);

        syncAll(server, uuid);
        LOGGER.info("{} 管理员给玩家 {} 发放经验 {}（实际 {}，等级 {}→{}）",
                ModConstants.LOG_PREFIX, uuid, amount, result.granted(), result.levelBefore(), result.levelAfter());
        return Result.ok("haojing_battlepass.admin.result.changed");
    }

    private Result addStarCoin(MinecraftServer server, ServerPlayerEntity actor, String target, String value) {
        UUID uuid = resolveTargetOrSelf(actor, target);

        if (uuid == null) {
            return Result.fail("haojing_battlepass.admin.result.player_not_found");
        }

        Integer amount = parseInt(value);

        if (amount == null || amount == 0) {
            return Result.fail("haojing_battlepass.admin.result.bad_value");
        }

        var global = dataManager.global(uuid);
        global.starCoin = Math.max(0, global.starCoin + amount);
        dataManager.markGlobalDirty(uuid);
        syncAll(server, uuid);
        return Result.ok("haojing_battlepass.admin.result.changed");
    }

    /** 发豁免卡：受 §5.9 的持有上限约束，超过上限的部分不发（并写日志）。 */
    private Result giveExemptCard(MinecraftServer server, ServerPlayerEntity actor, String target, String value) {
        UUID uuid = resolveTargetOrSelf(actor, target);

        if (uuid == null) {
            return Result.fail("haojing_battlepass.admin.result.player_not_found");
        }

        Integer amount = parseInt(value);

        if (amount == null || amount <= 0) {
            return Result.fail("haojing_battlepass.admin.result.bad_value");
        }

        SeasonConfig config = configManager.config();
        int max = config == null ? 3 : Math.max(0, config.exemptCardMax);
        var season = dataManager.season(uuid);
        int room = Math.max(0, max - season.exemptCards);

        if (room <= 0) {
            return Result.fail("haojing_battlepass.admin.result.card_limit");
        }

        season.exemptCards += Math.min(room, amount);
        dataManager.markSeasonDirty(uuid);
        syncAll(server, uuid);
        return Result.ok("haojing_battlepass.admin.result.changed");
    }

    /** 强制重抽每日任务（会作废该玩家当天进度，界面上有明确提示）。 */
    private Result forceTaskRefresh(MinecraftServer server, ServerPlayerEntity actor, String target) {
        UUID uuid = resolveTargetOrSelf(actor, target);

        if (uuid == null) {
            return Result.fail("haojing_battlepass.admin.result.player_not_found");
        }

        if (taskAssignmentService == null || !taskAssignmentService.forceDailyRefresh(uuid)) {
            return Result.fail("haojing_battlepass.admin.result.error");
        }

        syncAll(server, uuid);
        return Result.ok("haojing_battlepass.admin.result.tasks_refreshed");
    }

    /**
     * 清空自己的赛季进度（保留星币/称号/收藏册），用于反复自测。
     *
     * <p>复用 {@link SeasonResetService#resetSeasonData} 这份唯一实现，
     * 避免"管理员重置"和"赛季滚动"两处重置逻辑分叉。
     */
    private Result resetSelf(ServerPlayerEntity actor) {
        if (actor == null) {
            return Result.fail("haojing_battlepass.admin.result.bad_value");
        }

        UUID uuid = actor.getUuid();
        SeasonConfig config = configManager.config();
        String seasonId = config == null ? "S1" : config.seasonId;

        SeasonResetService.resetSeasonData(dataManager.season(uuid), seasonId);
        dataManager.markSeasonDirty(uuid);

        if (tracker != null) {
            tracker.invalidate(uuid);
        }

        syncAll(null, uuid);
        syncNow(actor);
        LOGGER.info("{} 管理员重置了自己的赛季进度（玩家 {}）", ModConstants.LOG_PREFIX, uuid);
        return Result.ok("haojing_battlepass.admin.result.self_reset");
    }

    /** 目标为空时用操作者自己（"给自己加经验"这种自测场景）。 */
    private UUID resolveTargetOrSelf(ServerPlayerEntity actor, String target) {
        if (target == null || target.isBlank()) {
            return actor == null ? null : actor.getUuid();
        }

        return resolvePlayer(target);
    }

    /** 立刻把最新快照推给该玩家（若在线）。 */
    private void syncNow(ServerPlayerEntity player) {
        if (syncService == null || player == null) {
            return;
        }

        syncService.markAllDirty(player.getUuid());

        for (String channel : com.haojing.battlepass.common.net.SyncChannels.ALL) {
            syncService.push(player, channel);
        }

        syncService.pushAdminNow(player);
    }

    // ------------------------------------------------------------------
    // 改赛季参数（§9 防肝参数 / 奖励管理）
    // ------------------------------------------------------------------

    private Result setSeasonEnabled(MinecraftServer server, String value) {
        SeasonConfig config = configManager.config();

        if (config == null) {
            return Result.fail("haojing_battlepass.admin.result.error");
        }

        config.enabled = Boolean.parseBoolean(value);
        return saveConfig(server, "赛季开关=" + config.enabled);
    }

    private Result setDailyXpCap(MinecraftServer server, String value) {
        SeasonConfig config = configManager.config();
        Integer cap = parseInt(value);

        if (config == null || cap == null) {
            return Result.fail("haojing_battlepass.admin.result.bad_value");
        }

        config.dailyXpCap = Math.max(0, cap);
        return saveConfig(server, "每日经验上限=" + config.dailyXpCap);
    }

    private Result setDailyRefresh(MinecraftServer server, String value) {
        SeasonConfig config = configManager.config();

        if (config == null || value == null || !value.contains(":")) {
            return Result.fail("haojing_battlepass.admin.result.bad_value");
        }

        config.dailyRefreshTime = value.trim();
        return saveConfig(server, "每日刷新时刻=" + config.dailyRefreshTime);
    }

    private Result setMaxLevel(MinecraftServer server, String value) {
        SeasonConfig config = configManager.config();
        Integer max = parseInt(value);

        if (config == null || max == null) {
            return Result.fail("haojing_battlepass.admin.result.bad_value");
        }

        config.maxLevel = Math.max(1, Math.min(100, max));
        return saveConfig(server, "等级上限=" + config.maxLevel);
    }

    private Result setBranchUnlock(MinecraftServer server, String value) {
        SeasonConfig config = configManager.config();
        Integer level = parseInt(value);

        if (config == null || level == null) {
            return Result.fail("haojing_battlepass.admin.result.bad_value");
        }

        config.branchUnlockLevel = Math.max(1, level);
        return saveConfig(server, "分支解锁等级=" + config.branchUnlockLevel);
    }

    private Result setXpCurve(MinecraftServer server, String field, String value) {
        SeasonConfig config = configManager.config();
        Integer number = parseInt(value);

        if (config == null || number == null || field == null) {
            return Result.fail("haojing_battlepass.admin.result.bad_value");
        }

        if ("base".equalsIgnoreCase(field)) {
            config.xpPerLevelBase = Math.max(0, number);
            return saveConfig(server, "经验曲线基础值=" + config.xpPerLevelBase);
        }

        if ("step".equalsIgnoreCase(field)) {
            config.xpPerLevelStep = Math.max(0, number);
            return saveConfig(server, "经验曲线递增量=" + config.xpPerLevelStep);
        }

        return Result.fail("haojing_battlepass.admin.result.bad_value");
    }

    private Result setLongNight(MinecraftServer server, String field, String value) {
        SeasonConfig config = configManager.config();

        if (config == null || config.longNight == null || field == null) {
            return Result.fail("haojing_battlepass.admin.result.error");
        }

        switch (field.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "enabled":
                config.longNight.enabled = Boolean.parseBoolean(value);
                break;
            case "start":
                config.longNight.start = value == null ? config.longNight.start : value.trim();
                break;
            case "end":
                config.longNight.end = value == null ? config.longNight.end : value.trim();
                break;
            case "xpmultiplier":
                try {
                    config.longNight.xpMultiplier = Double.parseDouble(value);
                } catch (NumberFormatException e) {
                    return Result.fail("haojing_battlepass.admin.result.bad_value");
                }
                break;
            default:
                return Result.fail("haojing_battlepass.admin.result.bad_value");
        }

        return saveConfig(server, "长夜." + field + "=" + value);
    }

    private Result saveConfig(MinecraftServer server, String describe) {
        // ConfigManager#save 会先校验再写盘：非法值在这里被修正 + 告警，不会写进文件。
        configManager.save();
        reloadDependentManagers();
        syncAllPlayers(server);
        LOGGER.info("{} 管理员修改了配置：{}", ModConstants.LOG_PREFIX, describe);
        return Result.ok("haojing_battlepass.admin.result.changed");
    }

    /** 配置变更后，依赖配置的管理器要重新按新参数校验（例如等级上限变了，奖励表要重剪）。 */
    private void reloadDependentManagers() {
        levelRewardManager.reload();
    }

    // ------------------------------------------------------------------
    // 赛季与备份
    // ------------------------------------------------------------------

    /**
     * 重新核对赛季并触发滚动。
     *
     * <p>语义说明（写给管理员）：判据是"玩家存档的 seasonId 与配置不一致"（见 D-16），
     * 因此**推进赛季的正确做法是先改 season.json 里的 seasonId，再点这个按钮**。
     * 这个按钮做的是"清掉已核对标记、立刻重新扫一遍"，配合改 ID 使用即可立即归档并重置。
     */
    private Result resetSeason(MinecraftServer server) {
        if (stateManager != null && stateManager.state() != null) {
            stateManager.state().lastSeasonId = "";
        }

        boolean started = seasonResetService != null && seasonResetService.checkAndRoll(
                server == null ? List.of() : server.getPlayerManager().getPlayerList().stream()
                        .map(ServerPlayerEntity::getUuid).toList());

        syncAllPlayers(server);
        LOGGER.info("{} 管理员触发了赛季重新核对（是否启动滚动={}）", ModConstants.LOG_PREFIX, started);
        return started
                ? Result.ok("haojing_battlepass.admin.result.season_rolling")
                : Result.ok("haojing_battlepass.admin.result.season_checked");
    }

    /** 把全部配置文件与全服状态复制到 data/haojing_battlepass/backup/&lt;时间戳&gt;/。 */
    private Result exportConfig() {
        Path backupDir = paths.globalDir().getParent().resolve("backup")
                .resolve(TimeUtil.now().format(BACKUP_STAMP));

        try {
            Files.createDirectories(backupDir);

            for (Path source : configFiles()) {
                if (Files.isRegularFile(source)) {
                    Files.copy(source, backupDir.resolve(source.getFileName().toString()),
                            StandardCopyOption.REPLACE_EXISTING);
                }
            }

            LOGGER.info("{} 已导出配置到 {}", ModConstants.LOG_PREFIX, backupDir);
            return Result.ok("haojing_battlepass.admin.result.exported");
        } catch (IOException e) {
            LOGGER.error("{} 导出配置失败：{}", ModConstants.LOG_PREFIX, backupDir, e);
            return Result.fail("haojing_battlepass.admin.result.error");
        }
    }

    /** 从备份目录导入配置并热重载。 */
    private Result importConfig(MinecraftServer server, String directory) {
        if (directory == null || directory.isBlank() || directory.contains("..") || directory.contains("/")
                || directory.contains("\\")) {
            // 目录名里出现路径分隔符或 .. 一律拒绝：这是 §9"禁止把输入直接当命令/路径执行"的同类风险。
            return Result.fail("haojing_battlepass.admin.result.bad_value");
        }

        Path backupDir = paths.globalDir().getParent().resolve("backup").resolve(directory);

        if (!Files.isDirectory(backupDir)) {
            return Result.fail("haojing_battlepass.admin.result.backup_not_found");
        }

        try {
            for (Path target : configFiles()) {
                Path source = backupDir.resolve(target.getFileName().toString());

                if (Files.isRegularFile(source)) {
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException e) {
            LOGGER.error("{} 导入配置失败：{}", ModConstants.LOG_PREFIX, backupDir, e);
            return Result.fail("haojing_battlepass.admin.result.error");
        }

        return reloadAll(server);
    }

    /** @return 全部需要备份/恢复的配置文件。 */
    private List<Path> configFiles() {
        List<Path> files = new ArrayList<>();
        files.add(paths.seasonConfigFile());
        files.add(paths.dailyTasksConfigFile());
        files.add(paths.rewardsConfigFile());
        files.add(paths.shopConfigFile());
        files.add(paths.eggsConfigFile());
        files.add(paths.codesConfigFile());
        files.add(paths.eventsConfigFile());
        files.add(paths.milestonesConfigFile());
        files.add(paths.stateFile());
        return files;
    }

    private Result startEvent(MinecraftServer server, String eventId) {
        if (randomEventManager == null || randomEventService == null) {
            return Result.fail("haojing_battlepass.admin.result.error");
        }

        com.haojing.battlepass.server.event.RandomEventDefinition definition =
                randomEventManager.config() == null ? null : randomEventManager.config().event(eventId);

        if (definition == null) {
            return Result.fail("haojing_battlepass.admin.result.event_not_found");
        }

        if (randomEventService.isActive()) {
            return Result.fail("haojing_battlepass.admin.result.event_active");
        }

        List<UUID> online = server == null ? List.of() : server.getPlayerManager().getPlayerList().stream()
                .map(ServerPlayerEntity::getUuid).toList();

        randomEventService.start(definition, System.currentTimeMillis(), online);
        return Result.ok("haojing_battlepass.admin.result.event_started");
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /**
     * 解析目标玩家：支持 UUID 字符串、在线玩家名、离线玩家的 lastKnownName。
     *
     * @param target 名字或 UUID
     * @return 玩家 UUID；找不到时为 null
     */
    UUID resolvePlayer(String target) {
        if (target == null || target.isBlank()) {
            return null;
        }

        String trimmed = target.trim();

        try {
            UUID uuid = UUID.fromString(trimmed);

            // UUID 有效但从未有过存档时也允许：管理员可能正在给新玩家预设数据。
            return uuid;
        } catch (IllegalArgumentException ignored) {
            // 不是 UUID，按名字找。
        }

        for (UUID uuid : dataManager.allStoredPlayerIds()) {
            String name = dataManager.global(uuid).lastKnownName;

            if (name != null && name.equalsIgnoreCase(trimmed)) {
                return uuid;
            }
        }

        return null;
    }

    private void syncAll(MinecraftServer server, UUID uuid) {
        if (syncService != null) {
            syncService.markAllDirty(uuid);

            ServerPlayerEntity player = server == null ? null : findOnline(server, uuid);

            if (player != null) {
                for (String channel : com.haojing.battlepass.common.net.SyncChannels.ALL) {
                    syncService.push(player, channel);
                }

                syncService.pushAdminNow(player);
            }
        }
    }

    private void syncAllPlayers(MinecraftServer server) {
        if (syncService != null) {
            syncService.markAllPlayersDirty(server);
        }
    }

    private ServerPlayerEntity findOnline(MinecraftServer server, UUID uuid) {
        return server == null ? null : server.getPlayerManager().getPlayer(uuid);
    }

    private static Integer parseInt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 取某等级升级所需经验（用于把经验夹到合法区间）。 */
    private static int LevelCurveNeed(SeasonConfig config, int level) {
        return com.haojing.battlepass.server.battlepass.LevelCurve.xpForLevel(config, level);
    }
}
