package com.haojing.battlepass.server.redeem;

import com.haojing.battlepass.common.ModConstants;
import com.haojing.battlepass.common.data.Reward;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用途：口令清单，对应 config/haojing_battlepass/codes.json（需求文档 §10）。
 *
 * <p>校验策略与其它配置一致（丢弃非法项 + 逐条告警），另外多做一件与口令特性相关的事：
 * **检测重复的口令文本**。两个人配了同一个口令文本时，匹配会命中第一条、
 * 第二条永远领不到，这属于管理员很难自己发现的配置错误，因此明确告警。
 */
public class RedeemCodePool {

    /** 当前配置结构版本。需求文档 §12 要求所有 JSON 含 schemaVersion 字段。 */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public int schemaVersion = CURRENT_SCHEMA_VERSION;

    /** 口令列表（顺序即匹配顺序）。 */
    public List<RedeemCode> codes = new ArrayList<>();

    /** ID → 口令。校验时重建，运行期只读。 */
    private transient Map<String, RedeemCode> index = new HashMap<>();

    private static final Logger LOGGER = LoggerFactory.getLogger(ModConstants.MOD_ID_SERVER);

    /** 校验并建立索引。 */
    public void validate() {
        Map<String, RedeemCode> built = new HashMap<>();
        Map<String, String> codeTexts = new HashMap<>();
        int dropped = 0;

        if (codes == null) {
            codes = new ArrayList<>();
        }

        for (RedeemCode code : codes) {
            if (code == null) {
                dropped++;
                continue;
            }

            code.normalize();

            if (code.id.isEmpty()) {
                LOGGER.warn("{} 口令配置里有一条缺少 id，已丢弃", ModConstants.LOG_PREFIX);
                dropped++;
                continue;
            }

            if (code.code.isEmpty()) {
                LOGGER.warn("{} 口令 {} 没有配口令文本，已丢弃", ModConstants.LOG_PREFIX, code.id);
                dropped++;
                continue;
            }

            if (built.containsKey(code.id)) {
                LOGGER.warn("{} 口令 id 重复：{}，只保留第一次出现的", ModConstants.LOG_PREFIX, code.id);
                dropped++;
                continue;
            }

            if (!code.enabled) {
                continue;
            }

            String normalizedText = code.code.toLowerCase(java.util.Locale.ROOT);
            String existingOwner = codeTexts.get(normalizedText);

            if (existingOwner != null) {
                LOGGER.warn("{} 口令文本「{}」同时配给了 {} 与 {}，只有靠前的那个能被领到（匹配忽略大小写）",
                        ModConstants.LOG_PREFIX, code.code, existingOwner, code.id);
            } else {
                codeTexts.put(normalizedText, code.id);
            }

            List<Reward> valid = new ArrayList<>();

            for (Reward reward : code.rewards) {
                if (reward == null) {
                    continue;
                }

                reward.normalize();
                String error = reward.validateError();

                if (error != null) {
                    LOGGER.warn("{} 口令 {} 的奖励非法，已丢弃：{}（{}）",
                            ModConstants.LOG_PREFIX, code.id, error, reward);
                    continue;
                }

                valid.add(reward);
            }

            code.rewards = valid;

            if (valid.isEmpty()) {
                LOGGER.warn("{} 口令 {} 没有任何可用奖励，领取后玩家什么也拿不到", ModConstants.LOG_PREFIX, code.id);
            }

            built.put(code.id, code);
        }

        index = built;

        if (dropped > 0) {
            LOGGER.warn("{} 口令配置校验完成：丢弃 {} 条", ModConstants.LOG_PREFIX, dropped);
        }
    }

    /**
     * @param id 口令 ID
     * @return 口令；不存在或已关闭时为 null
     */
    public RedeemCode code(String id) {
        return id == null ? null : index.get(id);
    }

    /** @return 全部启用口令（顺序即匹配顺序）。 */
    public List<RedeemCode> all() {
        return new ArrayList<>(index.values());
    }

    /** @return 有效口令数量。 */
    public int size() {
        return index.size();
    }
}
