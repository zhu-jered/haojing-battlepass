package com.haojing.battlepass.server.event;

import com.haojing.battlepass.common.ModConstants;
import com.haojing.battlepass.common.data.Reward;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用途：随机事件总配置，对应 config/haojing_battlepass/events.json。
 *
 * <p>分成"全局参数"与"事件清单"两段：触发概率与最小间隔是**全服共用**的策略
 * （§10 要求的最小间隔默认 ≥60 分钟指的是"两次事件之间"），
 * 而每个事件自己的时长、参与判定、奖励各不相同。
 * 把它们混在一层会让"我要整体调低事件频率"这件事变得很麻烦。
 */
public class RandomEventConfig {

    /** 当前配置结构版本。需求文档 §12 要求所有 JSON 含 schemaVersion 字段。 */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public int schemaVersion = CURRENT_SCHEMA_VERSION;

    /** 随机事件总开关。 */
    public boolean enabled = true;

    /** 判定间隔（分钟）：每过这么久掷一次骰子。 */
    public int checkIntervalMinutes = 5;

    /** 单次判定的触发概率（0~1）。 */
    public double rollChance = 0.08D;

    /** 两次事件之间的最小间隔（分钟）。§10 要求默认 ≥60。 */
    public int minIntervalMinutes = 90;

    /** 是否全服公告事件开始/结束。 */
    public boolean announce = true;

    /** 事件清单。 */
    public List<RandomEventDefinition> events = new ArrayList<>();

    /** ID → 定义。校验时重建，运行期只读。 */
    private transient Map<String, RandomEventDefinition> index = new HashMap<>();

    private static final Logger LOGGER = LoggerFactory.getLogger(ModConstants.MOD_ID_SERVER);

    /** 校验并建立索引。 */
    public void validate() {
        Map<String, RandomEventDefinition> built = new HashMap<>();
        int dropped = 0;

        if (checkIntervalMinutes < 1) {
            LOGGER.warn("{} checkIntervalMinutes={} 非法，已回退为 5 分钟",
                    ModConstants.LOG_PREFIX, checkIntervalMinutes);
            checkIntervalMinutes = 5;
        }

        if (rollChance < 0 || rollChance > 1) {
            double clamped = Math.max(0, Math.min(1, rollChance));
            LOGGER.warn("{} rollChance={} 超出 0~1，已夹紧为 {}", ModConstants.LOG_PREFIX, rollChance, clamped);
            rollChance = clamped;
        }

        if (minIntervalMinutes < 0) {
            LOGGER.warn("{} minIntervalMinutes={} 非法，已回退为 0（不限制间隔）",
                    ModConstants.LOG_PREFIX, minIntervalMinutes);
            minIntervalMinutes = 0;
        }

        if (minIntervalMinutes < 60) {
            LOGGER.warn("{} minIntervalMinutes={} 小于需求文档 §10 的默认下限 60 分钟，请确认是否有意为之",
                    ModConstants.LOG_PREFIX, minIntervalMinutes);
        }

        if (events == null) {
            events = new ArrayList<>();
        }

        for (RandomEventDefinition event : events) {
            if (event == null) {
                dropped++;
                continue;
            }

            event.normalize();

            if (event.id.isEmpty()) {
                LOGGER.warn("{} 随机事件里有一条缺少 id，已丢弃", ModConstants.LOG_PREFIX);
                dropped++;
                continue;
            }

            if (built.containsKey(event.id)) {
                LOGGER.warn("{} 随机事件 id 重复：{}，只保留第一次出现的", ModConstants.LOG_PREFIX, event.id);
                dropped++;
                continue;
            }

            if (!event.enabled) {
                continue;
            }

            if (event.typeOrNull() == null) {
                LOGGER.warn("{} 随机事件 {} 的类型无法识别（{}），已丢弃", ModConstants.LOG_PREFIX, event.id, event.type);
                dropped++;
                continue;
            }

            if (event.durationMinutes < 1) {
                LOGGER.warn("{} 随机事件 {} 的持续时长为 {} 分钟，已回退为 1 分钟",
                        ModConstants.LOG_PREFIX, event.id, event.durationMinutes);
                event.durationMinutes = 1;
            }

            if (event.weight < 1) {
                event.weight = 1;
            }

            List<EventEffect> validEffects = new ArrayList<>();

            for (EventEffect effect : event.effects) {
                if (effect == null) {
                    continue;
                }

                effect.normalize();
                String error = effect.validateError();

                if (error != null) {
                    LOGGER.warn("{} 随机事件 {} 的效果非法，已丢弃：{}", ModConstants.LOG_PREFIX, event.id, error);
                    continue;
                }

                validEffects.add(effect);
            }

            event.effects = validEffects;

            List<Reward> validRewards = new ArrayList<>();

            for (Reward reward : event.rewards) {
                if (reward == null) {
                    continue;
                }

                reward.normalize();
                String error = reward.validateError();

                if (error != null) {
                    LOGGER.warn("{} 随机事件 {} 的奖励非法，已丢弃：{}（{}）",
                            ModConstants.LOG_PREFIX, event.id, error, reward);
                    continue;
                }

                validRewards.add(reward);
            }

            event.rewards = validRewards;

            if (validRewards.isEmpty()) {
                LOGGER.warn("{} 随机事件 {} 没有任何参与者奖励，参与后什么都拿不到", ModConstants.LOG_PREFIX, event.id);
            }

            built.put(event.id, event);
        }

        index = built;

        if (dropped > 0) {
            LOGGER.warn("{} 随机事件配置校验完成：丢弃 {} 条", ModConstants.LOG_PREFIX, dropped);
        }
    }

    /**
     * @param id 事件 ID
     * @return 定义；不存在或已关闭时为 null
     */
    public RandomEventDefinition event(String id) {
        return id == null ? null : index.get(id);
    }

    /** @return 全部启用事件（顺序即配置顺序）。 */
    public List<RandomEventDefinition> all() {
        return new ArrayList<>(index.values());
    }

    /** @return 有效事件数量。 */
    public int size() {
        return index.size();
    }

    /** @return 判定间隔毫秒数。 */
    public long checkIntervalMillis() {
        return Math.max(1, checkIntervalMinutes) * 60_000L;
    }

    /** @return 最小间隔毫秒数。 */
    public long minIntervalMillis() {
        return Math.max(0, minIntervalMinutes) * 60_000L;
    }
}
