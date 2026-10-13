package com.haojing.battlepass.client.cape;

import com.haojing.battlepass.common.ModConstants;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端披风纹理管理器：把服务端下发的 PNG 字节解码并注册成 {@link NativeImageBackedTexture}。
 *
 * <p>为什么不直接读本地文件：披风 PNG 由服务端下发（玩家不能自行上传），
 * 客户端只是接收并注册。贴图缺失/损坏时不抛异常，返回 null，Mixin 渲染时回退原版披风。
 */
public final class CapeTextureManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModConstants.MOD_ID_CLIENT);

    /** capeId -> 注册后的纹理 Identifier。 */
    private static final Map<String, Identifier> REGISTERED = new ConcurrentHashMap<>();

    private CapeTextureManager() {
    }

    /**
     * 接收一份披风 PNG 字节并注册（若同名已注册则替换）。
     *
     * @param capeId 披风 ID
     * @param png    PNG 原始字节
     */
    public static void register(String capeId, byte[] png) {
        if (capeId == null || capeId.isEmpty() || png == null || png.length == 0) {
            return;
        }
        try {
            NativeImage image = NativeImage.read(png);
            Identifier id = Identifier.of(ModConstants.MOD_ID_CLIENT, "cape/" + capeId);
            MinecraftClient client = MinecraftClient.getInstance();
            // 若已注册先释放旧纹理，避免内存泄漏
            Identifier old = REGISTERED.get(capeId);
            if (old != null) {
                try {
                    client.getTextureManager().destroyTexture(old);
                } catch (Throwable ignored) {
                }
            }
            NativeImageBackedTexture texture = new NativeImageBackedTexture(() -> "haojing_cape_" + capeId, image);
            client.getTextureManager().registerTexture(id, texture);
            REGISTERED.put(capeId, id);
        } catch (Throwable t) {
            // 贴图损坏不能崩游戏：回退原版披风
            LOGGER.warn("{} 披风 {} 贴图注册失败：{}", ModConstants.LOG_PREFIX, capeId, t.toString());
            REGISTERED.remove(capeId);
        }
    }

    /**
     * 取某披风已注册的纹理。
     *
     * @param capeId 披风 ID
     * @return 纹理 Identifier；未注册时返回 null
     */
    public static Identifier get(String capeId) {
        if (capeId == null || capeId.isEmpty()) {
            return null;
        }
        return REGISTERED.get(capeId);
    }

    /** 清空（重连时调用）。 */
    public static void clear() {
        MinecraftClient client = MinecraftClient.getInstance();
        for (Identifier id : REGISTERED.values()) {
            try {
                client.getTextureManager().destroyTexture(id);
            } catch (Throwable ignored) {
            }
        }
        REGISTERED.clear();
    }
}
