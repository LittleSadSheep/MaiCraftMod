// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import it.unimi.dsi.fastutil.objects.Reference2IntMap;
import java.util.ArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import org.maiwithu.maicraft.server.machine.ae2.TransformProductionCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** 在 AE2 原生生成结束后读取当次消耗与产物；不调用配方执行器，也不改变生成调用的返回值。 */
@Pseudo
@Mixin(targets = "appeng.recipes.transform.TransformLogic", remap = false)
public abstract class Ae2TransformProductionMixin {
    @WrapOperation(method = "tryTransform", require = 0, remap = false,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"))
    private static boolean maicraft$observeSpawn(Level level, Entity emitted, Operation<Boolean> original,
            @Local(argsOnly = true) ItemEntity trigger, @Local RecipeHolder<?> recipe,
            @Local ArrayList<ItemStack> consumed, @Local Reference2IntMap<ItemEntity> claimed) {
        // 先冻结与 AE2 相同的实体中心流体格，再完成原版添加；被取消的生成不会记作成功生产。
        BlockPos at = BlockPos.containing(trigger.getX(), (trigger.getBoundingBox().minY + trigger.getBoundingBox().maxY) / 2, trigger.getZ());
        boolean accepted = original.call(level, emitted);
        TransformProductionCapture.spawned(level, emitted, accepted, at, recipe, consumed, claimed);
        return accepted;
    }
}
