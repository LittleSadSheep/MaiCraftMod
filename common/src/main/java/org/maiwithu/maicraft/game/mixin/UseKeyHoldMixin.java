// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.maiwithu.maicraft.game.ClientHooks;
import org.maiwithu.maicraft.game.player.UseKeyHold;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 把任务的持续持用投影成"按住使用键"：原版每刻读一次使用键决定进食、拉弓是否继续，
 * 任务交还控制权的刻里由 {@link UseKeyHold} 的投影顶上，防止原版把正在吃的食物误当作已经松手。
 * 结束持用后不再投影，也不改变其他按键的读值。
 */
@Mixin(Minecraft.class)
public abstract class UseKeyHoldMixin {
    @WrapOperation(method = "handleKeybinds", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/KeyMapping;isDown()Z"))
    private boolean maicraft$keepOwnedItemUse(KeyMapping key, Operation<Boolean> original) {
        boolean actual = original.call(key);
        Minecraft minecraft = (Minecraft) (Object) this;
        if (key != minecraft.options.keyUse) return actual;
        UseKeyHold hold = ClientHooks.useKeyHold();
        return hold != null ? hold.project(minecraft, actual) : actual;
    }
}
