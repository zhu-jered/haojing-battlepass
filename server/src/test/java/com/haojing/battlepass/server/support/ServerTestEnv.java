package com.haojing.battlepass.server.support;

import com.haojing.battlepass.common.data.Reward;
import com.haojing.battlepass.common.data.RewardType;
import com.haojing.battlepass.server.battlepass.BattlePassService;
import com.haojing.battlepass.server.battlepass.LevelRewardManager;
import com.haojing.battlepass.server.battlepass.XpMultiplierProvider;
import com.haojing.battlepass.server.battlepass.XpSource;
import com.haojing.battlepass.server.config.ConfigManager;
import com.haojing.battlepass.server.data.PlayerDataManager;
import com.haojing.battlepass.server.data.ServerStateManager;
import com.haojing.battlepass.server.egg.EggManager;
import com.haojing.battlepass.server.egg.EggService;
import com.haojing.battlepass.server.event.RandomEventManager;
import com.haojing.battlepass.server.event.RandomEventService;
import com.haojing.battlepass.server.milestone.MilestoneManager;
import com.haojing.battlepass.server.milestone.MilestoneService;
import com.haojing.battlepass.server.redeem.RedeemCodeManager;
import com.haojing.battlepass.server.redeem.RedeemCodeService;
import com.haojing.battlepass.server.reward.RewardSink;
import com.haojing.battlepass.server.season.SeasonResetService;
import com.haojing.battlepass.server.shop.ShopManager;
import com.haojing.battlepass.server.storage.JsonStore;
import com.haojing.battlepass.server.storage.StoragePaths;
import com.haojing.battlepass.server.task.TaskPoolManager;
import com.haojing.battlepass.server.task.TaskProgressTracker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 用途：阶段 5 单元测试的公共环境 —— 在临时目录里搭出一整套服务端模块。
 *
 * <p>为什么需要它：等级/经验/商店/赛季滚动这些测试都要"一份配置 + 一个数据层 + 一个业务服务"。
 * 每个测试类各搭一遍会重复七八十行样板，而且很容易出现"这个测试类忘了注入奖励出口"
 * 这种与待测逻辑无关的失败。集中一处之后，装配顺序只写一遍。
 *
 * <p>为什么默认不启动写盘线程（不调用 {@link PlayerDataManager#start()}）：
 * 后台线程会在断言的同时改文件，测试将变得不确定。需要落盘的测试显式调用
 * {@code data.flushDirty()}；这也是既有 {@code PlayerDataManagerTest} 的做法。
 *
 * <p>默认的测试配置刻意写得很小（每个任务组 2 个任务、奖励几条），
 * 这样断言可以直接对着具体数值写，而不是"大概涨了"。
 */
public final class ServerTestEnv implements AutoCloseable {

    /** 记录发放动作的假奖励出口，可指定某些类型必然失败（用于验证商店退款）。 */
    public static final class RecordingRewardSink implements RewardSink {

        /** 每条记录形如 {@code 玩家UUID|Reward(...)}，便于断言"发给了谁"。 */
        public final List<String> granted = new ArrayList<>();
        public final Set<RewardType> failFor = EnumSet.noneOf(RewardType.class);

        @Override
        public boolean grant(UUID playerUuid, Reward reward, String reason, XpSource xpSource) {
            if (reward == null) {
                return false;
            }

            RewardType type = reward.typeOrNull();

            if (type != null && failFor.contains(type)) {
                return false;
            }

            granted.add(playerUuid + "|" + reward);
            return true;
        }

        /** @param type 奖励类型 @return 该类型被发放过几次。 */
        public long countOf(RewardType type) {
            return granted.stream().filter(entry -> entry.contains(type.name())).count();
        }

        /** @param text 片段 @return 是否有发放记录包含该片段。 */
        public boolean sawText(String text) {
            return granted.stream().anyMatch(entry -> entry.contains(text));
        }

        /**
         * @param playerUuid 玩家
         * @return 发给该玩家的奖励条数
         */
        public long countFor(UUID playerUuid) {
            return granted.stream().filter(entry -> entry.startsWith(playerUuid + "|")).count();
        }
    }

    public final StoragePaths paths;
    public final JsonStore jsonStore;
    public final ConfigManager configs;
    public final PlayerDataManager data;
    public final ServerStateManager states;
    public final TaskPoolManager pools;
    public final LevelRewardManager rewards;
    public final ShopManager shop;
    public final TaskProgressTracker tracker;
    public final RecordingRewardSink sink = new RecordingRewardSink();
    public final BattlePassService battlePass;
    public final SeasonResetService seasonReset;
    public final List<String> announcements = new ArrayList<>();

    // ---- 阶段 6 ----
    public final EggManager eggManager;
    public final EggService eggService;
    public final RedeemCodeManager redeemCodeManager;
    public final RedeemCodeService redeemCodeService;
    public final RandomEventManager randomEventManager;
    public final RandomEventService randomEventService;
    public final MilestoneManager milestoneManager;
    public final MilestoneService milestoneService;

    /** 彩蛋解锁公告记录（"玩家|彩蛋ID"）。 */
    public final List<String> eggAnnouncements = new ArrayList<>();

    /** 随机事件公告记录（"开始/结束|事件ID|参与人数"）。 */
    public final List<String> eventAnnouncements = new ArrayList<>();

    /** 里程碑达成公告记录（"里程碑ID|数值|发放人数"）。 */
    public final List<String> milestoneAnnouncements = new ArrayList<>();

    private ServerTestEnv(Path tmp) throws IOException {
        paths = new StoragePaths(tmp.resolve("config"), tmp.resolve("game"));
        paths.ensureDirectories();
        jsonStore = new JsonStore();

        writeFixtures();

        configs = new ConfigManager(paths, jsonStore);
        configs.load();

        data = new PlayerDataManager(paths, jsonStore, configs.config().seasonId);
        states = new ServerStateManager(paths, jsonStore);
        states.load();

        pools = new TaskPoolManager(paths, jsonStore);
        pools.load();

        rewards = new LevelRewardManager(paths, jsonStore, configs);
        rewards.load();

        shop = new ShopManager(paths, jsonStore, data);
        shop.load();

        tracker = new TaskProgressTracker(data, pools, configs);

        battlePass = new BattlePassService(data, configs, pools, rewards, XpMultiplierProvider.alwaysOne());
        battlePass.setRewardSink(sink);
        shop.setRewardSink(sink);

        seasonReset = new SeasonResetService(data, configs, states, paths, jsonStore, tracker,
                (from, to) -> announcements.add(from + "->" + to));

        // 阶段 6：四份配置 + 四个服务（都不启动后台线程，避免与断言抢时序）。
        eggManager = new EggManager(paths, jsonStore);
        eggManager.load();

        redeemCodeManager = new RedeemCodeManager(paths, jsonStore);
        redeemCodeManager.load();

        randomEventManager = new RandomEventManager(paths, jsonStore);
        randomEventManager.load();

        milestoneManager = new MilestoneManager(paths, jsonStore);
        milestoneManager.load();

        milestoneService = new MilestoneService(data, milestoneManager, states);
        milestoneService.load();
        milestoneService.setRewardSink(sink);
        milestoneService.setAnnouncer((milestone, value, awarded) ->
                milestoneAnnouncements.add(milestone.id + "|" + value + "|" + awarded));

        battlePass.setMetricSink(milestoneService);

        eggService = new EggService(data, eggManager, battlePass);
        eggService.setMetricSink(milestoneService);
        eggService.setAnnouncer((uuid, playerName, egg) ->
                eggAnnouncements.add(playerName + "|" + egg.id));

        redeemCodeService = new RedeemCodeService(data, redeemCodeManager);
        redeemCodeService.setRewardSink(sink);

        randomEventService = new RandomEventService(randomEventManager);
        randomEventService.setRewardSink(sink);
        randomEventService.setAnnouncer((definition, started, participants) ->
                eventAnnouncements.add((started ? "开始" : "结束") + "|" + definition.id + "|" + participants.size()));
    }

    /**
     * 在临时目录里搭出整套模块。
     *
     * @param tmp JUnit 的 {@code @TempDir} 目录
     * @return 已装配好的环境
     */
    public static ServerTestEnv create(Path tmp) throws IOException {
        return new ServerTestEnv(tmp);
    }

    /**
     * 把一批固定测试配置写进临时目录。
     *
     * <p>必须"先写文件再加载"：三个管理器都是"文件存在就用文件、不存在才落地内置默认值"，
     * 因此预置文件就能精确控制测试数据，而不必依赖内置默认内容的具体数值。
     */
    private void writeFixtures() throws IOException {
        Files.writeString(paths.dailyTasksConfigFile(), """
                {
                  "schemaVersion": 1,
                  "weeklyCount": 1,
                  "groups": {
                    "explore": [
                      { "id": "t_exp_1", "action": "BREAK_BLOCK", "name": "破坏", "desc": "", "target": 2, "xp": 30, "starCoin": 0 },
                      { "id": "t_exp_2", "action": "BREAK_BLOCK", "name": "破坏2", "desc": "", "target": 2, "xp": 30, "starCoin": 0 }
                    ],
                    "build": [
                      { "id": "t_build_1", "action": "PLACE_BLOCK", "name": "放置", "desc": "", "target": 2, "xp": 30, "starCoin": 0 },
                      { "id": "t_build_2", "action": "PLACE_BLOCK", "name": "放置2", "desc": "", "target": 2, "xp": 30, "starCoin": 0 }
                    ],
                    "general": [
                      { "id": "t_gen_1", "action": "KILL_ENTITY", "name": "击杀", "desc": "", "target": 2, "xp": 30, "starCoin": 1 },
                      { "id": "t_gen_2", "action": "KILL_ENTITY", "name": "击杀2", "desc": "", "target": 2, "xp": 30, "starCoin": 1 }
                    ]
                  },
                  "weekly": [
                    { "id": "t_weekly_1", "action": "FISH", "name": "钓鱼", "desc": "", "target": 5, "xp": 200, "starCoin": 5 }
                  ]
                }
                """);

        Files.writeString(paths.rewardsConfigFile(), """
                {
                  "schemaVersion": 1,
                  "levels": [
                    { "level": 2, "common": [ { "type": "STAR_COIN", "amount": 10 } ] },
                    { "level": 3, "common": [ { "type": "TITLE", "titleId": "t_title_3" } ] },
                    { "level": 4, "hunt": [ { "type": "STAR_COIN", "amount": 7 } ],
                                  "build": [ { "type": "STAR_COIN", "amount": 9 } ] },
                    { "level": 5, "enabled": false, "common": [ { "type": "STAR_COIN", "amount": 999 } ] },
                    { "level": 6, "common": [] }
                  ]
                }
                """);

        Files.writeString(paths.shopConfigFile(), """
                {
                  "schemaVersion": 1,
                  "items": [
                    { "id": "s_title", "nameKey": "k.title", "price": 30, "limitPerPlayer": 1,
                      "reward": { "type": "TITLE", "titleId": "t_shop_title" } },
                    { "id": "s_card", "nameKey": "k.card", "price": 40, "limitPerPlayer": 3,
                      "reward": { "type": "EXEMPT_CARD", "amount": 1 } },
                    { "id": "s_off", "nameKey": "k.off", "price": 10, "enabled": false,
                      "reward": { "type": "TITLE", "titleId": "t_off" } },
                    { "id": "s_free", "nameKey": "k.free", "price": 0,
                      "reward": { "type": "STAR_COIN", "amount": 1 } }
                  ]
                }
                """);

        // ---- 阶段 6 夹具：彩蛋 / 口令 / 随机事件 / 里程碑 ----
        // 刻意写得很小且数值极端（阈值 10、时长 10 分钟、静听 5 秒），
        // 这样断言可以精确到具体数字，而不必"跑一分钟看看"。
        Files.writeString(paths.eggsConfigFile(), """
                {
                  "schemaVersion": 1,
                  "eggs": [
                    { "id": "egg_test_dawn", "name": "测试拂晓", "category": "GLOBAL", "xp": 20, "title": "t_dawn",
                      "condition": { "type": "TIME_ALTITUDE", "time": "08:00", "toleranceMin": 5,
                                     "minY": 90, "requireOutdoor": true } },
                    { "id": "egg_test_biomes", "name": "测试群系", "category": "SEASON_LIMITED", "xp": 30,
                      "condition": { "type": "BIOME_SET",
                                     "biomes": [ "minecraft:deep_dark", "minecraft:lush_caves" ] } },
                    { "id": "egg_test_still", "name": "测试静听", "category": "LONG_NIGHT", "xp": 0,
                      "condition": { "type": "NIGHT_STILL", "maxMovePerTick": 0.01, "stillSeconds": 5 } }
                  ],
                  "light": {
                    "chatKeywordsEnabled": true,
                    "chatKeywords": [
                      { "keyword": "镐京", "slogan": "协会标语", "cooldownSeconds": 60 }
                    ],
                    "birthdayEnabled": true,
                    "birthdayMessageKey": "k.birthday",
                    "festivals": [
                      { "id": "f_cross", "name": "跨年节", "startDate": "12-30", "endDate": "01-02",
                        "message": "跨年问候" }
                    ]
                  }
                }
                """);

        Files.writeString(paths.codesConfigFile(), """
                {
                  "schemaVersion": 1,
                  "codes": [
                    { "id": "c_open", "code": "Hello", "enabled": true,
                      "rewards": [ { "type": "STAR_COIN", "amount": 10 } ] },
                    { "id": "c_window", "code": "Winter", "startDate": "11-01", "endDate": "02-28",
                      "rewards": [ { "type": "STAR_COIN", "amount": 5 } ] },
                    { "id": "c_off", "code": "Off", "enabled": false,
                      "rewards": [ { "type": "STAR_COIN", "amount": 1 } ] }
                  ]
                }
                """);

        Files.writeString(paths.eventsConfigFile(), """
                {
                  "schemaVersion": 1,
                  "enabled": true,
                  "checkIntervalMinutes": 1,
                  "rollChance": 1.0,
                  "minIntervalMinutes": 60,
                  "announce": true,
                  "events": [
                    { "id": "e_double", "name": "测试双倍", "type": "DOUBLE_XP", "durationMinutes": 10, "weight": 1,
                      "participation": { "type": "ONLINE", "target": 1 },
                      "effects": [ { "kind": "XP_MULTIPLIER", "multiplier": 2.0 } ],
                      "rewards": [ { "type": "STAR_COIN", "amount": 7 } ] },
                    { "id": "e_kill", "name": "测试击杀", "type": "MOB_SURGE", "durationMinutes": 10, "weight": 1,
                      "participation": { "type": "KILL_ENTITY", "target": 2 },
                      "rewards": [ { "type": "STAR_COIN", "amount": 3 } ] }
                  ]
                }
                """);

        Files.writeString(paths.milestonesConfigFile(), """
                {
                  "schemaVersion": 1,
                  "milestones": [
                    { "id": "m_total", "name": "测试累计", "metric": "BLOCKS_BROKEN", "scope": "TOTAL",
                      "threshold": 10, "recipients": "PARTICIPANTS",
                      "rewards": [ { "type": "STAR_COIN", "amount": 5 } ] },
                    { "id": "m_percapita", "name": "测试人均", "metric": "MOBS_KILLED", "scope": "PER_CAPITA",
                      "threshold": 2, "recipients": "PARTICIPANTS",
                      "rewards": [ { "type": "STAR_COIN", "amount": 3 } ] }
                  ]
                }
                """);
    }

    /** 等待后台线程（赛季归档）完成任务。 */
    public static void awaitTrue(java.util.function.BooleanSupplier condition, String message) {
        long deadline = System.currentTimeMillis() + 8000L;

        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }

            try {
                Thread.sleep(20L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        throw new AssertionError("等待超时：" + message);
    }

    @Override
    public void close() {
        seasonReset.close();
        milestoneService.close();
        data.close();
    }
}
