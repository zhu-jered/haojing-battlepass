package com.haojing.battlepass.server.settlement;

import com.haojing.battlepass.common.data.RewardType;
import com.haojing.battlepass.server.support.ServerTestEnv;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用途：验证赛季结算服务的开关口径、档位发放与容错（D-39）。
 *
 * <p>夹具默认关闭结算，这里在需要发奖的用例体内把开关置 true，
 * 与"既有赛季重置用例不应收到任何结算奖励"的口径保持隔离。
 */
class SettlementServiceTest {

    @Test
    void 启用后按最终等级发放命中档位奖励(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            env.settlementManager.pool().enabled = true;
            UUID id = UUID.randomUUID();

            // 夹具 10-30 档：STAR_COIN 30 + EXEMPT_CARD 1
            int granted = env.settlementService.grantSettlement(id, 12, "S1");

            assertEquals(2, granted, "高档两条奖励都应发放");
            assertEquals(2, env.sink.countFor(id));
            assertEquals(1, env.sink.countOf(RewardType.STAR_COIN));
            assertEquals(1, env.sink.countOf(RewardType.EXEMPT_CARD));
            assertTrue(env.sink.sawText("STAR_COIN x30"), "高档京币数量应与配置一致");
        }
    }

    @Test
    void 开关关闭时不发放任何结算奖励(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            // 夹具默认 enabled=false，这里不打开开关。
            UUID id = UUID.randomUUID();

            int granted = env.settlementService.grantSettlement(id, 12, "S1");

            assertEquals(0, granted);
            assertEquals(0, env.sink.countFor(id));
        }
    }

    @Test
    void 等级未命中任何档位时不发放(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            env.settlementManager.pool().enabled = true;
            UUID id = UUID.randomUUID();

            int granted = env.settlementService.grantSettlement(id, 99, "S1");

            assertEquals(0, granted, "夹具最高只到 30 级，99 级无档可匹配");
            assertEquals(0, env.sink.countFor(id));
        }
    }

    @Test
    void 奖励出口未接入时不发放且不抛异常(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            env.settlementManager.pool().enabled = true;
            SettlementService standalone = new SettlementService(env.settlementManager);
            // 故意不调用 setRewardSink。

            int granted = standalone.grantSettlement(UUID.randomUUID(), 12, "S1");

            assertEquals(0, granted, "出口缺失时记日志返回 0，不抛出阻断轮换链路");
        }
    }

    @Test
    void 单条奖励失败不阻断其余奖励(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            env.settlementManager.pool().enabled = true;
            env.sink.failFor.add(RewardType.STAR_COIN);
            UUID id = UUID.randomUUID();

            int granted = env.settlementService.grantSettlement(id, 12, "S1");

            assertEquals(1, granted, "星币发放失败，豁免卡仍应发出");
            assertEquals(1, env.sink.countFor(id));
            assertEquals(1, env.sink.countOf(RewardType.EXEMPT_CARD));
        }
    }
}
