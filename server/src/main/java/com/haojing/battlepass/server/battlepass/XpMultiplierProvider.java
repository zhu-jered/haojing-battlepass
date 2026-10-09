package com.haojing.battlepass.server.battlepass;

import java.util.UUID;

/**
 * 用途：按玩家取当前经验倍率的出口（需求文档 §5.11 + §7 白名单）。
 *
 * <p>为什么不直接依赖 {@code LongNightManager}：那个类要 tick MinecraftServer，
 * 因此引用了 Minecraft 类型，而 Loom 不把 minecraft 依赖挂到测试源集。
 * 只要 BattlePassService 的字段/参数上出现它的类型，这个类的单元测试就会因为
 * 加载不到 Minecraft 类而失败。用一个函数式接口把它隔在外面，
 * "按玩家取倍率"这条 D-6 的关键约定仍然被强制（签名里必须传 UUID），
 * 但升级/上限/发奖这些真正需要测试的规则可以脱离服务端运行。
 */
@FunctionalInterface
public interface XpMultiplierProvider {

    /**
     * @param playerUuid 玩家
     * @return 该玩家当前的经验倍率；非长夜或白名单管理员应为 1.0
     */
    double multiplierFor(UUID playerUuid);

    /** @return 恒定 1.0 的倍率（长夜模块不可用时使用）。 */
    static XpMultiplierProvider alwaysOne() {
        return uuid -> 1.0D;
    }
}
