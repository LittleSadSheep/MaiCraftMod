// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

/**
 * 保留旧工厂使用的类名，实际施工全部由父类 FirstPersonBuildCompanionTask 执行。
 */
public final class BuildCompanionTask extends FirstPersonBuildCompanionTask {
    public BuildCompanionTask(LocalPlayer player, BuildTaskRecord record) {
        super(player, record);
    }

    /**
     * 把施工目标之外也需要保留的格子交给父类，例如靠近机器时不能拆掉的外围结构。
     */
    public void protectNavigationCells(Iterable<BlockPos> cells) {
        addProtectedNavigationCells(cells);
    }
}
