package com.haojing.battlepass.server.reward;

import com.haojing.battlepass.common.data.Reward;
import com.haojing.battlepass.server.battlepass.XpSource;

import java.util.UUID;

/**
 * 用途：奖励发放的出口（需求文档 §9：奖励统一抽象为 Reward，发放必须能覆盖
 * ITEM / COMMAND / BATTLEPASS_XP / STAR_COIN / TITLE 五类）。
 *
 * <p>为什么把发放做成一个接口而不是直接写工具方法：等级奖励、任务领取、商店兑换、
 * 彩蛋（阶段 6）都要发奖，但它们各自只需要"发给我这个玩家"这一件事。
 * 有了接口，{@code BattlePassService} / {@code ShopManager} / 彩蛋系统就完全不引用
 * 任何 Minecraft 类型（物品栏、命令都是 Minecraft 的东西），
 * 于是它们的规则可以用普通单元测试覆盖，而不必启动服务端。
 *
 * <p>实现有两层：{@link DataRewardApplier} 负责"只改数据文件"的四类奖励（离线也能发），
 * {@link MinecraftRewardSink} 负责需要在线玩家的 ITEM / COMMAND，
 * 并把其余类型转发给前者。这样"离线玩家能不能领奖"这件事被结构性地决定了，
 * 而不是靠各处 if 判断。
 */
public interface RewardSink {

    /**
     * 发放一条奖励。
     *
     * @param playerUuid 目标玩家
     * @param reward     奖励定义（调用方需保证已通过 {@link Reward#validateError()}）
     * @param reason     发放缘由，仅用于日志（例如"等级 12 奖励"）
     * @param xpSource   当奖励是 BATTLEPASS_XP 时，计入日志的经验来源；其余类型忽略
     * @return 是否成功发放；false 时调用方不应视为已发放（例如商店需要退款）
     */
    boolean grant(UUID playerUuid, Reward reward, String reason, XpSource xpSource);

    /**
     * 发放一条奖励（经验来源默认按"等级奖励"记日志）。
     *
     * @param playerUuid 目标玩家
     * @param reward     奖励定义
     * @param reason     发放缘由
     * @return 是否成功发放
     */
    default boolean grant(UUID playerUuid, Reward reward, String reason) {
        return grant(playerUuid, reward, reason, XpSource.LEVEL_REWARD);
    }
}
