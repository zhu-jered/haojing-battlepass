package com.haojing.battlepass.server.checkin;

import com.haojing.battlepass.common.ModConstants;
import com.haojing.battlepass.common.data.Reward;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用途：每日签到配置清单（config/haojing_battlepass/checkin.json）。
 *
 * <p>结构分两层：
 * <ul>
 *   <li>{@code dailyRewards}：按连击天数循环的每日奖励，第 1~cycleDays 天各一档，
 *       第 cycleDays+1 天回到第 1 档（七日循环是战令类产品的通行做法）。</li>
 *   <li>{@code streakMilestones}：达到固定连续天数一次性发放的里程碑奖励，只发一次。</li>
 * </ul>
 *
 * <p>校验策略与其它配置清单一致：丢弃非法项并逐条告警；奖励类型/数量/物品 ID 的合法性
 * 复用 {@link Reward#validateError()}，不在本类重复实现一套规则。
 */
public class CheckinPool {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModConstants.MOD_ID_SERVER);

    /** 当前配置结构版本。需求文档 §12 要求所有 JSON 含 schemaVersion 字段。 */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public int schemaVersion = CURRENT_SCHEMA_VERSION;

    /** 签到功能总开关。 */
    public boolean enabled = true;

    /** 每日奖励循环天数（默认 7 天一轮）；校验时夹紧到至少 1。 */
    public int cycleDays = 7;

    /** 每日奖励档位列表，第 i 档对应连击天数 ((i) % cycleDays) + 1。 */
    public List<List<Reward>> dailyRewards = new ArrayList<>();

    /** 连续签到里程碑档位（达到连续 N 天一次性发放，去重防重复发）。 */
    public List<CheckinMilestone> streakMilestones = new ArrayList<>();

    /** 校验配置、过滤非法项并规范化空集合。 */
    public void validate() {
        if (cycleDays < 1) {
            LOGGER.warn("{} checkin.cycleDays={} 非法，已回退为 7", ModConstants.LOG_PREFIX, cycleDays);
            cycleDays = 7;
        }

        if (dailyRewards == null) {
            dailyRewards = new ArrayList<>();
        }

        List<List<Reward>> validDaily = new ArrayList<>();

        for (List<Reward> dayRewards : dailyRewards) {
            List<Reward> valid = new ArrayList<>();

            if (dayRewards != null) {
                for (Reward reward : dayRewards) {
                    if (reward == null) {
                        continue;
                    }

                    reward.normalize();
                    String error = reward.validateError();

                    if (error != null) {
                        LOGGER.warn("{} 签到每日奖励中有一条非法，已丢弃：{}", ModConstants.LOG_PREFIX, error);
                        continue;
                    }

                    valid.add(reward);
                }
            }

            validDaily.add(valid);
        }

        dailyRewards = validDaily;

        if (streakMilestones == null) {
            streakMilestones = new ArrayList<>();
        }

        Map<Integer, CheckinMilestone> built = new HashMap<>();

        for (CheckinMilestone milestone : streakMilestones) {
            if (milestone == null) {
                continue;
            }

            milestone.normalize();

            if (milestone.streak <= 0) {
                LOGGER.warn("{} 签到里程碑缺少合法 streak 天数，已丢弃", ModConstants.LOG_PREFIX);
                continue;
            }

            if (built.containsKey(milestone.streak)) {
                LOGGER.warn("{} 签到里程碑 streak={} 重复配置，只保留第一次出现的", ModConstants.LOG_PREFIX, milestone.streak);
                continue;
            }

            List<Reward> valid = new ArrayList<>();

            for (Reward reward : milestone.rewards) {
                if (reward == null) {
                    continue;
                }

                reward.normalize();
                String error = reward.validateError();

                if (error != null) {
                    LOGGER.warn("{} 签到里程碑 {} 天的奖励非法，已丢弃：{}",
                            ModConstants.LOG_PREFIX, milestone.streak, error);
                    continue;
                }

                valid.add(reward);
            }

            milestone.rewards = valid;
            built.put(milestone.streak, milestone);
        }

        streakMilestones = new ArrayList<>(built.values());
    }

    /**
     * 取某连击天数对应的每日奖励档。
     *
     * @param streak 当前连续签到天数（1 起）
     * @return 该档奖励；未配置该档时返回空列表（不发每日奖励，但连击与里程碑照常结算）
     */
    public List<Reward> dailyRewardFor(int streak) {
        if (dailyRewards.isEmpty() || streak <= 0) {
            return new ArrayList<>();
        }

        int index = (streak - 1) % Math.max(1, cycleDays);

        if (index >= dailyRewards.size()) {
            return new ArrayList<>();
        }

        List<Reward> rewards = dailyRewards.get(index);
        return rewards == null ? new ArrayList<>() : new ArrayList<>(rewards);
    }

    /** @return 全部里程碑档位（顺序不影响发放，去重由 {@link #validate()} 保证）。 */
    public List<CheckinMilestone> milestones() {
        return streakMilestones == null ? new ArrayList<>() : new ArrayList<>(streakMilestones);
    }
}
