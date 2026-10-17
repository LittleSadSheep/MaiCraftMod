// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.approach;

import net.minecraft.core.BlockPos;

/**
 * 受保护格：只读接缝，问"这一格是不是不许踩"。
 * 哪些格子受保护由许可与保护模型说了算；接上实现之前，没有格子被当成受保护。
 */
public interface ProtectedCells {

    /** 这个站位格是否不许踩、也不许为了走过去而改动。 */
    boolean contains(BlockPos at);
}
