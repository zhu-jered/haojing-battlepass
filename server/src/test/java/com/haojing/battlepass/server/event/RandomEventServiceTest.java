package com.haojing.battlepass.server.event;

import com.haojing.battlepass.common.data.RewardType;
import com.haojing.battlepass.server.support.ServerTestEnv;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用途：验证世界随机事件（需求文档 §10）的调度规则、参与判定与奖励发放。
 *
 * <p>为什么这些规则必须单测：真机上要等 90 分钟冷却、要凑几个玩家一起击杀才能验证一次，
 * 而"冷却没到却触发了""参与次数不够也发了奖"这类 bug 恰恰是最难在生产里发现的。
 */
class RandomEventServiceTest {

    /** 一个足够大的时间基准，让"上一次事件结束时间 = 0"不会立刻卡住冷却。 */
    private static final long BASE = 10_000_000L;

    @Test
    void 达到判定间隔且概率通过时触发事件(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID online = UUID.randomUUID();

            env.randomEventService.poll(BASE, List.of(online), new Random(1));

            assertTrue(env.randomEventService.isActive());
            assertNotNull(env.randomEventService.activeEvent());
            assertEquals(1, env.randomEventService.startedCount());
            assertEquals(1, env.eventAnnouncements.size());
            assertTrue(env.eventAnnouncements.get(0).startsWith("开始|"));
        }
    }

    @Test
    void 未到判定间隔不掷骰子(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            env.randomEventService.poll(BASE, List.of(UUID.randomUUID()), new Random(1));
            assertTrue(env.randomEventService.isActive());

            // 先让进行中的事件结束，再在冷却期内尝试
            env.randomEventService.finish(BASE + 10_000L);
            env.randomEventService.poll(BASE + 20_000L, List.of(UUID.randomUUID()), new Random(1));

            assertFalse(env.randomEventService.isActive(), "§10：两次事件之间必须满足最小间隔");
            assertEquals(1, env.randomEventService.startedCount());
        }
    }

    @Test
    void 概率为零时不触发(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            env.randomEventManager.config().rollChance = 0.0D;

            env.randomEventService.poll(BASE, List.of(UUID.randomUUID()), new Random(1));

            assertFalse(env.randomEventService.isActive());
        }
    }

    @Test
    void 总开关关闭时不触发(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            env.randomEventManager.config().enabled = false;

            env.randomEventService.poll(BASE, List.of(UUID.randomUUID()), new Random(1));

            assertFalse(env.randomEventService.isActive());
        }
    }

    @Test
    void 在线口径在开始时即确定参与者(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID first = UUID.randomUUID();
            UUID second = UUID.randomUUID();
            RandomEventDefinition onlineEvent = env.randomEventManager.config().event("e_double");

            env.randomEventService.start(onlineEvent, BASE, List.of(first, second));

            assertEquals(2, env.randomEventService.participants().size());
            assertEquals(2.0D, env.randomEventService.xpMultiplier(), 1e-9D, "§10：事件期间经验倍率生效");
        }
    }

    @Test
    void 动作口径需要达到目标次数(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();
            RandomEventDefinition killEvent = env.randomEventManager.config().event("e_kill");

            env.randomEventService.start(killEvent, BASE, List.of(id));

            assertTrue(env.randomEventService.participants().isEmpty(), "击杀口径在开始时不算参与");

            env.randomEventService.onAction(id, RandomEventParticipationType.KILL_ENTITY);
            assertTrue(env.randomEventService.participants().isEmpty(), "次数不够不算参与");

            env.randomEventService.onAction(id, RandomEventParticipationType.KILL_ENTITY);
            assertEquals(List.of(id), env.randomEventService.participants(), "达到目标次数后算参与");
        }
    }

    @Test
    void 无关动作不会被计入参与(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID id = UUID.randomUUID();
            RandomEventDefinition killEvent = env.randomEventManager.config().event("e_kill");

            env.randomEventService.start(killEvent, BASE, List.of(id));
            env.randomEventService.onAction(id, RandomEventParticipationType.FISH);
            env.randomEventService.onAction(id, RandomEventParticipationType.FISH);

            assertTrue(env.randomEventService.participants().isEmpty());
        }
    }

    @Test
    void 事件结束只给参与者发奖(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            UUID participant = UUID.randomUUID();
            UUID bystander = UUID.randomUUID();
            RandomEventDefinition killEvent = env.randomEventManager.config().event("e_kill");

            env.randomEventService.start(killEvent, BASE, List.of(participant, bystander));
            env.randomEventService.onAction(participant, RandomEventParticipationType.KILL_ENTITY);
            env.randomEventService.onAction(participant, RandomEventParticipationType.KILL_ENTITY);

            env.randomEventService.finish(BASE + 60_000L);

            assertFalse(env.randomEventService.isActive());
            assertEquals(1, env.sink.countOf(RewardType.STAR_COIN), "只有参与者拿到奖励");
            assertEquals(1, env.sink.countFor(participant));
            assertEquals(0, env.sink.countFor(bystander), "§10：参与判定之外的玩家不该拿奖");
            assertTrue(env.eventAnnouncements.stream().anyMatch(entry -> entry.startsWith("结束|")));
        }
    }

    @Test
    void 事件期间的经验倍率会累乘(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            RandomEventDefinition doubleXp = env.randomEventManager.config().event("e_double");
            doubleXp.effects.add(xpEffect(3.0D));

            env.randomEventService.start(doubleXp, BASE, List.of(UUID.randomUUID()));

            assertEquals(6.0D, env.randomEventService.xpMultiplier(), 1e-9D);
        }
    }

    @Test
    void 没有事件时倍率为一(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            assertEquals(1.0D, env.randomEventService.xpMultiplier(), 1e-9D);
            assertTrue(env.randomEventService.activeEffects().isEmpty());
            assertEquals(0L, env.randomEventService.remainingSeconds(BASE));
            assertNotNull(env.randomEventService.describe());
        }
    }

    @Test
    void 热重载不会打断进行中的事件(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            RandomEventDefinition definition = env.randomEventManager.config().event("e_double");
            env.randomEventService.start(definition, BASE, List.of(UUID.randomUUID()));

            env.randomEventManager.reload();

            assertTrue(env.randomEventService.isActive(), "已经开始的这一场要跑完");
            assertEquals(definition, env.randomEventService.activeEvent());
        }
    }

    @Test
    void 到点自动结束(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            RandomEventDefinition definition = env.randomEventManager.config().event("e_double");
            env.randomEventService.start(definition, BASE, List.of(UUID.randomUUID()));

            long durationMillis = definition.durationMinutes * 60_000L;
            env.randomEventService.poll(BASE + durationMillis + 1000L, List.of(), new Random(1));

            assertFalse(env.randomEventService.isActive(), "§10：事件有持续时长，到点结束");
            assertEquals(1, env.randomEventService.startedCount(), "结束本身不应立刻再开一场");
        }
    }

    @Test
    void 剩余时间随推进递减(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            RandomEventDefinition definition = env.randomEventManager.config().event("e_double");
            env.randomEventService.start(definition, BASE, List.of(UUID.randomUUID()));

            assertEquals(600L, env.randomEventService.remainingSeconds(BASE));
            assertEquals(300L, env.randomEventService.remainingSeconds(BASE + 300_000L));
        }
    }

    @Test
    void 重复开始会被忽略(@TempDir Path tmp) throws IOException {
        try (ServerTestEnv env = ServerTestEnv.create(tmp)) {
            RandomEventDefinition first = env.randomEventManager.config().event("e_double");
            RandomEventDefinition second = env.randomEventManager.config().event("e_kill");

            env.randomEventService.start(first, BASE, List.of(UUID.randomUUID()));
            env.randomEventService.start(second, BASE, List.of(UUID.randomUUID()));

            assertEquals(first, env.randomEventService.activeEvent());
            assertEquals(1, env.randomEventService.startedCount());
        }
    }

    @Test
    void 事件配置校验会夹紧非法参数(@TempDir Path tmp) throws IOException {
        RandomEventConfig config = new RandomEventConfig();
        config.checkIntervalMinutes = 0;
        config.rollChance = 5.0D;
        config.minIntervalMinutes = -1;

        RandomEventDefinition broken = new RandomEventDefinition();
        broken.id = "broken";
        broken.type = "NOT_A_TYPE";
        config.events.add(broken);

        RandomEventDefinition shortDuration = new RandomEventDefinition();
        shortDuration.id = "short";
        shortDuration.type = "DOUBLE_XP";
        shortDuration.durationMinutes = 0;
        shortDuration.effects.add(badEffect());
        config.events.add(shortDuration);

        config.validate();

        assertEquals(5, config.checkIntervalMinutes);
        assertEquals(1.0D, config.rollChance, 1e-9D);
        assertEquals(0, config.minIntervalMinutes);
        assertEquals(1, config.size(), "类型无法识别的事件应被丢弃");
        assertEquals(1, config.event("short").durationMinutes, "时长至少 1 分钟");
        assertTrue(config.event("short").effects.isEmpty(), "非法效果应被丢弃");
    }

    private EventEffect xpEffect(double multiplier) {
        EventEffect effect = new EventEffect();
        effect.kind = EventEffectKind.XP_MULTIPLIER.name();
        effect.multiplier = multiplier;
        return effect;
    }

    private EventEffect badEffect() {
        EventEffect effect = new EventEffect();
        effect.kind = EventEffectKind.SPAWN.name();
        effect.value = "";
        return effect;
    }
}
