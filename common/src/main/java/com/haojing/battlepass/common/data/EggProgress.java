package com.haojing.battlepass.common.data;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 用途：单个彩蛋在某个玩家身上的"赛季内进度"。需求文档 §6 的 13 个彩蛋里，
 * 绝大多数都不是"一次动作命中即触发"，而是要跨时间累积：
 * <ul>
 *   <li>月下筑者：累计放置 100 个方块</li>
 *   <li>大地勘探者：本赛季内分别进入三种洞穴群系（需要记住"进过哪几种"）</li>
 *   <li>社团同游：同群系连续停留 5 分钟（中途分离超 10 秒则重置）</li>
 *   <li>守夜人：连续 3 个长夜窗口都满足条件（需要记住"哪几个窗口算过"）</li>
 *   <li>长夜微光 / 静听：连续满足若干秒</li>
 * </ul>
 *
 * <p>为什么把这些进度放在**赛季数据**里：§4 规定赛季结束时重置任务与进度，
 * 而 §6 的"大地勘探者"明确写着"本赛季内"；赛季限定彩蛋更是必须随赛季失效。
 * 已经解锁的彩蛋本身记在永久数据的收藏册里（§6：记录永久留存历史册），
 * 两件事分开存：进度可重置，成就永久保留。
 *
 * <p>字段的含义是"通用槽位"而不是每种彩蛋各有专属字段：13 个彩蛋要各自一套字段，
 * 序列化出来的 JSON 会有上百个字段、绝大多数永远为 0。统一用
 * 计数器 + 连续计数 + 标记集合 + 两个时间戳，每个彩蛋按自己的语义解释它们
 * （具体用法在 {@code EggEvaluator} 的每个分支里逐一注明，并有单元测试锁住）。
 */
public class EggProgress {

    /** 通用累计量。例如"已放置方块数"、"本长夜窗口在线秒数"。 */
    public int counter = 0;

    /** 连续计数。例如"连续满足条件的长夜窗口数"。 */
    public int streak = 0;

    /**
     * 通用标记集合。例如"已进入过的洞穴群系 key"、"已计入的长夜窗口键"、
     * "已记录待观察的树苗坐标"。
     *
     * <p>为什么用集合而不是计数：这三种语义都要求"同一个东西只算一次"
     * （同一个群系进两次不算两种、同一个窗口不重复计、同一个坐标只观察一次）。
     */
    public Set<String> flags = new LinkedHashSet<>();

    /**
     * 最近一次"条件满足"的墙上时钟毫秒数（{@code System.currentTimeMillis()}）。
     *
     * <p>为什么用墙上时钟而不是 tick：tick 计数会在服务端重启后归零，
     * 而这些彩蛋的连续性判定（分离 10 秒、长夜窗口）跨越"可能重启"的时间段；
     * 墙上时钟在重启后仍然可比较。它只用于算**时间差**，不参与任何业务时间判定
     * （业务时间一律走 TimeUtil 的北京时区）。
     */
    public long lastActiveMillis = 0;

    /**
     * 当前所处的"周期键"。例如守夜人用的是长夜窗口键（开始日 + 结束时刻）。
     *
     * <p>为什么需要它：连续类彩蛋的累计量必须"跨周期归零"。
     * 玩家在一个长夜窗口里在线 20 分钟后掉线、第二天只在线 1 分钟就等到天亮，
     * 若累计量不区分周期，就会把两个窗口的时长加在一起误判为达成。
     * 周期键一变就清零，是唯一不会算错的简单做法。
     */
    public String period = "";

    /** Gson 反序列化需要无参构造。 */
    public EggProgress() {
    }

    /** @return 强类型标记集合；字段缺失时为 null，这里保证非 null。 */
    public Set<String> flagsOrEmpty() {
        if (flags == null) {
            flags = new LinkedHashSet<>();
        }

        return flags;
    }

    /**
     * 记录一个只算一次的标记。
     *
     * @param flag 标记
     * @return true 表示本次是首次记录
     */
    public boolean addFlag(String flag) {
        return flag != null && !flag.isEmpty() && flagsOrEmpty().add(flag);
    }

    /** @return 是否已经有该标记。 */
    public boolean hasFlag(String flag) {
        return flag != null && flagsOrEmpty().contains(flag);
    }

    /** 把计数器加上一个增量，并夹紧到非负。 */
    public void addCounter(int delta) {
        counter = Math.max(0, counter + delta);
    }

    /** 清零全部进度（赛季滚动或管理员重置某个彩蛋时使用）。 */
    public void reset() {
        counter = 0;
        streak = 0;
        lastActiveMillis = 0;
        period = "";
        flagsOrEmpty().clear();
    }
}
