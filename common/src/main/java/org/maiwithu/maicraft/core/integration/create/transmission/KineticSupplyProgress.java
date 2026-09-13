// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create.transmission;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;

/** Detects reversible recipe loops that merely consume another still-required construction item. */
final class KineticSupplyProgress {
    private final Set<String> inventories=new HashSet<>();
    boolean begin(Inventory inventory) {
        var signature=new StringBuilder();
        for(var stack:inventory.items) signature.append(BuiltInRegistries.ITEM.getKey(stack.getItem())).append(':')
                .append(stack.getCount()).append(':').append(stack.getComponentsPatch().hashCode()).append(';');
        return inventories.size()<32&&inventories.add(signature.toString());
    }
}
