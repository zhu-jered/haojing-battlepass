package com.haojing.battlepass.server.checkin;

import com.haojing.battlepass.common.ModConstants;
import com.haojing.battlepass.common.data.Reward;
import com.haojing.battlepass.common.data.SeasonData;
import com.haojing.battlepass.server.battlepass.XpSource;
import com.haojing.battlepass.server.config.ConfigManager;
import com.haojing.battlepass.server.config.SeasonConfig;
import com.haojing.battlepass.server.data.PlayerDataManager;
import com.haojing.battlepass.server.reward.RewardSink;
import com.haojing.battlepass.server.time.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * 用途：每日签到与连续登录奖励的业务逻辑（签到判定、连击结算、每日档与里程碑发奖）。
 *
 * <p>规则口径（与每日任务同一业务日边界，即配置里的 {@code dailyRefreshTime}，默认 06:00）：
 * <ol>
 *   <li>玩家主动点击领取，不在进服时自动签到；同一业务日只能领一次。</li>
 *   <li>上次签到业务日 = 昨天业务日 → 连击 +1；否则（断签或首次）连击重置为 1。</li>
 *   <li>每日奖励按连击天数在 {@code cycleDays} 档内循环；里程碑达到固定天数只发一次，
 *       去重键记入赛季数据，赛季轮换后随赛季清零重新计数。</li>
 * </ol>
 *
 * <p>并发口径："检查是否已签 + 更新连击 + 占里程碑去重键"全部在赛季数据对象的锁内完成，
 * 同一玩家在同一 tick 连发两条签到包也只会成功一次（与口令领取同一取舍）。奖励发放放在锁外，
 * 避免奖励出口里可能的网络/物品操作长时间持锁。
 */
public final class CheckinService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModConstants.MOD_ID_SERVER);

    /** 签到结果。 */
    public enum Outcome {
        /** 签到成功。 */
        OK,
        /** 今日已经签过。 */
        ALREADY_CLAIMED,
        /** 签到功能未启用或配置缺失。 */
        DISABLED
    }

    /**
     * 签到结果明细。
     *
     * @param outcome          结果
     * @param streak           本次签到后的连续天数（非 OK 时为 0）
     * @param dailyRewards     本次发放的每日奖励
     * @param milestoneRewards 本次发放的里程碑奖励
     */
    public record CheckinResult(Outcome outcome, int streak,
                                List<Reward> dailyRewards, List<Reward> milestoneRewards) {

        static CheckinResult of(Outcome outcome) {
            return new CheckinResult(outcome, 0, List.of(), List.of());
        }
    }

    private final PlayerDataManager dataManager;
    private final CheckinManager manager;
    private final ConfigManager configManager;

    private volatile RewardSink rewardSink;

    public CheckinService(PlayerDataManager dataManager, CheckinManager manager, ConfigManager configManager) {
        this.dataManager = dataManager;
        this.manager = manager;
        this.configManager = configManager;
    }

    /** 注入奖励出口。 */
    public void setRewardSink(RewardSink sink) {
        this.rewardSink = sink;
    }

    /** @return 签到功能是否启用（配置缺失按关闭处理，避免无配置时白送奖励）。 */
    public boolean enabled() {
        return manager != null && manager.enabled();
    }

    /**
     * @param uuid 玩家
     * @return 今日（当前业务日）是否已经签到
     */
    public boolean isClaimedToday(UUID uuid) {
        if (uuid == null) {
            return false;
        }

        SeasonData season = dataManager.season(uuid);
        String todayKey = todayBusinessKey();
        return todayKey != null && todayKey.equals(season.lastSignInDate);
    }

    /**
     * 尝试领取今日签到奖励。
     *
     * @param uuid 玩家
     * @return 签到结果
     */
    public CheckinResult checkIn(UUID uuid) {
        CheckinPool pool = manager == null ? null : manager.pool();

        if (uuid == null || pool == null || !pool.enabled) {
            return CheckinResult.of(Outcome.DISABLED);
        }

        String todayKey = todayBusinessKey();
        String yesterdayKey = yesterdayBusinessKey();

        if (todayKey == null) {
            return CheckinResult.of(Outcome.DISABLED);
        }

        SeasonData season = dataManager.season(uuid);
        List<Reward> milestonePending = new ArrayList<>();

        synchronized (season) {
            if (todayKey.equals(season.lastSignInDate)) {
                return CheckinResult.of(Outcome.ALREADY_CLAIMED);
            }

            // 断签判定：上次签到日恰好是昨天才延续连击；跨了一天以上（或首次签到）重置为 1。
            int newStreak = yesterdayKey != null && yesterdayKey.equals(season.lastSignInDate)
                    ? season.signInStreak + 1 : 1;

            season.lastSignInDate = todayKey;
            season.signInStreak = newStreak;
            season.seasonSignInCount++;

            if (season.claimedStreakMilestones == null) {
                season.claimedStreakMilestones = new LinkedHashSet<>();
            }

            // 里程碑占位：先在锁内占去重键，再把待发奖励收集到锁外统一发放。
            for (CheckinMilestone milestone : pool.milestones()) {
                if (milestone.streak != newStreak) {
                    continue;
                }

                String key = String.valueOf(milestone.streak);

                if (season.claimedStreakMilestones.add(key)) {
                    milestonePending.addAll(milestone.rewards);
                }
            }
        }

        dataManager.markSeasonDirty(uuid);

        List<Reward> dailyRewards = pool.dailyRewardFor(season.signInStreak);
        grant(uuid, dailyRewards, "每日签到 第" + season.signInStreak + "天");
        grant(uuid, milestonePending, "连续签到 " + season.signInStreak + " 天里程碑");

        LOGGER.info("{} 玩家 {} 签到成功：连续 {} 天，本赛季累计 {} 次",
                ModConstants.LOG_PREFIX, uuid, season.signInStreak, season.seasonSignInCount);

        return new CheckinResult(Outcome.OK, season.signInStreak, dailyRewards, milestonePending);
    }

    /** 锁外发放奖励；出口未接入时只记 WARN，不回滚已占用的签到名额（宁可漏发不重复发）。 */
    private void grant(UUID uuid, List<Reward> rewards, String reason) {
        RewardSink sink = rewardSink;

        if (rewards == null || rewards.isEmpty()) {
            return;
        }

        if (sink == null) {
            LOGGER.warn("{} 签到奖励发放出口尚未接入，本次未发放：{}", ModConstants.LOG_PREFIX, reason);
            return;
        }

        for (Reward reward : rewards) {
            sink.grant(uuid, reward, reason, XpSource.DAILY_CHECKIN);
        }
    }

    /** @return 当前业务日键；时间或配置不可用时返回 null（调用方按 DISABLED 处理）。 */
    private String todayBusinessKey() {
        ZonedDateTime now = safeNow();
        return now == null ? null : TimeUtil.businessDateKey(now, refreshBoundary());
    }

    /** @return 上一业务日键；时间或配置不可用时返回 null。 */
    private String yesterdayBusinessKey() {
        ZonedDateTime now = safeNow();
        return now == null ? null : TimeUtil.businessDateKey(now.minusDays(1), refreshBoundary());
    }

    /** 每日任务刷新边界（签到与每日任务同一业务日口径）。 */
    private java.time.LocalTime refreshBoundary() {
        SeasonConfig config = configManager == null ? null : configManager.config();
        return config == null ? java.time.LocalTime.of(6, 0) : config.dailyRefresh();
    }

    private ZonedDateTime safeNow() {
        try {
            return TimeUtil.now();
        } catch (RuntimeException e) {
            LOGGER.warn("{} 获取北京时间失败，签到业务日判定本轮跳过：{}", ModConstants.LOG_PREFIX, e.toString());
            return null;
        }
    }
}
