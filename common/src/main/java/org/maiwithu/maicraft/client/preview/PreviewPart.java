// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.preview;

import java.util.Objects;
import java.util.Set;
import net.minecraft.core.BlockPos;

/**
 * 记录一个多部件结构的示意位置、物品名和安装面。这里不创建真实方块实体，也不验证部件能否实际连在一起。
 */
public record PreviewPart(BlockPos position, String itemId, String side) {
    public PreviewPart {
        position = Objects.requireNonNull(position).immutable();
        Objects.requireNonNull(itemId);
        if (!Set.of("center", "down", "up", "north", "south", "west", "east").contains(side))
            throw new IllegalArgumentException("unsupported preview part side: " + side);
    }
}
