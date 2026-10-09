package com.haojing.battlepass.server.redeem;

import com.haojing.battlepass.common.ModConstants;
import com.haojing.battlepass.server.time.TimeUtil;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 用途：聊天里输入口令的接入点（需求文档 §10：聊天输入口令领取一次性奖励）。
 *
 * <p>为什么用聊天事件而不是指令：§10 明确写的是"聊天输入口令"，
 * 指令会出现在 Tab 补全里、也能被记进指令历史，与"口令"这种半隐藏的玩法不符。
 *
 * <p>为什么本类只做"翻译与回执"：匹配、时间窗、幂等这些规则全在
 * {@link RedeemCodeService} 里（MC-free，可单测）。这里只负责把结果变成玩家能看到的话。
 */
public final class RedeemCodeEvents {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModConstants.MOD_ID_SERVER);

    private static volatile RedeemCodeService service;

    private RedeemCodeEvents() {
    }

    /** 注册聊天监听。应在服务端初始化时调用一次。 */
    public static void register(RedeemCodeService redeemService) {
        service = redeemService;

        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) ->
                onChat(sender, message.getSignedContent()));
    }

    private static void onChat(ServerPlayerEntity player, String content) {
        RedeemCodeService current = service;

        if (current == null || player == null || content == null || content.isBlank()) {
            return;
        }

        RedeemCodeService.RedeemResult result = current.redeem(player.getUuid(), content, TimeUtil.todayKey());
        RedeemCode code = result.code();

        switch (result.outcome()) {
            case OK:
                // 文案走 TranslationKey（§1），译文在客户端 zh_cn.json。
                player.sendMessage(Text.translatable(code.successMessageKey), false);

                if (code.broadcast) {
                    var server = player.getEntityWorld().getServer();

                    if (server != null) {
                        server.getPlayerManager().broadcast(Text.translatable(
                                "haojing_battlepass.code.broadcast", player.getName().getString(), code.name), false);
                    }
                }
                break;
            case ALREADY_CLAIMED:
                // 动作栏提示（true），不刷聊天框：玩家自己知道就好。
                player.sendMessage(Text.translatable("haojing_battlepass.code.already_claimed"), true);
                break;
            case NOT_IN_WINDOW:
                player.sendMessage(Text.translatable("haojing_battlepass.code.not_in_window"), true);
                break;
            default:
                // 绝大多数聊天都不是口令：静默忽略，绝不能在聊天里刷"口令不存在"。
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("{} 聊天内容未命中任何口令（玩家 {}）", ModConstants.LOG_PREFIX, player.getName().getString());
                }
                break;
        }
    }
}
