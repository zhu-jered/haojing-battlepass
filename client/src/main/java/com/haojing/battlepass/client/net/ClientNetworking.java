package com.haojing.battlepass.client.net;

import com.haojing.battlepass.common.ModConstants;
import com.haojing.battlepass.common.net.ModPayloadRegistration;
import com.haojing.battlepass.common.net.ModPayloads;
import com.haojing.battlepass.common.net.NetActions;
import com.haojing.battlepass.common.net.SyncChannels;
import com.haojing.battlepass.client.gui.AdminPanelScreen;
import com.haojing.battlepass.client.gui.BattlePassScreen;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * 用途：客户端网络层（需求文档 §2 的握手、§3 的接收与发送、§8/§9 的界面数据）。
 *
 * <p>做三件事：
 * <ol>
 *   <li><b>注册包类型</b>：两端都注册全部类型，这样服务端才能用 {@code canSend}
 *       判断"这个客户端装了本 Mod"（§2 的校验依据）。</li>
 *   <li><b>配置阶段发握手</b>：连接一开始就把协议版本与 Mod 版本报给服务端。</li>
 *   <li><b>play 阶段收发</b>：收同步分片 → 交给 {@link ClientBattlePassState}；
 *       收面板打开请求 → 开对应界面；发玩家动作与管理动作。</li>
 * </ol>
 *
 * <p>为什么收到的包都丢进 {@code client.execute(...)}：网络回调可能不在渲染线程，
 * 而界面状态与界面切换只能在渲染线程做。统一 post 到主线程是唯一安全的做法。
 */
@Environment(EnvType.CLIENT)
public final class ClientNetworking {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModConstants.MOD_ID_CLIENT);

    private static final ClientBattlePassState STATE = new ClientBattlePassState();

    /** 已经打过"首次收到"日志的通道（避免每秒刷屏）。 */
    private static final java.util.Set<String> firstReceives = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 最近一次服务端回发的配置文件内容（管理面板轮询读取后清空）。 */
    private static volatile String pendingConfigFile = null;
    private static volatile String pendingConfigContent = null;

    private ClientNetworking() {
    }

    /** 注册全部客户端包类型与监听。应在客户端初始化时调用一次。 */
    public static void register() {
        // ---- 包类型（与服务端共用同一个一次性注册器，见 ModPayloadRegistration 的说明） ----
        ModPayloadRegistration.registerAll();

        // ---- 配置阶段：把版本报给服务端（§2） ----
        ClientConfigurationConnectionEvents.INIT.register((handler, client) -> {
            if (ClientConfigurationNetworking.canSend(ModPayloads.Handshake.ID)) {
                ClientConfigurationNetworking.send(new ModPayloads.Handshake(
                        ModConstants.PROTOCOL_VERSION, modVersion(), false));
                LOGGER.info("{} 已向服务端发送握手（协议版本 {}）", ModConstants.LOG_PREFIX, ModConstants.PROTOCOL_VERSION);
            }
        });

        ClientConfigurationNetworking.registerGlobalReceiver(ModPayloads.HandshakeResult.ID, (payload, context) -> {
            if (payload.accepted()) {
                LOGGER.info("{} 服务端校验通过（服务端协议 {}）", ModConstants.LOG_PREFIX, payload.protocolVersion());
            } else {
                LOGGER.warn("{} 服务端拒绝了本次连接：{}（服务端协议 {}）",
                        ModConstants.LOG_PREFIX, payload.messageKey(), payload.protocolVersion());
            }
        });

        // ---- play 阶段：接收 ----
        ClientPlayNetworking.registerGlobalReceiver(ModPayloads.SyncFragment.ID, (payload, context) ->
                context.client().execute(() -> {
                    boolean updated = STATE.applyFragment(payload.channel(), payload.revision(),
                            payload.partIndex(), payload.partCount(), payload.data());

                    if (updated && firstReceives.add(payload.channel())) {
                        // 每个通道首次收到数据打一条 INFO：排查"界面空白"时，
                        // 它能直接回答"客户端到底收到没有"。
                        LOGGER.info("{} 首次收到通道 {} 的同步数据（修订 {}，{} 字节分片）",
                                ModConstants.LOG_PREFIX, payload.channel(), payload.revision(),
                                payload.data() == null ? 0 : payload.data().length);
                    }
                }));

        ClientPlayNetworking.registerGlobalReceiver(ModPayloads.OpenPanel.ID, (payload, context) ->
                context.client().execute(() -> openPanel(payload.panel())));

        ClientPlayNetworking.registerGlobalReceiver(ModPayloads.ActionResult.ID, (payload, context) ->
                context.client().execute(() -> {
                    // 记录回执：界面底部会显示"服务端为什么拒绝/是否成功"，这是最直接的自诊断信息。
                    STATE.recordResult(payload.success(), payload.messageKey());

                    var player = context.client().player;

                    if (player != null && payload.messageKey() != null && !payload.messageKey().isEmpty()) {
                        // 动作回执走动作栏，不刷聊天框（§8 的界面操作反馈）。
                        player.sendMessage(Text.translatable(payload.messageKey()), true);
                    }
                }));

        ClientPlayNetworking.registerGlobalReceiver(ModPayloads.TitleBroadcast.ID, (payload, context) ->
                context.client().execute(() -> {
                    try {
                        UUID uuid = UUID.fromString(payload.playerUuid());
                        OnlineTitles.put(uuid, payload.titleId());
                    } catch (IllegalArgumentException e) {
                        // 非法 UUID 直接丢弃，不影响聊天/游戏。
                    }
                }));

        // 配置文件内容回发：暂存到静态字段，管理面板轮询读取。
        ClientPlayNetworking.registerGlobalReceiver(ModPayloads.ConfigFileContent.ID, (payload, context) ->
                context.client().execute(() -> {
                    pendingConfigFile = payload.filename();
                    pendingConfigContent = payload.content();
                }));

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            STATE.reset();
            OnlineTitles.clear();
            firstReceives.clear();
        });

        LOGGER.info("{} 客户端网络层已注册", ModConstants.LOG_PREFIX);
    }

    /** @return 客户端缓存的服务端状态。 */
    public static ClientBattlePassState state() {
        return STATE;
    }

    /** 请求全量重新同步（打开界面时调用）。 */
    public static void requestResync() {
        sendAction(NetActions.ClientAction.RESYNC, "");
    }

    /**
     * 发送一个玩家动作。
     *
     * @param action 动作
     * @param arg    参数
     */
    public static void sendAction(NetActions.ClientAction action, String arg) {
        if (action == null) {
            return;
        }
        if (!ClientPlayNetworking.canSend(ModPayloads.ClientAction.ID)) {
            var player = net.minecraft.client.MinecraftClient.getInstance().player;
            if (player != null) {
                player.sendMessage(Text.translatable("haojing_battlepass.net.server_outdated"), true);
            }
            return;
        }

        ClientPlayNetworking.send(new ModPayloads.ClientAction(action.name(), arg == null ? "" : arg));
    }

    /**
     * 发送一个管理动作（服务端会再查一次 OP，§9）。
     *
     * @param action 动作
     * @param arg    参数
     * @param value  取值
     */
    public static void sendAdmin(NetActions.AdminAction action, String arg, String value) {
        if (action == null) {
            return;
        }
        if (!ClientPlayNetworking.canSend(ModPayloads.AdminAction.ID)) {
            // 服务器没注册管理通道：多半是服务端 jar 太旧，给玩家一个可见提示而不是静默丢弃。
            var player = net.minecraft.client.MinecraftClient.getInstance().player;
            if (player != null) {
                player.sendMessage(Text.translatable("haojing_battlepass.net.server_outdated"), true);
            }
            return;
        }

        ClientPlayNetworking.send(new ModPayloads.AdminAction(action.name(),
                arg == null ? "" : arg, value == null ? "" : value));
    }

    /** 向服务端请求读取某个配置文件内容。 */
    public static void requestConfigFile(String filename) {
        if (!ClientPlayNetworking.canSend(ModPayloads.ConfigFileRequest.ID)) {
            return;
        }
        ClientPlayNetworking.send(new ModPayloads.ConfigFileRequest(filename));
    }

    /** 向服务端保存配置文件内容。 */
    public static void saveConfigFile(String filename, String content) {
        if (!ClientPlayNetworking.canSend(ModPayloads.ConfigFileSave.ID)) {
            return;
        }
        ClientPlayNetworking.send(new ModPayloads.ConfigFileSave(filename, content == null ? "" : content));
    }

    /**
     * 取出并清空最近一次服务端回发的配置文件内容。
     *
     * @return [filename, content]；没有新内容返回 null
     */
    public static String[] drainPendingConfigFile() {
        String name = pendingConfigFile;
        String content = pendingConfigContent;
        if (name == null) {
            return null;
        }
        pendingConfigFile = null;
        pendingConfigContent = null;
        return new String[]{name, content};
    }

    private static void openPanel(String panel) {
        var client = net.minecraft.client.MinecraftClient.getInstance();

        if (SyncChannels.Panel.ADMIN.equals(panel)) {
            // 已经开着管理面板时不再重建界面：重建会重置页签/页码，还会再来一轮"请求数据"。
            if (client.currentScreen instanceof AdminPanelScreen) {
                return;
            }

            client.setScreen(new AdminPanelScreen());
            return;
        }

        if (!(client.currentScreen instanceof BattlePassScreen)) {
            client.setScreen(new BattlePassScreen());
        }
    }

    private static String modVersion() {
        try {
            return FabricLoader.getInstance().getModContainer(ModConstants.MOD_ID_CLIENT)
                    .map(container -> container.getMetadata().getVersion().getFriendlyString())
                    .orElse("unknown");
        } catch (RuntimeException e) {
            return "unknown";
        }
    }
}
