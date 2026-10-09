package com.haojing.battlepass.common.net;

import com.haojing.battlepass.common.ModConstants;
import net.minecraft.util.Identifier;

/**
 * 用途：全部自定义数据包通道的标识（需求文档 §3：Fabric 自定义 Packet，
 * play 阶段双向 + configuration 阶段握手）。
 *
 * <p>为什么通道名集中在一处：通道标识一旦两端不一致，表现是"包发出去对方收不到"，
 * 而 Fabric 对此**不会报错**（它只是没有接收者）。这类问题极难排查，
 * 因此所有标识只能在这一个文件里出现，两端都从这里引用。
 */
public final class ModNetworkingIds {

    /** configuration 阶段：客户端 → 服务端 握手。 */
    public static final Identifier HANDSHAKE_C2S = id("handshake");

    /** configuration 阶段：服务端 → 客户端 握手结果。 */
    public static final Identifier HANDSHAKE_RESULT_S2C = id("handshake_result");

    /** play 阶段：服务端 → 客户端 同步数据分片（内含通道名与 JSON）。 */
    public static final Identifier SYNC_FRAGMENT_S2C = id("sync_fragment");

    /** play 阶段：客户端 → 服务端 玩家动作（重 roll / 领奖 / 用卡 / 购买 / 佩戴称号 / 选分支）。 */
    public static final Identifier CLIENT_ACTION_C2S = id("client_action");

    /** play 阶段：客户端 → 服务端 管理面板动作（阶段 7 的管理面板）。 */
    public static final Identifier ADMIN_ACTION_C2S = id("admin_action");

    /** play 阶段：服务端 → 客户端 打开某个面板（例如 /battlepass admin）。 */
    public static final Identifier OPEN_PANEL_S2C = id("open_panel");

    /** play 阶段：服务端 → 客户端 动作回执（成功/失败 + 文案键）。 */
    public static final Identifier ACTION_RESULT_S2C = id("action_result");

    /** play 阶段：服务端 → 客户端 在线玩家称号广播（UUID + titleId，空串表示卸下）。 */
    public static final Identifier TITLE_BROADCAST_S2C = id("title_broadcast");

    /** play 阶段：客户端 → 服务端 请求读取配置文件内容（OP 专用）。 */
    public static final Identifier CONFIG_FILE_REQUEST_C2S = id("config_file_request");

    /** play 阶段：服务端 → 客户端 返回配置文件内容（OP 专用）。 */
    public static final Identifier CONFIG_FILE_CONTENT_S2C = id("config_file_content");

    /** play 阶段：客户端 → 服务端 保存配置文件内容（OP 专用）。 */
    public static final Identifier CONFIG_FILE_SAVE_C2S = id("config_file_save");

    private ModNetworkingIds() {
    }

    private static Identifier id(String path) {
        return Identifier.of(ModConstants.NETWORK_NAMESPACE, path);
    }
}
