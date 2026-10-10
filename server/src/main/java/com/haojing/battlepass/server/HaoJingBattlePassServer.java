package com.haojing.battlepass.server;

import com.haojing.battlepass.common.ModConstants;
import com.haojing.battlepass.server.battlepass.BattlePassService;
import com.haojing.battlepass.server.battlepass.LevelRewardManager;
import com.haojing.battlepass.server.config.ConfigManager;
import com.haojing.battlepass.server.config.SeasonConfig;
import com.haojing.battlepass.server.data.PlayerDataManager;
import com.haojing.battlepass.server.data.ServerStateManager;
import com.haojing.battlepass.server.egg.EggEvents;
import com.haojing.battlepass.server.egg.EggManager;
import com.haojing.battlepass.server.egg.EggService;
import com.haojing.battlepass.server.event.RandomEventEffects;
import com.haojing.battlepass.server.event.RandomEventManager;
import com.haojing.battlepass.server.event.RandomEventService;
import com.haojing.battlepass.server.longnight.LongNightManager;
import com.haojing.battlepass.server.milestone.MilestoneManager;
import com.haojing.battlepass.server.milestone.MilestoneMetric;
import com.haojing.battlepass.server.milestone.MilestoneService;
import com.haojing.battlepass.server.redeem.RedeemCodeEvents;
import com.haojing.battlepass.server.redeem.RedeemCodeManager;
import com.haojing.battlepass.server.redeem.RedeemCodeService;
import com.haojing.battlepass.server.reward.DataRewardApplier;
import com.haojing.battlepass.server.reward.MinecraftRewardSink;
import com.haojing.battlepass.server.season.SeasonResetService;
import com.haojing.battlepass.server.shop.ShopManager;
import com.haojing.battlepass.server.storage.JsonStore;
import com.haojing.battlepass.server.storage.StoragePaths;
import com.haojing.battlepass.server.task.TaskAssignmentService;
import com.haojing.battlepass.server.task.TaskEvents;
import com.haojing.battlepass.server.task.TaskPoolManager;
import com.haojing.battlepass.server.task.TaskProgressTracker;
import com.haojing.battlepass.server.time.TimeUtil;
import com.haojing.battlepass.server.title.TitleManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * 用途：服务端模组入口。需求文档 §2 规定全部业务逻辑只在服务端。
 *
 * <p>已接线的模块：
 * <ul>
 *   <li>阶段 2：数据持久化层（启动建目录、进服加载、退服标记、关服落盘）</li>
 *   <li>阶段 3：配置层（首次运行生成 season.json、自动热重载）与长夜模式</li>
 *   <li>阶段 4：任务池、任务进度与防刷、每日/每周刷新与重 roll、事件监听（含 3 处 Mixin）</li>
 *   <li>阶段 5：等级/经验/星币/分支、等级奖励表、商店、豁免卡与领奖、赛季归档与重置</li>
 *   <li>阶段 6：13 个彩蛋（含长夜专属与赛季限定）、世界随机事件、节日口令、全服里程碑、
 *       轻量趣味彩蛋（聊天关键词 / 生日 / 节日问候）</li>
 * </ul>
 *
 * <p>为什么把"每秒维护"（热重载 + 任务刷新 + 赛季滚动 + 随机事件 + 里程碑判定 + 待播公告）
 * 都收在一个 20 tick 的处理器里：§13 的性能预算只允许每 20 tick 做一次这类检查。
 * 集中在一处，一眼就能看出"每秒到底做了几件事"，不必去翻若干个注册点相加；
 * 而长夜状态机与彩蛋的每 tick 采样各自有明确的范围与理由，单独注册并注明。
 */
public class HaoJingBattlePassServer implements ModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModConstants.MOD_ID_SERVER);

    /** 维护轮询间隔：每 20 tick（1 秒）一次，符合 §13 的性能预算。 */
    private static final int MAINTENANCE_INTERVAL_TICKS = 20;

    private static volatile PlayerDataManager dataManager;
    private static volatile ConfigManager configManager;
    private static volatile LongNightManager longNightManager;
    private static volatile TaskPoolManager taskPoolManager;
    private static volatile TaskProgressTracker taskProgressTracker;
    private static volatile TaskAssignmentService taskAssignmentService;
    private static volatile LevelRewardManager levelRewardManager;
    private static volatile ShopManager shopManager;
    private static volatile BattlePassService battlePassService;
    private static volatile SeasonResetService seasonResetService;
    private static volatile EggManager eggManager;
    private static volatile EggService eggService;
    private static volatile RedeemCodeManager redeemCodeManager;
    private static volatile RedeemCodeService redeemCodeService;
    private static volatile RandomEventManager randomEventManager;
    private static volatile RandomEventService randomEventService;
    private static volatile MilestoneManager milestoneManager;
    private static volatile MilestoneService milestoneService;
    private static volatile TitleManager titleManager;
    private static volatile com.haojing.battlepass.server.net.ModNetworking networking;
    private static volatile com.haojing.battlepass.server.net.PlayerSyncService syncService;

    /** 随机事件用的随机源（集中一个，避免每次 new 出重复序列）。 */
    private static final Random RANDOM = new Random();

    private static int maintenanceCounter;

    public static PlayerDataManager data() {
        return dataManager;
    }

    public static ConfigManager config() {
        return configManager;
    }

    public static LongNightManager longNight() {
        return longNightManager;
    }

    public static TaskPoolManager taskPool() {
        return taskPoolManager;
    }

    public static TaskProgressTracker taskProgress() {
        return taskProgressTracker;
    }

    public static TaskAssignmentService taskAssignment() {
        return taskAssignmentService;
    }

    public static LevelRewardManager levelRewards() {
        return levelRewardManager;
    }

    public static ShopManager shop() {
        return shopManager;
    }

    public static BattlePassService battlePass() {
        return battlePassService;
    }

    public static SeasonResetService seasonReset() {
        return seasonResetService;
    }

    public static EggManager eggs() {
        return eggManager;
    }

    public static EggService eggService() {
        return eggService;
    }

    public static RedeemCodeManager redeemCodes() {
        return redeemCodeManager;
    }

    public static RedeemCodeService redeemCodeService() {
        return redeemCodeService;
    }

    public static RandomEventManager randomEvents() {
        return randomEventManager;
    }

    public static RandomEventService randomEventService() {
        return randomEventService;
    }

    public static MilestoneManager milestones() {
        return milestoneManager;
    }

    public static MilestoneService milestoneService() {
        return milestoneService;
    }

    public static TitleManager titles() {
        return titleManager;
    }

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTING.register(this::initModules);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> shutdownModules());

        // 长夜状态机自己按 20 tick 节流（§13 的性能预算），这里只管转发。
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            LongNightManager manager = longNightManager;

            if (manager != null) {
                manager.tick(server);
            }
        });

        // 每秒一次的服务端维护。真正干活之前先按 20 tick 节流，避免每 tick 取时间与遍历玩家。
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (++maintenanceCounter < MAINTENANCE_INTERVAL_TICKS) {
                return;
            }

            maintenanceCounter = 0;
            runMaintenance(server);
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            UUID uuid = handler.getPlayer().getUuid();

            PlayerDataManager data = dataManager;

            if (data != null) {
                data.season(uuid);
                data.rememberPlayerName(uuid, handler.getPlayer().getName().getString());
            }

            // 需求文档 §7：长夜期间新玩家上线弹规则说明。
            LongNightManager night = longNightManager;

            if (night != null) {
                night.sendRulesIfActive(handler.getPlayer());
            }

            // 上线时补结算等级奖励：玩家可能在上次升级时因为离线没拿到物品奖励，
            // 也可能管理员在他离线期间改了 rewards.json。去重键保证不会重复发。
            BattlePassService battlePass = battlePassService;

            if (battlePass != null) {
                battlePass.grantDueLevelRewards(uuid);
            }

            // 进服时补齐任务（真机反馈后补）：每日刷新只覆盖"刷新那一刻在线 + 已有存档"的玩家，
            // 因此新玩家或当天刷新后才上线的人会拿到**空任务列表**，直到第二天 06:00 都不会变。
            // 放在这里（ModNetworking 的 JOIN 同步之前注册，会先执行）能保证首次同步就带上任务。
            TaskAssignmentService assignment = taskAssignmentService;

            if (assignment != null) {
                assignment.ensureAssigned(uuid);
            }
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID uuid = handler.getPlayer().getUuid();

            PlayerDataManager data = dataManager;

            if (data != null) {
                // 只做标记，不在这里写盘：需求文档 §12 禁止主线程同步 IO。
                data.onPlayerQuit(uuid);
            }

            TaskProgressTracker tracker = taskProgressTracker;

            if (tracker != null) {
                // 释放该玩家的任务索引，避免内存随"见过的玩家数"无限增长。
                tracker.forget(uuid);
            }
        });

        LOGGER.info("{} 服务端模组骨架已加载（阶段 2-6：数据层 + 配置 + 长夜 + 任务 + 战令 + 彩蛋/事件/口令/里程碑）",
                ModConstants.LOG_PREFIX);
    }

    /**
     * 每秒一次的服务端维护：热重载各族配置、推进任务刷新、检查赛季滚动、推进随机事件、
     * 判定里程碑、补播结算公告。
     *
     * @param server 服务端实例
     */
    private void runMaintenance(MinecraftServer server) {
        List<UUID> online = new ArrayList<>();

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            online.add(player.getUuid());
        }

        // 配置热重载：任务池 / 奖励表 / 商店 / 彩蛋 / 口令 / 随机事件 / 里程碑都由这里统一驱动，
        // 改文件后 1 秒内生效（§2、§9 全部热重载）。
        // 赛季配置由长夜状态机负责（它是唯一按 tick 取时间的地方），因此这里不重复检测。
        try {
            TaskPoolManager pools = taskPoolManager;

            if (pools != null && pools.reloadIfChanged()) {
                TaskProgressTracker tracker = taskProgressTracker;

                if (tracker != null) {
                    // 任务定义换了，进度索引里的引用就过期了，必须重建。
                    tracker.invalidateAll();
                }
            }

            LevelRewardManager rewards = levelRewardManager;

            if (rewards != null && rewards.reloadIfChanged()) {
                // 奖励表被改过：给在线玩家补结算一次，让新增/调整的等级奖励立刻生效。
                BattlePassService battlePass = battlePassService;

                if (battlePass != null) {
                    for (UUID uuid : online) {
                        battlePass.grantDueLevelRewards(uuid);
                    }
                }
            }

            ShopManager shop = shopManager;

            if (shop != null) {
                shop.reloadIfChanged();
            }

            EggManager eggs = eggManager;

            if (eggs != null) {
                eggs.reloadIfChanged();
            }

            RedeemCodeManager codes = redeemCodeManager;

            if (codes != null) {
                codes.reloadIfChanged();
            }

            RandomEventManager events = randomEventManager;

            if (events != null) {
                events.reloadIfChanged();
            }

            MilestoneManager milestones = milestoneManager;

            if (milestones != null) {
                milestones.reloadIfChanged();
            }

            TitleManager titlesMgr = titleManager;

            if (titlesMgr != null) {
                titlesMgr.reloadIfChanged();
            }
        } catch (RuntimeException e) {
            LOGGER.warn("{} 配置热重载检查出现异常（本轮跳过）：{}", ModConstants.LOG_PREFIX, e.toString());
        }

        TaskAssignmentService assignment = taskAssignmentService;

        if (assignment != null) {
            try {
                assignment.poll(TimeUtil.now(), online);
            } catch (RuntimeException e) {
                // 任务刷新出错不应影响服务端运行，但要留下 WARN 便于排查。
                LOGGER.warn("{} 任务刷新轮询出现异常（本轮跳过）：{}", ModConstants.LOG_PREFIX, e.toString());
            }
        }

        // 随机事件：§10 要求全天可触发，长夜期间照常运行。
        RandomEventService events = randomEventService;

        if (events != null) {
            try {
                events.poll(System.currentTimeMillis(), online, RANDOM);
            } catch (RuntimeException e) {
                LOGGER.warn("{} 随机事件推进出现异常（本轮跳过）：{}", ModConstants.LOG_PREFIX, e.toString());
            }
        }

        MilestoneService milestones = milestoneService;

        if (milestones != null) {
            try {
                // §13 的可测量统计：长夜在线"人·秒"由在线人数累加，是里程碑指标之一。
                LongNightManager night = longNightManager;

                if (night != null && night.isActive() && !online.isEmpty()) {
                    milestones.add(MilestoneMetric.LONG_NIGHT_SECONDS, online.size());
                }

                milestones.check(online);
            } catch (RuntimeException e) {
                LOGGER.warn("{} 里程碑判定出现异常（本轮跳过）：{}", ModConstants.LOG_PREFIX, e.toString());
            }
        }

        SeasonResetService reset = seasonResetService;

        if (reset != null) {
            try {
                reset.checkAndRoll(online);

                // 只有真的有玩家在线才播报，否则公告会被标记成"已播报"而没人看到。
                if (!online.isEmpty()) {
                    reset.announcePendingIfAny();
                }
            } catch (RuntimeException e) {
                LOGGER.warn("{} 赛季滚动检查出现异常（本轮跳过）：{}", ModConstants.LOG_PREFIX, e.toString());
            }
        }

        // 阶段 7：增量同步（脏通道 + 界面兜底刷新，内容没变时不发包）。
        com.haojing.battlepass.server.net.ModNetworking network = networking;

        if (network != null) {
            try {
                network.tick(server);
            } catch (RuntimeException e) {
                LOGGER.warn("{} 状态同步出现异常（本轮跳过）：{}", ModConstants.LOG_PREFIX, e.toString());
            }
        }
    }

    private void initModules(MinecraftServer server) {
        try {
            StoragePaths paths = StoragePaths.createDefault();
            paths.ensureDirectories();

            JsonStore jsonStore = new JsonStore();

            // 配置必须先于数据层加载：赛季 ID 要从配置里取，决定玩家存档归属哪个赛季。
            ConfigManager configs = new ConfigManager(paths, jsonStore);
            SeasonConfig season = configs.load();
            configManager = configs;

            PlayerDataManager data = new PlayerDataManager(paths, jsonStore, season.seasonId);
            data.start();
            dataManager = data;

            ServerStateManager states = new ServerStateManager(paths, jsonStore);
            states.load();

            // 任务池要在数据层之后加载：它的校验会打日志，放在数据目录建好之后更清晰。
            TaskPoolManager pools = new TaskPoolManager(paths, jsonStore);
            pools.load();
            taskPoolManager = pools;

            // 等级奖励表的校验依赖当前 maxLevel，因此必须排在配置加载之后。
            LevelRewardManager rewards = new LevelRewardManager(paths, jsonStore, configs);
            rewards.load();
            levelRewardManager = rewards;

            ShopManager shop = new ShopManager(paths, jsonStore, data);
            shop.load();
            shopManager = shop;

            // 阶段 6 的四份配置。
            EggManager eggs = new EggManager(paths, jsonStore);
            eggs.load();
            eggManager = eggs;

            RedeemCodeManager codes = new RedeemCodeManager(paths, jsonStore);
            codes.load();
            redeemCodeManager = codes;

            RandomEventManager events = new RandomEventManager(paths, jsonStore);
            events.load();
            randomEventManager = events;

            MilestoneManager milestones = new MilestoneManager(paths, jsonStore);
            milestones.load();
            milestoneManager = milestones;

            TitleManager titles = new TitleManager(paths, jsonStore);
            titles.load();
            titleManager = titles;

            TaskProgressTracker tracker = new TaskProgressTracker(data, pools, configs);
            taskProgressTracker = tracker;

            taskAssignmentService = new TaskAssignmentService(data, pools, states, configs, tracker, paths, jsonStore);

            LongNightManager night = new LongNightManager(configs);
            longNightManager = night;

            // 战令业务：经验倍率是"长夜倍率 × 随机事件倍率"的乘积（§5.11 + §10）。
            // 用 lambda 在调用时读取静态字段，避免初始化顺序耦合（事件服务在本行之后才创建）。
            BattlePassService battlePass = new BattlePassService(data, configs, pools, rewards, uuid -> {
                LongNightManager currentNight = longNightManager;
                RandomEventService currentEvents = randomEventService;
                double nightMultiplier = currentNight == null ? 1.0D : currentNight.xpMultiplier(uuid);
                double eventMultiplier = currentEvents == null ? 1.0D : currentEvents.xpMultiplier();
                return nightMultiplier * eventMultiplier;
            });
            battlePassService = battlePass;

            // 奖励出口的两层装配：数据类奖励（离线也能发）与服务端相关奖励（物品/命令）。
            // 顺序是"先造业务、再造出口、再回填"，原因是出口发放经验时要回调业务（见 setRewardSink 注释）。
            DataRewardApplier dataApplier = new DataRewardApplier(data, configs);
            dataApplier.setXpSink((uuid, amount, source) -> battlePass.addXp(uuid, amount, source));

            MinecraftRewardSink sink = new MinecraftRewardSink(dataApplier, () -> server, configs::config);
            battlePass.setRewardSink(sink);
            shop.setRewardSink(sink);

            // 里程碑：先构造、恢复计数，再把它作为统计出口接到各业务上。
            MilestoneService milestoneServiceInstance = new MilestoneService(data, milestones, states);
            milestoneServiceInstance.load();
            milestoneServiceInstance.setRewardSink(sink);
            milestoneServiceInstance.setAnnouncer((milestone, value, awarded) ->
                    server.getPlayerManager().broadcast(Text.translatableWithFallback(
                            milestone.messageKey(),
                            "【全服里程碑】%s 已达成（实际 %s，奖励 %s 人）",
                            milestone.name, value, awarded), false));
            milestoneServiceInstance.start();
            milestoneService = milestoneServiceInstance;

            battlePass.setMetricSink(milestoneServiceInstance);

            // 彩蛋：解锁公告 + 里程碑的"累计解锁彩蛋"指标。
            EggService eggServiceInstance = new EggService(data, eggs, battlePass);
            eggServiceInstance.setMetricSink(milestoneServiceInstance);
            eggServiceInstance.setAnnouncer((uuid, playerName, egg) ->
                    server.getPlayerManager().broadcast(Text.translatableWithFallback(
                            egg.messageKey(),
                            "【彩蛋】%s 触发了隐藏彩蛋：「%s」",
                            playerName == null ? "某位玩家" : playerName, egg.name), false));
            eggService = eggServiceInstance;

            // 节日口令。
            RedeemCodeService redeemService = new RedeemCodeService(data, codes);
            redeemService.setRewardSink(sink);
            redeemCodeService = redeemService;

            // 随机事件：公告与效果由同一个回调驱动，避免"公告发了但效果忘了放"。
            RandomEventEffects effects = new RandomEventEffects(sink, () -> server, configs::config);
            RandomEventService eventService = new RandomEventService(events);
            eventService.setRewardSink(sink);
            eventService.setAnnouncer((definition, started, participants) -> {
                int duration = Math.max(1, definition.durationMinutes) * 60;

                if (started) {
                    effects.applyStart(definition.effects, duration, participants);
                    server.getPlayerManager().broadcast(Text.translatableWithFallback(
                            definition.startKey(),
                            "【随机事件】%s 开始了，持续 %s 分钟",
                            definition.name, definition.durationMinutes), false);
                } else {
                    effects.applyEnd(definition.effects, participants);
                    server.getPlayerManager().broadcast(Text.translatableWithFallback(
                            definition.endKey(),
                            "【随机事件】%s 已结束",
                            definition.name), false);
                }
            });
            randomEventService = eventService;

            // 赛季滚动：公告文案走 TranslationKey（§1），由客户端 zh_cn.json 提供译文。
            seasonResetService = new SeasonResetService(data, configs, states, paths, jsonStore, tracker,
                    (fromSeasonId, toSeasonId) -> server.getPlayerManager().broadcast(
                            Text.translatable("haojing_battlepass.season.rolled", fromSeasonId, toSeasonId), false));

            // 事件监听最后注册：确保上面所有依赖都已就绪，事件一到就能正常判定。
            // 任务事件同时把行为上报给里程碑与随机事件（§10 的统计口径与参与判定）。
            TaskEvents.register(tracker, milestoneServiceInstance, eventService);
            EggEvents.register(eggServiceInstance, night);
            RedeemCodeEvents.register(redeemService);

            // ---- 阶段 7：网络层（握手 + 增量同步 + 上行包路由 + 管理动作）----
            com.haojing.battlepass.server.net.SnapshotFactory snapshotFactory =
                    new com.haojing.battlepass.server.net.SnapshotFactory(data, configs, paths, pools, rewards,
                            shop, eggs, codes, events, milestones, milestoneServiceInstance, battlePass,
                            () -> {
                                LongNightManager current = longNightManager;
                                return current != null && current.isActive();
                            }, titles);
            snapshotFactory.setRandomEventService(eventService);

            com.haojing.battlepass.server.net.PlayerSyncService sync =
                    new com.haojing.battlepass.server.net.PlayerSyncService(snapshotFactory);
            syncService = sync;

            com.haojing.battlepass.server.net.HandshakeService handshake =
                    new com.haojing.battlepass.server.net.HandshakeService(configs);

            com.haojing.battlepass.server.net.ServerAdminActions adminActions =
                    new com.haojing.battlepass.server.net.ServerAdminActions(paths, configs, data, states,
                            battlePass, seasonResetService, pools, rewards, shop, eggs, codes, events,
                            eventService, milestones, sync, taskAssignmentService, tracker);

            com.haojing.battlepass.server.net.ModNetworking network =
                    new com.haojing.battlepass.server.net.ModNetworking(handshake, sync, adminActions,
                            battlePass, taskAssignmentService, shop, data);
            network.register();
            networking = network;

            // 聊天称号前缀（§8：服务端为佩戴者添加前缀，颜色与包裹符按 titles.json）。
            new com.haojing.battlepass.server.chat.ChatTitleDecorator(data, titles).register();

            // 服务端指令：/battlepass 与 /battlepass admin（§11）。
            com.haojing.battlepass.server.command.BattlePassCommand.register(sync);

            // 启动时先做一次赛季滚动检查：此时还没有玩家在线，
            // 处理的是离线存档（后台线程），归属上个赛季的玩家会在这一刻被归档并重置。
            seasonResetService.checkAndRoll(List.of());

            LOGGER.info("{} 模块已就绪：赛季={} 长夜={}({}~{} 倍率 {}) 任务池={} 等级奖励={} 级 商店={} 件",
                    ModConstants.LOG_PREFIX, season.seasonId, season.longNightEnabled(),
                    season.longNight.start, season.longNight.end, season.longNight.xpMultiplier,
                    pools.pool() == null ? 0 : pools.pool().totalSize(),
                    rewards.table() == null ? 0 : rewards.table().configuredLevelCount(),
                    shop.catalog() == null ? 0 : shop.catalog().size());
            LOGGER.info("{} 阶段 6 已就绪：彩蛋={}（{}）随机事件={} 种 口令={} 条 里程碑={} 条",
                    ModConstants.LOG_PREFIX,
                    eggs.pool() == null ? 0 : eggs.pool().size(),
                    eggs.pool() == null ? "-" : eggs.pool().describeCategories(),
                    events.config() == null ? 0 : events.config().size(),
                    codes.pool() == null ? 0 : codes.pool().size(),
                    milestones.table() == null ? 0 : milestones.table().size());
            LOGGER.info("{} 数据目录：{}", ModConstants.LOG_PREFIX, paths.describe());
        } catch (IOException e) {
            // 目录都建不出来时不应该让服务端直接崩，但必须明确报错，
            // 否则管理员会以为战令在正常工作，实际上数据根本没落盘。
            LOGGER.error("{} 初始化失败，战令功能将不可用", ModConstants.LOG_PREFIX, e);
        }
    }

    private void shutdownModules() {
        PlayerDataManager data = dataManager;
        TaskAssignmentService assignment = taskAssignmentService;
        SeasonResetService reset = seasonResetService;
        MilestoneService milestones = milestoneService;

        dataManager = null;
        configManager = null;
        longNightManager = null;
        taskPoolManager = null;
        taskProgressTracker = null;
        taskAssignmentService = null;
        levelRewardManager = null;
        shopManager = null;
        battlePassService = null;
        seasonResetService = null;
        eggManager = null;
        eggService = null;
        redeemCodeManager = null;
        redeemCodeService = null;
        randomEventManager = null;
        randomEventService = null;
        milestoneManager = null;
        milestoneService = null;
        titleManager = null;
        networking = null;
        syncService = null;

        if (assignment != null) {
            // 先停刷新线程，再落盘玩家数据，避免关服过程中还有线程在改数据。
            assignment.close();
        }

        if (reset != null) {
            // 归档线程同样先停，否则它可能正在写玩家文件时被数据层落盘打断。
            reset.close();
        }

        if (milestones != null) {
            // 里程碑计数是内存累加的，关服必须同步写一次（内部会先停掉定时落盘线程）。
            milestones.close();
        }

        if (data != null) {
            data.close();
        }
    }
}
