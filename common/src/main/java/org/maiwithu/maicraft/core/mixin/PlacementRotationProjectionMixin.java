// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.world.entity.Entity;
import org.maiwithu.maicraft.core.task.build.PlacementPlayerProjection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 机器原生放置直接读玩家角度时使用候选返回值；只读作用域按对象隔离，真实镜头和服务端玩家均不被改写。 */
@Mixin(Entity.class)
public abstract class PlacementRotationProjectionMixin {
    @ModifyReturnValue(method = "getYRot()F", at = @At("RETURN"))
    private float maicraft$projectPlacementYaw(float actual) {
        return PlacementPlayerProjection.projectYaw(this, actual);
    }

    @ModifyReturnValue(method = "getXRot()F", at = @At("RETURN"))
    private float maicraft$projectPlacementPitch(float actual) {
        return PlacementPlayerProjection.projectPitch(this, actual);
    }
}
