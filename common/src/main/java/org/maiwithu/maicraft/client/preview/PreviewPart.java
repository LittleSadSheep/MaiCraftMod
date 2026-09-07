// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.preview;

import java.util.Objects;
import java.util.Set;
import net.minecraft.core.BlockPos;

/** Supplemental multipart intent. Geometry is explicitly illustrative, never a simulated block entity. */
public record PreviewPart(BlockPos position, String itemId, String side) {
    public PreviewPart {
        position = Objects.requireNonNull(position).immutable();
        Objects.requireNonNull(itemId);
        if (!Set.of("center", "down", "up", "north", "south", "west", "east").contains(side))
            throw new IllegalArgumentException("unsupported preview part side: " + side);
    }
}
