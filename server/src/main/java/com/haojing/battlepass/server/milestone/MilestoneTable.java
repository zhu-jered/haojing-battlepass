package com.haojing.battlepass.server.milestone;

import com.haojing.battlepass.common.ModConstants;
import com.haojing.battlepass.common.data.Reward;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用途：全服里程碑清单，对应 config/haojing_battlepass/milestones.json。
 *
 * <p>校验策略与其它配置一致（丢弃非法项 + 逐条告警）。
 * 额外多做一件事：把"永远不可能达成"的配置挑出来告警 ——
 * 例如阈值为 0 或负数（一上线就直接达成，多半是手滑）以及没有配任何奖励
 * （达成了却不发东西，可能是漏配）。这两类都不丢弃，只提示。
 */
public class MilestoneTable {

    /** 当前配置结构版本。需求文档 §12 要求所有 JSON 含 schemaVersion 字段。 */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public int schemaVersion = CURRENT_SCHEMA_VERSION;

    /** 里程碑列表。 */
    public List<MilestoneDefinition> milestones = new ArrayList<>();

    /** ID → 定义。校验时重建，运行期只读。 */
    private transient Map<String, MilestoneDefinition> index = new HashMap<>();

    private static final Logger LOGGER = LoggerFactory.getLogger(ModConstants.MOD_ID_SERVER);

    /** 校验并建立索引。 */
    public void validate() {
        Map<String, MilestoneDefinition> built = new HashMap<>();
        int dropped = 0;

        if (milestones == null) {
            milestones = new ArrayList<>();
        }

        for (MilestoneDefinition milestone : milestones) {
            if (milestone == null) {
                dropped++;
                continue;
            }

            milestone.normalize();

            if (milestone.id.isEmpty()) {
                LOGGER.warn("{} 全服里程碑里有一条缺少 id，已丢弃", ModConstants.LOG_PREFIX);
                dropped++;
                continue;
            }

            if (built.containsKey(milestone.id)) {
                LOGGER.warn("{} 里程碑 id 重复：{}，只保留第一次出现的", ModConstants.LOG_PREFIX, milestone.id);
                dropped++;
                continue;
            }

            if (!milestone.enabled) {
                continue;
            }

            if (milestone.metricOrNull() == null) {
                LOGGER.warn("{} 里程碑 {} 的指标无法识别（{}），已丢弃", ModConstants.LOG_PREFIX, milestone.id, milestone.metric);
                dropped++;
                continue;
            }

            if (milestone.threshold <= 0) {
                LOGGER.warn("{} 里程碑 {} 的阈值为 {}，会在下一次检查时立刻达成，请确认是否有意为之",
                        ModConstants.LOG_PREFIX, milestone.id, milestone.threshold);
            }

            List<Reward> valid = new ArrayList<>();
            int badRewards = 0;

            for (Reward reward : milestone.rewards) {
                if (reward == null) {
                    badRewards++;
                    continue;
                }

                reward.normalize();
                String error = reward.validateError();

                if (error != null) {
                    LOGGER.warn("{} 里程碑 {} 的奖励非法，已丢弃：{}（{}）",
                            ModConstants.LOG_PREFIX, milestone.id, error, reward);
                    badRewards++;
                    continue;
                }

                valid.add(reward);
            }

            milestone.rewards = valid;

            if (valid.isEmpty()) {
                LOGGER.warn("{} 里程碑 {} 没有任何可用奖励，达成后不会发放任何东西", ModConstants.LOG_PREFIX, milestone.id);
            }

            if (badRewards > 0) {
                LOGGER.warn("{} 里程碑 {} 丢弃了 {} 条非法奖励", ModConstants.LOG_PREFIX, milestone.id, badRewards);
            }

            built.put(milestone.id, milestone);
        }

        index = built;

        if (dropped > 0) {
            LOGGER.warn("{} 里程碑配置校验完成：丢弃 {} 条", ModConstants.LOG_PREFIX, dropped);
        }
    }

    /**
     * @param id 里程碑 ID
     * @return 定义；不存在时为 null
     */
    public MilestoneDefinition milestone(String id) {
        return id == null ? null : index.get(id);
    }

    /** @return 全部启用的里程碑。 */
    public List<MilestoneDefinition> all() {
        return new ArrayList<>(index.values());
    }

    /** @return 有效里程碑数量。 */
    public int size() {
        return index.size();
    }
}
