// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import java.util.Map;
import java.util.Set;
import net.minecraft.core.Direction;

public final class MachinePlacementItemsTest {
    public static void main(String[] args) {
        check(MachinePlacementItems.itemId("create:gearbox", Map.of()).equals("create:gearbox"), "default gearbox uses its normal native item");
        check(MachinePlacementItems.itemId("create:gearbox", Map.of("axis", "y")).equals("create:gearbox"), "Y-axis gearbox needs the normal item");
        for (String axis : new String[] {"x", "z"}) check(MachinePlacementItems.itemId("create:gearbox", Map.of("axis", axis)).equals("create:vertical_gearbox"),
                "horizontal gearbox axis needs the vertical native item for both materials and construction");
        check(MachinePlacementItems.itemId("create:shaft", Map.of("axis", "x")).equals("create:shaft"), "unrelated axis blocks retain their actual item");
        check(MachinePlacementItems.verticalGearboxAxis(Set.of(Direction.Axis.X), Direction.NORTH) == Direction.Axis.Z, "a native X-axis shaft neighbor selects Z regardless of candidate view");
        check(MachinePlacementItems.verticalGearboxAxis(Set.of(Direction.Axis.Z), Direction.EAST) == Direction.Axis.X, "a native Z-axis shaft neighbor selects X regardless of candidate view");
        check(MachinePlacementItems.verticalGearboxAxis(Set.of(), Direction.NORTH) == Direction.Axis.X, "no neighbor uses clockwise candidate horizontal view");
        check(MachinePlacementItems.verticalGearboxAxis(Set.of(), Direction.EAST) == Direction.Axis.Z, "candidate yaw can naturally select the other horizontal axis");
        check(MachinePlacementItems.verticalGearboxAxis(Set.of(Direction.Axis.X, Direction.Axis.Z), Direction.EAST) == Direction.Axis.Z,
                "conflicting native neighbor axes use the original item's view fallback");
        System.out.println("MachinePlacementItemsTest: native gearbox item identities and post-placement axis projection passed");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
