package org.maiwithu.maicraft.core.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.maiwithu.maicraft.client.actor.ItemUseInputLease;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 防止原版把任务正在吃的面包误当作已经松手；结束持用后不再投影，也不改变其他按键。 */
@Mixin(Minecraft.class)
public abstract class ItemUseInputMixin {
    @WrapOperation(method = "handleKeybinds", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/KeyMapping;isDown()Z"))
    private boolean maicraft$keepOwnedItemUse(KeyMapping key, Operation<Boolean> original) {
        boolean actual = original.call(key);
        Minecraft minecraft = (Minecraft) (Object) this;
        return key == minecraft.options.keyUse ? ItemUseInputLease.project(minecraft, actual) : actual;
    }
}
