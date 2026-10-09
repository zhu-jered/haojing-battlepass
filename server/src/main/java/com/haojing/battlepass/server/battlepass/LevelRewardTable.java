package com.haojing.battlepass.server.battlepass;

import com.haojing.battlepass.common.ModConstants;
import com.haojing.battlepass.common.data.Branch;
import com.haojing.battlepass.common.data.Reward;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 用途：1~30 级的等级奖励表，对应 config/haojing_battlepass/rewards.json。
 *
 * <p>为什么需要这张表：§4 规定「装饰类奖励形态：称号、粒子特效、盔甲纹饰、自定义物品」，
 * §9 又要求管理员能在管理面板里编辑"1~30 级、双分支"的奖励。
 * 也就是说"每级 +10 星币"是硬规则（写在代码里），而"哪一级给什么装饰"是管理员数据。
 *
 * <p>为什么校验采取"丢弃非法项 + 告警"：与任务池（{@code TaskPool#validate}）保持一致。
 * 一条写错的奖励若被静默接受，最坏情况是发出去一个不存在的物品；而若因此拒绝加载整张表，
 * 又会波及所有等级。丢弃单条并明确告警是唯一"坏影响局部化"的选择。
 */
public class LevelRewardTable {

    /** 当前配置结构版本。需求文档 §12 要求所有 JSON 含 schemaVersion 字段。 */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public int schemaVersion = CURRENT_SCHEMA_VERSION;

    /** 按等级排列的奖励条目。 */
    public List<LevelRewardEntry> levels = new ArrayList<>();

    /** 等级 → 条目索引。校验时重建，运行期只读。 */
    private transient Map<Integer, LevelRewardEntry> index = new HashMap<>();

    private static final Logger LOGGER = LoggerFactory.getLogger(ModConstants.MOD_ID_SERVER);

    /**
     * 校验并建立索引。非法条目与非法奖励会被丢弃，每条都留 WARN。
     *
     * @param maxLevel 配置的等级上限；超出上限的条目会被丢弃
     */
    public void validate(int maxLevel) {
        Map<Integer, LevelRewardEntry> built = new HashMap<>();
        int droppedEntries = 0;
        int droppedRewards = 0;

        if (levels == null) {
            levels = new ArrayList<>();
        }

        for (LevelRewardEntry entry : levels) {
            if (entry == null) {
                droppedEntries++;
                continue;
            }

            if (!entry.enabled) {
                // 关掉的条目直接不索引：等于该级没有奖励，但配置内容仍然保留在文件里。
                continue;
            }

            if (entry.level < 1 || entry.level > maxLevel) {
                LOGGER.warn("{} 等级奖励表中的 level={} 超出 1~{}，该条目已丢弃",
                        ModConstants.LOG_PREFIX, entry.level, maxLevel);
                droppedEntries++;
                continue;
            }

            if (built.containsKey(entry.level)) {
                LOGGER.warn("{} 等级奖励表中 level={} 重复出现，只保留第一次出现的条目",
                        ModConstants.LOG_PREFIX, entry.level);
                droppedEntries++;
                continue;
            }

            int[] dropped = new int[1];
            entry.common = validateList(entry.common, entry.level, "通用", dropped);
            entry.hunt = validateList(entry.hunt, entry.level, "狩猎分支", dropped);
            entry.build = validateList(entry.build, entry.level, "建造分支", dropped);
            droppedRewards += dropped[0];
            if (entry.common.isEmpty() && entry.hunt.isEmpty() && entry.build.isEmpty()) {
                LOGGER.warn("{} 等级奖励表 level={} 的条目没有任何可用奖励，已丢弃", ModConstants.LOG_PREFIX, entry.level);
                droppedEntries++;
                continue;
            }

            built.put(entry.level, entry);
        }

        index = built;

        if (droppedEntries > 0 || droppedRewards > 0) {
            LOGGER.warn("{} 等级奖励表校验完成：丢弃条目 {} 个（其中非法奖励 {} 条）", ModConstants.LOG_PREFIX,
                    droppedEntries, droppedRewards);
        }
    }

    /** @return 指定等级 + 分支下应当发放的奖励（通用 + 分支专属），永不返回 null。 */
    public List<Reward> rewardsFor(int level, Branch branch) {
        List<Reward> result = new ArrayList<>(commonRewards(level));

        for (Reward reward : branchRewards(level, branch)) {
            if (reward != null) {
                result.add(reward);
            }
        }

        return result;
    }

    /** @return 指定等级的通用奖励（不分分支），永不返回 null。 */
    public List<Reward> commonRewards(int level) {
        LevelRewardEntry entry = index.get(level);
        return entry == null ? List.of() : entry.common;
    }

    /** @return 指定等级在指定分支下的专属奖励；分支为 {@link Branch#NONE} 时为空。 */
    public List<Reward> branchRewards(int level, Branch branch) {
        LevelRewardEntry entry = index.get(level);

        if (entry == null || branch == null || branch == Branch.NONE) {
            return List.of();
        }

        return entry.forBranch(branch);
    }

    /** @return 是否在指定等级配了"该分支专属"的奖励（用于日志与测试断言）。 */
    public boolean hasBranchReward(int level, Branch branch) {
        LevelRewardEntry entry = index.get(level);
        return entry != null && !entry.forBranch(branch).isEmpty();
    }

    /** @return 配了奖励的等级数量。 */
    public int configuredLevelCount() {
        return index.size();
    }

    /** @return 校验后仍然有效的等级列表（升序）。 */
    public List<Integer> configuredLevels() {
        List<Integer> keys = new ArrayList<>(index.keySet());
        keys.sort(Integer::compareTo);
        return keys;
    }

    /**
     * 逐条规范化并校验一个奖励列表。
     *
     * @param raw       原始列表
     * @param level     所属等级（仅用于日志）
     * @param groupName 组名（仅用于日志）
     * @param dropped   出参：本列表中被丢弃的条数（长度为 1 的数组，避免为计数再引入一个类）
     * @return 可用奖励列表
     */
    private List<Reward> validateList(List<Reward> raw, int level, String groupName, int[] dropped) {
        List<Reward> valid = new ArrayList<>();

        if (raw == null) {
            return valid;
        }

        for (Reward reward : raw) {
            if (reward == null) {
                dropped[0]++;
                continue;
            }

            reward.normalize();
            String error = reward.validateError();

            if (error != null) {
                LOGGER.warn("{} 等级奖励表 level={} 的{}奖励非法，已丢弃：{}（{}）",
                        ModConstants.LOG_PREFIX, level, groupName, error, reward);
                dropped[0]++;
                continue;
            }

            valid.add(reward);
        }

        return valid;
    }

    /**
     * @return 全部条目里出现过的奖励类型名集合。供管理面板展示与自检使用。
     */
    public Set<String> usedRewardTypes() {
        Set<String> types = new LinkedHashSet<>();

        for (LevelRewardEntry entry : index.values()) {
            for (Reward reward : entry.common) {
                types.add(reward.type);
            }

            for (Reward reward : entry.forBranch(Branch.HUNT)) {
                types.add(reward.type);
            }

            for (Reward reward : entry.forBranch(Branch.BUILD)) {
                types.add(reward.type);
            }
        }

        return types;
    }
}
