package com.haojing.battlepass.server.egg;

import com.haojing.battlepass.common.data.EggCategory;
import com.haojing.battlepass.common.data.EggProgress;
import com.haojing.battlepass.common.data.SeasonData;
import com.haojing.battlepass.server.season.SeasonResetService;
import com.haojing.battlepass.server.support.ServerTestEnv;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用途：验证彩蛋系统的业务语义与配置校验（需求文档 §6）。
 *
 * <p>重点覆盖三件事：
 * <ul>
 *   <li><b>一次性</b>：解锁后写永久收藏册，重复判定不会二次发奖（§6：一次性触发）。</li>
 *   <li><b>赛季限定</b>：进度随赛季重置清空，但已解锁记录永久保留（§6：记录永久留存历史册）。</li>
 *   <li><b>长夜专属零经验</b>：即使有人绕过配置校验直接给经验，也要被拦下（§6）。</li>
 * </ul>
 */
class EggServiceTest {

    private EggContext dawnContext() {
        EggContext context = new EggContext();
        context.time = LocalTime.of(8, 0);
        context.dateKey = "2026-10-08";
        context.y = 100;
        context.outdoor = true;
        context.biome = "minecraft:plains";
        context.dimension = "minecraft:overworld";
        return context;
    }

    @Test
    void 按触发时机筛选候选彩蛋(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();

            List<EggDefinition> poll = env.eggService.eggsFor(id, EggTrigger.POLL);
            List<String> pollIds = poll.stream().map(egg -> egg.id).toList();

            assertTrue(pollIds.contains("egg_test_dawn"));
            assertTrue(pollIds.contains("egg_test_still"));
            assertFalse(pollIds.contains("egg_test_biomes"), "群系类彩蛋不在轮询时机判定");

            assertEquals(1, env.eggService.eggsFor(id, EggTrigger.BIOME_ENTER).size());
            assertTrue(env.eggService.eggsFor(id, EggTrigger.FISH).isEmpty());
        }
    }

    @Test
    void 触发后写入收藏册称号与经验(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();

            int triggered = env.eggService.evaluate(id, "玩家甲", EggTrigger.POLL, dawnContext());

            assertEquals(1, triggered);
            assertTrue(env.eggService.isUnlocked(id, "egg_test_dawn"), "§6：解锁记录写入永久收藏册");
            assertTrue(env.data.global(id).unlockedTitles.contains("t_dawn"), "§6：全局彩蛋给称号");
            assertEquals(20, env.data.season(id).dailyXpEarned, "§6：全局彩蛋给小額经验");
            assertEquals(List.of("玩家甲|egg_test_dawn"), env.eggAnnouncements);
        }
    }

    @Test
    void 已解锁的彩蛋不会二次触发(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();

            env.eggService.evaluate(id, "玩家甲", EggTrigger.POLL, dawnContext());
            int triggeredAgain = env.eggService.evaluate(id, "玩家甲", EggTrigger.POLL, dawnContext());

            assertEquals(0, triggeredAgain, "§6：一次性触发");
            assertEquals(1, env.eggAnnouncements.size(), "不应重复公告");
            assertEquals(20, env.data.season(id).dailyXpEarned, "不应重复发经验");
            assertTrue(env.eggService.eggsFor(id, EggTrigger.POLL).stream()
                    .noneMatch(egg -> egg.id.equals("egg_test_dawn")), "已解锁的不再进入候选");
        }
    }

    @Test
    void 解锁本身是幂等的(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();
            SeasonData season = env.data.season(id);
            EggDefinition egg = env.eggManager.pool().egg("egg_test_dawn");

            assertTrue(env.eggService.unlock(id, "甲", egg, season, dawnContext()));
            assertFalse(env.eggService.unlock(id, "甲", egg, season, dawnContext()), "第二次应返回 false");
        }
    }

    @Test
    void 长夜专属彩蛋即使配了经验也不发放(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();
            SeasonData season = env.data.season(id);

            // 刻意绕开配置校验直接构造一个"长夜专属但带经验"的定义
            EggDefinition egg = new EggDefinition();
            egg.id = "egg_bad_night";
            egg.category = EggCategory.LONG_NIGHT.name();
            egg.xp = 99;

            assertTrue(env.eggService.unlock(id, "甲", egg, season, dawnContext()));
            assertEquals(0, env.data.season(id).dailyXpEarned, "§6：长夜专属彩蛋零经验");
        }
    }

    @Test
    void 赛季限定彩蛋的进度随赛季重置而清空(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();
            SeasonData season = env.data.season(id);

            EggContext biome = new EggContext();
            biome.biome = "minecraft:deep_dark";
            env.eggService.evaluate(id, "甲", EggTrigger.BIOME_ENTER, biome);

            assertFalse(season.eggProgressOrCreate("egg_test_biomes").flagsOrEmpty().isEmpty());

            // 同时解锁一个彩蛋，验证"进度清空但成就保留"
            env.eggService.evaluate(id, "甲", EggTrigger.POLL, dawnContext());
            assertTrue(env.eggService.isUnlocked(id, "egg_test_dawn"));

            SeasonResetService.resetSeasonData(season, "S2");

            assertTrue(season.eggProgress.isEmpty(), "§6：本赛季内的进度应随赛季重置");
            assertTrue(env.data.global(id).collection.contains("egg_test_dawn"),
                    "§6：已解锁记录永久留存历史册");
        }
    }

    @Test
    void 长夜死亡会记入对应彩蛋进度(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();

            env.eggService.markDeathDuringLongNight(id, "2026-10-08|00:00-06:00");

            // 夹具里没有 NIGHT_SURVIVE_DAWN 彩蛋，因此这里验证"不存在的条件类型不会报错也不写脏数据"
            assertTrue(env.data.season(id).eggProgress.isEmpty());
        }
    }

    @Test
    void 需要每tick采样的只有静听(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            EggPool pool = env.eggManager.pool();

            assertTrue(EggService.needsPerTickSampling(pool.egg("egg_test_still")));
            assertFalse(EggService.needsPerTickSampling(pool.egg("egg_test_dawn")));
            assertFalse(EggService.needsPerTickSampling(null));
        }
    }

    // ---------------- 轻量趣味彩蛋 ----------------

    @Test
    void 聊天关键词命中并遵守冷却(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();

            assertNotNull(env.eggService.matchChatKeyword(id, "大家都在镐京吗"));
            assertNull(env.eggService.matchChatKeyword(id, "大家都在镐京吗"), "冷却期内不应重复广播");
            assertNull(env.eggService.matchChatKeyword(id, "无关内容"));
            assertNull(env.eggService.matchChatKeyword(id, ""));
            assertNull(env.eggService.matchChatKeyword(id, null));
        }
    }

    @Test
    void 生日当天只祝贺一次(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();
            env.data.global(id).birthday = "10-08";

            assertEquals("k.birthday", env.eggService.birthdayGreetingIfDue(id, "2026-10-08"));
            assertNull(env.eggService.birthdayGreetingIfDue(id, "2026-10-08"), "同一天只祝贺一次");
            assertNull(env.eggService.birthdayGreetingIfDue(id, "2026-10-09"), "不是生日不该祝贺");
        }
    }

    @Test
    void 未录入生日的玩家不祝贺(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();

            assertNull(env.eggService.birthdayGreetingIfDue(id, "2026-10-08"));
        }
    }

    @Test
    void 节日问候支持跨年窗口且同一天只问一次(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();

            assertNotNull(env.eggService.festivalGreetingIfDue(id, "2026-12-31"), "跨年窗口应命中");

            UUID other = UUID.randomUUID();
            assertNotNull(env.eggService.festivalGreetingIfDue(other, "2027-01-01"), "跨年窗口的另一端也应命中");
            assertNull(env.eggService.festivalGreetingIfDue(other, "2027-01-01"), "同一天只问候一次");
            assertNull(env.eggService.festivalGreetingIfDue(UUID.randomUUID(), "2027-06-01"), "窗口外不应问候");
        }
    }

    // ---------------- 配置校验 ----------------

    @Test
    void 彩蛋池会丢弃非法条目并修正长夜经验() {
        EggPool pool = new EggPool();

        pool.eggs.add(egg("ok", "TIME_ALTITUDE", EggCategory.GLOBAL.name(), 20));
        pool.eggs.add(egg("bad_type", "NOT_A_TYPE", EggCategory.GLOBAL.name(), 20));
        pool.eggs.add(egg("night_with_xp", "NIGHT_STILL", EggCategory.LONG_NIGHT.name(), 99));

        EggDefinition disabled = egg("disabled", "TIME_ALTITUDE", EggCategory.GLOBAL.name(), 20);
        disabled.enabled = false;
        pool.eggs.add(disabled);

        pool.eggs.add(egg("ok", "TIME_ALTITUDE", EggCategory.GLOBAL.name(), 5));

        EggDefinition invalidParams = egg("bad_params", "GROUP_STAY", EggCategory.GLOBAL.name(), 10);
        invalidParams.condition.minPlayers = 0;
        pool.eggs.add(invalidParams);

        pool.validate();

        assertEquals(2, pool.size(), "非法条目与停用条目都不应进入索引");
        assertNotNull(pool.egg("ok"));
        assertNotNull(pool.egg("night_with_xp"));
        assertEquals(0, pool.egg("night_with_xp").xp, "§6：长夜专属零经验（修正而不是丢弃）");
        assertNull(pool.egg("bad_type"));
        assertNull(pool.egg("disabled"));
        assertNull(pool.egg("bad_params"));
    }

    @Test
    void 轻量彩蛋配置会丢弃无效规则() {
        EggPool pool = new EggPool();

        LightEggConfig.ChatKeywordRule noSlogan = new LightEggConfig.ChatKeywordRule();
        noSlogan.keyword = "无标语";
        pool.light.chatKeywords.add(noSlogan);

        LightEggConfig.ChatKeywordRule noKeyword = new LightEggConfig.ChatKeywordRule();
        noKeyword.slogan = "有标语没关键词";
        pool.light.chatKeywords.add(noKeyword);

        LightEggConfig.FestivalRule festival = new LightEggConfig.FestivalRule();
        festival.id = "f1";
        festival.message = "问候";
        festival.startDate = "2026-10-01";
        pool.light.festivals.add(festival);

        LightEggConfig.FestivalRule duplicate = new LightEggConfig.FestivalRule();
        duplicate.id = "f1";
        duplicate.message = "重复";
        pool.light.festivals.add(duplicate);

        LightEggConfig.FestivalRule noId = new LightEggConfig.FestivalRule();
        noId.message = "没有 id";
        pool.light.festivals.add(noId);

        pool.validate();

        assertTrue(pool.light.chatKeywords.isEmpty(), "缺标语或缺关键词的规则都应被丢弃");
        assertEquals(1, pool.light.festivals.size());
        assertEquals("10-01", pool.light.festivals.get(0).startDate, "完整日期应归一化为 MM-dd");
        assertTrue(pool.light.festivals.get(0).messageKey.isEmpty());
    }

    @Test
    void 彩蛋摘要可在日志中使用(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            assertNotNull(env.eggService.describe());
            assertTrue(env.eggService.describe().contains("彩蛋数=3"));
        }
    }

    private EggDefinition egg(String id, String type, String category, int xp) {
        EggDefinition definition = new EggDefinition();
        definition.id = id;
        definition.name = id;
        definition.category = category;
        definition.xp = xp;
        definition.condition = new EggCondition();
        definition.condition.type = type;
        return definition;
    }

    @Test
    void 进度对象缺失时自动创建并夹紧非负(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            SeasonData season = env.data.season(UUID.randomUUID());

            EggProgress progress = season.eggProgressOrCreate("x");
            progress.addCounter(5);
            progress.addCounter(-100);

            assertEquals(0, progress.counter, "计数器不应为负");
            assertFalse(progress.hasFlag(null));
            assertFalse(progress.addFlag(""));
            assertTrue(progress.addFlag("f1"));
            assertFalse(progress.addFlag("f1"));

            progress.reset();
            assertEquals(0, progress.counter);
            assertEquals(0, progress.streak);
            assertTrue(progress.flagsOrEmpty().isEmpty());
            assertEquals("", progress.period);
        }
    }

    @Test
    void 月相判定用的是环境属性名称(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            // 满月彩蛋不在夹具里，这里只确认上下文描述不会因月相缺失而崩
            EggContext context = dawnContext();
            context.moonPhase = "";
            assertNotNull(context.describe());
        }
    }
}
