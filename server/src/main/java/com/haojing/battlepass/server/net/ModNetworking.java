package com.haojing.battlepass.server.net;

import com.haojing.battlepass.common.ModConstants;
import com.haojing.battlepass.common.data.Branch;
import com.haojing.battlepass.common.net.C2SGuard;
import com.haojing.battlepass.common.net.ModPayloadRegistration;
import com.haojing.battlepass.common.net.ModPayloads;
import com.haojing.battlepass.common.net.NetActions;
import com.haojing.battlepass.common.net.SyncChannels;
import com.haojing.battlepass.server.battlepass.BattlePassService;
import com.haojing.battlepass.server.data.PlayerDataManager;
import com.haojing.battlepass.server.shop.ShopManager;
import com.haojing.battlepass.server.task.TaskAssignmentService;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 用途：服务端网络层的**装配与路由**（需求文档 §2、§3、§8、§9、§14）。
 *
 * <p>本类只做三件事：注册包类型、把上行包路由到业务服务、把业务结果回执给客户端。
 * 具体规则都在别处：握手判定在 {@link HandshakeService}，上行包护栏在 {@link C2SGuard}，
 * 管理动作实现在 {@link ServerAdminActions}，同步在 {@link PlayerSyncService}。
 * 这样"网络层"很薄，出错时一眼能看出是哪一层的责任。
 *
 * <p>§14 的三条要求在这里都有明确落点：
 * <ul>
 *   <li><b>字段校验</b>：动作名必须能解析成枚举（否则丢弃），参数长度上限在护栏里。</li>
 *   <li><b>频率限制</b>：每个上行包都先过 {@link C2SGuard}。</li>
 *   <li><b>幂等处理</b>：业务层本身幂等（领奖靠状态机、购买靠限购与余额、用户卡靠状态），
 *       因此重复包只会得到"已经领取/已拥有"的回执，不会重复发奖。</li>
 * </ul>
 */
public final class ModNetworking {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModConstants.MOD_ID_SERVER);

    private final HandshakeService handshakeService;
    private final PlayerSyncService syncService;
    private final ServerAdminActions adminActions;
    private final BattlePassService battlePassService;
    private final TaskAssignmentService taskAssignmentService;
    private final ShopManager shopManager;
    private final PlayerDataManager dataManager;
    private final C2SGuard guard = new C2SGuard();

    public ModNetworking(HandshakeService handshakeService, PlayerSyncService syncService,
                         ServerAdminActions adminActions, BattlePassService battlePassService,
                         TaskAssignmentService taskAssignmentService, ShopManager shopManager,
                         PlayerDataManager dataManager) {
        this.handshakeService = handshakeService;
        this.syncService = syncService;
        this.adminActions = adminActions;
        this.battlePassService = battlePassService;
        this.taskAssignmentService = taskAssignmentService;
        this.shopManager = shopManager;
        this.dataManager = dataManager;
    }

    /** 注册全部包类型与监听。应在服务端初始化时调用一次。 */
    public void register() {
        registerPayloadTypes();

        // ---- configuration 阶段：客户端强制校验（§2） ----
        ServerConfigurationConnectionEvents.BEFORE_CONFIGURE.register(
                (handler, server) -> handshakeService.onConfigureStart(handler));
        ServerConfigurationNetworking.registerGlobalReceiver(ModPayloads.Handshake.ID,
                (payload, context) -> handshakeService.onHandshake(payload, context));

        // ---- play 阶段：兜底校验 + 首次全量同步 + 退出清理 ----
        ServerPlayConnectionEvents.INIT.register((handler, server) -> {
            if (!handshakeService.allowsPlay(handler.player)) {
                LOGGER.warn("{} 玩家 {} 未通过客户端校验，已断开（§2）",
                        ModConstants.LOG_PREFIX, handler.player.getName().getString());
                handler.disconnect(Text.translatable("haojing_battlepass.net.kick.missing"));
            }
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            syncService.syncNow(handler.player);
            // 把该玩家的当前称号广播给全服；同时把其他在线玩家的称号推给该玩家。
            try {
                String ownTitle = dataManager == null ? ""
                        : String.valueOf(dataManager.global(handler.player.getUuid()).equippedTitle);
                broadcastToAll(server, handler.player.getUuid().toString(), ownTitle);
                for (var online : server.getPlayerManager().getPlayerList()) {
                    if (online == handler.player) {
                        continue;
                    }
                    String t = String.valueOf(dataManager.global(online.getUuid()).equippedTitle);
                    if (!t.isEmpty()) {
                        ServerPlayNetworking.send(handler.player,
                                new ModPayloads.TitleBroadcast(online.getUuid().toString(), t));
                    }
                }
            } catch (RuntimeException ignore) {
                // 称号广播失败不影响玩家进服。
            }
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            guard.forget(handler.getPlayer().getUuid());
            syncService.forget(handler.getPlayer().getUuid());
            // 玩家离开：清掉头顶称号。
            try {
                broadcastToAll(server, handler.getPlayer().getUuid().toString(), "");
            } catch (RuntimeException ignore) {
            }
        });

        // ---- play 阶段上行包 ----
        ServerPlayNetworking.registerGlobalReceiver(ModPayloads.ClientAction.ID,
                (payload, context) -> onClientAction(context.server(), context.player(), payload));

        ServerPlayNetworking.registerGlobalReceiver(ModPayloads.AdminAction.ID,
                (payload, context) -> onAdminAction(context.server(), context.player(), payload));

        // ---- 配置文件远程编辑（OP 专用）----
        ServerPlayNetworking.registerGlobalReceiver(ModPayloads.ConfigFileRequest.ID,
                (payload, context) -> onConfigFileRequest(context.server(), context.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(ModPayloads.ConfigFileSave.ID,
                (payload, context) -> onConfigFileSave(context.server(), context.player(), payload));

        LOGGER.info("{} 网络层已注册（{}）", ModConstants.LOG_PREFIX, handshakeService.describe());
    }

    /**
     * 注册包类型。
     *
     * <p>委托给 :common 的 {@link com.haojing.battlepass.common.net.ModPayloadRegistration}：
     * 两个 jar 都要注册同一批通道，而单人游戏（集成服务端）会把两个 jar 同时加载 ——
     * 重复注册会让 Fabric 直接抛异常崩游戏。共享的一次性注册器从机制上消除这个冲突。
     */
    private void registerPayloadTypes() {
        ModPayloadRegistration.registerAll();
    }

    /** 每 20 tick 由服务端维护调用，把增量同步推出去。 */
    public void tick(MinecraftServer server) {
        syncService.setPopulation(server == null ? 0 : server.getPlayerManager().getPlayerList().size(),
                dataManager == null ? 0 : dataManager.allStoredPlayerIds().size());
        syncService.tick(server);
    }

    /** 处理玩家动作（§8 的按钮）。 */
    private void onClientAction(MinecraftServer server, ServerPlayerEntity player, ModPayloads.ClientAction payload) {
        if (player == null) {
            return;
        }

        C2SGuard.Verdict verdict = guard.checkClient(player.getUuid(), payload.action(), payload.arg(),
                System.currentTimeMillis());

        if (verdict != C2SGuard.Verdict.OK) {
            // §14：非法/超频包直接丢弃并记日志，绝不进入业务层。
            LOGGER.warn("{} 丢弃玩家 {} 的动作包（{}：{}）",
                    ModConstants.LOG_PREFIX, player.getName().getString(), verdict, payload.action());
            return;
        }

        NetActions.ClientAction action = NetActions.ClientAction.fromName(payload.action());
        String arg = payload.arg() == null ? "" : payload.arg().trim();

        switch (action) {
            case RESYNC:
                syncService.syncNow(player);
                reply(player, true, "haojing_battlepass.action.resync");
                break;
            case REROLL: {
                // 只能刷新已选定的任务组；未选组时拒绝。
                var seasonReroll = dataManager.season(player.getUuid());
                if (seasonReroll.chosenDailyGroup == null || !seasonReroll.chosenDailyGroup.equals(arg)) {
                    reply(player, false, "haojing_battlepass.action.daily_group.not_chosen");
                    break;
                }
                boolean rerolled = taskAssignmentService != null && taskAssignmentService.rerollGroup(player.getUuid(), arg);
                reply(player, rerolled, rerolled ? "haojing_battlepass.action.reroll.ok" : "haojing_battlepass.action.reroll.fail");
                break;
            }
            case CLAIM: {
                // 领奖前校验：每日任务必须属于本日已选定的组；每周任务不受此限。
                var seasonClaim = dataManager.season(player.getUuid());
                boolean isWeeklyClaim = seasonClaim.weeklyTasks != null
                        && seasonClaim.weeklyTasks.containsKey(arg);
                if (!isWeeklyClaim) {
                    String groupOfTask = groupOfDailyTask(seasonClaim, arg);
                    String chosen = seasonClaim.chosenDailyGroup == null ? "" : seasonClaim.chosenDailyGroup;
                    if (groupOfTask == null || !chosen.equals(groupOfTask)) {
                        reply(player, false, "haojing_battlepass.action.daily_group.not_chosen");
                        break;
                    }
                }
                BattlePassService.ClaimResult claim = battlePassService == null
                        ? new BattlePassService.ClaimResult(BattlePassService.ClaimOutcome.POOL_UNAVAILABLE, null, 0)
                        : battlePassService.claimTaskReward(player.getUuid(), arg);
                reply(player, claim.outcome() == BattlePassService.ClaimOutcome.OK,
                        claimKey(claim.outcome()));
                break;
            }
            case EXEMPT: {
                var seasonExempt = dataManager.season(player.getUuid());
                if (seasonExempt.chosenDailyGroup == null || !seasonExempt.chosenDailyGroup.equals(arg)) {
                    reply(player, false, "haojing_battlepass.action.daily_group.not_chosen");
                    break;
                }
                BattlePassService.ExemptCardOutcome exempt = battlePassService == null
                        ? BattlePassService.ExemptCardOutcome.NO_CARD
                        : battlePassService.useExemptCard(player.getUuid(), arg);
                reply(player, exempt == BattlePassService.ExemptCardOutcome.OK, exemptKey(exempt));
                break;
            }
            case CHOOSE_DAILY_GROUP: {
                boolean ok = chooseDailyGroup(player.getUuid(), arg);
                reply(player, ok, ok ? "haojing_battlepass.action.daily_group.ok" : "haojing_battlepass.action.daily_group.fail");
                if (ok) {
                    // 立即推一次任务快照，让客户端马上看到选中组的任务，不用等 2 秒兜底刷新。
                    syncService.markDirty(player.getUuid(), SyncChannels.TASKS);
                    syncService.push(player, SyncChannels.TASKS);
                }
                break;
            }
            case BUY:
                ShopManager.PurchaseResult purchase = shopManager == null
                        ? new ShopManager.PurchaseResult(ShopManager.PurchaseOutcome.NOT_FOUND, arg, 0, 0)
                        : shopManager.purchase(player.getUuid(), arg);
                reply(player, purchase.outcome() == ShopManager.PurchaseOutcome.OK, buyKey(purchase.outcome()));
                break;
            case EQUIP_TITLE:
                boolean equippedOk = equipTitle(player, arg);
                reply(player, equippedOk, "haojing_battlepass.action.title");
                if (equippedOk && server != null) {
                    broadcastToAll(server, player.getUuid().toString(), arg);
                }
                break;
            case TOGGLE_CHAT_TITLE: {
                var global = dataManager.global(player.getUuid());
                global.chatTitleVisible = !global.chatTitleVisible;
                dataManager.markGlobalDirty(player.getUuid());
                reply(player, true, global.chatTitleVisible
                        ? "haojing_battlepass.action.title.toggle_chat_on"
                        : "haojing_battlepass.action.title.toggle_chat_off");
                break;
            }
            case TOGGLE_NAMETAG_TITLE: {
                var global = dataManager.global(player.getUuid());
                global.nametagTitleVisible = !global.nametagTitleVisible;
                dataManager.markGlobalDirty(player.getUuid());
                reply(player, true, global.nametagTitleVisible
                        ? "haojing_battlepass.action.title.toggle_nametag_on"
                        : "haojing_battlepass.action.title.toggle_nametag_off");
                break;
            }
            case CHOOSE_BRANCH:
                BattlePassService.BranchChooseOutcome branch = battlePassService == null
                        ? BattlePassService.BranchChooseOutcome.INVALID_BRANCH
                        : battlePassService.chooseBranch(player.getUuid(), Branch.fromName(arg));
                reply(player, branch == BattlePassService.BranchChooseOutcome.OK, branchKey(branch));
                break;
            default:
                break;
        }

        // 无论结果如何都标脏：内容比较保证"没变化就不发包"，因此这里可以放心标全脏。
        syncService.markAllDirty(player.getUuid());

        for (String channel : SyncChannels.ALL) {
            syncService.push(player, channel);
        }
    }

    /** 处理管理面板动作（§9：OP + 服务端二次校验）。 */
    private void onAdminAction(MinecraftServer server, ServerPlayerEntity player, ModPayloads.AdminAction payload) {
        if (player == null) {
            return;
        }

        // §9 的"服务端二次校验"：这里用原版权限判据再查一次，客户端界面是否打开过完全不影响判定。
        boolean operator = CommandManager.GAMEMASTERS_CHECK.allows(player.getPermissions());

        if (!operator) {
            LOGGER.warn("{} 非 OP 玩家 {} 尝试执行管理动作 {}，已拒绝",
                    ModConstants.LOG_PREFIX, player.getName().getString(), payload.action());
            reply(player, false, "haojing_battlepass.admin.result.no_permission");
            return;
        }

        C2SGuard.Verdict verdict = guard.checkAdmin(player.getUuid(), payload.action(),
                payload.arg(), payload.value(), System.currentTimeMillis());

        if (verdict != C2SGuard.Verdict.OK) {
            LOGGER.warn("{} 丢弃管理动作包（{}：{}）", ModConstants.LOG_PREFIX, verdict, payload.action());
            return;
        }

        // 通过 OP 校验后才登记为"管理面板观察者"：之后每 2 秒自动推一次管理快照，
        // 面板无需再主动请求（这也是消除"请求 → 回开面板 → 重建 → 再请求"死循环的关键）。
        syncService.markAdminViewer(player.getUuid());

        NetActions.AdminAction action = NetActions.AdminAction.fromName(payload.action());
        ServerAdminActions.Result result = adminActions.execute(player, server, action,
                payload.arg(), payload.value());

        reply(player, result.success(), result.messageKey());
        // 管理动作多半改了玩家的可见数据，直接全量刷新一次管理面板。
        syncService.pushAdminNow(player);
    }

    /**
     * 玩家选择本日要做的每日任务组。只能在尚未选择时选一次；选完不可更改（当日）。
     *
     * @return 是否选组成功
     */
    private boolean chooseDailyGroup(java.util.UUID uuid, String group) {
        if (dataManager == null || group == null) {
            return false;
        }

        if (!com.haojing.battlepass.server.task.TaskPool.DAILY_GROUPS.contains(group)) {
            return false;
        }

        var season = dataManager.season(uuid);

        // 必须有该组的任务才能选（防御性：理论上 rollDailyInto 会保证三组都有）。
        if (season.dailyTasks == null || !season.dailyTasks.containsKey(group)) {
            return false;
        }

        if (season.chosenDailyGroup != null && !season.chosenDailyGroup.isEmpty()) {
            return false;
        }

        season.chosenDailyGroup = group;
        dataManager.markSeasonDirty(uuid);
        return true;
    }

    /**
     * 反查一个每日任务 ID 属于哪个组；不是当日每日任务时返回 null。
     * 用于在玩家领奖前校验"这个任务是不是今天选定的组里的"。
     */
    private String groupOfDailyTask(com.haojing.battlepass.common.data.SeasonData season, String taskId) {
        if (season == null || season.dailyTasks == null || taskId == null) {
            return null;
        }

        for (var entry : season.dailyTasks.entrySet()) {
            if (entry.getValue() != null && taskId.equals(entry.getValue().taskId)) {
                return entry.getKey();
            }
        }

        return null;
    }

    /**
     * 佩戴/卸下称号。
     *
     * @return 是否成功（称号不存在或未解锁时失败）
     */
    private boolean equipTitle(ServerPlayerEntity player, String titleId) {
        if (dataManager == null) {
            return false;
        }

        var global = dataManager.global(player.getUuid());

        if (titleId.isEmpty()) {
            global.equippedTitle = "";
        } else {
            if (global.unlockedTitles == null || !global.unlockedTitles.contains(titleId)) {
                return false;
            }

            global.equippedTitle = titleId;
        }

        dataManager.markGlobalDirty(player.getUuid());
        return true;
    }

    private static void broadcastToAll(MinecraftServer server, String uuid, String titleId) {
        if (server == null) {
            return;
        }
        ModPayloads.TitleBroadcast payload = new ModPayloads.TitleBroadcast(uuid, titleId == null ? "" : titleId);
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            try {
                if (ServerPlayNetworking.canSend(p, ModPayloads.TitleBroadcast.ID)) {
                    ServerPlayNetworking.send(p, payload);
                }
            } catch (RuntimeException ignore) {
            }
        }
    }

    /** 处理 OP 请求读取配置文件：校验 OP 后把文件内容回发给该玩家。 */
    private void onConfigFileRequest(MinecraftServer server, ServerPlayerEntity player,
                                     ModPayloads.ConfigFileRequest payload) {
        if (player == null) {
            return;
        }
        if (!CommandManager.GAMEMASTERS_CHECK.allows(player.getPermissions())) {
            LOGGER.warn("{} 非 OP 玩家 {} 尝试读取配置文件 {}",
                    ModConstants.LOG_PREFIX, player.getName().getString(), payload.filename());
            return;
        }
        String content = adminActions == null ? null : adminActions.readConfigFile(payload.filename());
        if (content == null) {
            reply(player, false, "haojing_battlepass.admin.result.bad_value");
            return;
        }
        try {
            ServerPlayNetworking.send(player, new ModPayloads.ConfigFileContent(payload.filename(), content));
        } catch (RuntimeException e) {
            LOGGER.warn("{} 回发配置文件 {} 失败：{}", ModConstants.LOG_PREFIX, payload.filename(), e.getMessage());
        }
    }

    /** 处理 OP 保存配置文件：校验 OP + JSON 语法 + 路径穿越，写盘后热重载。 */
    private void onConfigFileSave(MinecraftServer server, ServerPlayerEntity player,
                                  ModPayloads.ConfigFileSave payload) {
        if (player == null) {
            return;
        }
        if (!CommandManager.GAMEMASTERS_CHECK.allows(player.getPermissions())) {
            LOGGER.warn("{} 非 OP 玩家 {} 尝试保存配置文件 {}",
                    ModConstants.LOG_PREFIX, player.getName().getString(), payload.filename());
            return;
        }
        ServerAdminActions.Result result = adminActions == null
                ? ServerAdminActions.Result.fail("haojing_battlepass.admin.result.error")
                : adminActions.writeConfigFile(server, payload.filename(), payload.content());
        reply(player, result.success(), result.messageKey());
    }

    private void reply(ServerPlayerEntity player, boolean success, String messageKey) {
        if (player == null || messageKey == null || messageKey.isEmpty()) {
            return;
        }

        if (!ServerPlayNetworking.canSend(player, ModPayloads.ActionResult.ID)) {
            return;
        }

        ServerPlayNetworking.send(player, new ModPayloads.ActionResult(success, messageKey));
    }

    // ------------------------------------------------------------------
    // 结果 → 文案键
    // ------------------------------------------------------------------

    private static String claimKey(BattlePassService.ClaimOutcome outcome) {
        switch (outcome) {
            case OK:
                return "haojing_battlepass.action.claim.ok";
            case NOT_COMPLETED:
                return "haojing_battlepass.action.claim.not_completed";
            case ALREADY_CLAIMED:
                return "haojing_battlepass.action.claim.already";
            default:
                return "haojing_battlepass.action.claim.fail";
        }
    }

    private static String exemptKey(BattlePassService.ExemptCardOutcome outcome) {
        switch (outcome) {
            case OK:
                return "haojing_battlepass.action.exempt.ok";
            case NO_CARD:
                return "haojing_battlepass.action.exempt.no_card";
            case TASK_ALREADY_COMPLETED:
                return "haojing_battlepass.action.exempt.completed";
            case ALREADY_CLAIMED:
                return "haojing_battlepass.action.exempt.already";
            default:
                return "haojing_battlepass.action.exempt.fail";
        }
    }

    private static String buyKey(ShopManager.PurchaseOutcome outcome) {
        switch (outcome) {
            case OK:
                return "haojing_battlepass.action.buy.ok";
            case NOT_ENOUGH_COINS:
                return "haojing_battlepass.action.buy.no_coins";
            case LIMIT_REACHED:
                return "haojing_battlepass.action.buy.limit";
            case GRANT_FAILED:
                return "haojing_battlepass.action.buy.failed";
            default:
                return "haojing_battlepass.action.buy.not_found";
        }
    }

    private static String branchKey(BattlePassService.BranchChooseOutcome outcome) {
        switch (outcome) {
            case OK:
                return "haojing_battlepass.action.branch.ok";
            case LEVEL_TOO_LOW:
                return "haojing_battlepass.action.branch.too_low";
            case ALREADY_CHOSEN:
                return "haojing_battlepass.action.branch.already";
            default:
                return "haojing_battlepass.action.branch.invalid";
        }
    }
}
