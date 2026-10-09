package com.haojing.battlepass.common.data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 用途：单个玩家的「赛季数据」，持久化到 data/haojing_battlepass/players/&lt;uuid&gt;.json
 * （需求文档 §12）。
 *
 * <p>为什么这些字段属于赛季数据：需求文档 §4 规定赛季结束时「重置等级/经验/任务/分支，
 * 保留星币与收藏册」。因此凡是会被重置的东西都放在这里，永久保留的东西放在
 * {@link GlobalData}，两者物理上就是两个文件，从结构上保证"该清的清、该留的留"。
 *
 * <p>为什么用 public 字段而不是 getter/setter：本类只作为 JSON 的载体（DTO），
 * 用 public 字段能让 Gson 直接映射，省掉几十行样板代码；行为逻辑一律放在服务端的
 * 业务类里，避免 DTO 变成"贫血模型+半个业务类"。
 */
public class SeasonData {

    /**
     * 当前存档结构版本。需求文档 §12 要求所有 JSON 含 schemaVersion 字段。
     * 将来字段发生不兼容变更时递增，并在服务端的持久化层里做迁移。
     */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    /** 存档结构版本。 */
    public int schemaVersion = CURRENT_SCHEMA_VERSION;

    /** 玩家 UUID（字符串形式）。冗余存储是为了让人工查看 JSON 时不必靠文件名猜。 */
    public String playerUuid = "";

    /**
     * 所属赛季 ID。用于判断该存档是否属于当前赛季：若与当前赛季不一致，
     * 说明赛季已滚动，需要在服务端做归档与重置（阶段 5 处理）。
     */
    public String seasonId = "";

    /** 战令等级，需求文档 §4 规定范围 1~30。 */
    public int level = 1;

    /** 当前等级内已获得的经验。等级换算规则在阶段 5 实现。 */
    public int xp = 0;

    /** 已选分支，取值见 {@link Branch}。存储为字符串以保证对未知值宽容。 */
    public String branch = Branch.NONE.name();

    /** 本「日」已获得的经验，用于需求文档 §5.10 的每日经验上限判定。 */
    public int dailyXpEarned = 0;

    /** 上一次每日刷新的北京日期（yyyy-MM-dd）。需求文档 §5.3 用它做跨天补刷的幂等标记。 */
    public String lastDailyRefreshDate = "";

    /** 本日已用掉的「重 roll 本组」次数，需求文档 §5.1 默认每日 1 次。 */
    public int dailyRerollUsed = 0;

    /**
     * 上一次刷新时的每日任务 ID。
     *
     * <p>需求文档 §5.1 要求每日任务「与昨日不重复」，因此必须把昨天的选择记下来 ——
     * 只看当天的 dailyTasks 无法判断昨天抽到了什么。刷新时先把它更新为当前值，再抽新任务。
     */
    public List<String> previousDailyTaskIds = new ArrayList<>();

    /**
     * 持有的豁免卡数量。需求文档 §5.9：来源为战令等级奖励与商店兑换，每人持有上限默认 3。
     *
     * <p>为什么放在赛季数据而不是永久数据：已确认赛季结束时豁免卡不清零留存（会一并重置）。
     * 需求文档 §4 只点名「保留星币与收藏册」，豁免卡不在保留清单内，因此跟随赛季重置。
     */
    public int exemptCards = 0;

    /**
     * 已发放过的等级奖励键集合，用于保证"同一奖励只发一次"（需求文档 §16 验收用例）。
     *
     * <p>键的格式是 {@code "12"}（该级的通用奖励）或 {@code "12:HUNT"}（该级的狩猎分支奖励）。
     * 为什么要按"级+分支"而不是只按级：§4 规定 30 级奖励分双分支，而分支是 10 级才选的。
     * 玩家在 12 级才补选分支时，10~12 级的分支奖励必须能补发，且补发过之后不能再发第二次 ——
     * 用"级+分支"作为键，两种情况（先选分支后升级 / 先升级后选分支）都能正确去重。
     */
    public Set<String> claimedLevelRewards = new LinkedHashSet<>();

    /**
     * 每日任务进度，键为任务组（explore / build / general）。
     * 用 LinkedHashMap 是为了让 JSON 的键顺序稳定，便于人工比对与版本库 diff。
     */
    public Map<String, TaskProgress> dailyTasks = new LinkedHashMap<>();

    /**
     * 每周挑战进度，键为任务 ID。
     */
    public Map<String, TaskProgress> weeklyTasks = new LinkedHashMap<>();

    /**
     * 彩蛋进度，键为彩蛋 ID（需求文档 §6）。
     *
     * <p>为什么放在赛季数据里：§6 的"大地勘探者"要求"本赛季内"进入三种洞穴群系，
     * 赛季限定彩蛋更是必须随赛季失效；而已经解锁的彩蛋记在永久数据的收藏册里。
     * 也就是说"进度会重置、成就永久保留" —— 这两件事分在两个文件里，
     * 由存储结构保证，而不是靠重置代码记得别删。
     */
    public Map<String, EggProgress> eggProgress = new LinkedHashMap<>();

    /** Gson 反序列化需要无参构造。 */
    public SeasonData() {
    }

    public SeasonData(String playerUuid, String seasonId) {
        this.playerUuid = playerUuid;
        this.seasonId = seasonId;
    }

    /** @return 强类型分支；未知值按 {@link Branch#NONE} 处理。 */
    public Branch branch() {
        return Branch.fromName(branch);
    }

    /** @param newBranch 新分支；null 按 {@link Branch#NONE} 处理。 */
    public void setBranch(Branch newBranch) {
        this.branch = (newBranch == null ? Branch.NONE : newBranch).name();
    }

    /** @return 是否已经选过分支（需求文档 §4：本赛季锁定，改选仅管理员指令可做）。 */
    public boolean hasChosenBranch() {
        return branch() != Branch.NONE;
    }

    /**
     * 生成等级奖励的去重键。
     *
     * @param level  等级
     * @param branch 分支；null 或 {@link Branch#NONE} 表示该级的"通用奖励"（双分支共有）
     * @return 去重键
     */
    public static String levelRewardKey(int level, Branch branch) {
        if (branch == null || branch == Branch.NONE) {
            return String.valueOf(level);
        }

        return level + ":" + branch.name();
    }

    /**
     * 原子地登记"该等级奖励已发放"。
     *
     * @param level  等级
     * @param branch 分支；null 表示通用奖励
     * @return true 表示本次是首次登记（调用方据此决定"可以发"）；false 表示已经发过
     */
    public boolean markLevelRewardClaimed(int level, Branch branch) {
        if (claimedLevelRewards == null) {
            // 兼容旧存档：字段缺失时为 null，这里补齐而不是抛异常。
            claimedLevelRewards = new LinkedHashSet<>();
        }

        return claimedLevelRewards.add(levelRewardKey(level, branch));
    }

    /** @return 该等级奖励是否已经发放过。 */
    public boolean isLevelRewardClaimed(int level, Branch branch) {
        return claimedLevelRewards != null && claimedLevelRewards.contains(levelRewardKey(level, branch));
    }

    /**
     * 取某个彩蛋的进度对象，缺失时创建。     *
     * <p>为什么"缺失就创建"而不是返回 null：调用方（判定循环）拿着它就要累加，
     * 每次判空会让十几个分支都多三行；而这里创建的代价只是一次 LinkedHashMap.put。
     *
     * @param eggId 彩蛋 ID
     * @return 进度对象（永不为 null）
     */
    public EggProgress eggProgressOrCreate(String eggId) {
        if (eggProgress == null) {
            eggProgress = new LinkedHashMap<>();
        }

        return eggProgress.computeIfAbsent(eggId == null ? "" : eggId, key -> new EggProgress());
    }

    /** @param eggId 彩蛋 ID @return 进度对象；不存在时为 null。 */
    public EggProgress eggProgress(String eggId) {
        return eggProgress == null || eggId == null ? null : eggProgress.get(eggId);
    }
}
