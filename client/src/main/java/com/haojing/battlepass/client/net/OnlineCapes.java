package com.haojing.battlepass.client.net;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端缓存的"在线玩家 → 披风 ID"映射，供披风 Mixin 渲染使用。
 *
 * <p>由服务端 CapeBroadcast 包驱动：玩家穿戴/卸下披风或上下线时，
 * 服务端广播最新状态。玩家离开时由服务端发空串清掉。
 */
public final class OnlineCapes {

    private static final Map<UUID, String> MAP = new ConcurrentHashMap<>();

    private OnlineCapes() {
    }

    /** 更新某玩家的披风（空串表示无披风）。 */
    public static void put(UUID playerId, String capeId) {
        if (playerId == null) {
            return;
        }
        if (capeId == null || capeId.isEmpty()) {
            MAP.remove(playerId);
        } else {
            MAP.put(playerId, capeId);
        }
    }

    /** 查询某玩家当前披风；无则返回空串。 */
    public static String capeOf(UUID playerId) {
        if (playerId == null) {
            return "";
        }
        return MAP.getOrDefault(playerId, "");
    }

    /** 清空（重连/退出时调用）。 */
    public static void clear() {
        MAP.clear();
    }
}
