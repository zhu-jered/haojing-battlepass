package com.haojing.battlepass.server.egg;

import com.haojing.battlepass.common.ModConstants;
import com.haojing.battlepass.common.data.EggCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 用途：彩蛋池，对应 config/haojing_battlepass/eggs.json（需求文档 §6、§18 样例）。
 *
 * <p>校验策略与任务池、等级奖励表、商店完全一致：**丢弃非法项 + 逐条告警**，
 * 绝不因为一条彩蛋写错就让整份配置失效。此外这里多做两件 §6 明确要求的事：
 * <ul>
 *   <li>长夜专属彩蛋的经验**强制归零**（§6：零经验，避免诱导熬夜）——不是丢弃，
 *       而是修正 + 告警：管理员可能只是想确认这个彩蛋值多少分。</li>
 *   <li>把"长夜类条件配在非长夜分类上"这类矛盾配置告警提示（不丢弃）。</li>
 * </ul>
 */
public class EggPool {

    /** 当前配置结构版本。需求文档 §12 要求所有 JSON 含 schemaVersion 字段。 */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public int schemaVersion = CURRENT_SCHEMA_VERSION;

    /** 彩蛋列表。 */
    public List<EggDefinition> eggs = new ArrayList<>();

    /** 轻量趣味彩蛋（聊天关键词 / 生日 / 节日）。 */
    public LightEggConfig light = new LightEggConfig();

    /** ID → 彩蛋。校验时重建，运行期只读。 */
    private transient Map<String, EggDefinition> index = new HashMap<>();

    private static final Logger LOGGER = LoggerFactory.getLogger(ModConstants.MOD_ID_SERVER);

    /** 校验并建立索引。 */
    public void validate() {
        Map<String, EggDefinition> built = new HashMap<>();
        int dropped = 0;
        int fixed = 0;

        if (eggs == null) {
            eggs = new ArrayList<>();
        }

        for (EggDefinition egg : eggs) {
            if (egg == null) {
                dropped++;
                continue;
            }

            if (egg.id == null || egg.id.isBlank()) {
                LOGGER.warn("{} 彩蛋池里有一个彩蛋缺少 id，已丢弃", ModConstants.LOG_PREFIX);
                dropped++;
                continue;
            }

            egg.id = egg.id.trim();
            egg.name = egg.name == null ? "" : egg.name.trim();
            egg.desc = egg.desc == null ? "" : egg.desc.trim();
            egg.title = egg.title == null ? "" : egg.title.trim();

            if (built.containsKey(egg.id)) {
                LOGGER.warn("{} 彩蛋 id 重复：{}，只保留第一次出现的", ModConstants.LOG_PREFIX, egg.id);
                dropped++;
                continue;
            }

            if (!egg.enabled) {
                // 关闭的彩蛋不索引，但配置内容原样保留（与等级奖励表一致）。
                continue;
            }

            if (egg.condition == null) {
                LOGGER.warn("{} 彩蛋 {} 缺少 condition，已丢弃", ModConstants.LOG_PREFIX, egg.id);
                dropped++;
                continue;
            }

            egg.condition.normalize();

            if (egg.condition.typeOrNull() == null) {
                LOGGER.warn("{} 彩蛋 {} 的条件类型无法识别（{}），已丢弃", ModConstants.LOG_PREFIX, egg.id, egg.condition.type);
                dropped++;
                continue;
            }

            if (!egg.condition.isValid()) {
                LOGGER.warn("{} 彩蛋 {} 的条件参数非法（type={}），已丢弃", ModConstants.LOG_PREFIX, egg.id, egg.condition.type);
                dropped++;
                continue;
            }

            EggCategory category = egg.categoryOrGlobal();

            if (category != EggCategory.fromName(egg.category)) {
                LOGGER.warn("{} 彩蛋 {} 的分类无法识别（{}），已按 GLOBAL 处理", ModConstants.LOG_PREFIX, egg.id, egg.category);
            }

            if (category == EggCategory.LONG_NIGHT && egg.xp != 0) {
                LOGGER.warn("{} 彩蛋 {} 属于长夜专属但配了 xp={}，按 §6「零经验」已修正为 0",
                        ModConstants.LOG_PREFIX, egg.id, egg.xp);
                egg.xp = 0;
                fixed++;
            }

            if (egg.xp < 0) {
                egg.xp = 0;
                fixed++;
            }

            if (egg.requiresLongNight() && category != EggCategory.LONG_NIGHT) {
                LOGGER.warn("{} 彩蛋 {} 用了长夜类条件却分类为 {}，请确认是否有意为之",
                        ModConstants.LOG_PREFIX, egg.id, category);
            }

            built.put(egg.id, egg);
        }

        index = built;

        if (light == null) {
            light = new LightEggConfig();
        }

        light.normalizeAndDrop((what, action) ->
                LOGGER.warn("{} 轻量彩蛋配置：{}（{}）", ModConstants.LOG_PREFIX, what, action));

        if (dropped > 0 || fixed > 0) {
            LOGGER.warn("{} 彩蛋池校验完成：丢弃 {} 个，修正 {} 个", ModConstants.LOG_PREFIX, dropped, fixed);
        }
    }

    /**
     * @param eggId 彩蛋 ID
     * @return 彩蛋；不存在或已关闭时为 null
     */
    public EggDefinition egg(String eggId) {
        return eggId == null ? null : index.get(eggId);
    }

    /** @return 全部启用的彩蛋（校验后仍有效的）。 */
    public List<EggDefinition> enabledEggs() {
        return new ArrayList<>(index.values());
    }

    /** @return 有效彩蛋数量。 */
    public int size() {
        return index.size();
    }

    /** @return 有效彩蛋里出现过的条件类型集合（供自检与日志）。 */
    public Set<String> usedConditionTypes() {
        Set<String> types = new LinkedHashSet<>();

        for (EggDefinition egg : index.values()) {
            types.add(egg.condition.type);
        }

        return types;
    }

    /** @return 按分类统计的彩蛋数量描述（用于启动日志）。 */
    public String describeCategories() {
        Map<EggCategory, Integer> counts = new HashMap<>();

        for (EggDefinition egg : index.values()) {
            counts.merge(egg.categoryOrGlobal(), 1, Integer::sum);
        }

        return "全局 " + counts.getOrDefault(EggCategory.GLOBAL, 0)
                + " / 长夜 " + counts.getOrDefault(EggCategory.LONG_NIGHT, 0)
                + " / 赛季限定 " + counts.getOrDefault(EggCategory.SEASON_LIMITED, 0);
    }
}
