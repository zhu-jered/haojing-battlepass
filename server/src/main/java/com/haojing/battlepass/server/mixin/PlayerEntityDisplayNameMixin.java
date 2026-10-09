package com.haojing.battlepass.server.mixin;

import com.haojing.battlepass.server.chat.ChatTitleDecorator;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 把称号前缀拼到玩家显示名前面，让聊天里的格式变成：
 *   &lt;【长夜守望】zhu_jered&gt; 哈哈哈
 * 而不是：
 *   &lt;zhu_jered&gt; 【长夜守望】 哈哈哈
 *
 * <p>只改聊天消息里的名字显示；Tab 列表、死亡消息等其他地方保持原样。
 * 通过 {@link ChatTitleDecorator#decorateDisplayName(ServerPlayerEntity, Text)} 提供逻辑。
 */
@Mixin(PlayerEntity.class)
public abstract class PlayerEntityDisplayNameMixin {

    @Inject(method = "getDisplayName", at = @At("RETURN"), cancellable = true)
    private void haojing$prependTitleToDisplayName(CallbackInfoReturnable<Text> cir) {
        Object self = this;
        if (!(self instanceof ServerPlayerEntity player)) {
            return;
        }

        Text original = cir.getReturnValue();
        if (original == null) {
            return;
        }

        Text decorated = ChatTitleDecorator.decorateDisplayName(player, original);
        if (decorated != original) {
            cir.setReturnValue(decorated);
        }
    }
}
