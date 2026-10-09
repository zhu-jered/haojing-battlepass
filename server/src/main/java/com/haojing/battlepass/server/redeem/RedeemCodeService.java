package com.haojing.battlepass.server.redeem;

import com.haojing.battlepass.common.ModConstants;
import com.haojing.battlepass.common.data.GlobalData;
import com.haojing.battlepass.common.data.Reward;
import com.haojing.battlepass.server.battlepass.XpSource;
import com.haojing.battlepass.server.data.PlayerDataManager;
import com.haojing.battlepass.server.reward.RewardSink;
import com.haojing.battlepass.server.time.MonthDayWindow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.UUID;

/**
 * 用途：节日口令的领取逻辑（需求文档 §10）。
 *
 * <p>§10 对口令提了三条硬要求，逐条落实在这里：
 * <ol>
 *   <li><b>匹配忽略大小写与首尾空白</b>：两边都做 trim + 小写后比较。</li>
 *   <li><b>生效时间段可配</b>：按 MM-dd 每年循环判定（复用 {@link MonthDayWindow}，
 *       与节日问候同一套规则，含跨年窗口）。</li>
 *   <li><b>每人每口令仅一次，去重键 playerUUID + 口令ID，需保证并发原子性</b>：
 *       去重键的玩家一半存在永久数据的 {@code claimedRedeemCodes} 里；
 *       "检查 + 置位"在**玩家永久数据对象**的锁内完成，
 *       因此同一玩家在同一 tick 里连发两条口令也只可能成功一次。</li>
 * </ol>
 *
 * <p>为什么本类不引用 Minecraft 类型：口令要在聊天事件里判定、
 * 但"匹配/时间窗/幂等"这些规则全部与渲染无关，必须能单元测试覆盖
 * （尤其是"同一 tick 连点两次"这种现实里很难复现的场景）。
 */
public final class RedeemCodeService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModConstants.MOD_ID_SERVER);

    /** 领取结果。 */
    public enum Outcome {
        /** 领取成功。 */
        OK,
        /** 口令不存在（没配或已停用）。 */
        NOT_FOUND,
        /** 不在生效时间段内。 */
        NOT_IN_WINDOW,
        /** 该玩家已经领过。 */
        ALREADY_CLAIMED,
        /** 输入为空。 */
        EMPTY_INPUT
    }

    /**
     * 领取结果明细。
     *
     * @param outcome 结果
     * @param code    命中的口令；未命中时为 null
     */
    public record RedeemResult(Outcome outcome, RedeemCode code) {

        static RedeemResult of(Outcome outcome) {
            return new RedeemResult(outcome, null);
        }
    }

    private final PlayerDataManager dataManager;
    private final RedeemCodeManager codeManager;

    private volatile RewardSink rewardSink;

    public RedeemCodeService(PlayerDataManager dataManager, RedeemCodeManager codeManager) {
        this.dataManager = dataManager;
        this.codeManager = codeManager;
    }

    /** 注入奖励出口。 */
    public void setRewardSink(RewardSink sink) {
        this.rewardSink = sink;
    }

    /**
     * 尝试用一段聊天文本领取口令。
     *
     * @param playerUuid 玩家
     * @param rawText    聊天原文
     * @param dateKey    当前北京日期（yyyy-MM-dd）
     * @return 结果
     */
    public RedeemResult redeem(UUID playerUuid, String rawText, String dateKey) {
        RedeemCodePool pool = codeManager == null ? null : codeManager.pool();

        if (playerUuid == null || rawText == null || rawText.isBlank() || pool == null) {
            return RedeemResult.of(rawText == null || rawText.isBlank() ? Outcome.EMPTY_INPUT : Outcome.NOT_FOUND);
        }

        String normalized = rawText.trim().toLowerCase(Locale.ROOT);
        String monthDay = MonthDayWindow.monthDayOf(dateKey);

        for (RedeemCode code : pool.all()) {
            if (code == null || code.code == null) {
                continue;
            }

            if (!code.code.toLowerCase(Locale.ROOT).equals(normalized)) {
                continue;
            }

            if (!MonthDayWindow.contains(monthDay, code.startDate, code.endDate)) {
                LOGGER.info("{} 玩家 {} 尝试领取口令 {}，但当前（{}）不在生效时间段 {}~{} 内",
                        ModConstants.LOG_PREFIX, playerUuid, code.id, monthDay, code.startDate, code.endDate);
                return RedeemResult.of(Outcome.NOT_IN_WINDOW);
            }

            if (!claim(playerUuid, code)) {
                return RedeemResult.of(Outcome.ALREADY_CLAIMED);
            }

            grant(playerUuid, code);

            // 需求文档 §10：关键操作写日志。
            LOGGER.info("{} 玩家 {} 领取了口令 {}（{}）",
                    ModConstants.LOG_PREFIX, playerUuid, code.id, code.name);
            return new RedeemResult(Outcome.OK, code);
        }

        return RedeemResult.of(Outcome.NOT_FOUND);
    }

    /**
     * 原子地占用"该玩家 + 该口令"的名额。
     *
     * @return true 表示本次占位成功（可以发奖）；false 表示已经领过
     */
    private boolean claim(UUID playerUuid, RedeemCode code) {
        GlobalData global = dataManager.global(playerUuid);

        synchronized (global) {
            if (global.claimedRedeemCodes == null) {
                global.claimedRedeemCodes = new java.util.LinkedHashSet<>();
            }

            if (!global.claimedRedeemCodes.add(code.id)) {
                return false;
            }
        }

        dataManager.markGlobalDirty(playerUuid);
        return true;
    }

    /** 发放奖励。先占位后发奖：宁可极端情况漏发，也不重复发放。 */
    private void grant(UUID playerUuid, RedeemCode code) {
        RewardSink sink = rewardSink;

        if (sink == null) {
            LOGGER.warn("{} 口令 {} 的奖励发放出口尚未接入，本次未发放任何奖励", ModConstants.LOG_PREFIX, code.id);
            return;
        }

        for (Reward reward : code.rewards) {
            sink.grant(playerUuid, reward, "口令 " + code.id, XpSource.SHOP);
        }
    }

    /** @param playerUuid 玩家 @param codeId 口令 ID @return 该玩家是否已领取过该口令。 */
    public boolean hasClaimed(UUID playerUuid, String codeId) {
        GlobalData global = dataManager.global(playerUuid);
        return global.claimedRedeemCodes != null && global.claimedRedeemCodes.contains(codeId);
    }
}
