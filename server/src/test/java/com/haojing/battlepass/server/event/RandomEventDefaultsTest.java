package com.haojing.battlepass.server.event;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用途：钉住内置默认事件配置（default_events.json）的自洽性。
 *
 * <p>为什么需要它：默认事件是玩家开箱即见的内容，一旦写坏（ID 重复、类型拼错、
 * 效果缺 value、权重溢出）就会在真机上以"事件不触发/刷不出怪/校验被丢弃"的形式暴露，
 * 而且往往要等冷却周期才被发现。这里是纯资源解析 + 校验，毫秒级跑完。
 */
class RandomEventDefaultsTest {

    private static final Gson GSON = new GsonBuilder().create();

    @Test
    void 内置默认事件全部通过校验且无重复() throws Exception {
        RandomEventConfig config = parseDefaults();

        assertNotNull(config, "内置默认事件资源应可解析");
        assertEquals(9, config.size(), "内置默认事件应有 9 个（5 个历史 + 4 个 2026-10 新增）");

        Set<String> ids = new HashSet<>();

        for (RandomEventDefinition event : config.all()) {
            assertTrue(ids.add(event.id), "事件 ID 重复：" + event.id);
            assertNotNull(event.typeOrNull(), "事件类型无法识别：" + event.id);
            assertTrue(event.weight >= 1 && event.weight <= 100, "事件权重应被夹紧到 1~100：" + event.id);
            assertTrue(event.durationMinutes >= 1, "事件时长至少 1 分钟：" + event.id);

            assertTrue(event.effects != null && !event.effects.isEmpty(),
                    "事件至少应有一条效果：" + event.id);

            for (EventEffect effect : event.effects) {
                assertEquals(null, effect.validateError(), "事件效果非法：" + event.id + " / " + effect);
            }

            assertTrue(event.rewards != null && !event.rewards.isEmpty(),
                    "事件至少应有一条奖励：" + event.id);
        }

        // 新事件按预期全部存在，且参与判定覆盖五种动作口径。
        assertNotNull(config.event("ev_mob_surge"));
        assertNotNull(config.event("ev_pillager_raid"));
        assertNotNull(config.event("ev_harvest_festival"));
        assertNotNull(config.event("ev_fishing_derby"));
        assertNotNull(config.event("ev_mining_rush"));
        assertEquals(RandomEventParticipationType.KILL_ENTITY,
                config.event("ev_pillager_raid").participation.typeOrDefault());
        assertEquals(RandomEventParticipationType.BREAK_BLOCK,
                config.event("ev_harvest_festival").participation.typeOrDefault());
        assertEquals(RandomEventParticipationType.FISH,
                config.event("ev_fishing_derby").participation.typeOrDefault());
        assertEquals(RandomEventParticipationType.BREAK_BLOCK,
                config.event("ev_mining_rush").participation.typeOrDefault());
    }

    @Test
    void 新增事件使用真实存在的原版效果与实体() throws Exception {
        RandomEventConfig config = parseDefaults();

        List<EventEffect> raidEffects = config.event("ev_pillager_raid").effects;
        List<String> spawnValues = raidEffects.stream()
                .filter(effect -> effect.kindOrNull() == EventEffectKind.SPAWN)
                .map(effect -> effect.value)
                .toList();
        assertTrue(spawnValues.contains("minecraft:pillager"), "掠夺者入侵应刷掠夺者");
        assertTrue(spawnValues.contains("minecraft:vindicator"), "掠夺者入侵应刷卫道士");

        List<EventEffect> fishingEffects = config.event("ev_fishing_derby").effects;
        assertTrue(fishingEffects.stream().anyMatch(effect ->
                effect.kindOrNull() == EventEffectKind.POTION
                        && "minecraft:luck".equals(effect.value)), "钓鱼大赛应给幸运效果");

        List<EventEffect> miningEffects = config.event("ev_mining_rush").effects;
        assertTrue(miningEffects.stream().anyMatch(effect ->
                effect.kindOrNull() == EventEffectKind.POTION
                        && "minecraft:haste".equals(effect.value)), "挖矿热潮应给急迫效果");
    }

    private RandomEventConfig parseDefaults() throws Exception {
        try (InputStream in = RandomEventDefaultsTest.class
                .getResourceAsStream("/haojing_battlepass/default_events.json")) {
            assertNotNull(in, "内置默认事件资源不存在");
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            RandomEventConfig config = GSON.fromJson(json, RandomEventConfig.class);
            assertNotNull(config, "内置默认事件解析结果为空");
            config.validate();
            return config;
        }
    }
}
