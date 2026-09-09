// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.mixin;

import net.minecraft.world.inventory.AbstractContainerMenu;
import org.maiwithu.maicraft.core.integration.machine.assembly.MekanismFilterSync;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 观察 Mekanism 菜单同步完成的时机。未装模组或没有对应方法时可跳过钩子，不能据此伪造“已经同步”。 */
@Pseudo
@Mixin(targets = "mekanism.common.inventory.container.MekanismContainer", remap = false)
public abstract class MekanismContainerSyncMixin {
    // 布尔属性处理完成后登记到达；这里不读 value，而让观察器按菜单与属性号核对所需状态。
    @Inject(method = "handleWindowProperty(SZ)V", at = @At("RETURN"), remap = false, require = 0)
    private void maicraft$sorterBoolean(short property, boolean value, CallbackInfo callback) {
        MekanismFilterSync.received((AbstractContainerMenu) (Object) this, property, false);
    }
    // 字节数组属性走过滤器数据这一类通知，与上面的布尔属性分开记同步事实。
    @Inject(method = "handleWindowProperty(S[B)V", at = @At("RETURN"), remap = false, require = 0)
    private void maicraft$sorterFilters(short property, byte[] value, CallbackInfo callback) {
        MekanismFilterSync.received((AbstractContainerMenu) (Object) this, property, true);
    }
}
