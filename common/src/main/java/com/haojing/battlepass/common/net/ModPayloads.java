package com.haojing.battlepass.common.net;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;

/**
 * 用途：全部自定义数据包的**定义**（需求文档 §3）。两端共用这一份源码 ——
 * 阶段的骨架设计里已经把 :common 的源码并入两个成品工程编译，
 * 因此协议结构在机制上不可能分叉（不是靠"记得两边都改"）。
 *
 * <p>为什么用嵌套 record 集中在一个文件里：协议是一个整体，字段的含义、
 * 顺序、版本约束必须放在一起看才能判断"这次改动会不会破坏老客户端"。
 * 拆成七八个文件反而让人忘记同时检查另一端。
 *
 * <p>为什么每个包都带 {@code protocolVersion} 或依赖握手：§14 要求
 * "非法/异常/超大客户端数据包直接丢弃，不得导致服务端崩溃"，
 * 而"丢弃"的前提是能判断"这个包我认不认得"。版本号就是那个判据。
 */
public final class ModPayloads {

    private ModPayloads() {
    }

    // ------------------------------------------------------------------
    // configuration 阶段：握手（§2）
    // ------------------------------------------------------------------

    /**
     * 客户端 → 服务端 握手。
     *
     * @param protocolVersion 客户端协议版本（与服务端不一致时会被拒绝）
     * @param modVersion      客户端 Mod 版本（仅用于日志与排错）
     * @param devMode         客户端是否处于 devMode（服务端只把它写进日志，判定以服务端配置为准）
     */
    public record Handshake(int protocolVersion, String modVersion, boolean devMode) implements CustomPayload {

        public static final CustomPayload.Id<Handshake> ID = new CustomPayload.Id<>(ModNetworkingIds.HANDSHAKE_C2S);

        public static final PacketCodec<PacketByteBuf, Handshake> CODEC = PacketCodec.tuple(
                PacketCodecs.VAR_INT, Handshake::protocolVersion,
                PacketCodecs.string(64), Handshake::modVersion,
                PacketCodecs.BOOLEAN, Handshake::devMode,
                Handshake::new);

        @Override
        public CustomPayload.Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /**
     * 服务端 → 客户端 握手结果。
     *
     * @param accepted        是否通过
     * @param protocolVersion 服务端协议版本（供客户端提示"请更新 Mod"）
     * @param messageKey      提示文案的翻译键（拒绝原因）
     */
    public record HandshakeResult(boolean accepted, int protocolVersion, String messageKey) implements CustomPayload {

        public static final CustomPayload.Id<HandshakeResult> ID =
                new CustomPayload.Id<>(ModNetworkingIds.HANDSHAKE_RESULT_S2C);

        public static final PacketCodec<PacketByteBuf, HandshakeResult> CODEC = PacketCodec.tuple(
                PacketCodecs.BOOLEAN, HandshakeResult::accepted,
                PacketCodecs.VAR_INT, HandshakeResult::protocolVersion,
                PacketCodecs.string(128), HandshakeResult::messageKey,
                HandshakeResult::new);

        @Override
        public CustomPayload.Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    // ------------------------------------------------------------------
    // play 阶段：同步（S2C）
    // ------------------------------------------------------------------

    /**
     * 同步数据的一个分片。
     *
     * <p>为什么要分片（§3 明确要求"对超大 payload 分片"）：商店清单、收藏册历史这类
     * 快照随赛季累积会越来越大，而单个自定义包过大会被协议层拒绝或造成明显的网络尖峰。
     * 这里把 JSON 切成固定大小的片段逐个发送，客户端按
     * {@code 通道 + 修订号} 重组。
     *
     * @param channel   逻辑通道名（见 {@link SyncChannels}），例如 {@code tasks}
     * @param revision  修订号：客户端只接受比手上更新的修订，旧包直接丢弃
     * @param partIndex 分片序号（0 起）
     * @param partCount 分片总数
     * @param data      该片的原始字节
     */
    public record SyncFragment(String channel, int revision, int partIndex, int partCount, byte[] data)
            implements CustomPayload {

        public static final CustomPayload.Id<SyncFragment> ID =
                new CustomPayload.Id<>(ModNetworkingIds.SYNC_FRAGMENT_S2C);

        public static final PacketCodec<PacketByteBuf, SyncFragment> CODEC = PacketCodec.tuple(
                PacketCodecs.string(32), SyncFragment::channel,
                PacketCodecs.VAR_INT, SyncFragment::revision,
                PacketCodecs.VAR_INT, SyncFragment::partIndex,
                PacketCodecs.VAR_INT, SyncFragment::partCount,
                PacketCodecs.byteArray(32768), SyncFragment::data,
                SyncFragment::new);

        @Override
        public CustomPayload.Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /**
     * 服务端要求客户端打开某个面板（§11：{@code /battlepass admin} 打开管理面板）。
     *
     * @param panel 面板名（见 {@link SyncChannels.Panel}）
     */
    public record OpenPanel(String panel) implements CustomPayload {

        public static final CustomPayload.Id<OpenPanel> ID = new CustomPayload.Id<>(ModNetworkingIds.OPEN_PANEL_S2C);

        public static final PacketCodec<PacketByteBuf, OpenPanel> CODEC = PacketCodec.tuple(
                PacketCodecs.string(32), OpenPanel::panel,
                OpenPanel::new);

        @Override
        public CustomPayload.Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /**
     * 动作回执（S2C）。
     *
     * @param success 是否成功
     * @param messageKey 提示文案翻译键（客户端按语言显示）
     */
    public record ActionResult(boolean success, String messageKey) implements CustomPayload {

        public static final CustomPayload.Id<ActionResult> ID =
                new CustomPayload.Id<>(ModNetworkingIds.ACTION_RESULT_S2C);

        public static final PacketCodec<PacketByteBuf, ActionResult> CODEC = PacketCodec.tuple(
                PacketCodecs.BOOLEAN, ActionResult::success,
                PacketCodecs.string(128), ActionResult::messageKey,
                ActionResult::new);

        @Override
        public CustomPayload.Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /**
     * 在线玩家称号广播（S2C）。服务端在玩家佩戴/卸下称号或上下线时，把该玩家的
     * 当前称号同步给所有在线客户端，用于在其他玩家头顶渲染称号。
     *
     * @param playerUuid 被广播玩家的 UUID（字符串形式，避免直接写 java.util.UUID）
     * @param titleId    称号 ID；空串表示该玩家当前没有佩戴称号
     */
    public record TitleBroadcast(String playerUuid, String titleId) implements CustomPayload {

        public static final CustomPayload.Id<TitleBroadcast> ID =
                new CustomPayload.Id<>(ModNetworkingIds.TITLE_BROADCAST_S2C);

        public static final PacketCodec<PacketByteBuf, TitleBroadcast> CODEC = PacketCodec.tuple(
                PacketCodecs.string(64), TitleBroadcast::playerUuid,
                PacketCodecs.string(64), TitleBroadcast::titleId,
                TitleBroadcast::new);

        @Override
        public CustomPayload.Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    // ------------------------------------------------------------------
    // play 阶段：客户端请求（C2S）
    // ------------------------------------------------------------------

    /**
     * 玩家动作。
     *
     * <p>为什么六个动作共用一个包而不是六个包：§14 要求对 C2S 包做
     * "字段校验、频率限制与幂等处理"，这些都按"玩家 + 动作"维度做最自然。
     * 合成一个包之后，频率限制只需要一处；拆成六个包就得写六遍（必然漏一个）。
     *
     * @param action 动作名（见 {@link NetActions.ClientAction}）
     * @param arg    动作参数（任务 ID / 组名 / 商品 ID / 称号 ID / 分支名），可为空串
     */
    public record ClientAction(String action, String arg) implements CustomPayload {

        public static final CustomPayload.Id<ClientAction> ID =
                new CustomPayload.Id<>(ModNetworkingIds.CLIENT_ACTION_C2S);

        public static final PacketCodec<PacketByteBuf, ClientAction> CODEC = PacketCodec.tuple(
                PacketCodecs.string(32), ClientAction::action,
                PacketCodecs.string(128), ClientAction::arg,
                ClientAction::new);

        @Override
        public CustomPayload.Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /**
     * 管理面板动作（§9：需要 OP 权限 + 服务端二次校验）。
     *
     * @param action 动作名（见 {@link NetActions.AdminAction}）
     * @param arg    目标（玩家名/UUID、字段名等）
     * @param value  取值（数字、分支名、布尔等）
     */
    public record AdminAction(String action, String arg, String value) implements CustomPayload {

        public static final CustomPayload.Id<AdminAction> ID =
                new CustomPayload.Id<>(ModNetworkingIds.ADMIN_ACTION_C2S);

        public static final PacketCodec<PacketByteBuf, AdminAction> CODEC = PacketCodec.tuple(
                PacketCodecs.string(32), AdminAction::action,
                PacketCodecs.string(64), AdminAction::arg,
                PacketCodecs.string(128), AdminAction::value,
                AdminAction::new);

        @Override
        public CustomPayload.Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /**
     * 客户端 → 服务端 请求读取某个配置文件（OP 专用）。
     *
     * @param filename 配置文件名（如 shop.json），不含路径分隔符
     */
    public record ConfigFileRequest(String filename) implements CustomPayload {

        public static final CustomPayload.Id<ConfigFileRequest> ID =
                new CustomPayload.Id<>(ModNetworkingIds.CONFIG_FILE_REQUEST_C2S);

        public static final PacketCodec<PacketByteBuf, ConfigFileRequest> CODEC = PacketCodec.tuple(
                PacketCodecs.string(64), ConfigFileRequest::filename,
                ConfigFileRequest::new);

        @Override
        public CustomPayload.Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /**
     * 服务端 → 客户端 返回配置文件完整内容（OP 专用）。
     *
     * @param filename 配置文件名
     * @param content  文件完整文本（UTF-8）
     */
    public record ConfigFileContent(String filename, String content) implements CustomPayload {

        public static final CustomPayload.Id<ConfigFileContent> ID =
                new CustomPayload.Id<>(ModNetworkingIds.CONFIG_FILE_CONTENT_S2C);

        public static final PacketCodec<PacketByteBuf, ConfigFileContent> CODEC = PacketCodec.tuple(
                PacketCodecs.string(64), ConfigFileContent::filename,
                PacketCodecs.string(262144), ConfigFileContent::content,
                ConfigFileContent::new);

        @Override
        public CustomPayload.Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /**
     * 客户端 → 服务端 保存配置文件内容（OP 专用）。
     *
     * @param filename 配置文件名
     * @param content  要写入的完整文本
     */
    public record ConfigFileSave(String filename, String content) implements CustomPayload {

        public static final CustomPayload.Id<ConfigFileSave> ID =
                new CustomPayload.Id<>(ModNetworkingIds.CONFIG_FILE_SAVE_C2S);

        public static final PacketCodec<PacketByteBuf, ConfigFileSave> CODEC = PacketCodec.tuple(
                PacketCodecs.string(64), ConfigFileSave::filename,
                PacketCodecs.string(262144), ConfigFileSave::content,
                ConfigFileSave::new);

        @Override
        public CustomPayload.Id<? extends CustomPayload> getId() {
            return ID;
        }
    }
}
