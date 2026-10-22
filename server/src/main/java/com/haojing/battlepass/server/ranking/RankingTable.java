package com.haojing.battlepass.server.ranking;

import com.haojing.battlepass.common.net.ModSnapshots;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 用途：赛季排行榜的纯聚合逻辑 —— 过滤、排序、截断 Top N、组装给客户端的快照。
 *
 * <p>为什么抽成不依赖 Minecraft 与数据层的纯函数：排名规则（等级降序 → 经验降序 →
 * 玩家名升序 → UUID 兜底）是排行榜最容易被改坏的部分，必须能在不启动服务端的测试里
 * 直接验证平局名次稳定、截断边界、本人行标注这些细节。
 *
 * <p>排序口径说明：等级相同比经验，经验再相同比玩家名（字典序），名字也相同（同名玩家）
 * 再按 UUID 升序兜底 —— 保证任何两条记录的先后都是确定的，不会因为 HashMap 遍历顺序
 * 让同一批玩家每次刷新名次乱跳。
 */
public final class RankingTable {

    private RankingTable() {
    }

    /**
     * 一条待排名记录（服务端从存档聚合后传入，不含任何客户端不该看到的敏感信息之外的字段）。
     *
     * @param uuid  玩家 UUID（仅服务端内部用于标注本人行，不下发给其他玩家）
     * @param name  玩家名（显示用，取存档里的 lastKnownName）
     * @param level 战令等级
     * @param xp    当前等级内经验
     */
    public record Entry(UUID uuid, String name, int level, int xp) {
    }

    /**
     * 聚合后的全服排名结果：保留全量有序列表（用于计算本人真实名次）与截断后的 Top N 列表。
     *
     * @param fullOrdered 全量有序记录（名次 1 起）
     * @param topEntries  截断到 topN 的榜单条目
     * @param participants 参与排名的有效玩家总数
     */
    public record Ranked(List<Entry> fullOrdered, List<Entry> topEntries, int participants) {
    }

    /**
     * 聚合记录并完成排序、截断。
     *
     * @param records 原始记录（允许含 null 元素，会被过滤）
     * @param topN    榜单展示前 N 名（调用方已夹紧到 1~100）
     * @return 排名结果
     */
    public static Ranked buildRanked(List<Entry> records, int topN) {
        List<Entry> valid = new ArrayList<>();

        if (records != null) {
            for (Entry entry : records) {
                // 缺 UUID 的记录无法标注本人行，也无法稳定排序，直接剔除。
                if (entry != null && entry.uuid() != null) {
                    valid.add(entry);
                }
            }
        }

        Comparator<Entry> byLevel = Comparator.comparingInt(Entry::level).reversed();
        Comparator<Entry> byXp = Comparator.comparingInt(Entry::xp).reversed();
        Comparator<Entry> byName = Comparator.comparing(
                entry -> entry.name() == null ? "" : entry.name(), Comparator.naturalOrder());
        Comparator<Entry> byUuid = Comparator.comparing(Entry::uuid);

        valid.sort(byLevel.thenComparing(byXp).thenComparing(byName).thenComparing(byUuid));

        int limit = Math.max(1, topN);
        List<Entry> top = valid.size() > limit ? new ArrayList<>(valid.subList(0, limit)) : new ArrayList<>(valid);

        return new Ranked(valid, top, valid.size());
    }

    /**
     * 把全服排名结果翻译成指定玩家视角的快照：本人行标注 {@code isSelf}，并算出本人全服名次。
     *
     * <p>为什么本人名次取全量列表位置而不是 Top N 位置：未进榜单的玩家也应当看到"我排第
     * N 名"，否则他不知道自己离上榜还差多少；榜单条目仍然只下发 Top N，避免一次性把全服
     * 几百上千人的数据塞进同步包。
     *
     * @param ranked    聚合结果；null 时返回空快照
     * @param selfUuid  当前查看快照的玩家 UUID；null 表示不标注本人
     * @param enabled   排行榜是否启用（配置开关）
     * @return 给客户端的排行榜快照
     */
    public static ModSnapshots.Ranking toSnapshot(Ranked ranked, UUID selfUuid, boolean enabled) {
        ModSnapshots.Ranking snapshot = new ModSnapshots.Ranking();
        snapshot.enabled = enabled;

        // 关闭开关或尚无重建结果时，只回一份空快照：不下发参与人数、条目与名次，
        // 与"关闭后榜单不展示"的口径一致，避免把缓存的旧排名残留在关闭状态的快照里。
        if (!enabled || ranked == null) {
            return snapshot;
        }

        snapshot.topN = ranked.topEntries().size();
        snapshot.participants = ranked.participants();

        int myRank = -1;

        if (selfUuid != null) {
            for (int i = 0; i < ranked.fullOrdered().size(); i++) {
                if (selfUuid.equals(ranked.fullOrdered().get(i).uuid())) {
                    myRank = i + 1;
                    break;
                }
            }
        }

        snapshot.myRank = myRank;

        for (int i = 0; i < ranked.topEntries().size(); i++) {
            Entry entry = ranked.topEntries().get(i);
            ModSnapshots.RankEntry line = new ModSnapshots.RankEntry();
            line.rank = i + 1;
            line.name = entry.name() == null ? "" : entry.name();
            line.level = entry.level();
            line.xp = entry.xp();
            line.isSelf = selfUuid != null && selfUuid.equals(entry.uuid());
            snapshot.entries.add(line);
        }

        return snapshot;
    }
}
