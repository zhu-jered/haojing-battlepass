package com.haojing.battlepass.server.mixin;

import com.haojing.battlepass.server.task.TaskEvents;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.TradeOutputSlot;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 用途：检测"玩家与村民完成了一次交易"。需求文档 §5.6 的白名单写作"交易屏幕交互类"；
 * Fabric API 没有交易事件，因此只能走 Mixin。
 *
 * <p><b>为什么选 TradeOutputSlot 而不是 MerchantScreenHandler</b>：
 * 交易界面里"取出产物槽"这一步才是真正成交。原版为此专门有 {@code TradeOutputSlot} 这个具名类，
 * 比注入匿名内部类稳定得多（匿名类的编号会随原版改动而变）。
 *
 * <p><b>判据</b>：读原版反编译源码可知，{@code onTakeItem} 里只有在
 * {@code tradeOffer.depleteBuyItems(...)} 成功（即材料确实被扣掉）之后才会调用
 * {@code merchant.trade(tradeOffer)}。因此注入到这次调用的位置，
 * 就精确等于"一笔交易完成"，不会把"点开界面又关掉"算进去。
 */
@Mixin(TradeOutputSlot.class)
public abstract class TradeOutputSlotMixin {

    @Inject(method = "onTakeItem",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/village/Merchant;trade(Lnet/minecraft/village/TradeOffer;)V"))
    private void haojingbp$detectTradeCompleted(PlayerEntity player, ItemStack stack, CallbackInfo ci) {
        if (player instanceof ServerPlayerEntity serverPlayer) {
            TaskEvents.onTradeCompleted(serverPlayer);
        }
    }
}
