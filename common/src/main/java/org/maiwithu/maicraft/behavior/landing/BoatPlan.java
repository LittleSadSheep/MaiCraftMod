// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.landing;

import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.Vec3;

/**
 * 船落地的打算：人落在哪一格、船放在哪一点、用身上哪种船（坐现成的空船时为 null，另给那条船）、
 * 是在空中边掉边放（airborne），还是站在出发点就把船放到下面、直接坐进去。
 */
public record BoatPlan(BlockPos landing, Vec3 spawn, Item item, UUID existingBoat, boolean airborne) {

    public BoatPlan {
        landing = landing.immutable();
    }

    /** 换成准星实际点到的那一点放船。 */
    BoatPlan at(Vec3 actualSpawn) {
        return new BoatPlan(landing, actualSpawn, item, existingBoat, airborne);
    }
}
