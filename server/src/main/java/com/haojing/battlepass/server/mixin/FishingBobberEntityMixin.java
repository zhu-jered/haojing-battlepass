package com.haojing.battlepass.server.mixin;

import com.haojing.battlepass.server.egg.EggEvents;
import com.haojing.battlepass.server.task.TaskEvents;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.FishingBobberEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 用途：检测"玩家钓鱼成功"。需求文档 §5.6 的白名单包含 FishingBobberEntity；
 * Fabric API 没有钓鱼事件，因此只能走 Mixin。
 *
 * <p><b>判据</b>：{@code use(ItemStack)} 的返回值 —— 读原版反编译源码可知：
 * 钓上东西时该方法返回 <b>1</b>（勾到实体返回 3 或 5，什么都没勾到返回 0）。
 * 这是"确实产出了渔获"的精确信号。
 *
 * <p>为什么不在 {@code tickFishingLogic} 里判断"鱼咬钩"：鱼咬钩不等于钓上来 ——
 * 玩家可能不收杆、可能收杆时鱼已被别的因素清掉。需求文档说的是"钓鱼成功"，计费点应当是收杆。
 */
@Mixin(FishingBobberEntity.class)
public abstract class FishingBobberEntityMixin {

    @Inject(method = "use", at = @At("RETURN"))
    private void haojingbp$detectFishingSuccess(ItemStack usedItem, CallbackInfoReturnable<Integer> cir) {
        Integer result = cir.getReturnValue();

        // 原版约定：1 = 钓上渔获。其余取值（0/3/5）都不是"钓到东西"。
        if (result == null || result != 1) {
            return;
        }

        FishingBobberEntity self = (FishingBobberEntity) (Object) this;
        PlayerEntity owner = self.getPlayerOwner();

        if (owner instanceof ServerPlayerEntity serverPlayer) {
            TaskEvents.onFishingSuccess(serverPlayer, self.getEntityWorld());
            // 阶段 6：§6 鱼信彩蛋（雷雨 + 河流群系 + 钓鱼成功一次）复用同一个判据。
            EggEvents.onFishingSuccess(serverPlayer, self.getEntityWorld());
        }
    }
}
