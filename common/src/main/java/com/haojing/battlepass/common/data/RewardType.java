package com.haojing.battlepass.common.data;

/**
 * 用途：奖励类型枚举。需求文档 §9 要求把奖励统一抽象为
 * ITEM / COMMAND / BATTLEPASS_XP / STAR_COIN / TITLE 五类，
 * 其中 COMMAND 必须走管理员命令白名单，禁止把 GUI 输入直接当命令执行。
 *
 * <p>为什么多出一个 {@link #EXEMPT_CARD}（文档的五类里没有）：
 * §5.9 明确要求豁免卡「来源为战令等级奖励与商店兑换」，也就是等级奖励表与商店商品
 * 都必须能表达"发一张豁免卡"。若不给它一个类型，就只能把 §5.9 的要求硬编码进
 * 等级/商店两处逻辑里，管理员反而无法通过配置增删豁免卡的发放点。
 * 这条扩展已登记在 docs/需求偏差记录.md（D-13）。
 *
 * <p>为什么 {@link #fromName(String)} 认不出来时返回 null 而不是兜底成某一类：
 * 奖励类型认错会直接把道具发成星币（或反过来），比"丢弃并告警"危险得多。
 * 调用方一律按"无法识别即丢弃该奖励并记 WARN"处理，与任务池对未知 action 的做法一致。
 */
public enum RewardType {

    /** 发放物品（原版或模组物品 ID）。 */
    ITEM,

    /** 以管理员身份执行一条命令；必须命中命令白名单。 */
    COMMAND,

    /** 发放战令经验（同样受 §5.10 每日经验上限与 §5.11 长夜倍率的约束）。 */
    BATTLEPASS_XP,

    /** 发放星币（写入永久数据文件，赛季重置不清零）。 */
    STAR_COIN,

    /** 解锁称号（写入永久数据文件的称号库）。 */
    TITLE,

    /** 发放豁免卡（写入赛季数据文件，赛季重置时清零）。 */
    EXEMPT_CARD;

    /**
     * 宽容地把字符串转成枚举：大小写不敏感、容忍首尾空白，认不出来返回 {@code null}。
     *
     * @param name 配置里的类型名
     * @return 对应枚举；为 null 表示无法识别
     */
    public static RewardType fromName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }

        String trimmed = name.trim();

        for (RewardType type : values()) {
            if (type.name().equalsIgnoreCase(trimmed)) {
                return type;
            }
        }

        return null;
    }

    /** @return 该类型的奖励是否需要"在线的玩家"才能真正发出去。 */
    public boolean requiresOnlinePlayer() {
        // 物品要塞进背包、命令要挂到玩家身上执行，这两类离线时无法完成；
        // 其余四类都只改数据文件，离线玩家也能正常发放（管理员数据维护会用到）。
        return this == ITEM || this == COMMAND;
    }
}
