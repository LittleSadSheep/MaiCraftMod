// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import java.util.Optional;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

/** ME 设备读写端的替身：哪一格都不是 ME 设备，什么物品都不是部件；只管终端取货的测试用它。 */
final class NoGrid implements Ae2Grid {
    @Override public Optional<GridCell> cell(ClientLevel level, BlockPos at) {
        return Optional.empty();
    }

    @Override public boolean partItem(String itemId) {
        return false;
    }

    @Override public boolean cableItem(String itemId) {
        return false;
    }
}
