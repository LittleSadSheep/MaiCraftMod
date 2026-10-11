// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.compat.sable;

import dev.simulated_team.simulated.network.packets.AssemblePacket;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.network.PacketDistributor;

import org.maiwithu.maicraft.compat.sable.SimulatedParts;

/**
 * Simulated 载具部件的读写端：拉物理组装器的杆发 Simulated 自己的组装包（和玩家把杆拉到底松手时发的一样）。只翻译，不判断。
 */
public final class SimulatedPartReads implements SimulatedParts {

    @Override public void pullAssembler(BlockPos at) {
        PacketDistributor.sendToServer(new AssemblePacket(at));
    }
}
