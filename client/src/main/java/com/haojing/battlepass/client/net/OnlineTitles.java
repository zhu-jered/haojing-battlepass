package com.haojing.battlepass.client.net;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端缓存的"在线玩家 → 称号 ID"映射，供 NameTag 渲染使用。
 *
 * <p>由服务端 TitleBroadcast 包驱动：玩家佩戴/卸下称号或上下线时，
 * 服务端广播最新状态。玩家离开时由服务端发空串清掉。
 */
public final class OnlineTitles {

    private static final Map<UUID, String> MAP = new ConcurrentHashMap<>();

    private OnlineTitles() {
    }

    /** 更新某玩家的称号（空串表示无称号）。 */
    public static void put(UUID playerId, String titleId) {
        if (playerId == null) {
            return;
        }
        if (titleId == null || titleId.isEmpty()) {
            MAP.remove(playerId);
        } else {
            MAP.put(playerId, titleId);
        }
    }

    /** 查询某玩家当前称号；无则返回空串。 */
    public static String titleOf(UUID playerId) {
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
