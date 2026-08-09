// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

/**
 * Public compatibility name for the receipt-driven, local first-person build task.
 *
 * <p>All construction behavior lives in {@link FirstPersonBuildCompanionTask};
 * this type keeps existing task factories and registrations source compatible.
 */
public final class BuildCompanionTask extends FirstPersonBuildCompanionTask {
    public BuildCompanionTask(LocalPlayer player, BuildTaskRecord record) {
        super(player, record);
    }

    /** Supply non-target world cells that pathing must preserve while approaching this build. */
    public void protectNavigationCells(Iterable<BlockPos> cells) {
        addProtectedNavigationCells(cells);
    }
}
