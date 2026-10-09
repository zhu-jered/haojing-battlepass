package com.haojing.battlepass.common.net;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用途：客户端上行包的护栏（需求文档 §14："非法/异常/超大客户端数据包直接丢弃，
 * 不得导致服务端崩溃；对 C2S 包做字段校验、频率限制与幂等处理"）。
 *
 * <p>为什么把这三件事收在一个类里：它们必须**在业务之前**一起判定。
 * 如果分散到各个业务方法里，那么每加一个新动作就会漏掉其中一项
 * （最常见的是忘加频率限制，于是界面按钮被连点就把服务端刷爆）。
 * 收在一处之后，任何上行包都必须先过 {@link #checkClient} / {@link #checkAdmin}。
 *
 * <p>本类不引用任何 Minecraft 类型：频率限制与字段校验是纯逻辑，
 * 而它们恰恰是最需要单元测试的（"第 21 个包必须被限流"这种断言不可能靠手动点击验证）。
 */
public final class C2SGuard {

    /** 判定结果。 */
    public enum Verdict {
        /** 放行。 */
        OK,
        /** 动作名无法识别（协议不一致或伪造包）。 */
        UNKNOWN_ACTION,
        /** 参数不合法（缺失、过长）。 */
        BAD_ARG,
        /** 单位时间内包数超限。 */
        RATE_LIMITED,
        /** 同一动作触发过快（防连点）。 */
        TOO_FREQUENT
    }

    /** 默认窗口内允许的包数。 */
    public static final int DEFAULT_MAX_PER_WINDOW = 30;

    /** 默认窗口长度（毫秒）。 */
    public static final long DEFAULT_WINDOW_MILLIS = 1000L;

    /** 默认的同一动作最小间隔（毫秒）。 */
    public static final long DEFAULT_MIN_ACTION_INTERVAL_MILLIS = 150L;

    /** 参数最大长度。 */
    public static final int MAX_ARG_LENGTH = 64;

    /** 管理动作取值最大长度。 */
    public static final int MAX_VALUE_LENGTH = 128;

    private final int maxPerWindow;
    private final long windowMillis;
    private final long minActionIntervalMillis;
    private final int maxArgLength;

    /** 玩家 → 当前窗口计数。 */
    private final Map<UUID, Window> windows = new ConcurrentHashMap<>();

    /** 玩家|动作 → 上次通过的时刻。 */
    private final Map<String, Long> lastActionAt = new ConcurrentHashMap<>();

    public C2SGuard() {
        this(DEFAULT_MAX_PER_WINDOW, DEFAULT_WINDOW_MILLIS, DEFAULT_MIN_ACTION_INTERVAL_MILLIS, MAX_ARG_LENGTH);
    }

    /**
     * @param maxPerWindow            窗口内允许的包数
     * @param windowMillis            窗口长度
     * @param minActionIntervalMillis 同一动作的最小间隔
     * @param maxArgLength            参数最大长度
     */
    public C2SGuard(int maxPerWindow, long windowMillis, long minActionIntervalMillis, int maxArgLength) {
        this.maxPerWindow = Math.max(1, maxPerWindow);
        this.windowMillis = Math.max(1L, windowMillis);
        this.minActionIntervalMillis = Math.max(0L, minActionIntervalMillis);
        this.maxArgLength = Math.max(1, maxArgLength);
    }

    /**
     * 校验一个玩家动作包。
     *
     * @param playerUuid 玩家
     * @param action     动作名
     * @param arg        参数
     * @param nowMillis  当前毫秒
     * @return 判定结果
     */
    public Verdict checkClient(UUID playerUuid, String action, String arg, long nowMillis) {
        NetActions.ClientAction parsed = NetActions.ClientAction.fromName(action);

        if (parsed == null) {
            return Verdict.UNKNOWN_ACTION;
        }

        if (arg != null && arg.length() > maxArgLength) {
            return Verdict.BAD_ARG;
        }

        if (parsed.requiresArg() && (arg == null || arg.isBlank())) {
            return Verdict.BAD_ARG;
        }

        return rateCheck(playerUuid, parsed.name(), nowMillis);
    }

    /**
     * 校验一个管理面板动作包。
     *
     * <p>注意：**权限**不在这里判断 —— 那属于 Minecraft 的 OP 体系（§9 要求
     * "需 OP 权限 + 服务端二次校验"），由网络层用命令源的权限判据处理。
     * 这里只管"包本身合不合法、发得太快不快"。
     *
     * @param playerUuid 玩家
     * @param action     动作名
     * @param arg        参数
     * @param value      取值
     * @param nowMillis  当前毫秒
     * @return 判定结果
     */
    public Verdict checkAdmin(UUID playerUuid, String action, String arg, String value, long nowMillis) {
        NetActions.AdminAction parsed = NetActions.AdminAction.fromName(action);

        if (parsed == null) {
            return Verdict.UNKNOWN_ACTION;
        }

        if (tooLong(arg, maxArgLength) || tooLong(value, MAX_VALUE_LENGTH)) {
            return Verdict.BAD_ARG;
        }

        return rateCheck(playerUuid, parsed.name(), nowMillis);
    }

    private Verdict rateCheck(UUID playerUuid, String actionName, long nowMillis) {
        if (playerUuid == null) {
            return Verdict.BAD_ARG;
        }

        // 一、窗口总量限制：挡住"疯狂连点"与简单的脚本刷包。
        Window window = windows.computeIfAbsent(playerUuid, key -> new Window(nowMillis));

        synchronized (window) {
            if (nowMillis - window.start >= windowMillis) {
                window.start = nowMillis;
                window.count = 0;
            }

            if (++window.count > maxPerWindow) {
                return Verdict.RATE_LIMITED;
            }
        }

        // 二、同一动作的最小间隔：挡住"同一个按钮被按住不放"。
        if (minActionIntervalMillis > 0) {
            String key = playerUuid + "|" + actionName;
            Long previous = lastActionAt.get(key);

            if (previous != null && nowMillis - previous < minActionIntervalMillis) {
                return Verdict.TOO_FREQUENT;
            }

            lastActionAt.put(key, nowMillis);
        }

        return Verdict.OK;
    }

    /** 玩家退出时清理状态，避免内存随"见过的玩家数"增长。 */
    public void forget(UUID playerUuid) {
        if (playerUuid == null) {
            return;
        }

        windows.remove(playerUuid);
        String prefix = playerUuid + "|";
        lastActionAt.keySet().removeIf(key -> key.startsWith(prefix));
    }

    /** @return 当前被跟踪的玩家数（诊断用）。 */
    public int trackedPlayers() {
        return windows.size();
    }

    private static boolean tooLong(String value, int max) {
        return value != null && value.length() > max;
    }

    /** 单个玩家的窗口状态。 */
    private static final class Window {

        private long start;
        private int count;

        private Window(long start) {
            this.start = start;
        }
    }
}
