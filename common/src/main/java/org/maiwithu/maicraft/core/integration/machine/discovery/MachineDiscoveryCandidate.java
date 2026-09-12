// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.discovery;

import java.util.List;
import net.minecraft.core.BlockPos;

/** Native presence and heuristic purpose clues are deliberately separate observations. */
public record MachineDiscoveryCandidate(String dimension, BlockPos position, String blockId,
                                        String blockEntityType, Boolean nativeContainer, String family,
                                        List<String> possibleRoles, String roleBasis, String source, long observedTick) {
    public MachineDiscoveryCandidate {
        position = position.immutable(); possibleRoles = List.copyOf(possibleRoles);
    }
}
