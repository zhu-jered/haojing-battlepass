package com.haojing.battlepass.server.time;

import com.haojing.battlepass.common.ModConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 用途：全局统一的时间工具。需求文档 §2 硬性规定：所有时间判定统一使用
 * {@code ZonedDateTime.now(ZoneId.of("Asia/Shanghai"))}，与容器/主机时区无关，不依赖任何网络请求。
 *
 * <p>为什么单独抽一个类而不是各处直接调 ZonedDateTime.now()：只要有一处忘了带时区，
 * 整条时间线就会随宿主机时区漂移，而且这种 bug 在开发机上往往看不出来（开发机常常就是 +08:00）。
 * 把时区收进一个常量，任何人想绕开都得显式写出来。
 *
 * <p>为什么时间区间判断做成"传入时刻"的纯函数：这样时间逻辑可以被单元测试确定性地覆盖，
 * 不必等到半夜三点去实测长夜是否生效。
 *
 * <p>需求文档 §2 提到的「可选 NTP 校准时钟漂移」尚未实现：它被明确定为可选，
 * 且要求"失败仅打 WARN、绝不参与业务判定"，优先级低于本阶段的核心功能，已列入待办。
 */
public final class TimeUtil {

    /**
     * 服务器业务时区。需求文档 §2：与容器/主机时区无关。
     *
     * <p>为什么用 ZoneOffset 兜底：万一运行环境的 tzdata 损坏导致 Asia/Shanghai 解析失败，
     * 退回固定 +08:00 仍然在语义上正确（中国自 1991 年起不实行夏令时），
     * 比退回"系统默认时区"更安全 —— 后者会让业务时间随宿主机漂移，正是 §2 要避免的。
     */
    public static final ZoneId ZONE = resolveZone();

    private static final Logger LOGGER = LoggerFactory.getLogger(ModConstants.MOD_ID_SERVER);

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter STAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static ZoneId resolveZone() {
        try {
            return ZoneId.of("Asia/Shanghai");
        } catch (RuntimeException e) {
            // 这里不能用 LOGGER 之前的实例化顺序问题：LOGGER 是静态字段，此时可能尚未初始化，
            // 因此直接输出到标准错误，避免在类初始化期间再触发一次类初始化。
            System.err.println("[HaoJingBP] 无法加载 Asia/Shanghai 时区，已回退到固定 +08:00：" + e.getMessage());
            return ZoneOffset.ofHours(8);
        }
    }

    private TimeUtil() {
    }

    /** @return 北京时间当前时刻。 */
    public static ZonedDateTime now() {
        return ZonedDateTime.now(ZONE);
    }

    /** @return 北京时间的当前日期。 */
    public static LocalDate today() {
        return now().toLocalDate();
    }

    /** @param moment 时刻 @return yyyy-MM-dd 形式的日期键（用于判重与"是否跨天"比较）。 */
    public static String dateKey(ZonedDateTime moment) {
        return moment.toLocalDate().format(DATE_FORMAT);
    }

    /** @param date 日期 @return yyyy-MM-dd 形式的日期键。 */
    public static String dateKey(LocalDate date) {
        return date.format(DATE_FORMAT);
    }

    /** @return 北京时间的今日日期键。 */
    public static String todayKey() {
        return dateKey(now());
    }

    /** @param moment 时刻 @return 便于日志阅读的 yyyy-MM-dd HH:mm:ss 时间戳。 */
    public static String stamp(ZonedDateTime moment) {
        return moment.format(STAMP_FORMAT);
    }

    /**
     * 解析 HH:mm 形式的时间配置。对配置错误宽容处理而不是抛异常 ——
     * 一个写错的配置项不应该让整个模组加载失败。
     *
     * @param text     形如 "06:00" 或 "6:00" 的文本
     * @param fallback 解析失败时的兜底值
     * @return 解析结果；无法解析时返回 fallback 并记 WARN
     */
    public static LocalTime parseTime(String text, LocalTime fallback) {
        if (text == null) {
            return fallback;
        }

        String trimmed = text.trim();

        // 手写解析而不是用 DateTimeFormatter："6:00" 这种少写前导零的配置很常见，
        // 而 HH:mm 会直接拒绝它。宽容解析能少一堆无谓的启动失败。
        int colon = trimmed.indexOf(':');

        if (colon <= 0 || colon == trimmed.length() - 1) {
            LOGGER.warn("{} 时间配置格式非法（应为 HH:mm）：{}，已回退为 {}",
                    ModConstants.LOG_PREFIX, text, fallback);
            return fallback;
        }

        try {
            int hour = Integer.parseInt(trimmed.substring(0, colon).trim());
            int minute = Integer.parseInt(trimmed.substring(colon + 1).trim());

            if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
                throw new NumberFormatException("超出范围");
            }

            return LocalTime.of(hour, minute);
        } catch (NumberFormatException e) {
            LOGGER.warn("{} 时间配置无法解析：{}，已回退为 {}", ModConstants.LOG_PREFIX, text, fallback);
            return fallback;
        }
    }

    /**
     * 判断某时刻是否落在 [start, end) 时间区间内，支持跨零点区间（例如 22:00~06:00）。
     *
     * <p>为什么右端是开区间：需求文档 §5.2 规定「日」边界以刷新时刻为界，
     * 06:00 整应当属于新的一天，而不是仍算旧的一天。
     *
     * <p>为什么 start 与 end 相等时返回 false：那是一个零长度时段。
     * 若把它当作"全天生效"，一个手误写成的 "00:00~00:00" 就会让长夜永远开着；
     * 当作空时段则表现为"功能没生效"，是更安全的失败方向。配置加载处会额外告警。
     *
     * @param moment 待判断时刻
     * @param start  起始（含）
     * @param end    结束（不含）
     * @return 是否在区间内
     */
    public static boolean isWithin(ZonedDateTime moment, LocalTime start, LocalTime end) {
        return moment != null && isWithin(moment.toLocalTime(), start, end);
    }

    /**
     * 与 {@link #isWithin(ZonedDateTime, LocalTime, LocalTime)} 同语义，但只比较"一天中的时刻"。
     *
     * <p>为什么需要这个重载：任务过滤器（需求文档 §5.7 的"北京时间时段"条件）在判定时
     * 手上只有一个 LocalTime，没有完整时区时刻；硬凑一个 ZonedDateTime 反而会引入虚假的日期信息。
     *
     * @param time  待判断的时刻（当天）
     * @param start 起始（含）
     * @param end   结束（不含）
     * @return 是否在区间内
     */
    public static boolean isWithin(LocalTime time, LocalTime start, LocalTime end) {
        if (time == null || start == null || end == null || start.equals(end)) {
            return false;
        }

        if (start.isBefore(end)) {
            return !time.isBefore(start) && time.isBefore(end);
        }

        // 跨零点：例如 22:00~06:00，命中 [22:00,24:00) 或 [00:00,06:00)
        return !time.isBefore(start) || time.isBefore(end);
    }

    /**
     * 计算从某时刻到下一个 target 时刻的分钟数（0~1439），用于"开始前 N 分钟预告"。
     *
     * <p>注意会把秒抹掉再算：否则 05:59:59 到 06:00 会算成 0 分钟，
     * 而管理员看到日志里"距开始 0 分钟"却还没到点，会以为是 bug。
     *
     * @param moment 当前时刻
     * @param target 目标时刻（每天循环出现）
     * @return 分钟数，已到点或在同一分钟内时为 0
     */
    public static long minutesUntil(ZonedDateTime moment, LocalTime target) {
        if (target == null) {
            return Long.MAX_VALUE;
        }

        LocalTime from = moment.toLocalTime().withSecond(0).withNano(0);
        long minutes = Duration.between(from, target).toMinutes();

        if (minutes < 0) {
            minutes += 24L * 60L;
        }

        return minutes;
    }

    /**
     * 计算某时刻属于哪个「业务日」。需求文档 §5.2 规定所有「日」边界（每日任务、
     * 每日经验上限、每日计数）统一以 dailyRefreshTime 为界，而不是自然日零点。
     *
     * <p>举例：边界为 06:00 时，1 月 2 日 03:00 仍属于业务日 1 月 1 日；
     * 1 月 2 日 07:00 才算 1 月 2 日。这样"跨天补刷"的判重键才是正确的。
     *
     * @param moment      待判断时刻
     * @param dayBoundary 业务日边界时刻（例如 06:00）
     * @return yyyy-MM-dd 形式的业务日键
     */
    public static String businessDateKey(ZonedDateTime moment, LocalTime dayBoundary) {
        LocalDate date = moment.toLocalDate();

        if (dayBoundary != null && moment.toLocalTime().isBefore(dayBoundary)) {
            date = date.minusDays(1);
        }

        return dateKey(date);
    }
}
