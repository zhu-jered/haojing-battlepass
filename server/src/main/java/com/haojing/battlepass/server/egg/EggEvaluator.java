package com.haojing.battlepass.server.egg;

import com.haojing.battlepass.common.data.EggProgress;
import com.haojing.battlepass.server.time.TimeUtil;

import java.time.Duration;
import java.time.LocalTime;

/**
 * 用途：把 {@link EggContext}（当前状态）与 {@link EggProgress}（历史进度）算成
 * "这次有没有触发" —— 需求文档 §6 那 10 条量化定义全部实现在这里。
 *
 * <p>为什么一个条件一个分支、并逐条注明"进度字段怎么用"：§6 要求"必须按此实现，
 * 不得自行解释"，那么每一处数值的来源与状态机语义就必须可追溯到文档原句。
 * 分支上方的注释就是那句话，下面的单元测试则是它的可执行版本。
 *
 * <p>为什么刻意不引用任何 Minecraft 类型：见 {@link EggContext} 的说明。
 * 本类是全工程最需要回归保护的地方之一（13 个状态机 + 跨天/跨窗口的边界），
 * 必须能脱离服务端运行。
 */
public final class EggEvaluator {

    /** 一秒对应的 tick 数。 */
    static final int TICKS_PER_SECOND = 20;

    private EggEvaluator() {
    }

    /**
     * 执行一次判定，必要时就地修改进度。
     *
     * @param definition 彩蛋定义
     * @param trigger    本次判定的时机
     * @param context    当前状态
     * @param progress   该玩家在这个彩蛋上的历史进度
     * @return 判定结果
     */
    public static EggOutcome evaluate(EggDefinition definition, EggTrigger trigger,
                                      EggContext context, EggProgress progress) {
        if (definition == null || definition.condition == null || context == null || progress == null) {
            return EggOutcome.NONE;
        }

        EggConditionType type = definition.condition.typeOrNull();

        if (type == null) {
            return EggOutcome.NONE;
        }

        EggCondition condition = definition.condition;

        switch (type) {
            case TIME_ALTITUDE:
                return timeAndAltitude(trigger, condition, context);
            case MOON_FULL_PLACE:
                return moonFullPlace(trigger, condition, context, progress);
            case RAIN_RIVER_FISH:
                return rainRiverFish(trigger, condition, context);
            case BIOME_SET:
                return biomeSet(trigger, condition, context, progress);
            case GROUP_STAY:
                return groupStay(trigger, condition, context, progress);
            case SAPLING_GROW:
                return saplingGrow(trigger, condition, context, progress);
            case NIGHT_STREAK:
                return nightStreak(trigger, condition, context, progress);
            case NIGHT_TORCH_OUTDOOR:
                return nightTorchOutdoor(trigger, condition, context, progress);
            case NIGHT_SURVIVE_DAWN:
                return nightSurviveDawn(trigger, context, progress);
            case NIGHT_STILL:
                return nightStill(trigger, condition, context, progress);
            default:
                return EggOutcome.NONE;
        }
    }

    /**
     * §6 长安拂晓：北京时间 08:00±5 分钟，玩家 Y≥90 且该坐标天空可达（户外）。
     *
     * <p>瞬时条件，不需要进度。跨零点也正确（差值取 min(d, 1440-d)）。
     */
    private static EggOutcome timeAndAltitude(EggTrigger trigger, EggCondition condition, EggContext context) {
        if (trigger != EggTrigger.POLL || context.time == null) {
            return EggOutcome.NONE;
        }

        LocalTime target = TimeUtil.parseTime(condition.time, LocalTime.of(8, 0));
        LocalTime now = context.time.withSecond(0).withNano(0);

        long diff = Math.abs(Duration.between(now, target).toMinutes());
        diff = Math.min(diff, 24L * 60L - diff);

        if (diff > condition.toleranceMin) {
            return EggOutcome.NONE;
        }

        if (context.y < condition.minY) {
            return EggOutcome.NONE;
        }

        if (condition.requireOutdoor && !context.outdoor) {
            return EggOutcome.NONE;
        }

        return EggOutcome.TRIGGERED;
    }

    /**
     * §6 月下筑者：满月且夜晚，累计放置 100 方块，触发瞬间半径 16 格内无敌对生物。
     *
     * <p>进度语义：{@code counter} = 在"满月 + 夜晚"期间累计放置的方块数。
     * 放置事件只负责累计（便宜），"触发瞬间周边无敌对生物"这个条件必须在一个
     * **同时能看到实体数量**的采样里判，所以达标后的触发判定放在 POLL 阶段。
     */
    private static EggOutcome moonFullPlace(EggTrigger trigger, EggCondition condition,
                                            EggContext context, EggProgress progress) {
        if (trigger == EggTrigger.PLACE_BLOCK) {
            if (!"FULL_MOON".equalsIgnoreCase(context.moonPhase) || !context.night) {
                return EggOutcome.NONE;
            }

            if (progress.counter >= condition.placeTarget) {
                // 已经攒够，正在等一个"周边安全"的瞬间；继续数下去没有意义。
                return EggOutcome.NONE;
            }

            progress.addCounter(1);
            return EggOutcome.PROGRESS;
        }

        if (trigger == EggTrigger.POLL) {
            if (progress.counter >= condition.placeTarget && context.nearbyHostile <= 0) {
                return EggOutcome.TRIGGERED;
            }
        }

        return EggOutcome.NONE;
    }

    /**
     * §6 鱼信：雷雨天气 + 河流群系 + 钓鱼成功一次。
     *
     * <p>瞬时条件。"雷雨"默认要求 thundering（原版的雷雨与下雨是两个标记），
     * 可用 {@code requireThunder} 关掉变成"下雨即可"。
     */
    private static EggOutcome rainRiverFish(EggTrigger trigger, EggCondition condition, EggContext context) {
        if (trigger != EggTrigger.FISH) {
            return EggOutcome.NONE;
        }

        boolean weatherOk = condition.requireThunder ? context.thundering : (context.raining || context.thundering);

        if (!weatherOk) {
            return EggOutcome.NONE;
        }

        return condition.biome.equalsIgnoreCase(context.biome) ? EggOutcome.TRIGGERED : EggOutcome.NONE;
    }

    /**
     * §6 大地勘探者：本赛季内分别进入深邃洞穴、繁茂洞穴、溶洞三种群系。
     *
     * <p>进度语义：{@code flags} 里每个 {@code biome:<id>} 表示"这一季进过该群系"。
     * 集齐配置里的全部群系即触发。进度随赛季重置（§6 明写"本赛季内"）。
     */
    private static EggOutcome biomeSet(EggTrigger trigger, EggCondition condition,
                                       EggContext context, EggProgress progress) {
        if (trigger != EggTrigger.BIOME_ENTER || context.biome == null || context.biome.isEmpty()) {
            return EggOutcome.NONE;
        }

        boolean required = condition.biomes.stream().anyMatch(entry -> entry.equalsIgnoreCase(context.biome));

        if (!required) {
            return EggOutcome.NONE;
        }

        boolean added = progress.addFlag("biome:" + context.biome);

        if (!added) {
            return EggOutcome.NONE;
        }

        for (String entry : condition.biomes) {
            if (!progress.hasFlag("biome:" + entry)) {
                return EggOutcome.PROGRESS;
            }
        }

        return EggOutcome.TRIGGERED;
    }

    /**
     * §6 社团同游：半径 32 格内 ≥3 名协会玩家，同群系连续停留 5 分钟
     * （中途分离超 10 秒则重置）。
     *
     * <p>进度语义：{@code counter} = 已连续满足的**毫秒数**；
     * {@code lastActiveMillis} = 上一次"条件满足"的墙上时刻。
     * 为什么用毫秒而不是 tick：10 秒容忍度要靠两次采样之间的真实时间差来判，
     * 而这个差值与采样频率、服务端卡顿都有关，用墙上时钟最稳。
     *
     * <p>同伴人数由 MC 侧只统计"同群系且距离内"的玩家（§6 同时要求"同群系连续停留"），
     * 因此这里的判据只比较人数。
     */
    private static EggOutcome groupStay(EggTrigger trigger, EggCondition condition,
                                        EggContext context, EggProgress progress) {
        if (trigger != EggTrigger.POLL) {
            return EggOutcome.NONE;
        }

        boolean satisfied = context.nearbyPlayers >= condition.minPlayers;
        long toleranceMillis = Math.max(0, condition.breakToleranceSeconds) * 1000L;
        long requiredMillis = Math.max(1, condition.staySeconds) * 1000L;
        boolean changed = false;

        if (satisfied) {
            if (progress.lastActiveMillis > 0) {
                long gap = context.nowMillis - progress.lastActiveMillis;

                if (gap > toleranceMillis) {
                    // §6：中途分离超 10 秒则重置 —— 之前攒的时间全部作废。
                    progress.counter = 0;
                    changed = true;
                } else if (gap > 0) {
                    progress.counter += (int) Math.min(Integer.MAX_VALUE - progress.counter, gap);
                    changed = true;
                }
            }

            progress.lastActiveMillis = context.nowMillis;

            if (progress.counter >= requiredMillis) {
                return EggOutcome.TRIGGERED;
            }

            return EggOutcome.of(changed);
        }

        // 不满足：只有超过容忍时间才清零（短暂走出 32 格或换个群系又回来，不应作废）。
        if (progress.lastActiveMillis > 0 && context.nowMillis - progress.lastActiveMillis > toleranceMillis) {
            progress.counter = 0;
            progress.lastActiveMillis = 0;
            return EggOutcome.PROGRESS;
        }

        return EggOutcome.NONE;
    }

    /**
     * §6 樱落归镐：在樱花林群系种下树苗并成功生长。
     *
     * <p>进度语义：{@code flags} 里 {@code sapling:<维度>} 表示"在这个维度、这个群系里种过树苗"。
     * **具体坐标由 MC 侧在内存里跟踪**（最多 8 个），因为"树苗什么时候长成树"
     * 只能由事件侧轮询方块状态来判定，而把这些坐标写进存档既没意义（重启后玩家可以再种）
     * 又会让配置/存档里出现一堆易碎的坐标字符串。
     */
    private static EggOutcome saplingGrow(EggTrigger trigger, EggCondition condition,
                                          EggContext context, EggProgress progress) {
        if (trigger == EggTrigger.PLACE_BLOCK) {
            if (!condition.biome.equalsIgnoreCase(context.biome)) {
                return EggOutcome.NONE;
            }

            if (context.placedBlockId == null || !context.placedBlockId.contains("sapling")) {
                // 只关心树苗；其他方块不登记（否则会出现"在樱花林放石头也算"的荒谬结果）。
                return EggOutcome.NONE;
            }

            return EggOutcome.of(progress.addFlag("sapling:" + context.dimension));
        }

        if (trigger == EggTrigger.SAPLING_GROWN && context.saplingGrown) {
            return EggOutcome.TRIGGERED;
        }

        return EggOutcome.NONE;
    }

    /**
     * §6 守夜人：连续 3 个长夜窗口，每段在线 ≥10 分钟且在线至 06:00。
     *
     * <p>进度语义：{@code counter} = **本窗口内**累计在线 tick；
     * {@code period} = 当前长夜窗口键（换窗口即归零，见 {@code EggProgress#period}）；
     * {@code streak} = 已经达成的连续窗口数。
     *
     * <p>注意"在线至 06:00"由 MC 侧保证 —— 窗口结束事件只对**那一刻在线**的玩家派发，
     * 中途退出的玩家根本收不到这个事件，因此 counter 不会再涨，也就不会被算作一个窗口。
     */
    private static EggOutcome nightStreak(EggTrigger trigger, EggCondition condition,
                                          EggContext context, EggProgress progress) {
        if (trigger == EggTrigger.POLL) {
            if (!context.longNight) {
                return EggOutcome.NONE;
            }

            if (context.periodKey != null && !context.periodKey.isEmpty()
                    && !context.periodKey.equals(progress.period)) {
                // 进入了一个新的长夜窗口：上一个窗口的时长不能累计到这一个里。
                progress.period = context.periodKey;
                progress.counter = 0;
            }

            progress.addCounter(Math.max(1, context.deltaTicks));
            return EggOutcome.PROGRESS;
        }

        if (trigger == EggTrigger.NIGHT_WINDOW_END) {
            int requiredTicks = Math.max(0, condition.minOnlineSeconds) * TICKS_PER_SECOND;
            boolean qualified = progress.counter >= requiredTicks;
            progress.counter = 0;

            if (!qualified) {
                // 这个窗口没待够，连续记录中断（§6 要求"连续"）。
                boolean hadStreak = progress.streak > 0;
                progress.streak = 0;
                return EggOutcome.of(hadStreak);
            }

            progress.streak++;

            if (progress.streak >= Math.max(1, condition.nights)) {
                return EggOutcome.TRIGGERED;
            }

            return EggOutcome.PROGRESS;
        }

        return EggOutcome.NONE;
    }

    /**
     * §6 长夜微光：长夜内四件防具位为空 + 手持火把 + 户外连续停留 60 秒。
     *
     * <p>进度语义：{@code counter} = 连续满足的 tick 数，任一条件不满足立即归零
     * （§6 要求"连续停留"，没有容忍度）。
     */
    private static EggOutcome nightTorchOutdoor(EggTrigger trigger, EggCondition condition,
                                                EggContext context, EggProgress progress) {
        if (trigger != EggTrigger.POLL) {
            return EggOutcome.NONE;
        }

        boolean satisfied = context.longNight && context.armorEmpty && context.holdingTorch && context.outdoor;

        if (!satisfied) {
            if (progress.counter != 0) {
                progress.counter = 0;
                return EggOutcome.PROGRESS;
            }

            return EggOutcome.NONE;
        }

        progress.addCounter(Math.max(1, context.deltaTicks));

        if (progress.counter >= Math.max(1, condition.holdSeconds) * TICKS_PER_SECOND) {
            return EggOutcome.TRIGGERED;
        }

        return EggOutcome.PROGRESS;
    }

    /**
     * §6 晨归：长夜内保持存活至 06:00 且当时处于户外。
     *
     * <p>进度语义：{@code flags} 里的 {@code died:<窗口键>} 表示"这个长夜里死过"。
     * 死亡由 MC 侧在玩家死亡事件里写入（长夜期间才记），因此"保持存活"这条是真的被检查了，
     * 而不是"只要在线就算"。
     */
    private static EggOutcome nightSurviveDawn(EggTrigger trigger, EggContext context, EggProgress progress) {
        if (trigger != EggTrigger.NIGHT_WINDOW_END || !context.onlineAtWindowEnd) {
            return EggOutcome.NONE;
        }

        if (context.periodKey != null && !context.periodKey.isEmpty()
                && progress.hasFlag("died:" + context.periodKey)) {
            return EggOutcome.NONE;
        }

        return context.outdoor ? EggOutcome.TRIGGERED : EggOutcome.NONE;
    }

    /**
     * §6 静听：长夜内每 tick 位移 &lt; 0.01、无破坏/放置/攻击行为，持续 5 分钟。
     *
     * <p>进度语义：{@code counter} = 连续满足的 tick 数；位移阈值按采样跨度折算
     * （{@code maxMovePerTick × deltaTicks}），因此无论按每 tick 还是每 20 tick 采样，
     * 判据的物理含义都是"每 tick 位移 &lt; 0.01 格"。
     *
     * <p>这是唯一一个按**每 tick** 采样的彩蛋：§6 明确写了"每 tick 位移"，
     * 而 §13 只要求"轮询类检测统一每 20 tick"。两者冲突时以 §6 的量化定义为准，
     * 并且 MC 侧只对"长夜中 + 该彩蛋尚未解锁"的玩家做一次浮点比较，开销可忽略。
     */
    private static EggOutcome nightStill(EggTrigger trigger, EggCondition condition,
                                         EggContext context, EggProgress progress) {
        if (trigger != EggTrigger.POLL) {
            return EggOutcome.NONE;
        }

        int delta = Math.max(1, context.deltaTicks);
        boolean satisfied = context.longNight
                && !context.acted
                && context.movedDistance < condition.maxMovePerTick * delta;

        if (!satisfied) {
            if (progress.counter != 0) {
                progress.counter = 0;
                return EggOutcome.PROGRESS;
            }

            return EggOutcome.NONE;
        }

        progress.addCounter(delta);

        if (progress.counter >= Math.max(1, condition.stillSeconds) * TICKS_PER_SECOND) {
            return EggOutcome.TRIGGERED;
        }

        return EggOutcome.PROGRESS;
    }
}
