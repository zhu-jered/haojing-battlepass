package com.haojing.battlepass.common.net;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用途：验证上行包的护栏（需求文档 §14："非法/异常/超大客户端数据包直接丢弃；
 * 对 C2S 包做字段校验、频率限制与幂等处理"）。
 *
 * <p>为什么这些断言必须存在：频率限制是"被人连点/写脚本刷"时唯一挡住服务端的防线，
 * 而它在正常使用中几乎不可能被触发 —— 也就是说**没有测试就永远不会被发现写错**。
 */
class C2SGuardTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private C2SGuard guard() {
        // 窗口 100ms / 3 个包、同动作最小间隔 10ms：让测试能在毫秒级验证限流。
        return new C2SGuard(3, 100L, 10L, 16);
    }

    @Test
    void 合法动作通过() {
        C2SGuard guard = guard();

        assertEquals(C2SGuard.Verdict.OK,
                guard.checkClient(PLAYER, NetActions.ClientAction.CLAIM.name(), "t_exp_1", 1000L));
    }

    @Test
    void 未知动作被拒绝() {
        C2SGuard guard = guard();

        assertEquals(C2SGuard.Verdict.UNKNOWN_ACTION, guard.checkClient(PLAYER, "DROP_TABLE", "x", 1000L));
        assertEquals(C2SGuard.Verdict.UNKNOWN_ACTION, guard.checkClient(PLAYER, "", "x", 1000L));
        assertEquals(C2SGuard.Verdict.UNKNOWN_ACTION, guard.checkClient(PLAYER, null, "x", 1000L));
    }

    @Test
    void 缺少参数与参数过长被拒绝() {
        C2SGuard guard = guard();

        assertEquals(C2SGuard.Verdict.BAD_ARG,
                guard.checkClient(PLAYER, NetActions.ClientAction.CLAIM.name(), "", 1000L));
        assertEquals(C2SGuard.Verdict.BAD_ARG,
                guard.checkClient(PLAYER, NetActions.ClientAction.CLAIM.name(), "x".repeat(17), 1000L));
    }

    @Test
    void 无参数动作允许空参数() {
        C2SGuard guard = guard();

        assertEquals(C2SGuard.Verdict.OK,
                guard.checkClient(PLAYER, NetActions.ClientAction.RESYNC.name(), "", 1000L));
    }

    @Test
    void 窗口内包数超限被限流() {
        C2SGuard guard = new C2SGuard(2, 1000L, 0L, 16);

        assertEquals(C2SGuard.Verdict.OK, guard.checkClient(PLAYER, "RESYNC", "", 0L));
        assertEquals(C2SGuard.Verdict.OK, guard.checkClient(PLAYER, "RESYNC", "", 0L));
        assertEquals(C2SGuard.Verdict.RATE_LIMITED, guard.checkClient(PLAYER, "RESYNC", "", 0L),
                "第三个包必须被限流");

        // 进入下一个窗口后恢复
        assertEquals(C2SGuard.Verdict.OK, guard.checkClient(PLAYER, "RESYNC", "", 1000L));
    }

    @Test
    void 同一动作过快被拒绝() {
        C2SGuard guard = new C2SGuard(100, 1000L, 50L, 16);

        assertEquals(C2SGuard.Verdict.OK, guard.checkClient(PLAYER, "CLAIM", "t1", 0L));
        assertEquals(C2SGuard.Verdict.TOO_FREQUENT, guard.checkClient(PLAYER, "CLAIM", "t1", 10L));
        assertEquals(C2SGuard.Verdict.OK, guard.checkClient(PLAYER, "CLAIM", "t1", 60L));

        // 不同动作之间互不影响
        assertEquals(C2SGuard.Verdict.OK, guard.checkClient(PLAYER, "BUY", "s1", 60L));
    }

    @Test
    void 不同玩家互不影响限流() {
        C2SGuard guard = new C2SGuard(1, 1000L, 0L, 16);
        UUID other = UUID.randomUUID();

        assertEquals(C2SGuard.Verdict.OK, guard.checkClient(PLAYER, "RESYNC", "", 0L));
        assertEquals(C2SGuard.Verdict.RATE_LIMITED, guard.checkClient(PLAYER, "RESYNC", "", 0L));
        assertEquals(C2SGuard.Verdict.OK, guard.checkClient(other, "RESYNC", "", 0L));
    }

    @Test
    void 管理动作的校验() {
        C2SGuard guard = guard();

        assertEquals(C2SGuard.Verdict.OK, guard.checkAdmin(PLAYER, "RELOAD", "", "", 1000L));
        assertEquals(C2SGuard.Verdict.UNKNOWN_ACTION, guard.checkAdmin(PLAYER, "NOPE", "", "", 1000L));
        assertEquals(C2SGuard.Verdict.BAD_ARG, guard.checkAdmin(PLAYER, "RELOAD", "x".repeat(17), "", 1000L));
        assertEquals(C2SGuard.Verdict.BAD_ARG,
                guard.checkAdmin(PLAYER, "RELOAD", "", "v".repeat(C2SGuard.MAX_VALUE_LENGTH + 1), 1000L));
        assertEquals(C2SGuard.Verdict.BAD_ARG, guard.checkAdmin(null, "RELOAD", "", "", 1000L));
    }

    @Test
    void 空玩家被拒绝() {
        C2SGuard guard = guard();

        assertEquals(C2SGuard.Verdict.BAD_ARG, guard.checkClient(null, "RESYNC", "", 0L));
    }

    @Test
    void 退出时清理状态() {
        C2SGuard guard = guard();

        guard.checkClient(PLAYER, "RESYNC", "", 0L);
        assertEquals(1, guard.trackedPlayers());

        guard.forget(PLAYER);
        assertEquals(0, guard.trackedPlayers());
        guard.forget(null);
    }

    @Test
    void 动作的解析是大小写宽容的() {
        assertEquals(NetActions.ClientAction.CLAIM, NetActions.ClientAction.fromName(" claim "));
        assertEquals(NetActions.ClientAction.CHOOSE_BRANCH, NetActions.ClientAction.fromName("choose_branch"));
        assertNull(NetActions.ClientAction.fromName("not_an_action"));
        assertNull(NetActions.ClientAction.fromName(null));

        assertEquals(NetActions.AdminAction.SET_LEVEL, NetActions.AdminAction.fromName("set_level"));
        assertEquals(NetActions.AdminAction.OPEN_PANEL, NetActions.AdminAction.fromName("open_panel"));
        assertNull(NetActions.AdminAction.fromName("drop_table"));
    }

    @Test
    void 动作的参数声明自洽() {
        assertTrue(NetActions.ClientAction.CLAIM.requiresArg());
        assertFalse(NetActions.ClientAction.RESYNC.requiresArg());
        assertEquals("group", NetActions.ClientAction.REROLL.argHint());
        assertEquals("", NetActions.AdminAction.RELOAD.argHint());
    }
}
