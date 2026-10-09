package com.haojing.battlepass.server.season;

import com.haojing.battlepass.common.data.Branch;
import com.haojing.battlepass.common.data.SeasonData;
import com.haojing.battlepass.common.data.TaskProgress;
import com.haojing.battlepass.common.data.TaskStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * 用途：赛季归档文件，对应 data/history/season_&lt;id&gt;.json（需求文档 §4、§12）。
 *
 * <p>为什么只归档"结算所需的摘要"而不是把每个玩家的完整赛季存档塞进去：
 * 归档的用途是留档与结算公告（这一季谁练到几级、选了哪个分支），
 * 完整存档里的任务进度、重 roll 次数、豁免卡余额在赛季结束后没有任何意义；
 * 塞进去只会让归档文件膨胀，且把"归档"变成"备份存档"，
 * 反而让人以为可以靠它恢复赛季数据。
 *
 * <p>为什么同一个赛季的归档要支持"合并写入"：赛季滚动是逐个玩家判断的
 * （玩家存档里的 seasonId 各不相同，例如中途新加入的玩家），
 * 因此同一份 history 文件可能被写入不止一次。合并时按 UUID 去重取最新，
 * 既不会丢人，也不会产生重复条目。
 */
public class SeasonArchive {

    /** 当前结构版本。需求文档 §12 要求所有 JSON 含 schemaVersion 字段。 */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public int schemaVersion = CURRENT_SCHEMA_VERSION;

    /** 该归档所属的赛季 ID。 */
    public String seasonId = "";

    /** 归档时间（北京时间 yyyy-MM-dd HH:mm:ss）。多次合并时更新为最后一次的时间。 */
    public String archivedAt = "";

    /** 归档的玩家摘要。 */
    public List<ArchivePlayer> players = new ArrayList<>();

    /**
     * 单个玩家的赛季结算摘要。
     */
    public static class ArchivePlayer {

        public String playerUuid = "";

        /**
         * 该玩家当时所属的赛季 ID —— 也就是这份归档对应的赛季。
         *
         * <p>为什么每个条目都要记一次（文件级已经有一个 seasonId 了）：
         * 赛季滚动是逐玩家判断的，同一批被重置的玩家理论上可能带着不同的旧赛季 ID
         * （例如中途加入的玩家）。分组时以条目自带的值分组，
         * 才能在重置之后仍然知道每个人是从哪个赛季滚过来的 ——
         * 一旦重置完成，玩家存档里的 seasonId 已经被改成新赛季了。
         */
        public String seasonId = "";

        /** 玩家名（来自永久数据里的 lastKnownName），便于人读归档。 */
        public String playerName = "";

        /** 赛季结束时的等级。 */
        public int level = 1;

        /** 赛季结束时该等级内的剩余经验。 */
        public int xp = 0;

        /** 赛季结束时的分支名。 */
        public String branch = Branch.NONE.name();

        /** 已完成的每日任务数（完成待领取与已领取都算）。 */
        public int completedDailyTasks = 0;

        /** 已完成的每周挑战数。 */
        public int completedWeeklyTasks = 0;

        public ArchivePlayer() {
        }

        /** @return 强类型分支。 */
        public Branch branch() {
            return Branch.fromName(branch);
        }

        /**
         * 从一份赛季存档生成摘要。
         *
         * @param season    赛季存档
         * @param playerName 玩家名（可为空串）
         * @return 归档条目
         */
        public static ArchivePlayer of(SeasonData season, String playerName) {
            ArchivePlayer entry = new ArchivePlayer();

            if (season == null) {
                return entry;
            }

            entry.playerUuid = season.playerUuid == null ? "" : season.playerUuid;
            entry.seasonId = season.seasonId == null ? "" : season.seasonId;
            entry.playerName = playerName == null ? "" : playerName;
            entry.level = season.level;
            entry.xp = season.xp;
            entry.branch = season.branch().name();
            entry.completedDailyTasks = countCompleted(season.dailyTasks == null ? null : season.dailyTasks.values());
            entry.completedWeeklyTasks = countCompleted(season.weeklyTasks == null ? null : season.weeklyTasks.values());

            return entry;
        }

        private static int countCompleted(java.util.Collection<TaskProgress> progresses) {
            if (progresses == null) {
                return 0;
            }

            int count = 0;

            for (TaskProgress progress : progresses) {
                if (progress == null) {
                    continue;
                }

                TaskStatus status = progress.status();

                if (status == TaskStatus.COMPLETED || status == TaskStatus.CLAIMED) {
                    count++;
                }
            }

            return count;
        }
    }

    /**
     * 把新条目合并进来：按 UUID 覆盖旧条目，新增的追加到末尾。
     *
     * @param entries 新条目
     * @return 本次是否产生了变化
     */
    public boolean merge(List<ArchivePlayer> entries) {
        if (entries == null || entries.isEmpty()) {
            return false;
        }

        if (players == null) {
            players = new ArrayList<>();
        }

        boolean changed = false;

        for (ArchivePlayer entry : entries) {
            if (entry == null || entry.playerUuid == null || entry.playerUuid.isEmpty()) {
                continue;
            }

            int existing = indexOf(entry.playerUuid);

            if (existing >= 0) {
                players.set(existing, entry);
            } else {
                players.add(entry);
            }

            changed = true;
        }

        return changed;
    }

    private int indexOf(String playerUuid) {
        for (int i = 0; i < players.size(); i++) {
            ArchivePlayer entry = players.get(i);

            if (entry != null && playerUuid.equals(entry.playerUuid)) {
                return i;
            }
        }

        return -1;
    }

    /** @return 归档中的玩家数量。 */
    public int playerCount() {
        return players == null ? 0 : players.size();
    }
}
