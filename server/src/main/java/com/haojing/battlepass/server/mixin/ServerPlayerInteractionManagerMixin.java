package com.haojing.battlepass.server.mixin;

import com.haojing.battlepass.server.egg.EggEvents;
import com.haojing.battlepass.server.task.TaskEvents;
import net.minecraft.block.BlockState;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.network.ServerPlayerInteractionManager;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 用途：检测"玩家放置了方块"。需求文档 §5.6 允许 Mixin 的白名单包含本类；
 * Fabric API 没有方块放置事件（已全量扫描确认），因此只能走 Mixin。
 *
 * <p><b>判据</b>：比较右键前后目标位置的方块状态 —— 由 A 变为非空气的 B 即发生了一次放置。
 * 为什么不用"调用栈里出现了 useOnBlock"之类的判据：那依赖具体内部调用链，
 * 原版一重构就失效；而"方块状态真的变了"是放置这一行为不可回避的结果，稳定得多。
 *
 * <p><b>为什么要额外判断手持物是 {@link BlockItem}</b>：打火石会点火、骨粉会长草，
 * 它们同样会让目标位置的方块状态发生变化，但那不是"放置方块"，不该计入建造任务。
 *
 * <p><b>为什么用 {@link Unique} 字段暂存前置状态是安全的</b>：每个玩家连接对应一个
 * {@code ServerPlayerInteractionManager} 实例，且该方法只在服务端线程执行，
 * 因此不存在同一实例并发进入的情况。
 */
@Mixin(ServerPlayerInteractionManager.class)
public abstract class ServerPlayerInteractionManagerMixin {

    /** 右键前目标位置的方块状态。命名带模组前缀以免与原版/其他模组字段冲突。 */
    @Unique
    private BlockState haojingbp$stateBeforeUse;

    @Inject(method = "interactBlock", at = @At("HEAD"))
    private void haojingbp$captureStateBeforeUse(ServerPlayerEntity player, World world, ItemStack stack,
                                                 Hand hand, BlockHitResult hitResult,
                                                 CallbackInfoReturnable<ActionResult> cir) {
        if (world.isClient()) {
            return;
        }

        haojingbp$stateBeforeUse = world.getBlockState(hitResult.getBlockPos().offset(hitResult.getSide()));
    }

    @Inject(method = "interactBlock", at = @At("RETURN"))
    private void haojingbp$detectBlockPlacement(ServerPlayerEntity player, World world, ItemStack stack,
                                                Hand hand, BlockHitResult hitResult,
                                                CallbackInfoReturnable<ActionResult> cir) {
        BlockState before = haojingbp$stateBeforeUse;
        // 无论后续如何判断都要清空，避免把上一次的结果带到下一次调用里（例如上一轮提前 return）。
        haojingbp$stateBeforeUse = null;

        if (before == null || world.isClient()) {
            return;
        }

        if (!(stack.getItem() instanceof BlockItem)) {
            return;
        }

        BlockPos target = hitResult.getBlockPos().offset(hitResult.getSide());
        BlockState after = world.getBlockState(target);

        // BlockState 实例在服务端是驻留（interned）的，因此引用比较即可判断有无变化。
        if (after == before || after.isAir()) {
            return;
        }

        TaskEvents.onBlockPlaced(player, after, target, world);
        // 阶段 6：同一次放置也要参与彩蛋判定（§6 月下筑者的"累计放置 100 方块"、
        // 樱落归镐的"在樱花林种下树苗"）。放在同一个 Mixin 里，避免为了同一件事再注入一次。
        EggEvents.onBlockPlaced(player, after, target, world);
    }
}
