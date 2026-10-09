package com.haojing.battlepass.common.net;

/**
 * 用途：客户端强制校验的判定规则（需求文档 §2：在 CONFIGURATION 阶段通过自定义 payload
 * 握手，未握手的客户端拒绝进入 PLAY；同时提供 devMode 配置开关，开启后跳过校验）。
 *
 * <p><b>这不是反作弊</b>，必须写清楚（§2 明确要求注释说明）：
 * 它只区分"装了本 Mod 的客户端"与"没装的（含原版）客户端"，用来避免玩家进入后
 * 面对一个永远空白的战令界面。任何改过客户端的人都能伪造这个握手，
 * 因此它**不承担任何安全职责**；真正的判定全部在服务端（§2：客户端提交的数据一律不作为判定依据）。
 *
 * <p><b>Velocity / BungeeCord 透传注意事项</b>：本模组的握手发生在**后端服**与玩家之间。
 * 代理不会理解自定义 payload，正常情况下会原样转发，因此：
 * <ul>
 *   <li>代理必须开启**现代转发**（Velocity modern forwarding / BungeeCord 的
 *       {@code online-mode=false} + ip_forward），否则配置阶段由代理自己完成，
 *       后端服可能收不到这次握手；</li>
 *   <li>若代理配置了丢弃未知 payload 的插件（少数安全插件会这么做），需要把它对本模组
 *       命名空间（{@code haojing_battlepass}）放行；</li>
 *   <li>跨服跳转（后端之间切换）会重新走一次配置阶段，因此每次跳转都会重新握手 ——
 *       这是正确的行为，不需要特殊处理。</li>
 * </ul>
 *
 * <p>本类不引用 Minecraft 类型，因此"服务端 devMode 开着就放行所有人""版本不一致必须拒绝"
 * 这些规则可以直接单元测试。
 */
public final class HandshakeValidator {

    /** 判定结果。 */
    public enum Verdict {
        /** 通过。 */
        ACCEPT,
        /** 服务端开了 devMode：无论客户端有没有握手都放行。 */
        DEV_BYPASS,
        /** 客户端没有握手（多半是原版客户端或未安装本 Mod）。 */
        MISSING,
        /** 协议版本不一致（客户端 Mod 与服务端 Mod 版本不匹配）。 */
        VERSION_MISMATCH
    }

    private HandshakeValidator() {
    }

    /**
     * 判定一名客户端能否进入 PLAY。
     *
     * @param serverDevMode     服务端是否开启 devMode（配置项，默认 false）
     * @param handshakeReceived 是否收到并校验通过握手
     * @param clientProtocol    客户端上报的协议版本
     * @param serverProtocol    服务端协议版本
     * @return 判定结果
     */
    public static Verdict evaluate(boolean serverDevMode, boolean handshakeReceived,
                                   int clientProtocol, int serverProtocol) {
        if (serverDevMode) {
            // devMode 的用途就是本地测试：此时连原版客户端也应当能进来。
            return Verdict.DEV_BYPASS;
        }

        if (!handshakeReceived) {
            return Verdict.MISSING;
        }

        if (clientProtocol != serverProtocol) {
            return Verdict.VERSION_MISMATCH;
        }

        return Verdict.ACCEPT;
    }

    /** @param verdict 判定 @return 是否允许继续。 */
    public static boolean allowed(Verdict verdict) {
        return verdict == Verdict.ACCEPT || verdict == Verdict.DEV_BYPASS;
    }

    /**
     * @param verdict 判定结果
     * @return 拒绝时展示给玩家的文案翻译键；通过时为空串
     */
    public static String messageKey(Verdict verdict) {
        switch (verdict) {
            case MISSING:
                // §2：必须明确告知玩家"你缺少客户端 Mod"，否则玩家只会看到莫名其妙的断开。
                return "haojing_battlepass.net.kick.missing";
            case VERSION_MISMATCH:
                return "haojing_battlepass.net.kick.version";
            default:
                return "";
        }
    }
}
