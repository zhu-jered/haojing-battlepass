package com.haojing.battlepass.client.mixin;

import com.haojing.battlepass.client.net.ClientNetworking;
import com.haojing.battlepass.client.net.OnlineTitles;
import com.haojing.battlepass.common.gui.GuiColors;
import com.haojing.battlepass.common.net.ModSnapshots;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.util.math.MatrixStack;import net.minecraft.text.MutableText;
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
 * <p>1.21.11 的 renderLabelIfPresent 接收 EntityRenderState，标签文本存在 state 的某个字段里。
 * 用反射找到该字段（name/label/displayName），在方法入口把称号拼到前面。
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {

    private static Field haojing$nameField;
    private static boolean haojing$nameFieldSearched;

    @Inject(method = "renderLabelIfPresent", at = @At("HEAD"))
    private void haojing$prependTitle(EntityRenderState state, MatrixStack matrices,
                                      VertexConsumerProvider.Immediate consumers, int light, CallbackInfo ci) {
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

    private static Field haojing$resolveField(Class<?> cls) throws Exception {
        if (haojing$nameFieldSearched) {
            return haojing$nameField;
        }
        haojing$nameFieldSearched = true;
        // 按常见名称找
        String[] candidates = {"name", "label", "displayName", "labelText"};
        for (String c : candidates) {
            try {
                Field f = cls.getField(c);
                if (Text.class.isAssignableFrom(f.getType())) {
                    haojing$nameField = f;
                    return f;
                }
            } catch (NoSuchFieldException ignored) {
            }
        }
        // 找不到公开字段，扫所有字段（包括私有）
        for (Field f : cls.getDeclaredFields()) {
            if (Text.class.isAssignableFrom(f.getType())) {
                f.setAccessible(true);
                haojing$nameField = f;
                return f;
            }
        }
        return null;
    }
}
