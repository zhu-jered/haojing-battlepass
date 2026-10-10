package com.haojing.battlepass.server.event;

import com.haojing.battlepass.common.ModConstants;
import com.haojing.battlepass.common.data.Reward;
import com.haojing.battlepass.server.battlepass.XpSource;
import com.haojing.battlepass.server.reward.RewardSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用途：世界随机事件的调度与参与判定（需求文档 §10：类型枚举、触发概率、
 * 最小间隔（默认 ≥60 分钟）、持续时长、参与判定条件、奖励表；全天可触发，
 * 长夜期间正常运行且经验 ×0.5）。
 *
 * <p>为什么本类不引用 Minecraft 类型：调度规则（概率、冷却、时长、参与者判定、发奖）
 * 全部是纯逻辑，而它们恰恰很难在真机上验证 —— 要等 90 分钟的冷却、要凑够 3 个玩家一起击杀。
 * 不引用 MC 类型之后，"冷却没到不触发""参与次数不够不发奖""长夜期间照样触发"
 * 这些用例都能毫秒级跑完。
 *
 * <p>三个已明确的语义：
 * <ul>
 *   <li><b>长夜期间照常运行</b>（§10 明确要求），经验 ×0.5 由 {@code BattlePassService#addXp}
 *       统一负责（§5.11），事件只负责把经验奖励交给它。</li>
 *   <li><b>事件期间的经验倍率</b>（如"双倍经验"）通过 {@link #xpMultiplier()} 暴露，
 *       由入口把它与长夜倍率相乘后交给战令业务，因此两个倍率不会互相覆盖。</li>
 *   <li><b>进行中的事件不受热重载影响</b>：重载只换清单，已开始的那一场继续跑完。</li>
 * </ul>
 */
public final class RandomEventService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModConstants.MOD_ID_SERVER);

    /**
     * 事件公告出口（开始/结束）。做成接口是为了保持本类 MC-free；
     * 实现方除了发公告，还负责施放 Minecraft 侧的效果
     * （见 {@code RandomEventEffects}）—— 这样"开始/结束"这两个时点只有一个回调，
     * 不会出现"公告发了但效果忘了放"的分叉。
     */
    @FunctionalInterface
    public interface Announcer {
        /**
         * @param definition   事件定义
         * @param started      true = 开始，false = 结束
         * @param participants 参与者快照（开始时为 ONLINE 口径确定的名单，结束时为最终名单）
         */
        void announce(RandomEventDefinition definition, boolean started, List<UUID> participants);
    }

    private final RandomEventManager eventManager;

    private volatile RewardSink rewardSink;
    private volatile Announcer announcer;

    /** 进行中的事件定义；null 表示当前没有事件。 */
    private volatile RandomEventDefinition active;

    private volatile long activeStartedAt;
    private volatile long activeEndsAt;

    /** 参与者（ONLINE 类型在开始时就全部加入；其余类型在动作达标时加入）。 */
    private final Set<UUID> participants = ConcurrentHashMap.newKeySet();

    /** 参与判定用的动作计数：玩家 → 次数。 */
    private final Map<UUID, Integer> actionCounts = new ConcurrentHashMap<>();

    /** 上一次掷骰时间与上一次事件结束时间（毫秒）。 */
    private volatile long lastCheckAt;
    private volatile long lastEndedAt;

    /** 已开始过的事件场次（供 §13 的性能/运营观测与测试断言）。 */
    private volatile int startedCount;

    public RandomEventService(RandomEventManager eventManager) {
        this.eventManager = eventManager;
    }

    /** 注入奖励出口。 */
    public void setRewardSink(RewardSink sink) {
        this.rewardSink = sink;
    }

    /** 注入公告出口。 */
    public void setAnnouncer(Announcer value) {
        this.announcer = value;
    }

    /** @return 当前是否有事件进行中。 */
    public boolean isActive() {
        return active != null;
    }

    /** @return 进行中的事件定义；没有时为 null。 */
    public RandomEventDefinition activeEvent() {
        return active;
    }

    /** @return 已开始过的事件场次。 */
    public int startedCount() {
        return startedCount;
    }

    /** @return 参与者快照。 */
    public List<UUID> participants() {
        return new ArrayList<>(participants);
    }

    /** @return 进行中事件的效果列表；没有事件时为空。 */
    public List<EventEffect> activeEffects() {
        RandomEventDefinition current = active;
        return current == null ? List.of() : current.effects;
    }

    /**
     * @return 当前生效的事件经验倍率（多个 XP_MULTIPLIER 效果相乘；没有事件时为 1.0）。
     */
    public double xpMultiplier() {
        RandomEventDefinition current = active;

        if (current == null) {
            return 1.0D;
        }

        double multiplier = 1.0D;

        for (EventEffect effect : current.effects) {
            if (effect != null && effect.kindOrNull() == EventEffectKind.XP_MULTIPLIER) {
                multiplier *= effect.multiplier;
            }
        }

        return multiplier;
    }

    /**
     * 由服务端每秒维护调用：推进进行中的事件、或在冷却结束后掷骰子。
     *
     * @param nowMillis 当前毫秒（墙上时钟）
     * @param online    当前在线玩家
     * @param random    随机源（注入便于测试）
     */
    public void poll(long nowMillis, List<UUID> online, Random random) {
        RandomEventConfig config = eventManager == null ? null : eventManager.config();
        List<UUID> onlineList = online == null ? List.of() : online;

        // 进行中：只判断是否到点结束（不受总开关影响，已经开始的事件要跑完）。
        if (active != null) {
            if (nowMillis >= activeEndsAt) {
                finish(nowMillis);
            }

            return;
        }

        if (config == null || !config.enabled || random == null) {
            return;
        }

        if (nowMillis - lastCheckAt < config.checkIntervalMillis()) {
            return;
        }

        lastCheckAt = nowMillis;

        if (nowMillis - lastEndedAt < config.minIntervalMillis()) {
            // §10：最小间隔（默认 ≥60 分钟）——冷却期内连骰子都不掷。
            return;
        }

        if (config.rollChance <= 0 || random.nextDouble() >= config.rollChance) {
            return;
        }

        RandomEventDefinition picked = pickWeighted(config, random);

        if (picked == null) {
            return;
        }

        start(picked, nowMillis, onlineList);
    }

    /**
     * 开始一个事件（也供管理员手动触发使用）。
     *
     * @param definition 事件定义
     * @param nowMillis  当前毫秒
     * @param online     当前在线玩家
     */
    public void start(RandomEventDefinition definition, long nowMillis, List<UUID> online) {
        if (definition == null || active != null) {
            return;
        }

        active = definition;
        activeStartedAt = nowMillis;
        activeEndsAt = nowMillis + Math.max(1, definition.durationMinutes) * 60_000L;

        participants.clear();
        actionCounts.clear();

        if (definition.participation.typeOrDefault() == RandomEventParticipationType.ONLINE) {
            // ONLINE 口径：开始时在线即算参与。
            for (UUID uuid : online == null ? List.<UUID>of() : online) {
                if (uuid != null) {
                    participants.add(uuid);
                }
            }
        }

        startedCount++;

        // 需求文档 §10：关键操作写日志。
        LOGGER.info("{} 随机事件开始：{}（{}）持续 {} 分钟，参与判定={} 目标 {}，初始参与者 {} 人",
                ModConstants.LOG_PREFIX, definition.id, definition.type,
                definition.durationMinutes, definition.participation.typeOrDefault(),
                definition.participation.target, participants.size());

        announce(definition, true);
    }

    /**
     * 记录一次可能影响参与判定的动作。
     *
     * @param playerUuid 玩家
     * @param type       动作对应的参与判定类型
     */
    public void onAction(UUID playerUuid, RandomEventParticipationType type) {
        RandomEventDefinition current = active;

        if (current == null || playerUuid == null || type == null) {
            return;
        }

        RandomEventParticipation participation = current.participation;

        if (participation.typeOrDefault() != type) {
            return;
        }

        int target = Math.max(1, participation.target);
        int count = actionCounts.merge(playerUuid, 1, Integer::sum);

        if (count >= target) {
            participants.add(playerUuid);
        }
    }

    /** 结束当前事件并发放参与者奖励。 */
    public void finish(long nowMillis) {
        RandomEventDefinition current = active;

        if (current == null) {
            return;
        }

        active = null;
        lastEndedAt = nowMillis;

        int awarded = 0;
        RewardSink sink = rewardSink;

        if (sink != null) {
            for (UUID uuid : participants()) {
                boolean any = false;

                for (Reward reward : current.rewards) {
                    // 经验奖励走 BATTLEPASS_XP（由出口转交战令业务），因此长夜减半与每日上限照常生效。
                    if (sink.grant(uuid, reward, "随机事件 " + current.id, XpSource.RANDOM_EVENT)) {
                        any = true;
                    }
                }

                if (any) {
                    awarded++;
                }
            }
        }

        LOGGER.info("{} 随机事件结束：{}（{}）参与者 {} 人，实际收到奖励 {} 人",
                ModConstants.LOG_PREFIX, current.id, current.type, participants.size(), awarded);

        announce(current, false);

        participants.clear();
        actionCounts.clear();
    }

    private void announce(RandomEventDefinition definition, boolean started) {
        Announcer current = announcer;

        if (current == null) {
            return;
        }

        RandomEventConfig config = eventManager == null ? null : eventManager.config();

        if (config != null && !config.announce) {
            return;
        }

        try {
            current.announce(definition, started, participants());
        } catch (RuntimeException e) {
            LOGGER.warn("{} 随机事件 {} 的公告发送失败：{}", ModConstants.LOG_PREFIX, definition.id, e.toString());
        }
    }

    /** 按权重抽一个事件。 */
    private RandomEventDefinition pickWeighted(RandomEventConfig config, Random random) {
        List<RandomEventDefinition> candidates = config.all();

        if (candidates.isEmpty()) {
            return null;
        }

        int total = 0;

        for (RandomEventDefinition candidate : candidates) {
            total += Math.max(1, candidate.weight);
        }

        int roll = random.nextInt(Math.max(1, total));
        int cumulative = 0;

        for (RandomEventDefinition candidate : candidates) {
            cumulative += Math.max(1, candidate.weight);

            if (roll < cumulative) {
                return candidate;
            }
        }

        return candidates.get(candidates.size() - 1);
    }

    /** @return 进行中事件的剩余秒数；没有事件时为 0。 */
    public long remainingSeconds(long nowMillis) {
        RandomEventDefinition current = active;

        if (current == null) {
            return 0L;
        }

        return Math.max(0L, (activeEndsAt - nowMillis) / 1000L);
    }

    /** @return 便于日志与测试的一行摘要。 */
    public String describe() {
        RandomEventDefinition current = active;

        if (current == null) {
            return "无进行中的随机事件";
        }

        return "进行中=" + current.id + " 已开始 " + ((System.currentTimeMillis() - activeStartedAt) / 1000L)
                + " 秒 / 共 " + current.durationMinutes + " 分钟";
    }

    /**
     * 某玩家在当前事件中的参与进度（动作计数；ONLINE 类型未参与时返回 0，已参与时返回 target）。
     */
    public int playerProgress(UUID playerUuid) {
        if (playerUuid == null || active == null) {
            return 0;
        }

        if (participants.contains(playerUuid)) {
            return Math.max(1, active.participation.target);
        }

        Integer count = actionCounts.get(playerUuid);
        return count == null ? 0 : count;
    }

    /**
     * 距下次可触发事件的剩余秒数（无事件且在冷却中时；否则返回 0）。
     */
    public long cooldownSeconds(long nowMillis) {
        if (active != null) {
            return 0L;
        }

        RandomEventConfig config = eventManager == null ? null : eventManager.config();

        if (config == null || lastEndedAt <= 0L) {
            return 0L;
        }

        long elapsed = nowMillis - lastEndedAt;
        long remaining = config.minIntervalMillis() - elapsed;
        return Math.max(0L, remaining / 1000L);
    }

    /** 使用 {@link LinkedHashSet} 语义的参与者容器（保留加入顺序，便于日志）。 */
    Set<UUID> participantSet() {
        return new LinkedHashSet<>(participants);
    }
}
