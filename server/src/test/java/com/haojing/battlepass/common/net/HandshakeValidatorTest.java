package com.haojing.battlepass.common.net;

import com.haojing.battlepass.common.ModConstants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用途：验证客户端强制校验的判定规则（需求文档 §2）。
 *
 * <p>三条规则各自对应一个真实的运维场景：
 * <ul>
 *   <li>未安装 Mod 的普通玩家 → 必须在配置阶段就被拒绝（否则他进服后看到的是空白界面）；</li>
 *   <li>Mod 版本不匹配 → 必须拒绝并提示更新（否则两端协议错位，表现为数据乱跳）；</li>
 *   <li>devMode → 本地测试时放行所有客户端（§2 要求提供这个开关）。</li>
 * </ul>
 */
class HandshakeValidatorTest {

    @Test
    void 版本一致且已握手时通过() {
        assertEquals(HandshakeValidator.Verdict.ACCEPT,
                HandshakeValidator.evaluate(false, true, ModConstants.PROTOCOL_VERSION, ModConstants.PROTOCOL_VERSION));
        assertTrue(HandshakeValidator.allowed(HandshakeValidator.Verdict.ACCEPT));
        assertEquals("", HandshakeValidator.messageKey(HandshakeValidator.Verdict.ACCEPT));
    }

    @Test
    void 未握手被拒绝并给出提示() {
        HandshakeValidator.Verdict verdict = HandshakeValidator.evaluate(false, false, 0, ModConstants.PROTOCOL_VERSION);

        assertEquals(HandshakeValidator.Verdict.MISSING, verdict);
        assertFalse(HandshakeValidator.allowed(verdict));
        assertEquals("haojing_battlepass.net.kick.missing", HandshakeValidator.messageKey(verdict));
    }

    @Test
    void 协议版本不一致被拒绝() {
        HandshakeValidator.Verdict verdict = HandshakeValidator.evaluate(false, true,
                ModConstants.PROTOCOL_VERSION + 1, ModConstants.PROTOCOL_VERSION);

        assertEquals(HandshakeValidator.Verdict.VERSION_MISMATCH, verdict);
        assertFalse(HandshakeValidator.allowed(verdict));
        assertEquals("haojing_battlepass.net.kick.version", HandshakeValidator.messageKey(verdict));
    }

    @Test
    void devMode放行所有客户端() {
        HandshakeValidator.Verdict verdict = HandshakeValidator.evaluate(true, false, 0, ModConstants.PROTOCOL_VERSION);

        assertEquals(HandshakeValidator.Verdict.DEV_BYPASS, verdict);
        assertTrue(HandshakeValidator.allowed(verdict), "§2：devMode 开启后跳过校验");
    }

    @Test
    void 协议版本号必须为正() {
        assertTrue(ModConstants.PROTOCOL_VERSION > 0, "协议版本必须显式声明，便于升级时判断兼容性");
    }

    @Test
    void 通道标识都在约定命名空间下() {
        assertEquals(ModConstants.NETWORK_NAMESPACE, ModNetworkingIds.HANDSHAKE_C2S.getNamespace());
        assertEquals(ModConstants.NETWORK_NAMESPACE, ModNetworkingIds.SYNC_FRAGMENT_S2C.getNamespace());
        assertEquals(ModConstants.NETWORK_NAMESPACE, ModNetworkingIds.CLIENT_ACTION_C2S.getNamespace());
        assertEquals(5, SyncChannels.ALL.size(), "五个玩家可见通道：首页/任务/商店/称号/收藏");
        assertTrue(SyncChannels.ALL.contains(SyncChannels.PLAYER));
        assertFalse(SyncChannels.ALL.contains(SyncChannels.ADMIN), "管理通道只发给 OP，不属于常规同步");
    }

    @Test
    void 快照契约的默认值可直接发送() {
        // 新建的快照必须能安全序列化（老客户端缺少字段时也不会 NPE）。
        ModSnapshots.Player player = new ModSnapshots.Player();
        ModSnapshots.Tasks tasks = new ModSnapshots.Tasks();
        ModSnapshots.Admin admin = new ModSnapshots.Admin();

        assertEquals("", player.seasonId);
        assertEquals(1, player.level);
        assertTrue(tasks.weekly.isEmpty());
        assertTrue(tasks.group("explore").isEmpty());
        assertTrue(tasks.group("weekly").isEmpty());
        assertTrue(tasks.group("不存在的组").isEmpty());
        assertEquals(0, admin.onlinePlayers);

        String json = SyncFragments.toJson(admin);
        assertTrue(json.contains("configFiles"));
    }
}
