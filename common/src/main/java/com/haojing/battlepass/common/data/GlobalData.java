package com.haojing.battlepass.common.data;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 用途：单个玩家的「永久数据」，持久化到 data/haojing_battlepass/global/&lt;uuid&gt;.json
 * （需求文档 §12：星币、收藏册等永久数据）。
 *
 * <p>为什么与 {@link SeasonData} 分开成两个文件：需求文档 §4 明确赛季结束时「保留星币与
 * 收藏册」，而等级/经验/任务/分支要重置。把永久数据放在独立文件里，赛季重置只需删改赛季
 * 文件，永久数据文件连碰都不用碰 —— 这是从存储结构上消除"重置时误删永久数据"的可能，
 * 而不是靠代码里小心判断。
 *
 * <p>为什么集合用 LinkedHashSet：既要 O(1) 去重（收藏册、称号库会频繁判重），又要让
 * JSON 输出顺序稳定，便于人工比对。
 */
public class GlobalData {

    /** 当前存档结构版本。需求文档 §12 要求所有 JSON 含 schemaVersion 字段。 */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    /** 存档结构版本。 */
    public int schemaVersion = CURRENT_SCHEMA_VERSION;

    /** 玩家 UUID（字符串形式）。 */
    public String playerUuid = "";

    /**
     * 最近一次见到的玩家名。
     * 需求文档 §9 要求管理员能「查询任意玩家」，只给 UUID 无法辨认，因此记下名字。
     */
    public String lastKnownName = "";

    /** 星币余额。需求文档 §4：每升 1 级 +10 星币，永久保留、不随赛季重置。 */
    public int starCoin = 0;

    /** 已解锁的称号 ID（需求文档 §8 称号库）。 */
    public Set<String> unlockedTitles = new LinkedHashSet<>();

    /**
     * 收藏册：已解锁的彩蛋 ID（需求文档 §8 收藏册，本赛季 + 历史赛季档案）。
     * 永久保留，因此不放在赛季文件里。
     */
    public Set<String> collection = new LinkedHashSet<>();

    /**
     * 已领取过的节日口令 ID（需求文档 §10）。
     * 去重键实际是 playerUUID + 口令ID，玩家维度的一半存在这里。
     */
    public Set<String> claimedRedeemCodes = new LinkedHashSet<>();

    /**
     * 生日，格式 MM-dd（需求文档 §6：管理员录入生日，生日当天上线触发祝贺标题）。
     * 空串表示未录入。
     */
    public String birthday = "";

    /**
     * 当前佩戴的称号 ID（需求文档 §8：称号库支持佩戴；§8 还要求服务端在聊天事件里
     * 为佩戴者加前缀）。空串表示未佩戴。
     *
     * <p>为什么放在永久数据里：佩戴状态与"解锁了哪些称号"是同一件事的两面，
     * 赛季重置后称号库仍保留，佩戴状态自然也应该保留。
     */
    public String equippedTitle = "";

    /** 聊天称号显示开关（客户端偏好，服务端持久化；默认 true）。 */
    public boolean chatTitleVisible = true;

    /** 头顶称号显示开关（客户端偏好，服务端持久化；默认 true）。 */
    public boolean nametagTitleVisible = true;

    /**
     * 商店购买记录：商品 ID → 已购次数（需求文档 §9「商店定价」与限购判定）。
     *
     * <p>为什么放在永久数据里而不是赛季数据：星币是永久货币（§4），
     * 用永久货币买的东西如果购买次数随赛季清零，就会出现"每赛季都能再薅一次限购商品"，
     * 与"限购"的语义矛盾。
     */
    public Map<String, Integer> shopPurchases = new LinkedHashMap<>();

    /**
     * 最近一次"生日祝贺"的北京日期（yyyy-MM-dd）。
     *
     * <p>需求文档 §6：管理员录入生日，生日当天上线触发祝贺标题。
     * 记下日期是为了"当天只祝贺一次" —— 玩家一天里反复上下线，
     * 每上一次就刷一次标题会很快变成骚扰。
     */
    public String lastBirthdayGreeted = "";

    /**
     * 最近一次节日问候的键，格式 {@code yyyy-MM-dd|节日ID}。
     *
     * <p>需求文档 §6：可配置节日时间，登录触发问候（独立于口令系统）。
     * 与生日同样的理由：同一个节日同一天只问候一次。
     */
    public String lastFestivalGreeted = "";

    /** Gson 反序列化需要无参构造。 */
    public GlobalData() {
    }

    public GlobalData(String playerUuid) {
        this.playerUuid = playerUuid;
    }

    /**
     * 取某商品已购买次数。
     *
     * @param itemId 商品 ID
     * @return 已购次数；从未购买过时为 0
     */
    public int purchaseCount(String itemId) {
        if (itemId == null || shopPurchases == null) {
            return 0;
        }

        Integer count = shopPurchases.get(itemId);
        return count == null ? 0 : count;
    }

    /**
     * 记一次商品购买。
     *
     * @param itemId 商品 ID
     */
    public void recordPurchase(String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return;
        }

        if (shopPurchases == null) {
            shopPurchases = new LinkedHashMap<>();
        }

        shopPurchases.merge(itemId, 1, Integer::sum);
    }

    /**
     * 记录一次称号解锁。
     *
     * @param titleId 称号 ID
     * @return 本次调用是否产生了变化（用于判断要不要落盘，避免无意义写盘）
     */
    public boolean unlockTitle(String titleId) {
        return titleId != null && !titleId.isEmpty() && unlockedTitles.add(titleId);
    }

    /**
     * 记录一次彩蛋收藏。
     *
     * @param eggId 彩蛋 ID
     * @return 本次调用是否产生了变化
     */
    public boolean collect(String eggId) {
        return eggId != null && !eggId.isEmpty() && collection.add(eggId);
    }
}
