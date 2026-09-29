// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create.transmission;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;

/** 检测可逆配方循环，以免只消耗另一项仍需用于施工的物品。 */
final class KineticSupplyProgress {
    private final Set<String> inventories=new HashSet<>();
    private String latest;
    boolean begin(Inventory inventory) {
        var signature=new StringBuilder();
        for(var stack:inventory.items) signature.append(BuiltInRegistries.ITEM.getKey(stack.getItem())).append(':')
                .append(stack.getCount()).append(':').append(stack.getComponentsPatch().hashCode()).append(';');
        String value=signature.toString();
        if(inventories.size()>=32 || !inventories.add(value))return false;
        latest=value;return true;
    }
    // 自卫打断的取料尚未结算，不把该次开始前的库存记成已经完成过一轮配方；更早的循环证据仍然保留。
    void interrupted(){if(latest!=null)inventories.remove(latest);latest=null;}
}
