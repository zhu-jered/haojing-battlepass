package com.haojing.battlepass.server.egg;

import com.haojing.battlepass.common.ModConstants;
import com.haojing.battlepass.common.data.EggCategory;
import com.haojing.battlepass.common.data.EggProgress;
import com.haojing.battlepass.common.data.GlobalData;
import com.haojing.battlepass.common.data.SeasonData;
import com.haojing.battlepass.server.battlepass.BattlePassService;
import com.haojing.battlepass.server.battlepass.XpSource;
import com.haojing.battlepass.server.data.PlayerDataManager;
import com.haojing.battlepass.server.milestone.MetricSink;
import com.haojing.battlepass.server.milestone.MilestoneMetric;
import com.haojing.battlepass.server.time.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用途：彩蛋系统的业务核心 —— 判定调度、一次性触发、奖励发放、赛季限定语义，
 * 以及 §6 末尾三条轻量趣味彩蛋（聊天关键词 / 生日 / 节日问候）。
 *
 * <p>为什么本类不引用 Minecraft 类型：它是"13 个状态机 + 一次性保护 + 发奖幂等"的汇集点，
 * 也是最容易出"重复发奖""赛季限定彩蛋没随赛季失效"这类事故的地方。
 * 与判定器一样，它必须能被单元测试直接覆盖。
 *
 * <p>三个关键语义在这里落地：
 * <ul>
 *   <li><b>一次性</b>：触发记录写永久数据的收藏册（{@code GlobalData#collection}），
 *       因此同一个彩蛋一辈子只触发一次（§6：满足条件自动一次性触发）。</li>
 *   <li><b>赛季限定</b>：进度存在赛季数据里，赛季滚动会连进度一起清掉（§6）。
 *       但**已经解锁的记录**在永久数据里，所以历史册仍然留着（§6：记录永久留存历史册）。</li>
 *   <li><b>长夜专属零经验</b>：由 {@link EggPool} 在配置校验时强制归零，
 *       这里再按分类兜一层（{@code allowsXp()}），避免有人绕开校验直接构造定义。</li>
 * </ul>
 */
public final class EggService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModConstants.MOD_ID_SERVER);

    /** 触发时机 → 需要在该时机判定的条件类型。用于避免"每 tick 遍历全部 13 个彩蛋"。 */
    private static final Map<EggTrigger, EnumSet<EggConditionType>> TRIGGER_MATRIX = buildTriggerMatrix();

    /**
     * 彩蛋解锁公告出口。做成接口是为了让本类保持 MC-free
     * （公告要拿 MinecraftServer，而本类需要单元测试）。
     */
    @FunctionalInterface
    public interface Announcer {
        /**
         * @param playerUuid 触发者
         * @param playerName 玩家名（用于公告文案）
         * @param egg        彩蛋定义
         */
        void unlocked(UUID playerUuid, String playerName, EggDefinition egg);
    }

    private final PlayerDataManager dataManager;
    private final EggManager eggManager;
    private final BattlePassService battlePassService;

    private volatile Announcer announcer;
    private volatile MetricSink metricSink;

    /** 聊天关键词的冷却记录：玩家|关键词 → 上次触发毫秒数。 */
    private final Map<String, Long> keywordCooldowns = new ConcurrentHashMap<>();

    public EggService(PlayerDataManager dataManager, EggManager eggManager, BattlePassService battlePassService) {
        this.dataManager = dataManager;
        this.eggManager = eggManager;
        this.battlePassService = battlePassService;
    }

    /** 注入解锁公告出口。未注入时只写日志，不公告。 */
    public void setAnnouncer(Announcer value) {
        this.announcer = value;
    }

    /** 注入里程碑统计出口（彩蛋解锁次数等指标）。 */
    public void setMetricSink(MetricSink sink) {
        this.metricSink = sink;
    }

    /** @return 该玩家是否已解锁某个彩蛋（永久收藏册）。 */
    public boolean isUnlocked(UUID playerUuid, String eggId) {
        GlobalData global = dataManager.global(playerUuid);
        return global.collection != null && global.collection.contains(eggId);
    }

    /**
     * @param trigger 判定时机
     * @return 该时机下需要判定的彩蛋（已排除未启用、已解锁、条件类型不匹配的）
     */
    public List<EggDefinition> eggsFor(UUID playerUuid, EggTrigger trigger) {
        EggPool pool = eggManager == null ? null : eggManager.pool();

        if (pool == null) {
            return List.of();
        }

        EnumSet<EggConditionType> types = TRIGGER_MATRIX.get(trigger);

        if (types == null) {
            return List.of();
        }

        List<EggDefinition> result = new ArrayList<>();

        for (EggDefinition egg : pool.enabledEggs()) {
            EggConditionType type = egg.condition == null ? null : egg.condition.typeOrNull();

            if (type == null || !types.contains(type)) {
                continue;
            }

            // 已经解锁的彩蛋不必再判定：§6 是一次性触发。
            if (isUnlocked(playerUuid, egg.id)) {
                continue;
            }

            result.add(egg);
        }

        return result;
    }

    /**
     * @param egg 彩蛋
     * @return 该彩蛋是否需要**每 tick** 采样（目前只有"静听"，见 {@link EggEvaluator} 的说明）
     */
    public static boolean needsPerTickSampling(EggDefinition egg) {
        EggConditionType type = egg == null || egg.condition == null ? null : egg.condition.typeOrNull();
        return type == EggConditionType.NIGHT_STILL;
    }

    /**
     * 在给定时机对该玩家的相关彩蛋做一轮判定。
     *
     * @param playerUuid 玩家
     * @param playerName 玩家名（公告用）
     * @param trigger    判定时机
     * @param context    当前状态
     * @return 本轮触发的彩蛋数量
     */
    public int evaluate(UUID playerUuid, String playerName, EggTrigger trigger, EggContext context) {
        List<EggDefinition> candidates = eggsFor(playerUuid, trigger);

        if (candidates.isEmpty()) {
            return 0;
        }

        SeasonData season = dataManager.season(playerUuid);
        int triggered = 0;
        boolean changed = false;

        for (EggDefinition egg : candidates) {
            EggProgress progress = season.eggProgressOrCreate(egg.id);
            EggOutcome outcome = EggEvaluator.evaluate(egg, trigger, context, progress);

            if (outcome.changed()) {
                changed = true;
            }

            if (outcome.triggered()) {
                unlock(playerUuid, playerName, egg, season, context);
                triggered++;
            }
        }

        if (changed || triggered > 0) {
            dataManager.markSeasonDirty(playerUuid);
        }

        return triggered;
    }

    /**
     * 解锁一个彩蛋并发放奖励（幂等：收藏册里已有则直接返回 false）。
     *
     * <p>顺序是"先记收藏册再发奖"：§6 要求一次性触发，宁可极端情况下漏发一次奖励，
     * 也不能出现重复发放（与等级奖励同一个取舍方向）。
     *
     * @return 本次是否真的完成了首次解锁
     */
    public boolean unlock(UUID playerUuid, String playerName, EggDefinition egg, SeasonData season, EggContext context) {
        if (egg == null || egg.id == null || egg.id.isEmpty()) {
            return false;
        }

        GlobalData global = dataManager.global(playerUuid);

        if (!global.collect(egg.id)) {
            // 已经解锁过：这是幂等保护，正常情况下 eggsFor 已经把它过滤掉了。
            return false;
        }

        dataManager.markGlobalDirty(playerUuid);

        int xp = egg.xp;
        EggCategory category = egg.categoryOrGlobal();

        if (!category.allowsXp() && xp > 0) {
            LOGGER.warn("{} 彩蛋 {} 属于 {} 分类却带有经验值，已按 §6「长夜专属零经验」忽略",
                    ModConstants.LOG_PREFIX, egg.id, category);
            xp = 0;
        }

        if (xp > 0 && battlePassService != null) {
            // 全局彩蛋的经验同样受 §5.11 长夜减半与 §5.10 每日上限约束（§5.11 只豁免长夜专属彩蛋，
            // 而它本来就不给经验）。
            battlePassService.addXp(playerUuid, xp, XpSource.EGG);
        }

        if (egg.title != null && !egg.title.isBlank()) {
            if (global.unlockTitle(egg.title)) {
                dataManager.markGlobalDirty(playerUuid);
            }
        }

        MetricSink sink = metricSink;

        if (sink != null) {
            sink.add(MilestoneMetric.EGGS_UNLOCKED, 1);
        }

        // 需求文档 §6：触发记录写服务端日志；§10：彩蛋解锁必须写日志。
        LOGGER.info("{} 彩蛋解锁：{}（{}）由玩家 {} 触发，分类={} 经验={} 称号={} 现场={}",
                ModConstants.LOG_PREFIX, egg.id, egg.name, playerName == null ? playerUuid : playerName,
                category, xp, egg.title, context == null ? "-" : context.describe());

        Announcer current = announcer;

        if (egg.announce && current != null) {
            try {
                current.unlocked(playerUuid, playerName, egg);
            } catch (RuntimeException e) {
                // 公告失败不能影响解锁本身（奖励已经发完了）。
                LOGGER.warn("{} 彩蛋 {} 的公告发送失败：{}", ModConstants.LOG_PREFIX, egg.id, e.toString());
            }
        }

        return true;
    }

    // ------------------------------------------------------------------
    // §6 轻量趣味彩蛋
    // ------------------------------------------------------------------

    /**
     * 记录"玩家在长夜期间死亡"，用于 §6 晨归的"保持存活至 06:00"判定。
     *
     * <p>为什么专门写一个方法：死亡信息只能由 MC 侧的事件提供，而判定归本类管。
     * 把标记写在对应彩蛋的进度里（{@code died:<窗口键>}），
     * 窗口结束时的判定就能看到"这一晚他死过"，从而不给触发。
     *
     * @param playerUuid 玩家
     * @param periodKey  当前长夜窗口键；为空表示不在长夜中（不记录）
     */
    public void markDeathDuringLongNight(UUID playerUuid, String periodKey) {
        EggPool pool = eggManager == null ? null : eggManager.pool();

        if (pool == null || playerUuid == null || periodKey == null || periodKey.isEmpty()) {
            return;
        }

        SeasonData season = dataManager.season(playerUuid);
        boolean changed = false;

        for (EggDefinition egg : pool.enabledEggs()) {
            EggConditionType type = egg.condition == null ? null : egg.condition.typeOrNull();

            if (type != EggConditionType.NIGHT_SURVIVE_DAWN || isUnlocked(playerUuid, egg.id)) {
                continue;
            }

            changed |= season.eggProgressOrCreate(egg.id).addFlag("died:" + periodKey);
        }

        if (changed) {
            dataManager.markSeasonDirty(playerUuid);
        }
    }

    /**
     * 匹配聊天关键词。
     *
     * @param playerUuid 发言玩家
     * @param message    聊天内容（已去掉首尾空白）
     * @return 命中的规则；未命中、已关闭或处于冷却中时为 null
     */
    public LightEggConfig.ChatKeywordRule matchChatKeyword(UUID playerUuid, String message) {
        EggPool pool = eggManager == null ? null : eggManager.pool();

        if (pool == null || pool.light == null || !pool.light.chatKeywordsEnabled
                || message == null || message.isBlank()) {
            return null;
        }

        String normalized = message.trim().toLowerCase(java.util.Locale.ROOT);

        for (LightEggConfig.ChatKeywordRule rule : pool.light.chatKeywords) {
            if (rule == null || rule.keyword == null || rule.keyword.isBlank()) {
                continue;
            }

            if (!normalized.contains(rule.keyword.toLowerCase(java.util.Locale.ROOT))) {
                continue;
            }

            if (rule.cooldownSeconds > 0) {
                String key = playerUuid + "|" + rule.keyword;
                long now = System.currentTimeMillis();
                Long previous = keywordCooldowns.get(key);

                if (previous != null && now - previous < rule.cooldownSeconds * 1000L) {
                    // 冷却中：同一个玩家连着刷同一句话不再广播，避免刷屏。
                    return null;
                }

                keywordCooldowns.put(key, now);
            }

            return rule;
        }

        return null;
    }

    /**
     * 若今天是该玩家的生日且今天还没祝贺过，则返回祝贺文案键。
     *
     * @param playerUuid 玩家
     * @param dateKey    北京日期（yyyy-MM-dd）
     * @return 文案翻译键；不该祝贺时为 null
     */
    public String birthdayGreetingIfDue(UUID playerUuid, String dateKey) {
        EggPool pool = eggManager == null ? null : eggManager.pool();

        if (pool == null || pool.light == null || !pool.light.birthdayEnabled || dateKey == null) {
            return null;
        }

        GlobalData global = dataManager.global(playerUuid);
        String birthday = global.birthday;

        if (birthday == null || birthday.isBlank()) {
            return null;
        }

        String monthDay = monthDayOf(dateKey);

        if (!birthday.equals(monthDay)) {
            return null;
        }

        if (dateKey.equals(global.lastBirthdayGreeted)) {
            return null;
        }

        global.lastBirthdayGreeted = dateKey;
        dataManager.markGlobalDirty(playerUuid);

        LOGGER.info("{} 玩家 {} 生日祝贺已触发（{}）", ModConstants.LOG_PREFIX, playerUuid, monthDay);
        return pool.light.birthdayMessageKey;
    }

    /**
     * 若当前日期落在某个节日的问候窗口内且今天还没问候过，则返回该节日规则。
     *
     * @param playerUuid 玩家
     * @param dateKey    北京日期（yyyy-MM-dd）
     * @return 命中的节日；不该问候时为 null
     */
    public LightEggConfig.FestivalRule festivalGreetingIfDue(UUID playerUuid, String dateKey) {
        EggPool pool = eggManager == null ? null : eggManager.pool();

        if (pool == null || pool.light == null || dateKey == null || pool.light.festivals.isEmpty()) {
            return null;
        }

        String monthDay = monthDayOf(dateKey);
        GlobalData global = dataManager.global(playerUuid);

        for (LightEggConfig.FestivalRule festival : pool.light.festivals) {
            if (festival == null || !isWithinMonthDayWindow(monthDay, festival.startDate, festival.endDate)) {
                continue;
            }

            String key = dateKey + "|" + festival.id;

            if (key.equals(global.lastFestivalGreeted)) {
                return null;
            }

            global.lastFestivalGreeted = key;
            dataManager.markGlobalDirty(playerUuid);

            LOGGER.info("{} 玩家 {} 的节日问候已触发（节日 {} @{}）",
                    ModConstants.LOG_PREFIX, playerUuid, festival.id, monthDay);
            return festival;
        }

        return null;
    }

    /**
     * 判断某个 MM-dd 是否落在问候窗口内，支持跨年窗口（例如 12-30 ~ 01-02）。
     *
     * <p>实现委托给 {@link com.haojing.battlepass.server.time.MonthDayWindow}：
     * 节日问候与节日口令的时间段判定规则必须完全一致，
     * 两处各写一遍必然会在跨年窗口上分叉。
     */
    static boolean isWithinMonthDayWindow(String monthDay, String startDate, String endDate) {
        return com.haojing.battlepass.server.time.MonthDayWindow.contains(monthDay, startDate, endDate);
    }

    /** @param dateKey yyyy-MM-dd @return MM-dd。 */
    static String monthDayOf(String dateKey) {
        return com.haojing.battlepass.server.time.MonthDayWindow.monthDayOf(dateKey);
    }

    /** @return 便于日志的一行摘要（当前北京时间与已加载的彩蛋数）。 */
    public String describe() {
        EggPool pool = eggManager == null ? null : eggManager.pool();
        return "彩蛋数=" + (pool == null ? 0 : pool.size()) + " 时间=" + TimeUtil.stamp(TimeUtil.now());
    }

    private static Map<EggTrigger, EnumSet<EggConditionType>> buildTriggerMatrix() {
        Map<EggTrigger, EnumSet<EggConditionType>> matrix = new java.util.EnumMap<>(EggTrigger.class);

        matrix.put(EggTrigger.POLL, EnumSet.of(
                EggConditionType.TIME_ALTITUDE,
                EggConditionType.MOON_FULL_PLACE,
                EggConditionType.GROUP_STAY,
                EggConditionType.NIGHT_STREAK,
                EggConditionType.NIGHT_TORCH_OUTDOOR,
                EggConditionType.NIGHT_STILL));
        matrix.put(EggTrigger.PLACE_BLOCK, EnumSet.of(
                EggConditionType.MOON_FULL_PLACE,
                EggConditionType.SAPLING_GROW));
        matrix.put(EggTrigger.FISH, EnumSet.of(EggConditionType.RAIN_RIVER_FISH));
        matrix.put(EggTrigger.BIOME_ENTER, EnumSet.of(EggConditionType.BIOME_SET));
        matrix.put(EggTrigger.SAPLING_GROWN, EnumSet.of(EggConditionType.SAPLING_GROW));
        matrix.put(EggTrigger.NIGHT_WINDOW_END, EnumSet.of(
                EggConditionType.NIGHT_STREAK,
                EggConditionType.NIGHT_SURVIVE_DAWN));

        return matrix;
    }
}
