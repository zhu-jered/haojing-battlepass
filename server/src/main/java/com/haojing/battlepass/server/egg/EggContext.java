package com.haojing.battlepass.server.egg;

import java.time.LocalTime;

/**
 * 用途：一次彩蛋判定的"上下文"—— 把 Minecraft 里的世界状态翻译成纯数据。
 * 与任务系统的 {@code TaskFilterContext} 是同一个思路。
 *
 * <p>为什么必须这样分层：13 个彩蛋里有一半是"跨时间累积 + 中途可被打破"的状态机
 * （连续 5 分钟同群系停留、分离 10 秒重置、连续 3 个长夜窗口、静止 5 分钟…），
 * 它们是最容易写错、也最难在真机上复现的部分。把判定收进
 * {@link EggEvaluator} 之后，这些边界都能用单元测试毫秒级覆盖，
 * 而不必真的在游戏里静坐 5 分钟、或者等三个长夜。
 *
 * <p>本类是**可变**的简单载体（字段 public，直接 new 出来按需赋值）：
 * 它每次判定都会重新构造，没有并发共享，用 public 字段比 20 个 setter 更省事，
 * 与项目里其它 DTO 的做法一致。
 */
public class EggContext {

    /** 北京时间（判定"08:00±5 分钟"这类条件用）。 */
    public LocalTime time;

    /** 自然日键（yyyy-MM-dd，北京时区）。 */
    public String dateKey = "";

    /** 墙上时钟毫秒数，用于算时间差（见 {@code EggProgress#lastActiveMillis}）。 */
    public long nowMillis = System.currentTimeMillis();

    /** 本次采样覆盖的 tick 数（每 tick 采样为 1，每 20 tick 采样为 20）。 */
    public int deltaTicks = 1;

    /** 玩家 Y 坐标。 */
    public int y = 0;

    /** 是否天空可达（§6 的"户外"判据）。 */
    public boolean outdoor = false;

    /** 群系 ID（{@code minecraft:river} 形式）。 */
    public String biome = "";

    /** 维度 ID。 */
    public String dimension = "";

    /** 月相名（{@code FULL_MOON} 等），来自 1.21.11 的环境属性。 */
    public String moonPhase = "";

    /** 是否夜晚。 */
    public boolean night = false;

    /** 是否正在下雨。 */
    public boolean raining = false;

    /** 是否雷雨（thunderstorm）。 */
    public boolean thundering = false;

    /** 当前是否处于长夜时段。 */
    public boolean longNight = false;

    /** 长夜周期键（长夜未进行时为空串），见 {@code EggProgress#period}。 */
    public String periodKey = "";

    /** 半径内（同群系）的协会玩家数，**含自己**。 */
    public int nearbyPlayers = 0;

    /** 半径内的敌对生物数量。 */
    public int nearbyHostile = 0;

    /** 四件防具位是否全空。 */
    public boolean armorEmpty = false;

    /** 主手是否拿着火把。 */
    public boolean holdingTorch = false;

    /** 上一次采样周期内的位移（格）。 */
    public double movedDistance = 0.0D;

    /** 上一次采样周期内是否有破坏/放置/攻击行为。 */
    public boolean acted = false;

    /** 本次事件涉及的方块 ID（放置类条件用）。 */
    public String placedBlockId = "";

    /** 是否检测到"已记录的树苗长成了树"（樱落归镐）。 */
    public boolean saplingGrown = false;

    /** 长夜窗口结束这一刻玩家是否在线（由 MC 侧只对在线玩家派发窗口结束事件）。 */
    public boolean onlineAtWindowEnd = false;

    /** @return 便于日志排查的一行摘要。 */
    public String describe() {
        return "biome=" + biome + " y=" + y + " outdoor=" + outdoor + " moon=" + moonPhase
                + " night=" + night + " rain=" + raining + "/" + thundering
                + " longNight=" + longNight + " 同伴=" + nearbyPlayers + " 敌对=" + nearbyHostile;
    }
}
