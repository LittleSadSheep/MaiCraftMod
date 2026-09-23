// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;

/** 原生带完成后才装附件，附件之间仍遵守依赖；错误的循环关系必须在角色行动前被拒绝。 */
public final class MachinePlacementDependenciesTest {
    public static void main(String[] args) {
        BlockPos support = BlockPos.ZERO, first = support.above(), second = first.above(), independent = support.east();
        var layers = MachinePlacementDependencies.layers(Map.of(second, List.of(first), first, List.of(support), independent, List.of(support)));
        if (layers.size() != 2 || !layers.getFirst().containsAll(List.of(first, independent)) || !layers.getLast().equals(List.of(second)))
            throw new AssertionError("native support -> independent attachments -> dependent attachment");
        try { MachinePlacementDependencies.layers(Map.of(first, List.of(second), second, List.of(first))); throw new AssertionError("cyclic installation accepted"); }
        catch (IllegalArgumentException expected) { if (!expected.getMessage().contains("dependency_cycle")) throw expected; }
    }
}
