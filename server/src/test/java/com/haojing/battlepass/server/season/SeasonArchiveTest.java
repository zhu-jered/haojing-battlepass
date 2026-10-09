package com.haojing.battlepass.server.season;

import com.haojing.battlepass.common.data.Branch;
import com.haojing.battlepass.common.data.SeasonData;
import com.haojing.battlepass.common.data.TaskProgress;
import com.haojing.battlepass.common.data.TaskStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用途：验证赛季归档文件的生成与合并（需求文档 §4、§12）。
 *
 * <p>为什么重点测"合并"：赛季滚动是逐玩家判断的，同一份 season_&lt;id&gt;.json
 * 可能被写入多次（例如先处理了离线玩家、之后又有人上线被发现还带着旧赛季 ID）。
 * 合并若不去重，归档里会出现同一个玩家两份互相矛盾的结算数据。
 */
class SeasonArchiveTest {

    private SeasonData seasonOf(String uuid, int level, String seasonId) {
        SeasonData season = new SeasonData(uuid, seasonId);
        season.level = level;
        season.xp = 12;
        season.setBranch(Branch.HUNT);
        return season;
    }

    @Test
    void 摘要统计已完成任务数() {
        SeasonData season = seasonOf("u1", 7, "S1");

        TaskProgress claimed = new TaskProgress("a");
        claimed.setStatus(TaskStatus.CLAIMED);
        TaskProgress completed = new TaskProgress("b");
        completed.setStatus(TaskStatus.COMPLETED);
        TaskProgress inProgress = new TaskProgress("c");
        inProgress.setStatus(TaskStatus.IN_PROGRESS);

        season.dailyTasks.put("explore", claimed);
        season.dailyTasks.put("build", completed);
        season.dailyTasks.put("general", inProgress);

        TaskProgress weekly = new TaskProgress("w");
        weekly.setStatus(TaskStatus.COMPLETED);
        season.weeklyTasks.put("w", weekly);

        SeasonArchive.ArchivePlayer entry = SeasonArchive.ArchivePlayer.of(season, "玩家甲");

        assertEquals("u1", entry.playerUuid);
        assertEquals("S1", entry.seasonId, "来源赛季必须记在条目里（重置后就查不到了）");
        assertEquals("玩家甲", entry.playerName);
        assertEquals(7, entry.level);
        assertEquals(12, entry.xp);
        assertEquals(Branch.HUNT, entry.branch());
        assertEquals(2, entry.completedDailyTasks, "完成与已领取都算完成");
        assertEquals(1, entry.completedWeeklyTasks);
    }

    @Test
    void 空任务集合不会崩() {
        SeasonData season = seasonOf("u1", 1, "S1");
        season.dailyTasks = null;
        season.weeklyTasks = null;

        SeasonArchive.ArchivePlayer entry = SeasonArchive.ArchivePlayer.of(season, null);

        assertEquals(0, entry.completedDailyTasks);
        assertEquals(0, entry.completedWeeklyTasks);
        assertEquals("", entry.playerName);
    }

    @Test
    void null玩家存档返回空条目() {
        SeasonArchive.ArchivePlayer entry = SeasonArchive.ArchivePlayer.of(null, "x");

        assertEquals("", entry.playerUuid);
        assertEquals(1, entry.level);
    }

    @Test
    void 合并时按UUID覆盖而不是追加() {
        SeasonArchive archive = new SeasonArchive();

        SeasonArchive.ArchivePlayer first = SeasonArchive.ArchivePlayer.of(seasonOf("u1", 5, "S1"), "甲");
        SeasonArchive.ArchivePlayer second = SeasonArchive.ArchivePlayer.of(seasonOf("u2", 8, "S1"), "乙");
        SeasonArchive.ArchivePlayer updated = SeasonArchive.ArchivePlayer.of(seasonOf("u1", 9, "S1"), "甲");

        List<SeasonArchive.ArchivePlayer> batch1 = new ArrayList<>();
        batch1.add(first);
        batch1.add(second);
        assertTrue(archive.merge(batch1));

        List<SeasonArchive.ArchivePlayer> batch2 = new ArrayList<>();
        batch2.add(updated);
        assertTrue(archive.merge(batch2));

        assertEquals(2, archive.playerCount(), "同一玩家不应出现两条");
        assertEquals(9, archive.players.stream().filter(e -> e.playerUuid.equals("u1")).findFirst().orElseThrow().level);
    }

    @Test
    void 合并空批次不产生变化() {
        SeasonArchive archive = new SeasonArchive();

        assertFalse(archive.merge(null));
        assertFalse(archive.merge(List.of()));
    }

    @Test
    void 合并会跳过没有UUID的条目() {
        SeasonArchive archive = new SeasonArchive();
        List<SeasonArchive.ArchivePlayer> entries = new ArrayList<>();
        entries.add(new SeasonArchive.ArchivePlayer());
        entries.add(null);

        assertFalse(archive.merge(entries));
        assertEquals(0, archive.playerCount());
    }
}
