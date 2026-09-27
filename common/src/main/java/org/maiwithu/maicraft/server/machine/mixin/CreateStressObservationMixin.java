// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine.mixin;

import org.maiwithu.maicraft.server.machine.create.CreateStressView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;

/** inspect_machine 读取 Create 自己已经同步的网络账，不因观察而创建或更新动力网络。 */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.kinetics.base.KineticBlockEntity", remap = false)
public abstract class CreateStressObservationMixin implements CreateStressView {
    @Shadow protected float capacity;
    @Shadow protected float stress;
    @Shadow private int networkSize;
    @Shadow public abstract float getTheoreticalSpeed();
    @Shadow public abstract boolean hasSource();
    @Unique private long maicraft$selfRotationSync;
    @Override public float maicraft$stressCapacity() { return capacity; }
    @Override public float maicraft$stressLoad() { return stress; }
    @Override public int maicraft$stressNetworkSize() { return networkSize; }
    @Override public long maicraft$selfRotationSync() { return maicraft$selfRotationSync; }
    @Inject(method = "read", at = @At("TAIL"), require = 0)
    private void maicraft$observeRotation(CompoundTag data, HolderLookup.Provider registries, boolean clientPacket, CallbackInfo callback) {
        // 只记服务端发来的原生转动同步；客户端 turn() 的 inUse 预测不计入，避免把本地动画当作真实发电。
        float speed = getTheoreticalSpeed();
        if (clientPacket && Float.isFinite(speed) && speed != 0 && !hasSource()) maicraft$selfRotationSync++;
    }
}
