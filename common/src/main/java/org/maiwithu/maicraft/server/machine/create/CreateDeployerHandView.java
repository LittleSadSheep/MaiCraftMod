// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine.create;

import net.minecraft.world.item.ItemStack;

/** 只读服务端原生同步给客户端的机械手持料；没有收到持料字段时不能把默认空堆当作空槽证据。 */
public interface CreateDeployerHandView {
    ItemStack maicraft$receivedHandStack();
    long maicraft$handUpdateRevision();
}
