// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.follow;

import org.maiwithu.maicraft.behavior.travel.DimensionRoute;

/**
 * 他走进的那扇门通到哪个维度：下界门连着主世界与下界，末地门从主世界通到末地。
 * 末地里的末地门是打败末影龙后的回程门，出行不代劳，答不出（为 null）。纯函数。
 */
final class PortalTrail {

    static final String NETHER_PORTAL = "minecraft:nether_portal";
    static final String END_PORTAL = "minecraft:end_portal";

    private PortalTrail() {}

    /**
     * @param dimension 他走进门时所在的维度
     * @param portal    他身子沾着的门方块 ID
     * @return 跟进去会到的维度；跟不进去为 null
     */
    static String destination(String dimension, String portal) {
        if (NETHER_PORTAL.equals(portal)) {
            if (DimensionRoute.NETHER.equals(dimension)) return DimensionRoute.OVERWORLD;
            if (DimensionRoute.OVERWORLD.equals(dimension)) return DimensionRoute.NETHER;
            return null;
        }
        if (END_PORTAL.equals(portal)) {
            return DimensionRoute.END.equals(dimension) ? null : DimensionRoute.END;
        }
        return null;
    }
}
