package com.haojing.battlepass.client.mixin;

import com.haojing.battlepass.client.net.ClientNetworking;
import com.haojing.battlepass.client.net.OnlineTitles;
import com.haojing.battlepass.common.gui.GuiColors;
import com.haojing.battlepass.common.net.ModSnapshots;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.entity.EntityAttachmentType;
import net.minecraft.entity.PlayerLikeEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * 在玩家渲染状态构建完成后，把已佩戴称号作为同行前缀拼到头顶玩家名前。
 *
 * <p><b>为什么注入 {@link PlayerEntityRenderer#updateRenderState} 而不是基类
 * {@code EntityRenderer#renderLabelIfPresent}：</b>1.21.11 的 {@code PlayerEntityRenderer}
 * 重写了 {@code renderLabelIfPresent}；旧实现把回调织在基类方法体里，玩家实体走子类重写方法，
 * 基类回调常态根本不执行，称号拼不出来（偏差 D-43）。状态构建阶段既能拿到实体 UUID 与原版
 * 显示名，也能在原版 64 格门控把 {@code displayName}/{@code nameLabelPos} 清空之后补填，
 * 从而做到实体在视距内即显示称号。
 *
 * <p><b>遮挡口径不做改动：</b>标签提交仍由原版 {@code renderLabelIfPresent} 按 normal
 * （被方块挡住即隐藏）/ seethrough（未潜行穿墙半透明）两路提交；本类只改标签文本、补标签坐标，
 * 不接管渲染队列、不做额外视线检测。
 *
 * <p><b>为什么直接取实体 UUID：</b>旧实现按显示名反查 {@code PlayerListEntry} 再取 UUID，
 * 队伍装饰名会污染名字匹配导致查不到；直接用 {@code entity.getUuid()} 与在线称号表比对，
 * 不依赖名字字符串。
 */
@Mixin(PlayerEntityRenderer.class)
public abstract class PlayerEntityRendererMixin {

    @Inject(method = "updateRenderState", at = @At("RETURN"))
    private void haojing$prependTitle(PlayerLikeEntity playerLikeEntity, PlayerEntityRenderState state,
                                      float tickProgress, CallbackInfo ci) {
        try {
            if (playerLikeEntity == null || state == null) {
                return;
            }

            // 观察者自己的"头顶称号"开关：本机是否显示其他玩家头顶的称号（仅本机偏好，不改变他人）。
            ModSnapshots.Player snap = ClientNetworking.state().player();
            if (snap == null || !snap.nametagTitleVisible) {
                return;
            }

            // 第三人称看自己时，原版本就不画自己的名标签，称号也不补。
            if (playerLikeEntity == MinecraftClient.getInstance().player) {
                return;
            }

            UUID uuid = playerLikeEntity.getUuid();
            String titleId = OnlineTitles.titleOf(uuid);
            if (titleId == null || titleId.isEmpty()) {
                return;
            }

            ModSnapshots.TitleDef def = null;
            ModSnapshots.Titles titles = ClientNetworking.state().titles();
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

            // 原版显示名：64 格内基类已填到 state.displayName；超距被门控置空时回退实体自身显示名。
            Text original = state.displayName != null ? state.displayName : playerLikeEntity.getDisplayName();
            if (original == null) {
                return;
            }
            if (original.getString().isBlank()) {
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

            state.displayName = title.append(original);

            // 超距或原版门控未填坐标时补填头顶附着点；坐标为空，标签不会进入提交队列。
            if (state.nameLabelPos == null) {
                state.nameLabelPos = playerLikeEntity.getAttachments().getPointNullable(
                        EntityAttachmentType.NAME_TAG, 0, playerLikeEntity.getLerpedYaw(tickProgress));
            }
        } catch (Throwable t) {
            // 纯装饰渲染层：任何异常都静默回退到原版头顶名称，不拖垮客户端。
        }
    }
}
