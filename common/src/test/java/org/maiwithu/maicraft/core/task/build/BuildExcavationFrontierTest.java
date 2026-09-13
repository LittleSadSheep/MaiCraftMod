package org.maiwithu.maicraft.core.task.build;

import java.util.Set;
import net.minecraft.core.BlockPos;

public final class BuildExcavationFrontierTest {
    public static void main(String[] args) {
        BlockPos feet = new BlockPos(0, 65, 0), top = new BlockPos(2, 64, 0);
        BlockPos floor = new BlockPos(0, 59, 0), footing = feet.below();
        check(BuildExcavationFrontier.select(Set.of(floor, top), Set.of(), feet).equals(top),
                "open the surface before asking navigation to reach a buried basement floor");
        check(BuildExcavationFrontier.select(Set.of(footing, top), Set.of(), feet).equals(top),
                "retain the current footing while another cell can be worked");
        check(BuildExcavationFrontier.select(Set.of(floor, top), Set.of(top), feet) == null,
                "an inaccessible top layer must not redirect excavation into buried cells");
        check(BuildExcavationFrontier.select(Set.of(floor), Set.of(), feet).equals(floor),
                "advance to the lower layer after the overburden is removed");
        System.out.println("BuildExcavationFrontierTest: passed");
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
