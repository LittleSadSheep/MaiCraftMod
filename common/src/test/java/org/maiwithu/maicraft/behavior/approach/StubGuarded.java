// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.approach;

import net.minecraft.core.BlockPos;

import java.util.HashSet;
import java.util.Set;

/** 受保护格替身：声明的格子不许踩，其余随便。 */
final class StubGuarded implements ProtectedCells {

    private final Set<BlockPos> cells = new HashSet<>();

    void add(BlockPos at) {
        cells.add(at);
    }

    @Override public boolean contains(BlockPos at) {
        return cells.contains(at);
    }
}
