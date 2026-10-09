// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.deposit;

import java.util.Objects;
import java.util.Optional;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.acquire.ClientDigsBlocks;
import org.maiwithu.maicraft.kernel.task.Action;

/** 挖开容器盖子的接缝实现：压住箱子上方的堵块走与采集同一条原生挖掘路径。 */
final class NativeLidDigging implements DepositSeams.DigsLid {

    private final ClientDigsBlocks digsBlocks;

    NativeLidDigging(ClientDigsBlocks digsBlocks) {
        this.digsBlocks = Objects.requireNonNull(digsBlocks);
    }

    @Override
    public Optional<Action> dig(BlockPos lidCell) {
        return digsBlocks.dig(lidCell);
    }
}
