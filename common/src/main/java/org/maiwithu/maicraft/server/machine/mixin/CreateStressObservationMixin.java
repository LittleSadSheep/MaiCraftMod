// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine.mixin;

import org.maiwithu.maicraft.server.machine.create.CreateStressView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;

/** inspect_machine 读取 Create 自己已经同步的网络账，不因观察而创建或更新动力网络。 */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.kinetics.base.KineticBlockEntity", remap = false)
public abstract class CreateStressObservationMixin implements CreateStressView {
    @Shadow protected float capacity;
    @Shadow protected float stress;
    @Shadow private int networkSize;
    @Override public float maicraft$stressCapacity() { return capacity; }
    @Override public float maicraft$stressLoad() { return stress; }
    @Override public int maicraft$stressNetworkSize() { return networkSize; }
}
