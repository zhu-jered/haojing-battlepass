package com.haojing.battlepass.server.ranking;

import com.haojing.battlepass.common.data.GlobalData;
import com.haojing.battlepass.common.data.SeasonData;
import com.haojing.battlepass.common.net.ModSnapshots;
import com.haojing.battlepass.server.support.ServerTestEnv;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用途：验证排行榜服务从存档聚合（含离线玩家）、排序口径、TopN 截断、本人全服名次与开关状态。
 *
 * <p>纯排序规则在 {@link RankingTableTest} 覆盖；这里专门验证"服务端是否正确地从数据层
 * 把等级/经验/名字读进榜单"，以及配置开关是否进入快照。测试直接调用 {@code rebuild()}，
 * 不启动 60 秒后台线程，避免等待初始延迟。
 */
class RankingServiceTest {

    /** 直接往存档里写等级/经验/名字，模拟一名已离线但已落盘的玩家。 */
    private static void seed(ServerTestEnv env, UUID id, String name, int level, int xp) {
        SeasonData season = env.data.season(id);
        season.level = level;
        season.xp = xp;
        env.data.markSeasonDirty(id);

        GlobalData global = env.data.global(id);
        global.lastKnownName = name;
        env.data.markGlobalDirty(id);
    }

    @Test
    void 聚合存档并按等级降序经验降序排列(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID a = UUID.randomUUID();
            UUID b = UUID.randomUUID();
            UUID c = UUID.randomUUID();
            seed(env, a, "alpha", 5, 100);
            seed(env, b, "beta", 8, 20);
            seed(env, c, "gamma", 8, 50);

            RankingService service = new RankingService(env.data, env.configs);
            service.rebuild();

            ModSnapshots.Ranking snapshot = service.snapshotFor(a);

            assertEquals(3, snapshot.participants);
            assertEquals("gamma", snapshot.entries.get(0).name, "同等级经验高的在前");
            assertEquals("beta", snapshot.entries.get(1).name);
            assertEquals("alpha", snapshot.entries.get(2).name, "等级低的垫底");
            assertEquals(3, snapshot.myRank, "本人未进前列时也应拿到全量真实名次");
            assertTrue(snapshot.entries.get(2).isSelf, "本人行需标注");
        }
    }

    @Test
    void 超出TopN的玩家仍保留全量真实名次(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            env.configs.config().rankingTopN = 10;

            // 12 名等级依次升高的玩家：第 12 名等级最低。
            UUID[] ids = new UUID[12];

            for (int i = 0; i < 12; i++) {
                ids[i] = UUID.randomUUID();
                seed(env, ids[i], "player" + i, i + 1, 0);
            }

            RankingService service = new RankingService(env.data, env.configs);
            service.rebuild();

            // 取等级最低的第 12 名玩家视角。
            ModSnapshots.Ranking snapshot = service.snapshotFor(ids[0]);

            assertEquals(10, snapshot.entries.size(), "榜单只展示前 10 名");
            assertEquals(12, snapshot.myRank, "未进榜也返回全量真实名次");
            assertFalse(snapshot.entries.stream().anyMatch(e -> e.isSelf));
        }
    }

    @Test
    void 关闭排行榜开关后快照置为不可用(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();
            seed(env, id, "alone", 3, 50);
            env.configs.config().rankingEnabled = false;

            RankingService service = new RankingService(env.data, env.configs);
            service.rebuild();

            ModSnapshots.Ranking snapshot = service.snapshotFor(id);

            assertFalse(snapshot.enabled);
            assertEquals(0, snapshot.participants);
        }
    }

    @Test
    void 无玩家时返回空榜单且本人名次为负一(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            RankingService service = new RankingService(env.data, env.configs);
            service.rebuild();

            ModSnapshots.Ranking snapshot = service.snapshotFor(UUID.randomUUID());

            assertTrue(snapshot.enabled);
            assertEquals(0, snapshot.participants);
            assertEquals(-1, snapshot.myRank);
            assertTrue(snapshot.entries.isEmpty());
        }
    }
}
