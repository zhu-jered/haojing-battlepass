package com.haojing.battlepass.common.net;

import com.haojing.battlepass.common.ModConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.packet.CustomPayload;

/**
 * 用途：**一次性**注册全部自定义包类型（需求文档 §3）。
 *
 * <p>为什么必须抽到 :common 并加一次性保护：本工程交付**两个 jar**（服务端 / 客户端），
 * 而在单人游戏（集成服务端）里两个 jar 会同时被加载 —— 此时服务端入口与客户端入口
 * 都会去注册同一批包类型，而 Fabric 的实现是：
 *
 * <pre>
 * throw new IllegalArgumentException("Packet type " + id + " is already registered!");
 * </pre>
 *
 * 也就是说**第二个注册的人会让游戏直接崩在启动阶段**。两个入口共用这一个小工具后，
 * `registered` 静态标记在同一个 JVM（Knot 类加载器）里只可能被置位一次，
 * 第二次调用直接返回，问题从机制上消失。
 *
 * <p>另外对每一次注册单独兜住 {@code IllegalArgumentException}：万一将来有别的模组
 * 抢先注册了同名通道，也应当是"这个包不可用 + 明确告警"，而不是让玩家进不去游戏。
 */
public final class ModPayloadRegistration {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModConstants.MOD_ID_SERVER);

    /** 是否已经注册过（同 JVM 内只允许一次）。 */
    private static boolean registered;

    private ModPayloadRegistration() {
    }

    /** 注册全部包类型。重复调用是安全的（直接返回）。 */
    public static synchronized void registerAll() {
        if (registered) {
            return;
        }

        registered = true;

        register(PayloadTypeRegistry.configurationC2S(), ModPayloads.Handshake.ID, ModPayloads.Handshake.CODEC);
        register(PayloadTypeRegistry.configurationS2C(), ModPayloads.HandshakeResult.ID, ModPayloads.HandshakeResult.CODEC);

        register(PayloadTypeRegistry.playC2S(), ModPayloads.ClientAction.ID, ModPayloads.ClientAction.CODEC);
        register(PayloadTypeRegistry.playC2S(), ModPayloads.AdminAction.ID, ModPayloads.AdminAction.CODEC);
        register(PayloadTypeRegistry.playC2S(), ModPayloads.ConfigFileRequest.ID, ModPayloads.ConfigFileRequest.CODEC);
        register(PayloadTypeRegistry.playC2S(), ModPayloads.ConfigFileSave.ID, ModPayloads.ConfigFileSave.CODEC);

        register(PayloadTypeRegistry.playS2C(), ModPayloads.SyncFragment.ID, ModPayloads.SyncFragment.CODEC);
        register(PayloadTypeRegistry.playS2C(), ModPayloads.OpenPanel.ID, ModPayloads.OpenPanel.CODEC);
        register(PayloadTypeRegistry.playS2C(), ModPayloads.ActionResult.ID, ModPayloads.ActionResult.CODEC);
        register(PayloadTypeRegistry.playS2C(), ModPayloads.TitleBroadcast.ID, ModPayloads.TitleBroadcast.CODEC);
        register(PayloadTypeRegistry.playS2C(), ModPayloads.ConfigFileContent.ID, ModPayloads.ConfigFileContent.CODEC);
    }

    /** @return 是否已经完成注册。 */
    public static synchronized boolean isRegistered() {
        return registered;
    }

    /** 单个类型的注册，冲突时告警而不是崩游戏。 */
    private static <B extends net.minecraft.network.PacketByteBuf, T extends CustomPayload> void register(
            PayloadTypeRegistry<B> registry, CustomPayload.Id<T> id, net.minecraft.network.codec.PacketCodec<? super B, T> codec) {
        try {
            registry.register(id, codec);
        } catch (IllegalArgumentException e) {
            LOGGER.warn("{} 数据包通道 {} 已经被注册过（可能是单机同时加载了两端，或其它模组占用了同名通道）：{}",
                    ModConstants.LOG_PREFIX, id.id(), e.getMessage());
        }
    }
}
