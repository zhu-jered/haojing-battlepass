package com.haojing.battlepass.client.mixin;

import com.haojing.battlepass.client.net.ClientNetworking;
import com.haojing.battlepass.client.net.OnlineTitles;
import com.haojing.battlepass.common.gui.GuiColors;
import com.haojing.battlepass.common.net.ModSnapshots;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * 在玩家头顶名称标签前注入装备称号。
 *
 * <p>使用 {@code @ModifyVariable} 改 {@code renderLabelIfPresent} 的 Text 入参：
 * 隐身玩家、未加载区块玩家的标签由原版逻辑决定是否渲染，本 Mixin 只在原版决定
 * 要画标签时才把称号拼在前面。
 *
 * <p>纯客户端、纯渲染：服务端零额外计算；任何异常都静默回退原标签。
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {

    @ModifyVariable(method = "renderLabelIfPresent", at = @At("HEAD"), argsOnly = true)
    private Text haojing$prependTitle(Text original, Entity entity) {
        try {
            if (!(entity instanceof PlayerEntity)) {
                return original;
            }

            // 本机玩家关闭了"头顶称号显示"。
            var snap = ClientNetworking.state().player();
            if (snap == null || !snap.nametagTitleVisible) {
                return original;
            }

            PlayerEntity player = (PlayerEntity) entity;
            String titleId = OnlineTitles.titleOf(player.getUuid());

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

            String name = def.name == null || def.name.isEmpty()
                    ? Text.translatable("haojing_battlepass.title." + titleId).getString()
                    : def.name;
            String wrapPrefix = def.wrapPrefix == null || def.wrapPrefix.isEmpty() ? "【" : def.wrapPrefix;
            String wrapSuffix = def.wrapSuffix == null || def.wrapSuffix.isEmpty() ? "】" : def.wrapSuffix;

            MutableText title = Text.literal(wrapPrefix + name + wrapSuffix + " ");
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
