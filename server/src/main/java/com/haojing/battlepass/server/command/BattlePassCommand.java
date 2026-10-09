package com.haojing.battlepass.server.command;

import com.haojing.battlepass.common.ModConstants;
import com.haojing.battlepass.common.net.SyncChannels;
import com.haojing.battlepass.server.net.PlayerSyncService;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 用途：服务端指令（需求文档 §11）。
 *
 * <p>§11 只要求两条：{@code /battlepass}（打开战令 GUI）与 {@code /battlepass admin}（打开管理面板）。
 * 这里注册的是**服务端**的那一半：
 * <ul>
 *   <li>{@code /battlepass} —— 由客户端注册的本地指令处理（打开本地界面不需要服务端参与），
 *       但为了让"在服务端控制台敲"或"客户端没装 Mod"时有明确反馈，
 *       服务端也注册了同名根指令，只回一句提示。</li>
 *   <li>{@code /battlepass admin} —— 必须由服务端处理：§9 要求"需 OP 权限 + 服务端二次校验"。
 *       服务端验证权限后，再通过数据包让客户端打开管理面板。</li>
 * </ul>
 *
 * <p>§11 明确「不提供 /battlepass dailyRefresh」：刷新由模组自动完成（阶段 4 已实现）。
 * 这里也就不注册任何刷新指令 —— 手工刷新会破坏 §5.3 的幂等标记语义。
 */
public final class BattlePassCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModConstants.MOD_ID_SERVER);

    private BattlePassCommand() {
    }

    /**
     * 注册指令。
     *
     * @param syncService 用来把"打开管理面板"的通知发给客户端
     */
    public static void register(PlayerSyncService syncService) {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                registerCommands(dispatcher, syncService));
    }

    private static void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher, PlayerSyncService syncService) {
        dispatcher.register(CommandManager.literal("battlepass")
                .executes(context -> {
                    // 服务端这一支只在"客户端没处理"时到达（例如控制台，或未安装客户端 Mod）。
                    ServerCommandSource source = context.getSource();
                    ServerPlayerEntity player = source.getPlayer();

                    if (player == null) {
                        source.sendFeedback(() -> Text.translatable("haojing_battlepass.command.need_client"), false);
                        return 0;
                    }

                    if (syncService != null) {
                        syncService.openPanel(player, SyncChannels.Panel.PLAYER);
                    }

                    return 1;
                })
                .then(CommandManager.literal("admin")
                        // §9：需 OP 权限（原版权限等级 2 = GAMEMASTERS）。
                        .requires(CommandManager.requirePermissionLevel(CommandManager.GAMEMASTERS_CHECK))
                        .executes(context -> {
                            ServerPlayerEntity player = context.getSource().getPlayer();

                            if (player == null) {
                                context.getSource().sendError(Text.translatable("haojing_battlepass.command.need_player"));
                                return 0;
                            }

                            if (syncService != null) {
                                // 先把管理配置推过去，再让客户端开界面 —— 反过来会出现"界面先出来、内容是空的"。
                                syncService.pushAdminNow(player);
                                syncService.openPanel(player, SyncChannels.Panel.ADMIN);
                            }

                            LOGGER.info("{} 管理员 {} 打开了管理面板", ModConstants.LOG_PREFIX, player.getName().getString());
                            return 1;
                        })));
    }
}
