package com.haojing.battlepass.client.mixin;

import com.haojing.battlepass.client.cape.CapeTextureManager;
import com.haojing.battlepass.client.net.OnlineCapes;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;

/**
 * 披风渲染 Mixin：在 {@link AbstractClientPlayerEntity#getSkinTextures()} 返回时，
 * 把返回的 SkinTextures（1.21 是 record）里的"披风纹理"分量替换成本模组下发的披风。
 *
 * <p>为什么用反射重建 record：SkinTextures 的组件数量/顺序随版本变化，硬编码下标
 * 会在升级时崩。这里用 record 的 canonical 构造器 + 按组件名（含 "cape"）定位替换，
 * 对版本漂移更稳。整个注入包在 try/catch 里，失败时静默回退原版披风。
 */
@Environment(EnvType.CLIENT)
@Mixin(AbstractClientPlayerEntity.class)
public abstract class AbstractClientPlayerEntityMixin {

    @Inject(method = "getSkinTextures", at = @At("RETURN"), cancellable = true)
    private void haojing$replaceCape(CallbackInfoReturnable<Object> cir) {
        try {
            AbstractClientPlayerEntity self = (AbstractClientPlayerEntity) (Object) this;
            String capeId = OnlineCapes.capeOf(self.getUuid());
            if (capeId == null || capeId.isEmpty()) {
                return;
            }
            Identifier capeTex = CapeTextureManager.get(capeId);
            if (capeTex == null) {
                return;
            }

            Object original = cir.getReturnValue();
            if (original == null) {
                return;
            }

            Class<?> cls = original.getClass();
            RecordComponent[] components = cls.getRecordComponents();
            if (components == null || components.length == 0) {
                return;
            }

            // 读当前每个分量的值
            Object[] args = new Object[components.length];
            int capeIndex = -1;
            for (int i = 0; i < components.length; i++) {
                Method accessor = components[i].getAccessor();
                accessor.setAccessible(true);
                args[i] = accessor.invoke(original);
                String name = components[i].getName();
                // 定位披风分量：名字含 "cape" 且类型是 Identifier
                if (name != null && name.toLowerCase().contains("cape")
                        && Identifier.class.isAssignableFrom(components[i].getType())) {
                    capeIndex = i;
                }
            }

            if (capeIndex < 0) {
                return;
            }
            args[capeIndex] = capeTex;

            Constructor<?> ctor = cls.getDeclaredConstructors()[0];
            ctor.setAccessible(true);
            Object rebuilt = ctor.newInstance(args);
            cir.setReturnValue(rebuilt);
        } catch (Throwable ignored) {
            // 任何反射/版本不匹配都静默回退原版披风，绝不影响游戏。
        }
    }
}
