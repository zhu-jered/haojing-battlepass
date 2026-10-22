package com.haojing.battlepass.server.ranking;

import com.haojing.battlepass.common.net.ModSnapshots;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用途：覆盖排行榜的排序口径、Top N 截断、本人名次与本人行标注。
 *
 * <p>为什么这些规则必须自动化锁住：排名顺序一旦因为 HashMap 遍历或改了比较器而乱掉，
 * 玩家会直接在榜单上看到错误名次，属于很难事后排查的观感事故；纯函数测试可以把
 * "等级 → 经验 → 名字 → UUID"四级 tiebreak 逐档验到。
 */
class RankingTableTest {

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();
    private static final UUID C = UUID.randomUUID();
    private static final UUID D = UUID.randomUUID();

    private static RankingTable.Entry entry(UUID uuid, String name, int level, int xp) {
        return new RankingTable.Entry(uuid, name, level, xp);
    }

    @Test
    void 按等级降序经验降序名字升序排序() {
        List<RankingTable.Entry> records = List.of(
                entry(C, "Charlie", 5, 100),
                entry(A, "Alice", 8, 40),
                entry(B, "Bob", 8, 90),
                entry(D, "Dave", 3, 200));

        RankingTable.Ranked ranked = RankingTable.buildRanked(records, 10);

        List<String> ordered = ranked.fullOrdered().stream().map(RankingTable.Entry::name).toList();
        // 等级 8 的两人先比经验：Bob(90) 在 Alice(40) 前；再等级 5、等级 3。
        assertEquals(List.of("Bob", "Alice", "Charlie", "Dave"), ordered);
        assertEquals(4, ranked.participants());
    }

    @Test
    void 同等级同经验按名字升序同名按UUID兜底() {
        UUID first = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID second = UUID.fromString("22222222-2222-2222-2222-222222222222");

        List<RankingTable.Entry> records = List.of(
                entry(second, "张三", 5, 50),
                entry(first, "张三", 5, 50));

        RankingTable.Ranked ranked = RankingTable.buildRanked(records, 10);

        // 名字相同，UUID 字典序靠前的在前。
        assertEquals(first, ranked.fullOrdered().get(0).uuid());
        assertEquals(second, ranked.fullOrdered().get(1).uuid());
    }

    @Test
    void 榜单截断到TopN但保留全量用于算本人名次() {
        List<RankingTable.Entry> records = List.of(
                entry(A, "A", 10, 100), entry(B, "B", 9, 90), entry(C, "C", 8, 80), entry(D, "D", 7, 70));

        RankingTable.Ranked ranked = RankingTable.buildRanked(records, 2);

        assertEquals(2, ranked.topEntries().size());
        assertEquals(4, ranked.fullOrdered().size(), "全量列表不截断");
        assertEquals(4, ranked.participants());
    }

    @Test
    void 本人未进TopN仍返回真实全服名次并标注本人行() {
        List<RankingTable.Entry> records = List.of(
                entry(A, "A", 10, 100), entry(B, "B", 9, 90), entry(C, "C", 8, 80), entry(D, "D", 7, 70));

        RankingTable.Ranked ranked = RankingTable.buildRanked(records, 2);
        ModSnapshots.Ranking snapshot = RankingTable.toSnapshot(ranked, D, true);

        assertEquals(4, snapshot.myRank, "第四名即使不在 Top2 也要给出真实名次");
        assertEquals(2, snapshot.entries.size());
        assertFalse(snapshot.entries.stream().anyMatch(line -> line.isSelf), "本人不在榜单条目里");
        assertEquals(4, snapshot.participants);
    }

    @Test
    void 本人在榜单内被标注高亮() {
        List<RankingTable.Entry> records = List.of(
                entry(A, "A", 10, 100), entry(B, "B", 9, 90), entry(C, "C", 8, 80));

        RankingTable.Ranked ranked = RankingTable.buildRanked(records, 10);
        ModSnapshots.Ranking snapshot = RankingTable.toSnapshot(ranked, B, true);

        assertEquals(2, snapshot.myRank);
        ModSnapshots.RankEntry second = snapshot.entries.get(1);
        assertTrue(second.isSelf);
        assertEquals("B", second.name);
        assertFalse(snapshot.entries.get(0).isSelf);
    }

    @Test
    void 空记录与null结果返回空快照不抛异常() {
        RankingTable.Ranked empty = RankingTable.buildRanked(List.of(), 10);

        assertEquals(0, empty.participants());

        ModSnapshots.Ranking snapshot = RankingTable.toSnapshot(empty, A, true);
        assertEquals(-1, snapshot.myRank);
        assertTrue(snapshot.entries.isEmpty());

        ModSnapshots.Ranking nullSnapshot = RankingTable.toSnapshot(null, A, false);
        assertFalse(nullSnapshot.enabled);
        assertTrue(nullSnapshot.entries.isEmpty());
    }

    @Test
    void 非法记录被过滤不参与排名() {
        // Arrays.asList 允许放入 null 元素（List.of 会直接 NPE），用于验证 buildRanked 的过滤分支。
        List<RankingTable.Entry> records = java.util.Arrays.asList(
                null,
                new RankingTable.Entry(null, "无名", 5, 10),
                entry(A, "A", 5, 10));

        RankingTable.Ranked ranked = RankingTable.buildRanked(records, 10);

        assertEquals(1, ranked.participants());
        assertEquals(A, ranked.topEntries().get(0).uuid());
    }
}
