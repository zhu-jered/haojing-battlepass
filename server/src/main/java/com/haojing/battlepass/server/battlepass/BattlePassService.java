package com.haojing.battlepass.server.battlepass;

import com.haojing.battlepass.common.ModConstants;
import com.haojing.battlepass.common.data.Branch;
import com.haojing.battlepass.common.data.GlobalData;
import com.haojing.battlepass.common.data.Reward;
import com.haojing.battlepass.common.data.SeasonData;
import com.haojing.battlepass.common.data.TaskProgress;
import com.haojing.battlepass.common.data.TaskStatus;
import com.haojing.battlepass.server.config.ConfigManager;
import com.haojing.battlepass.server.config.SeasonConfig;
import com.haojing.battlepass.server.data.PlayerDataManager;
import com.haojing.battlepass.server.milestone.MetricSink;
import com.haojing.battlepass.server.milestone.MilestoneMetric;
import com.haojing.battlepass.server.reward.RewardSink;
import com.haojing.battlepass.server.task.TaskDefinition;
import com.haojing.battlepass.server.task.TaskPool;
import com.haojing.battlepass.server.task.TaskPoolManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;

/**
 * 用途：战令核心业务 —— 经验换算与升级、每级 +10 星币、分支选择（本赛季锁定）、
 * 任务领奖、豁免卡使用、每日经验上限（§5.10）、长夜经验减半（§5.11）。
 * 对应需求文档 §4 的赛季基础机制、§5.9、§5.10、§5.11。
 *
 * <p>为什么本类刻意不引用任何 Minecraft 类型（与 {@code TaskProgressTracker} 同一原则）：
 * 升级循环、每日上限截断、满级短路、每级星币、分支锁定、领奖幂等这些规则
 * 全都是"一堆数字和状态的搬运"，但它们恰恰是最容易写错、也最需要回归保护的部分。
 * 不引用 Minecraft 类型，就能在毫秒级单元测试里覆盖 30 级满级、上限刚好用完、
 * 长夜倍率取整等边界；否则只能开服、练级、等到半夜。
 * 因此经验倍率走 {@link XpMultiplierProvider}，发奖走 {@link RewardSink}，两个出口都是接口。
 *
 * <p>为什么"领取任务奖励"放在这里而不是任务系统里：§9 要求奖励统一抽象为 Reward，
 * 等级奖励、任务奖励、商店兑换最终都要落到同一套发放逻辑与同一份幂等保护上。
 * 若把领奖留在任务系统，就会出现"两处各自发经验"的分叉，
 * 而每日经验上限与长夜倍率只要有一处漏判，玩家就能刷出双倍收益。
 */
public final class BattlePassService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModConstants.MOD_ID_SERVER);

    /** 领奖结果。 */
    public enum ClaimOutcome {
        /** 领取成功。 */
        OK,
        /** 玩家身上没有这个任务（可能刚被刷新换掉）。 */
        TASK_NOT_FOUND,
        /** 任务尚未完成（§5.5：只有 COMPLETED 才能领）。 */
        NOT_COMPLETED,
        /** 已经领取过（§16：同一奖励重复点击只发一次）。 */
        ALREADY_CLAIMED,
        /** 任务池不可用（配置加载失败），无法确定奖励内容。 */
        POOL_UNAVAILABLE
    }

    /** 豁免卡使用结果。 */
    public enum ExemptCardOutcome {
        /** 使用成功，任务已直接置为 CLAIMED。 */
        OK,
        /** 没有豁免卡。 */
        NO_CARD,
        /** 组名非法（不是 explore / build / general）。 */
        INVALID_GROUP,
        /** 该组当前没有任务。 */
        TASK_NOT_FOUND,
        /** 任务已经领取过，再使用只是浪费一张卡。 */
        ALREADY_CLAIMED,
        /**
         * 任务已经完成。§5.9 说使用豁免卡后"视为已领取、不重复发奖"，
         * 也就是用在这类任务上会白白丢掉本该到手的奖励，因此直接拒绝并提示去领取。
         */
        TASK_ALREADY_COMPLETED
    }

    /** 分支选择结果。 */
    public enum BranchChooseOutcome {
        /** 选择成功。 */
        OK,
        /** 等级不足（§4：10 级才解锁）。 */
        LEVEL_TOO_LOW,
        /** 本赛季已经选过（§4：本赛季锁定）。 */
        ALREADY_CHOSEN,
        /** 分支取值非法（不能选 NONE）。 */
        INVALID_BRANCH
    }

    /**
     * 领取结果明细。
     *
     * @param outcome        结果
     * @param xpResult       经验发放明细；未发经验时为 null
     * @param starCoinGained 本次直接获得的星币（任务自带的 starCoin，不含升级奖励）
     */
    public record ClaimResult(ClaimOutcome outcome, XpGrantResult xpResult, int starCoinGained) {

        static ClaimResult of(ClaimOutcome outcome) {
            return new ClaimResult(outcome, null, 0);
        }
    }

    private final PlayerDataManager dataManager;
    private final ConfigManager configManager;
    private final TaskPoolManager poolManager;
    private final LevelRewardManager levelRewardManager;
    private final XpMultiplierProvider multiplierProvider;

    /** 奖励发放出口。构造后注入，原因见 {@link #setRewardSink}。 */
    private volatile RewardSink rewardSink;

    /**
     * 里程碑统计出口（阶段 6）。默认为空实现，因此里程碑模块不可用时业务照常，
     * 只是不计数 —— 见 {@code MetricSink} 的说明。
     */
    private volatile MetricSink metricSink = MetricSink.NOOP;

    public BattlePassService(PlayerDataManager dataManager, ConfigManager configManager,
                             TaskPoolManager poolManager, LevelRewardManager levelRewardManager,
                             XpMultiplierProvider multiplierProvider) {
        this.dataManager = dataManager;
        this.configManager = configManager;
        this.poolManager = poolManager;
        this.levelRewardManager = levelRewardManager;
        this.multiplierProvider = multiplierProvider == null ? XpMultiplierProvider.alwaysOne() : multiplierProvider;
    }

    /**
     * 注入里程碑统计出口（阶段 6：全服累计战令经验、累计领取任务）。
     *
     * @param sink 统计出口；null 表示不统计
     */
    public void setMetricSink(MetricSink sink) {
        this.metricSink = sink == null ? MetricSink.NOOP : sink;
    }

    /**
     * 注入奖励发放出口。
     *
     * <p>为什么不在构造参数里直接传：奖励出口（{@code MinecraftRewardSink}）在发放
     * BATTLEPASS_XP 时要回调本服务的 {@link #addXp}，构造参数会形成循环依赖。
     * 先造本服务、再造出口、再回填，依赖顺序就是线性的，不必依赖"谁先 new"的隐式约定。
     *
     * @param sink 奖励出口
     */
    public void setRewardSink(RewardSink sink) {
        this.rewardSink = sink;
    }

    // ------------------------------------------------------------------
    // 经验与升级（§4 每升 1 级 +10 星币；§5.10 每日上限；§5.11 长夜减半）
    // ------------------------------------------------------------------

    /**
     * 发放经验（受每日上限约束）。
     *
     * @param playerUuid 玩家
     * @param rawXp      原始经验值（未乘倍率）
     * @param source     经验来源
     * @return 发放明细
     */
    public XpGrantResult addXp(UUID playerUuid, int rawXp, XpSource source) {
        return addXp(playerUuid, rawXp, source, false);
    }

    /**
     * 发放经验。
     *
     * <p>顺序很重要：先乘长夜倍率，再受每日上限截断。
     * 也就是说 §5.10 的"每日已获得经验"记的是**真正进入经验条的经验**，
     * 而不是玩家行为的原始产出 —— 否则长夜期间玩家的实际上限会莫名其妙翻倍。
     * （这条解释属于实现方对文档空白的取舍，已登记在 docs/需求偏差记录.md。）
     *
     * @param playerUuid      玩家
     * @param rawXp           原始经验值
     * @param source          经验来源
     * @param bypassDailyCap  是否绕过每日上限（仅管理员手动发放使用）
     * @return 发放明细
     */
    public XpGrantResult addXp(UUID playerUuid, int rawXp, XpSource source, boolean bypassDailyCap) {
        if (playerUuid == null || rawXp <= 0) {
            return XpGrantResult.none(1, 0);
        }

        SeasonConfig config = configManager.config();

        if (config == null) {
            return XpGrantResult.none(1, 0);
        }

        SeasonData season = dataManager.season(playerUuid);
        double multiplier = multiplierProvider.multiplierFor(playerUuid);

        if (season.level >= config.maxLevel) {
            // 满级后经验不再有去处：明确返回"满级"，而不是把经验塞进经验条造成显示异常。
            return new XpGrantResult(rawXp, 0, multiplier, false, true,
                    season.level, season.level, 0, season.dailyXpEarned);
        }

        // 倍率取整用四舍五入：0.5 倍下 25 点经验得 13 点，1 点经验得 1 点。
        // 若改成向下取整，长夜期间小额经验（1~2 点）会被抹成 0，
        // 玩家看到的是"进度涨了但经验一点没给"，比多给半点的观感问题严重得多。
        int effective = (int) Math.round(rawXp * multiplier);

        if (effective <= 0) {
            return new XpGrantResult(rawXp, 0, multiplier, false, false,
                    season.level, season.level, 0, season.dailyXpEarned);
        }

        int cap = config.dailyXpCap;
        // §5.10 的 0 表示不限制（与赛事配置里的说明一致）。
        int remaining = cap <= 0 ? Integer.MAX_VALUE : Math.max(0, cap - season.dailyXpEarned);

        int granted = bypassDailyCap ? effective : Math.min(effective, remaining);
        boolean capped = !bypassDailyCap && granted < effective;

        if (granted <= 0) {
            LOGGER.info("{} 玩家 {} 已达每日经验上限（{}/{}），本次 {} 点经验未发放（来源 {}）",
                    ModConstants.LOG_PREFIX, playerUuid, season.dailyXpEarned, cap, effective, source.label());

            return new XpGrantResult(rawXp, 0, multiplier, true, false,
                    season.level, season.level, 0, season.dailyXpEarned);
        }

        int levelBefore = season.level;
        int coinsGained = 0;

        season.xp += granted;
        season.dailyXpEarned += granted;

        while (season.level < config.maxLevel) {
            int need = LevelCurve.xpForLevel(config, season.level);

            if (need <= 0 || season.xp < need) {
                break;
            }

            season.xp -= need;
            season.level++;
            coinsGained += Math.max(0, config.starCoinPerLevel);
        }

        dataManager.markSeasonDirty(playerUuid);

        if (coinsGained > 0) {
            // §4：星币永久保留，因此写在永久数据文件里，赛季重置不会清零。
            GlobalData global = dataManager.global(playerUuid);
            global.starCoin += coinsGained;
            dataManager.markGlobalDirty(playerUuid);
        }

        if (season.level != levelBefore) {
            LOGGER.info("{} 玩家 {} 升级 {} → {}（本批经验 {}，倍率 {}，来源 {}，累计京币 +{}）",
                    ModConstants.LOG_PREFIX, playerUuid, levelBefore, season.level, granted,
                    multiplier, source.label(), coinsGained);
        }

        if (capped) {
            LOGGER.info("{} 玩家 {} 本次经验被每日上限截断：发放 {}/{}（今日累计 {}/{}）",
                    ModConstants.LOG_PREFIX, playerUuid, granted, effective, season.dailyXpEarned, cap);
        }

        XpGrantResult result = new XpGrantResult(rawXp, granted, multiplier, capped, false,
                levelBefore, season.level, coinsGained, season.dailyXpEarned);

        // §10：全服累计战令经验是里程碑指标之一，这里记的是**实际发放量**
        // （已经过倍率与每日上限），因此永远不会比玩家真正拿到的多。
        metricSink.add(MilestoneMetric.BATTLEPASS_XP, granted);

        if (result.leveledUp()) {
            // 升级后立刻结算等级奖励（§4 装饰类奖励 + §5.9 豁免卡来源之一）。
            grantDueLevelRewards(playerUuid);
        }

        return result;
    }

    /**
     * @param playerUuid 玩家
     * @return 今日还可以获得多少经验；配置为 0（不限）时返回 -1
     */
    public int remainingDailyXp(UUID playerUuid) {
        SeasonConfig config = configManager.config();

        if (config == null || playerUuid == null) {
            return 0;
        }

        if (config.dailyXpCap <= 0) {
            return -1;
        }

        SeasonData season = dataManager.season(playerUuid);
        return Math.max(0, config.dailyXpCap - season.dailyXpEarned);
    }

    /**
     * 结算到目前为止应当发放、但还没有发过的等级奖励。
     *
     * <p>为什么不是"升到某级时发那一级的奖励"就完事：还有两条路径会用到它 ——
     * ①玩家先升到 12 级、之后才在 10 级解锁处补选分支，此时 10~12 级的分支奖励要补发；
     * ②管理员改了奖励表或补发漏发的奖励。用"遍历 1~当前等级 + 去重键"的方式实现，
     * 三种路径共用同一份逻辑，也不会重复发放（去重键见 {@code SeasonData#claimedLevelRewards}）。
     *
     * <p>权衡：先去重再发放。若某条奖励发放失败（最典型的是玩家离线时发物品），
     * 这一组会被记为已发而不再重试。这是有意选择的方向 —— §16 明确要求
     * "同一奖励重复点击只发一次"，而重复发物品的后果比漏发一次更难挽回。
     *
     * @param playerUuid 玩家
     */
    public void grantDueLevelRewards(UUID playerUuid) {
        RewardSink sink = rewardSink;
        LevelRewardTable table = levelRewardManager == null ? null : levelRewardManager.table();

        if (sink == null || table == null || playerUuid == null) {
            if (sink == null) {
                LOGGER.warn("{} 奖励发放出口尚未接入，等级奖励暂不结算（玩家 {}）",
                        ModConstants.LOG_PREFIX, playerUuid);
            }
            return;
        }

        SeasonData season = dataManager.season(playerUuid);
        Branch branch = season.branch();
        boolean changed = false;

        for (int level = 1; level <= season.level; level++) {
            changed |= grantGroup(sink, playerUuid, season, level, null, table.commonRewards(level));

            if (branch != Branch.NONE) {
                changed |= grantGroup(sink, playerUuid, season, level, branch, table.branchRewards(level, branch));
            }
        }

        if (changed) {
            dataManager.markSeasonDirty(playerUuid);
            dataManager.markGlobalDirty(playerUuid);
        }
    }

    /** 发放某一级某一分支组（通用/分支）的奖励；已发过的整组跳过。 */
    private boolean grantGroup(RewardSink sink, UUID playerUuid, SeasonData season, int level,
                               Branch branch, List<Reward> rewards) {
        if (rewards == null || rewards.isEmpty()) {
            return false;
        }

        String label = branch == null
                ? "等级 " + level + " 通用奖励"
                : "等级 " + level + " " + branch + " 分支奖励";

        // 先登记再发放：登记返回 false 说明整组已经发过，直接跳过（幂等）。
        if (!season.markLevelRewardClaimed(level, branch)) {
            return false;
        }

        for (Reward reward : rewards) {
            sink.grant(playerUuid, reward, label);
        }

        return true;
    }

    // ------------------------------------------------------------------
    // 任务领奖与豁免卡（§5.9、§5.5）
    // ------------------------------------------------------------------

    /**
     * 领取一个已完成任务的奖励。
     *
     * <p>幂等由"状态机单向迁移"保证：只有处于 COMPLETED 的任务才会被改成 CLAIMED，
     * 改成 CLAIMED 之后再次调用只会得到 {@link ClaimOutcome#ALREADY_CLAIMED}。
     * 状态检查与置位在同一把锁里完成（锁的是玩家存档对象），因此并发点击也不可能双发。
     *
     * @param playerUuid 玩家
     * @param taskId     任务 ID（每日按组存进度，这里按任务 ID 反查）
     * @return 领取明细
     */
    public ClaimResult claimTaskReward(UUID playerUuid, String taskId) {
        TaskPool pool = poolManager == null ? null : poolManager.pool();

        if (playerUuid == null || taskId == null || pool == null) {
            return ClaimResult.of(pool == null ? ClaimOutcome.POOL_UNAVAILABLE : ClaimOutcome.TASK_NOT_FOUND);
        }

        TaskDefinition definition = pool.indexById().get(taskId);

        if (definition == null) {
            // 任务已从池子里被管理员删掉：玩家身上的旧进度不认，直接告知找不到。
            return ClaimResult.of(ClaimOutcome.TASK_NOT_FOUND);
        }

        SeasonData season = dataManager.season(playerUuid);
        TaskProgress progress = findProgress(season, taskId);

        if (progress == null) {
            return ClaimResult.of(ClaimOutcome.TASK_NOT_FOUND);
        }

        boolean isWeekly = season.weeklyTasks != null && season.weeklyTasks.containsKey(taskId);

        // 检查 + 置位必须是原子的：本方法将来会由数据包（阶段 7）触发，
        // 玩家手速够快就能在同一 tick 内点到两次。
        synchronized (season) {
            TaskStatus status = progress.status();

            if (status == TaskStatus.CLAIMED) {
                return ClaimResult.of(ClaimOutcome.ALREADY_CLAIMED);
            }

            if (status != TaskStatus.COMPLETED) {
                return ClaimResult.of(ClaimOutcome.NOT_COMPLETED);
            }

            progress.setStatus(TaskStatus.CLAIMED);
        }

        dataManager.markSeasonDirty(playerUuid);

        XpGrantResult xpResult = null;

        if (definition.xp > 0) {
            xpResult = addXp(playerUuid, definition.xp, isWeekly ? XpSource.WEEKLY_TASK : XpSource.DAILY_TASK);
        }

        int starCoin = 0;

        if (definition.starCoin > 0) {
            GlobalData global = dataManager.global(playerUuid);
            global.starCoin += definition.starCoin;
            dataManager.markGlobalDirty(playerUuid);
            starCoin = definition.starCoin;
        }

        LOGGER.info("{} 玩家 {} 领取任务奖励 {}（每周={} 经验 {} 京币 {}）",
                ModConstants.LOG_PREFIX, playerUuid, taskId, isWeekly, definition.xp, starCoin);

        metricSink.add(MilestoneMetric.TASKS_CLAIMED, 1L);

        return new ClaimResult(ClaimOutcome.OK, xpResult, starCoin);
    }

    /**
     * 使用一张豁免卡，把某个每日任务组直接置为 CLAIMED。
     *
     * <p>需求文档 §8 的玩家 GUI 上，"使用豁免卡"是挂在每日任务页（按组）的按钮，
     * 因此这里的参数是组名而不是任务 ID。§5.9 规定使用后"视为已领取，不重复发奖"，
     * 所以本方法**不发任何奖励**，只做状态迁移与扣卡。
     *
     * @param playerUuid 玩家
     * @param group      任务组（explore / build / general）
     * @return 使用结果
     */
    public ExemptCardOutcome useExemptCard(UUID playerUuid, String group) {
        if (playerUuid == null || group == null || !TaskPool.DAILY_GROUPS.contains(group)) {
            return ExemptCardOutcome.INVALID_GROUP;
        }

        SeasonData season = dataManager.season(playerUuid);

        if (season.exemptCards <= 0) {
            return ExemptCardOutcome.NO_CARD;
        }

        TaskProgress progress = season.dailyTasks == null ? null : season.dailyTasks.get(group);

        if (progress == null) {
            return ExemptCardOutcome.TASK_NOT_FOUND;
        }

        synchronized (season) {
            TaskStatus status = progress.status();

            if (status == TaskStatus.CLAIMED) {
                return ExemptCardOutcome.ALREADY_CLAIMED;
            }

            if (status == TaskStatus.COMPLETED) {
                // 已完成的任务直接领取就能拿到奖励，用豁免卡等于把奖励丢掉，拒绝并让调用方提示玩家。
                return ExemptCardOutcome.TASK_ALREADY_COMPLETED;
            }

            progress.setStatus(TaskStatus.CLAIMED);
            season.exemptCards--;
        }

        dataManager.markSeasonDirty(playerUuid);
        LOGGER.info("{} 玩家 {} 对任务组 {} 使用了任务卡（剩余 {} 张）",
                ModConstants.LOG_PREFIX, playerUuid, group, season.exemptCards);

        return ExemptCardOutcome.OK;
    }

    // ------------------------------------------------------------------
    // 分支（§4：10 级二选一，本赛季锁定，改选仅管理员指令可做）
    // ------------------------------------------------------------------

    /**
     * 玩家自行选择分支。
     *
     * @param playerUuid 玩家
     * @param branch     分支（HUNT / BUILD）
     * @return 选择结果
     */
    public BranchChooseOutcome chooseBranch(UUID playerUuid, Branch branch) {
        if (playerUuid == null || branch == null || branch == Branch.NONE) {
            return BranchChooseOutcome.INVALID_BRANCH;
        }

        SeasonConfig config = configManager.config();

        if (config == null) {
            return BranchChooseOutcome.INVALID_BRANCH;
        }

        SeasonData season = dataManager.season(playerUuid);

        synchronized (season) {
            if (season.hasChosenBranch()) {
                return BranchChooseOutcome.ALREADY_CHOSEN;
            }

            if (season.level < config.branchUnlockLevel) {
                return BranchChooseOutcome.LEVEL_TOO_LOW;
            }

            season.setBranch(branch);
        }

        dataManager.markSeasonDirty(playerUuid);

        // 补发"已经达到的等级"上该分支的奖励：玩家可能先升级、后回来补选分支。
        grantDueLevelRewards(playerUuid);

        LOGGER.info("{} 玩家 {} 选择了 {} 分支（等级 {}）",
                ModConstants.LOG_PREFIX, playerUuid, branch, season.level);

        return BranchChooseOutcome.OK;
    }

    /**
     * 管理员改选分支。需求文档 §4：本赛季锁定，**改选仅管理员指令可做**。
     *
     * @param playerUuid 玩家
     * @param branch     新分支（HUNT / BUILD）
     */
    public boolean adminSetBranch(UUID playerUuid, Branch branch) {
        if (playerUuid == null || branch == null || branch == Branch.NONE) {
            return false;
        }

        SeasonData season = dataManager.season(playerUuid);
        Branch previous;

        synchronized (season) {
            previous = season.branch();

            if (previous == branch) {
                return false;
            }

            season.setBranch(branch);
        }

        dataManager.markSeasonDirty(playerUuid);
        // 改了分支就要按新分支补发此前没发过的分支奖励（去重键里带分支名，因此不会重复发）。
        grantDueLevelRewards(playerUuid);

        LOGGER.info("{} 管理员把玩家 {} 的分支由 {} 改为 {}",
                ModConstants.LOG_PREFIX, playerUuid, previous, branch);

        return true;
    }

    // ------------------------------------------------------------------

    /** 在每日/每周任务里按任务 ID 找进度对象。 */
    private TaskProgress findProgress(SeasonData season, String taskId) {
        if (season.dailyTasks != null) {
            for (TaskProgress progress : season.dailyTasks.values()) {
                if (progress != null && taskId.equals(progress.taskId)) {
                    return progress;
                }
            }
        }

        if (season.weeklyTasks != null) {
            TaskProgress weekly = season.weeklyTasks.get(taskId);

            if (weekly != null) {
                return weekly;
            }
        }

        return null;
    }
}
