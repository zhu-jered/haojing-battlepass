package com.haojing.battlepass.server.shop;

import com.haojing.battlepass.common.data.Reward;

/**
 * 用途：战令商店的一件商品（需求文档 §8「战令商店：星币兑换装饰奖励」、§9「商店定价」）。
 *
 * <p>为什么用 TranslationKey（{@code nameKey}）而不是直接写中文名：
 * §1 明确规定"界面文案走 TranslationKey，不在代码里硬编码中文"。
 * 商店名会显示在客户端 GUI 上，译文放在客户端 mod 的 zh_cn.json 里，
 * 这样将来加英文或改文案都不需要动服务端配置。
 */
public class ShopItem {

    /** 商品 ID，配置内唯一；也是购买记录的键。 */
    public String id = "";

    /** 商品名翻译键，例如 {@code haojing_battlepass.shop.title.pioneer}。 */
    public String nameKey = "";

    /** 商品描述翻译键；留空表示不显示描述。 */
    public String descKey = "";

    /** 价格（星币）。 */
    public int price = 0;

    /**
     * 每个玩家的限购次数，0 表示不限购。
     *
     * <p>为什么限购要按玩家记在永久数据里：星币是永久货币，若购买次数随赛季清零，
     * 玩家每赛季都能把限购商品再买一遍，"限购"就失去了意义。
     */
    public int limitPerPlayer = 0;

    /** 是否上架。下架的商品会保留配置，但玩家买不到。 */
    public boolean enabled = true;

    /**
     * 商品分组键（例如 "titles" / "consumable" / "decor"）。
     *
     * <p>仅用于 GUI 给不同分组的商品名上色（颜色在 season.json 的 gui.shopGroupColors 里配置）；
     * 不影响购买逻辑。空串表示未分组，回退默认白色。
     */
    public String group = "";

    /** 购买后发放的奖励。 */
    public Reward reward = new Reward();

    /** @return 是否有限购。 */
    public boolean limited() {
        return limitPerPlayer > 0;
    }
}
