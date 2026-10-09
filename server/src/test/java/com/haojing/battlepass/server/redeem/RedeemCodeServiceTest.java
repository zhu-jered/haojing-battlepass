package com.haojing.battlepass.server.redeem;

import com.haojing.battlepass.server.support.ServerTestEnv;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用途：验证节日口令（需求文档 §10）的匹配、生效时间段与"每人一次"的原子性。
 */
class RedeemCodeServiceTest {

    @Test
    void 口令匹配忽略大小写与首尾空白(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();

            RedeemCodeService.RedeemResult result = env.redeemCodeService.redeem(id, "   HeLLo   ", "2026-10-08");

            assertEquals(RedeemCodeService.Outcome.OK, result.outcome());
            assertEquals("c_open", result.code().id);
            // 测试用的奖励出口只记录不落库，因此断言"发给了谁、发了什么"。
            assertEquals(1, env.sink.countOf(com.haojing.battlepass.common.data.RewardType.STAR_COIN), "奖励应已发放");
            assertTrue(env.sink.sawText("STAR_COIN x10"));
        }
    }

    @Test
    void 同一口令每人只能领一次(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();

            assertEquals(RedeemCodeService.Outcome.OK,
                    env.redeemCodeService.redeem(id, "Hello", "2026-10-08").outcome());
            assertEquals(RedeemCodeService.Outcome.ALREADY_CLAIMED,
                    env.redeemCodeService.redeem(id, "hello", "2026-10-08").outcome(), "§10：每人每口令仅一次");

            assertEquals(1, env.sink.countOf(com.haojing.battlepass.common.data.RewardType.STAR_COIN), "第二次不应再发奖");
            assertTrue(env.redeemCodeService.hasClaimed(id, "c_open"));
        }
    }

    @Test
    void 不同玩家可以各领一次(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID first = UUID.randomUUID();
            UUID second = UUID.randomUUID();

            assertEquals(RedeemCodeService.Outcome.OK,
                    env.redeemCodeService.redeem(first, "Hello", "2026-10-08").outcome());
            assertEquals(RedeemCodeService.Outcome.OK,
                    env.redeemCodeService.redeem(second, "Hello", "2026-10-08").outcome());

            assertTrue(env.redeemCodeService.hasClaimed(first, "c_open"));
            assertTrue(env.redeemCodeService.hasClaimed(second, "c_open"));
        }
    }

    @Test
    void 生效时间段内的口令可以领取(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();

            assertEquals(RedeemCodeService.Outcome.OK,
                    env.redeemCodeService.redeem(id, "WINTER", "2027-01-15").outcome(), "11-01~02-28 的窗口应命中 1 月");
            assertEquals(1, env.sink.countFor(id), "窗口内应当发奖");
            assertTrue(env.sink.sawText("STAR_COIN x5"));
        }
    }

    @Test
    void 生效时间段外的口令被拒绝(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();

            assertEquals(RedeemCodeService.Outcome.NOT_IN_WINDOW,
                    env.redeemCodeService.redeem(id, "Winter", "2027-06-01").outcome());
            assertEquals(0, env.sink.countFor(id), "不在窗口内不应发奖");
            assertFalse(env.redeemCodeService.hasClaimed(id, "c_window"), "被拒后不应占位");
        }
    }

    @Test
    void 未命中的聊天不会被当成口令(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();

            assertEquals(RedeemCodeService.Outcome.NOT_FOUND,
                    env.redeemCodeService.redeem(id, "今天天气不错", "2026-10-08").outcome());
            assertNull(env.redeemCodeService.redeem(id, "今天天气不错", "2026-10-08").code());
        }
    }

    @Test
    void 空输入与非法参数安全返回(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();

            assertEquals(RedeemCodeService.Outcome.EMPTY_INPUT,
                    env.redeemCodeService.redeem(id, "   ", "2026-10-08").outcome());
            assertEquals(RedeemCodeService.Outcome.NOT_FOUND,
                    env.redeemCodeService.redeem(null, "Hello", "2026-10-08").outcome());
        }
    }

    @Test
    void 停用的口令无法领取(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();

            assertEquals(RedeemCodeService.Outcome.NOT_FOUND,
                    env.redeemCodeService.redeem(id, "Off", "2026-10-08").outcome());
        }
    }

    @Test
    void 并发领取只会成功一次(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();
            int threads = 8;
            java.util.concurrent.atomic.AtomicInteger success = new java.util.concurrent.atomic.AtomicInteger();
            java.util.List<Thread> workers = new java.util.ArrayList<>();

            for (int i = 0; i < threads; i++) {
                Thread thread = new Thread(() -> {
                    if (env.redeemCodeService.redeem(id, "Hello", "2026-10-08").outcome()
                            == RedeemCodeService.Outcome.OK) {
                        success.incrementAndGet();
                    }
                });
                workers.add(thread);
                thread.start();
            }

            for (Thread thread : workers) {
                try {
                    thread.join(5000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            assertEquals(1, success.get(), "§10：去重键 playerUUID + 口令ID，且必须并发原子");
            assertEquals(1, env.sink.countFor(id), "只应发放一次奖励");
        }
    }

    @Test
    void 口令池会丢弃非法条目并提示重复口令(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            RedeemCodePool pool = env.redeemCodeManager.pool();

            assertEquals(2, pool.size(), "停用的口令不进索引");
            assertNull(pool.code("c_off"));
            assertEquals("Hello", pool.code("c_open").code);
        }
    }
}
