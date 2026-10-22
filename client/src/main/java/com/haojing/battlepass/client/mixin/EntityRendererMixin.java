package com.haojing.battlepass.client.mixin;

import com.haojing.battlepass.client.net.ClientNetworking;
import com.haojing.battlepass.client.net.OnlineTitles;
import com.haojing.battlepass.common.gui.GuiColors;
import com.haojing.battlepass.common.net.ModSnapshots;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.util.UUID;

/**
 * 在玩家头顶名称标签前注入装备称号。
 *
 * <p>1.21.11 的渲染管线把标签绘制参数从旧版的 {@code VertexConsumerProvider.Immediate + light}
 * 换成了 {@code OrderedRenderCommandQueue + CameraRenderState}：基类
 * {@code EntityRenderer#renderLabelIfPresent} 的真实签名是
 * {@code (EntityRenderState, MatrixStack, OrderedRenderCommandQueue, CameraRenderState)}。
 * 注入方法参数必须与该签名逐项对应，否则 Mixin 在 APPLY 阶段就会因描述符不符而硬崩溃。
 * 标签文本存放在渲染状态的 public Text 字段 {@code displayName}；在 HEAD 把称号拼到原名前写回，
 * 后续管线会直接读取该字段绘制头顶名称。
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {

    private static Field haojing$nameField;
    private static boolean haojing$nameFieldSearched;

    @Inject(method = "renderLabelIfPresent", at = @At("HEAD"))
    private void haojing$prependTitle(EntityRenderState state, MatrixStack matrices,
                                      OrderedRenderCommandQueue queue, CameraRenderState camera, CallbackInfo ci) {
        try {
            if (state == null) {
                return;
            }

            Text original = haojing$extractName(state);
            if (original == null) {
                return;
            }

            var snap = ClientNetworking.state().player();
            if (snap == null || !snap.nametagTitleVisible) {
                return;
            }

            String name = original.getString();
            if (name == null || name.isBlank()) {
                return;
            }

            MinecraftClient client = MinecraftClient.getInstance();
            var networkHandler = client.getNetworkHandler();
            if (networkHandler == null) {
                return;
            }

            var listEntry = networkHandler.getPlayerListEntry(name);
            if (listEntry == null) {
                return;
            }

            UUID uuid = listEntry.getProfile().id();
            String titleId = OnlineTitles.titleOf(uuid);
            if (titleId == null || titleId.isEmpty()) {
                return;
            }

            ModSnapshots.TitleDef def = null;
            var titles = ClientNetworking.state().titles();

            if (titles != null && titles.definitions != null) {
                for (ModSnapshots.TitleDef d : titles.definitions) {
                    if (titleId.equals(d.id)) {
                        def = d;
                        break;
                    }
                }
            }

            if (def == null) {
                return;
            }

            String displayName = def.name == null || def.name.isEmpty()
                    ? Text.translatable("haojing_battlepass.title." + titleId).getString()
                    : def.name;
            String wrapPrefix = def.wrapPrefix == null || def.wrapPrefix.isEmpty() ? "【" : def.wrapPrefix;
            String wrapSuffix = def.wrapSuffix == null || def.wrapSuffix.isEmpty() ? "】" : def.wrapSuffix;

            MutableText title = Text.literal(wrapPrefix + displayName + wrapSuffix + " ");
            Integer argb = GuiColors.parseSectionColor(def.color);

            if (argb != null) {
                title.setStyle(Style.EMPTY.withColor(TextColor.fromRgb(argb)));
            }

            haojing$setName(state, title.append(original));
        } catch (Throwable t) {
            // 静默回退
        }
    }

    /** 在 EntityRenderState 上找 Text 类型的名称字段。 */
    private static Text haojing$extractName(EntityRenderState state) throws Exception {
        Field f = haojing$resolveField(state.getClass());
        if (f == null) {
            return null;
        }
        Object val = f.get(state);
        return val instanceof Text ? (Text) val : null;
    }

    private static void haojing$setName(EntityRenderState state, Text value) throws Exception {
        Field f = haojing$resolveField(state.getClass());
        if (f != null) {
            f.set(state, value);
        }
    }

    /**
     * 定位渲染状态上存放名称标签的 Text 字段。
     *
     * <p>1.21.11 基类 {@code EntityRenderState} 的 public 字段 {@code displayName} 即标签文本，
     * 直接按声明类取字段，避免被首个渲染的非玩家实体子类差异影响而误缓存 {@code null}；
     * 未命中时再沿继承链扫描 Text 类型字段，兼容后续版本字段位置微调。
     */
    private static Field haojing$resolveField(Class<?> cls) throws Exception {
        if (haojing$nameFieldSearched) {
            return haojing$nameField;
        }
        haojing$nameFieldSearched = true;
        // 1. 基类 public Text 字段 displayName
        try {
            Field f = EntityRenderState.class.getField("displayName");
            if (Text.class.isAssignableFrom(f.getType())) {
                haojing$nameField = f;
                return f;
            }
        } catch (NoSuchFieldException ignored) {
        }
        // 2. 沿类继承链向上扫描 Text 类型字段
        Class<?> c = cls;
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (Text.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    haojing$nameField = f;
                    return f;
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }
}
