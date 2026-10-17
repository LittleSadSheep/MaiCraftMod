// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.deposit;

import java.util.Objects;
import java.util.Optional;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.acquire.CollectsBlocks;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;

/** 挖开容器盖子的接缝实现：压住箱子的那一格走"走过去、挖掉、捡起掉落"的同一套，不隔空挖。 */
final class NativeLidDigging implements DepositSeams.DigsLid {

    private final CollectsBlocks collects;

    NativeLidDigging(CollectsBlocks collects) {
        this.collects = Objects.requireNonNull(collects);
    }

    @Override
    public Optional<Action> dig(BlockPos lidCell, Permissions permissions) {
        return collects.collect(lidCell, permissions);
    }
}
