// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.sable;

import net.minecraft.core.BlockPos;

/**
 * Simulated（物理组装器、无线红石打字机、舵轮这些载具部件）的读写接缝：玩家能做的原生操作。只翻译，不判断。
 */
public interface SimulatedParts {

    /**
     * 拉一次物理组装器的杆：和玩家按住右键把杆拉到底、松手时发的一样；组装器在世界里就组装、在结构上就拆回。
     *
     * @param at 组装器所在格：在世界里是世界坐标，在结构上是结构地块里的坐标
     */
    void pullAssembler(BlockPos at);
}
