// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.pathing.execute;

import net.minecraft.core.BlockPos;

/** A body-owned ground movement from the preceding client tick; waiting is not movement. */
public record NavigationStep(BlockPos from, BlockPos to) {
    public NavigationStep { from=from.immutable();to=to.immutable(); }
}
