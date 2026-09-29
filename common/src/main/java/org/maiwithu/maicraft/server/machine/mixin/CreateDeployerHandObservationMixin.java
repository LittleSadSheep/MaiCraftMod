// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine.mixin;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.server.machine.create.CreateDeployerHandView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 旁听原生机械手同步包，只复制观察值；不修改假玩家、库存、过滤器或加工进度。 */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.kinetics.deployer.DeployerBlockEntity", remap = false)
public abstract class CreateDeployerHandObservationMixin implements CreateDeployerHandView {
    @Unique private ItemStack maicraft$receivedHand = ItemStack.EMPTY;
    @Unique private long maicraft$handRevision;
    @Inject(method = "read", at = @At("TAIL"), require = 0)
    private void maicraft$observeHand(CompoundTag data, HolderLookup.Provider registries, boolean clientPacket, CallbackInfo callback) {
        // 只接收真实客户端同步中的 HeldItem；服务端载入存档和没有持料字段的动画包都不冒充新观察。
        if (!clientPacket || !data.contains("HeldItem", 10)) return;
        maicraft$receivedHand = ItemStack.parseOptional(registries, data.getCompound("HeldItem"));
        maicraft$handRevision++;
    }
    public ItemStack maicraft$receivedHandStack() { return maicraft$receivedHand.copy(); }
    public long maicraft$handUpdateRevision() { return maicraft$handRevision; }
}
