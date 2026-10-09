package com.haojing.battlepass.server.reward;

import com.haojing.battlepass.common.ModConstants;
import com.haojing.battlepass.common.data.Reward;
import com.haojing.battlepass.common.data.RewardType;
import com.haojing.battlepass.server.battlepass.XpSource;
import com.haojing.battlepass.server.config.SeasonConfig;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec2f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 用途：真正面向 Minecraft 的奖励发放实现 —— 负责 ITEM 与 COMMAND，
 * 其余四类（星币/称号/豁免卡/战令经验）转发给 {@link DataRewardApplier}。
 *
 * <p>为什么服务端实例与配置都用 {@link Supplier} 传入而不是直接持有：
 * 模块初始化时 MinecraftServer 还在启动过程中，而配置会被热重载换引用。
 * 用 Supplier 每次现取，既能拿到"当前的"服务端与"当前的"白名单，
 * 又不会因为缓存了一个已过期的引用而出现"改了配置但发放还在用旧白名单"。
 *
 * <p>为什么本类不做单元测试：它引用的全是 Minecraft 类型，而 Loom 不会把
 * minecraft 依赖挂到测试源集（见 server/build.gradle）。因此这里刻意把逻辑压到最薄 ——
 * 只剩"解析 ID → 构造 ItemStack → 塞进背包"与"校验白名单 → 执行命令"两步，
 * 真正需要测试的规则（白名单判定、奖励校验、数据类发放）都放在 MC-free 的类里。
 */
public final class MinecraftRewardSink implements RewardSink {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModConstants.MOD_ID_SERVER);

    private final DataRewardApplier dataApplier;
    private final Supplier<MinecraftServer> serverSupplier;
    private final Supplier<SeasonConfig> configSupplier;

    public MinecraftRewardSink(DataRewardApplier dataApplier,
                               Supplier<MinecraftServer> serverSupplier,
                               Supplier<SeasonConfig> configSupplier) {
        this.dataApplier = dataApplier;
        this.serverSupplier = serverSupplier;
        this.configSupplier = configSupplier;
    }

    @Override
    public boolean grant(UUID playerUuid, Reward reward, String reason, XpSource xpSource) {
        if (playerUuid == null || reward == null) {
            return false;
        }

        RewardType type = reward.typeOrNull();

        if (type == null) {
            LOGGER.warn("{} {}：无法识别的奖励类型 {}，已丢弃", ModConstants.LOG_PREFIX, reason, reward.type);
            return false;
        }

        switch (type) {
            case ITEM:
                return grantItem(playerUuid, reward, reason);
            case COMMAND:
                return runCommand(playerUuid, reward, reason);
            default:
                return dataApplier.grant(playerUuid, reward, reason, xpSource);
        }
    }

    /** 把物品塞进在线玩家的背包。离线玩家拿不到物品，明确告警并返回失败。 */
    private boolean grantItem(UUID playerUuid, Reward reward, String reason) {
        MinecraftServer server = serverSupplier.get();
        ServerPlayerEntity player = findPlayer(server, playerUuid);

        if (player == null) {
            LOGGER.warn("{} {}：玩家 {} 不在线，物品奖励 {} ×{} 无法发放（数据类奖励不受影响）",
                    ModConstants.LOG_PREFIX, reason, playerUuid, reward.itemId, reward.amount);
            return false;
        }

        Identifier id = Identifier.tryParse(reward.itemId);

        if (id == null) {
            LOGGER.warn("{} {}：物品 ID 非法（{}），已丢弃", ModConstants.LOG_PREFIX, reason, reward.itemId);
            return false;
        }

        Item item = Registries.ITEM.get(id);

        if (item == null || item == Items.AIR) {
            // 名字写错或该模组未安装时会落到这里。必须明确告警：静默发一堆空气最难排查。
            LOGGER.warn("{} {}：物品 ID 不存在（{}），已丢弃", ModConstants.LOG_PREFIX, reason, reward.itemId);
            return false;
        }

        ItemStack stack = new ItemStack(item, reward.amount);
        player.giveItemStack(stack);

        LOGGER.info("{} {}：玩家 {} 获得物品 {} ×{}",
                ModConstants.LOG_PREFIX, reason, playerUuid, reward.itemId, reward.amount);
        return true;
    }

    /**
     * 以管理员权限执行奖励命令。
     *
     * <p>为什么用"服务端命令源 + 指定执行实体"而不是直接用玩家自己的命令源：
     * 玩家命令源的权限等级就是这名玩家的权限等级，普通玩家（非 OP）执行 give 会直接被拒；
     * 而奖励命令是管理员写在配置里的，必须以管理员权限运行。
     * 同时 {@code withEntity} 只替换执行实体、不动权限（已核对 1.21.11 反编译源码），
     * 因此 {@code @s} 会正确解析成这名玩家；位置/世界再显式对齐，
     * 免得 particle / playsound 这类带坐标的命令跑到世界原点去。
     *
     * <p>执行入口用 {@code parseAndExecute}：1.21.11 的 CommandManager 已经没有
     * executeWithPrefix（已用 javap 核对），且该方法内部会处理语法错误并回执给命令源，不需要外层捕获。
     */
    private boolean runCommand(UUID playerUuid, Reward reward, String reason) {
        MinecraftServer server = serverSupplier.get();

        if (server == null) {
            LOGGER.warn("{} {}：服务端不可用，命令奖励未执行", ModConstants.LOG_PREFIX, reason);
            return false;
        }

        SeasonConfig config = configSupplier.get();
        List<String> whitelist = config == null ? List.of() : config.commandWhitelist;

        if (!CommandWhitelist.isAllowed(reward.command, whitelist)) {
            LOGGER.warn("{} {}：命令「{}」不在白名单内，已拒绝执行（需求文档 §9）",
                    ModConstants.LOG_PREFIX, reason, reward.command);
            return false;
        }

        ServerPlayerEntity player = findPlayer(server, playerUuid);
        ServerCommandSource source = server.getCommandSource().withSilent();

        if (player != null) {
            source = source.withEntity(player)
                    .withPosition(player.getEntityPos())
                    .withRotation(new Vec2f(player.getYaw(), player.getPitch()));

            if (player.getEntityWorld() instanceof ServerWorld serverWorld) {
                source = source.withWorld(serverWorld);
            }
        }

        try {
            server.getCommandManager().parseAndExecute(source, reward.command);
            LOGGER.info("{} {}：已为玩家 {} 执行命令 /{}",
                    ModConstants.LOG_PREFIX, reason, playerUuid, reward.command);
            return true;
        } catch (RuntimeException e) {
            // 命令本身的语法错误由服务端命令系统负责回执，这里只兜住意外异常，
            // 避免一条写错的奖励命令把整批奖励发放中断掉。
            LOGGER.error("{} {}：执行命令 /{} 时出现异常", ModConstants.LOG_PREFIX, reason, reward.command, e);
            return false;
        }
    }

    private ServerPlayerEntity findPlayer(MinecraftServer server, UUID playerUuid) {
        return server == null ? null : server.getPlayerManager().getPlayer(playerUuid);
    }
}
