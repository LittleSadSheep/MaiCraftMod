// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import org.maiwithu.maicraft.core.integration.ultimine.UltimineInputLease;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 仅补入任务持有的菜单修饰键；原生提示界面、形状切换和权限规则继续完整执行。 */
@Pseudo
@Mixin(targets = "dev.ftb.mods.ftbultimine.client.FTBUltimineClient", remap = false)
public abstract class UltimineInputMixin {
    @ModifyReturnValue(method = "isMenuSneaking()Z", at = @At("RETURN"), remap = false, require = 0)
    private boolean maicraft$ownedMenuModifier(boolean actual) { return UltimineInputLease.menuModifier(actual); }
    // 原生处理按键之前先释放失效租约，防止任务结束后下一次挖掘仍带着连锁。
    @Inject(method = "clientTick", at = @At("HEAD"), remap = false, require = 0)
    private void maicraft$releaseExpiredKey(CallbackInfo callback) { UltimineInputLease.beforeNativeTick(); }
    @Inject(method = "clientTick", at = @At("RETURN"), remap = false, require = 0)
    private void maicraft$observeNativePanel(CallbackInfo callback) { UltimineInputLease.nativeTickFinished(); }
    @Inject(method = "renderGameOverlay", at = @At("RETURN"), remap = false, require = 0)
    // 在原生界面实际绘制后记录可见证明，不用人工拼出的提示替代玩家看到的原生预览。
    private void maicraft$observeVisiblePanel(CallbackInfo callback) { UltimineInputLease.hudRendered(); }
}
