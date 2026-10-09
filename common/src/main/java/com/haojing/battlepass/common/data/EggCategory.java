package com.haojing.battlepass.common.data;

/**
 * 用途：彩蛋分类。需求文档 §6 明确分为三类，并且三类的奖励形态不同：
 * <ul>
 *   <li>{@link #GLOBAL} 全局战令彩蛋：小额经验 + 称号 + 收藏</li>
 *   <li>{@link #LONG_NIGHT} 长夜专属彩蛋：**零经验**，仅称号 + 收藏（避免诱导熬夜）</li>
 *   <li>{@link #SEASON_LIMITED} 赛季限定彩蛋：赛季结束后不可再获取，记录永久留存历史册</li>
 * </ul>
 *
 * <p>为什么要有 {@code fromName} 的宽容解析：与项目里其他枚举一致 ——
 * Gson 遇到不认识的枚举值会抛异常，而持久化层会把解析失败当成"文件损坏"并回滚 .bak，
 * 那会连带丢掉玩家数据。彩蛋配置里的分类认不出来时按 {@link #GLOBAL} 处理并告警，
 * 至少不会让整份配置失效。
 */
public enum EggCategory {

    /** 全局战令彩蛋：小额经验 + 称号 + 收藏。 */
    GLOBAL,

    /** 长夜专属彩蛋：零经验，仅称号 + 收藏。 */
    LONG_NIGHT,

    /** 赛季限定彩蛋：赛季结束后不可再获取（进度随赛季重置）。 */
    SEASON_LIMITED;

    /**
     * 宽容地把字符串转成枚举。
     *
     * @param name 配置里的分类名
     * @return 对应枚举；无法识别或为空时返回 {@link #GLOBAL}
     */
    public static EggCategory fromName(String name) {
        if (name == null || name.isBlank()) {
            return GLOBAL;
        }

        String trimmed = name.trim();

        for (EggCategory category : values()) {
            if (category.name().equalsIgnoreCase(trimmed)) {
                return category;
            }
        }

        return GLOBAL;
    }

    /** @return 该分类是否允许发放经验（§6：长夜专属彩蛋零经验）。 */
    public boolean allowsXp() {
        return this != LONG_NIGHT;
    }
}
