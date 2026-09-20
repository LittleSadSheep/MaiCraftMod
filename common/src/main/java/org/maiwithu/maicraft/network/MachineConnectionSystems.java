// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.network;

import java.util.Set;

/** 两端共用连接查询的系统与介质约束；原版漏斗只申请物品检查，不借中立名称放行其他输送机制。 */
public final class MachineConnectionSystems {
    public static final String MINECRAFT = "minecraft";
    private static final Set<String> SYSTEMS = Set.of(MINECRAFT, "create", "ae2", "mekanism");
    private static final Set<String> MEDIA = Set.of("items", "fluids", "chemicals", "energy", "kinetic");

    private MachineConnectionSystems() {}

    public static boolean supports(String system, String medium) {
        return system != null && medium != null && SYSTEMS.contains(system) && MEDIA.contains(medium)
                && (!MINECRAFT.equals(system) || medium.equals("items"));
    }
}
