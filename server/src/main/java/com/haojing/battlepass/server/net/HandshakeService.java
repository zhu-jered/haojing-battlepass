package com.haojing.battlepass.server.net;

import com.haojing.battlepass.common.ModConstants;
import com.haojing.battlepass.common.net.HandshakeValidator;
import com.haojing.battlepass.common.net.ModNetworkingIds;
import com.haojing.battlepass.common.net.ModPayloads;
import com.haojing.battlepass.server.config.ConfigManager;
import com.haojing.battlepass.server.config.SeasonConfig;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 用途：客户端强制校验（需求文档 §2、§14）。
 *
 * <p><b>本机制不是反作弊，必须在代码与文档里都说清楚</b>（§2 原文要求注释说明）：
 * 它只区分"装了本 Mod 的客户端"与"没装的（含原版）客户端"，目的是避免玩家进服后
 * 面对一个永远空白的战令界面、以及避免两端协议不一致导致的错位数据。
 * 改过客户端的人可以伪造这个握手，因此**没有任何安全职责**；
 * 真正的判定全部在服务端（§2：客户端提交的数据一律不作为判定依据）。
 *
 * <p>三处校验（缺一不可）：
 * <ol>
 *   <li><b>配置阶段开始</b>：若客户端声明收不到我们的握手结果通道 → 判定为原版客户端 → 断开。
 *       这是唯一能"在配置阶段就拦下"的判据（1.21.11 的配置阶段拿不到玩家 UUID，
 *       因此无法用玩家身份做记录）。</li>
 *   <li><b>收到握手包</b>：比对协议版本，不一致立刻断开，并把"请更新 Mod"发给客户端。</li>
 *   <li><b>进入 PLAY 时</b>：再用 play 阶段的能力查询做一次兜底（防止有人只改配置阶段）。</li>
 * </ol>
 *
 * <p>devMode（默认 false）开启时三处全部跳过（§2 要求提供该开关）。
 */
public final class HandshakeService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModConstants.MOD_ID_SERVER);

    private final ConfigManager configManager;

    /** 统计：通过/拒绝的握手次数（§13 的可测量指标）。 */
    private final AtomicInteger accepted = new AtomicInteger();
    private final AtomicInteger rejected = new AtomicInteger();

    public HandshakeService(ConfigManager configManager) {
        this.configManager = configManager;
    }

    /** @return 是否处于 devMode（配置缺失时按 false 处理，即"校验生效"）。 */
    public boolean devMode() {
        SeasonConfig config = configManager == null ? null : configManager.config();
        return config != null && config.devMode;
    }

    /** @return 通过校验的次数。 */
    public int acceptedCount() {
        return accepted.get();
    }

    /** @return 被拒绝的次数。 */
    public int rejectedCount() {
        return rejected.get();
    }

    /**
     * 配置阶段开始时调用（Fabric `ServerConfigurationConnectionEvents.BEFORE_CONFIGURE`）。
     *
     * @param handler 配置阶段连接
     */
    public void onConfigureStart(net.minecraft.server.network.ServerConfigurationNetworkHandler handler) {
        if (devMode() || handler == null) {
            return;
        }

        // canSend 问的是"客户端声明自己能不能收这个通道"，未安装本 Mod 的客户端一律 false。
        if (!ServerConfigurationNetworking.canSend(handler, ModPayloads.HandshakeResult.ID)) {
            rejected.incrementAndGet();
            LOGGER.warn("{} 拒绝一名未安装客户端 Mod 的连接（§2：未握手拒绝进入 PLAY）", ModConstants.LOG_PREFIX);
            disconnect(handler, "haojing_battlepass.net.kick.missing");
        }
    }

    /**
     * 收到客户端握手时调用。
     *
     * @param payload 握手内容
     * @param context 配置阶段上下文
     */
    public void onHandshake(ModPayloads.Handshake payload, ServerConfigurationNetworking.Context context) {
        if (context == null) {
            return;
        }

        if (devMode()) {
            LOGGER.info("{} devMode 已开启，跳过客户端校验（不校版本，仅记录：客户端 {} 协议 {}）",
                    ModConstants.LOG_PREFIX, payload.modVersion(), payload.protocolVersion());
            return;
        }

        HandshakeValidator.Verdict verdict = HandshakeValidator.evaluate(false, true,
                payload.protocolVersion(), ModConstants.PROTOCOL_VERSION);

        if (verdict == HandshakeValidator.Verdict.ACCEPT) {
            accepted.incrementAndGet();
            LOGGER.info("{} 客户端校验通过：Mod 版本 {} 协议 {}",
                    ModConstants.LOG_PREFIX, payload.modVersion(), payload.protocolVersion());
            // 回执只是为了让客户端在日志里也能看到"服务端认可了这个版本"。
            context.responseSender().sendPacket(
                    new ModPayloads.HandshakeResult(true, ModConstants.PROTOCOL_VERSION, ""));
            return;
        }

        rejected.incrementAndGet();
        LOGGER.warn("{} 客户端协议版本不匹配：客户端 {}（协议 {}）服务端协议 {}",
                ModConstants.LOG_PREFIX, payload.modVersion(), payload.protocolVersion(), ModConstants.PROTOCOL_VERSION);
        context.responseSender().sendPacket(new ModPayloads.HandshakeResult(
                false, ModConstants.PROTOCOL_VERSION, HandshakeValidator.messageKey(verdict)));
        context.responseSender().disconnect(Text.translatable(HandshakeValidator.messageKey(verdict)));
    }

    /**
     * 进入 PLAY 时的兜底校验。
     *
     * @param player 玩家
     * @return 是否允许继续（false 表示调用方应当断开该玩家）
     */
    public boolean allowsPlay(net.minecraft.server.network.ServerPlayerEntity player) {
        if (devMode() || player == null) {
            return true;
        }

        // 用 play 阶段自己的通道再问一次能力：只改了配置阶段行为的客户端会在这里被拦下。
        return net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
                .canSend(player, ModPayloads.ActionResult.ID);
    }

    /**
     * 断开的统一入口。
     *
     * @param handler 配置阶段连接
     * @param messageKey 文案翻译键（客户端会用 zh_cn.json 显示）
     */
    private void disconnect(net.minecraft.server.network.ServerConfigurationNetworkHandler handler, String messageKey) {
        try {
            ServerConfigurationNetworking.getSender(handler).disconnect(Text.translatable(messageKey));
        } catch (RuntimeException e) {
            // 断开本身失败不能再抛出去（§14：任何网络异常都不能让服务端崩）。
            LOGGER.warn("{} 断开未握手客户端时出现异常：{}", ModConstants.LOG_PREFIX, e.toString());
        }
    }

    /** @return 便于日志的一行摘要。 */
    public String describe() {
        return "客户端校验=" + (devMode() ? "devMode（跳过）" : "开启")
                + " 通过 " + accepted.get() + " 次 / 拒绝 " + rejected.get() + " 次"
                + " 服务端协议=" + ModConstants.PROTOCOL_VERSION;
    }

    /** @return 握手相关通道的标识（供注册处引用，避免各处重复写字符串）。 */
    public static net.minecraft.util.Identifier handshakeChannel() {
        return ModNetworkingIds.HANDSHAKE_C2S;
    }
}
