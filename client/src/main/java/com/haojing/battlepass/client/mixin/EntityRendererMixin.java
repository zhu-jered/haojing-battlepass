package com.haojing.battlepass.client.mixin;

import com.haojing.battlepass.client.net.ClientNetworking;
import com.haojing.battlepass.client.net.OnlineTitles;
import com.haojing.battlepass.common.gui.GuiColors;
import com.haojing.battlepass.common.net.ModSnapshots;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.UUID;

/**
 * 在玩家头顶名称标签前注入装备称号。
 *
 * <p>1.21.11 把 {@code renderLabelIfPresent} 的签名改成接收 EntityRenderState 而非 Entity，
 * 因此这里拿不到实体引用。改用"原标签文本 = 玩家名"反查 PlayerListEntry 拿 UUID，
 * 再去 OnlineTitles 查该玩家当前称号。
 *
 * <p>隐身玩家、未加载区块玩家的标签由原版逻辑决定是否渲染，本 Mixin 只在原版决定
 * 要画标签时才把称号拼在前面。任何异常静默回退原标签。
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {

    @ModifyVariable(method = "renderLabelIfPresent", at = @At("STORE"), ordinal = 0)
    private Text haojing$prependTitle(Text original) {
        try {
            if (original == null) {
                return original;
            }

            var snap = ClientNetworking.state().player();
            if (snap == null || !snap.nametagTitleVisible) {
                return original;
            }

            String name = original.getString();
            if (name == null || name.isBlank()) {
                return original;
            }

            MinecraftClient client = MinecraftClient.getInstance();
            var networkHandler = client.getNetworkHandler();
            if (networkHandler == null) {
                return original;
            }

            // 用玩家名反查 UUID；非玩家实体（物品展示框等）查不到，直接返回原标签。
            var listEntry = networkHandler.getPlayerListEntry(name);
            if (listEntry == null) {
                return original;
            }

            UUID uuid = listEntry.getProfile().id();
            String titleId = OnlineTitles.titleOf(uuid);
            if (titleId == null || titleId.isEmpty()) {
                return original;
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
                return original;
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

            return title.append(original);
        } catch (Throwable t) {
            return original;
        }
    }
}
