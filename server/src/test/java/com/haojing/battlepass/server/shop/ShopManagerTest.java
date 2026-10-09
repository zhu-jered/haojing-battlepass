package com.haojing.battlepass.server.shop;

import com.haojing.battlepass.common.data.RewardType;
import com.haojing.battlepass.common.data.SeasonData;
import com.haojing.battlepass.server.support.ServerTestEnv;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用途：验证战令商店的兑换规则（需求文档 §8「星币兑换装饰奖励」、§9「商店定价」）。
 *
 * <p>最关键的用例是"扣了钱就必须给货"：发放失败必须原路退款。
 * 这是商店与等级奖励最大的差别 —— 前者是玩家的既有资产，后者是白送的。
 */
class ShopManagerTest {

    @Test
    void 购买成功扣除星币并记录次数(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();
            env.data.global(id).starCoin = 100;

            ShopManager.PurchaseResult result = env.shop.purchase(id, "s_title");

            assertEquals(ShopManager.PurchaseOutcome.OK, result.outcome());
            assertEquals(30, result.coinsSpent());
            assertEquals(70, result.balanceAfter());
            assertEquals(70, env.data.global(id).starCoin);
            assertEquals(1, env.data.global(id).purchaseCount("s_title"));
            assertTrue(env.sink.sawText("TITLE"), "奖励应通过奖励出口发放");
        }
    }

    @Test
    void 星币不足时无法购买(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();
            env.data.global(id).starCoin = 29;

            ShopManager.PurchaseResult result = env.shop.purchase(id, "s_title");

            assertEquals(ShopManager.PurchaseOutcome.NOT_ENOUGH_COINS, result.outcome());
            assertEquals(29, env.data.global(id).starCoin, "失败不应扣钱");
            assertEquals(0, env.data.global(id).purchaseCount("s_title"));
        }
    }

    @Test
    void 达到限购次数后无法再买(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();
            env.data.global(id).starCoin = 1000;

            assertEquals(ShopManager.PurchaseOutcome.OK, env.shop.purchase(id, "s_title").outcome());
            ShopManager.PurchaseResult second = env.shop.purchase(id, "s_title");

            assertEquals(ShopManager.PurchaseOutcome.LIMIT_REACHED, second.outcome());
            assertEquals(970, env.data.global(id).starCoin, "第二次不应扣钱");
        }
    }

    @Test
    void 不限购的商品可以反复购买(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();

            // s_free 价格为 0、不限购，买三次应成功三次
            for (int i = 0; i < 3; i++) {
                assertEquals(ShopManager.PurchaseOutcome.OK, env.shop.purchase(id, "s_free").outcome());
            }

            assertEquals(3, env.data.global(id).purchaseCount("s_free"));
        }
    }

    @Test
    void 下架商品买不到(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();
            env.data.global(id).starCoin = 100;

            assertEquals(ShopManager.PurchaseOutcome.DISABLED, env.shop.purchase(id, "s_off").outcome());
            assertEquals(100, env.data.global(id).starCoin);
        }
    }

    @Test
    void 不存在的商品返回找不到(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();

            assertEquals(ShopManager.PurchaseOutcome.NOT_FOUND, env.shop.purchase(id, "s_nope").outcome());
            assertEquals(ShopManager.PurchaseOutcome.NOT_FOUND, env.shop.purchase(id, null).outcome());
        }
    }

    @Test
    void 发放失败必须原路退款(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();
            env.data.global(id).starCoin = 100;
            env.sink.failFor.add(RewardType.TITLE);

            ShopManager.PurchaseResult result = env.shop.purchase(id, "s_title");

            assertEquals(ShopManager.PurchaseOutcome.GRANT_FAILED, result.outcome());
            assertEquals(100, env.data.global(id).starCoin, "发放失败必须退款，玩家不能钱货两空");
            assertEquals(0, env.data.global(id).purchaseCount("s_title"), "失败的交易不计入限购次数");
        }
    }

    @Test
    void 限购次数记在永久数据里不随赛季清空(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();
            env.data.global(id).starCoin = 100;
            env.shop.purchase(id, "s_title");

            // 模拟赛季滚动：赛季数据整体重置，永久数据（含购买记录）不动。
            SeasonData season = env.data.season(id);
            season.level = 9;
            com.haojing.battlepass.server.season.SeasonResetService.resetSeasonData(season, "S2");

            assertEquals(1, env.data.global(id).purchaseCount("s_title"), "购买记录属于永久数据");
            assertEquals(ShopManager.PurchaseOutcome.LIMIT_REACHED, env.shop.purchase(id, "s_title").outcome());
        }
    }

    @Test
    void 商店配置能正确加载测试夹具(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            ShopCatalog catalog = env.shop.catalog();

            assertNotNull(catalog);
            assertEquals(4, catalog.size());
            assertEquals(3, catalog.enabledItems().size(), "s_off 已下架");
            assertEquals(40, catalog.maxPrice());
            assertEquals("t_shop_title", catalog.item("s_title").reward.titleId);
            assertEquals(3, catalog.item("s_card").limitPerPlayer);
        }
    }
}
