// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import java.util.List;
import net.minecraft.core.BlockPos;

/** Standalone regression entry point; uses the common Minecraft runtime classpath. */
public final class MachineControlTest {
    private MachineControlTest() {}

    public static void main(String[] args) {
        BlockPos a = new BlockPos(2, 70, 3);
        BlockPos b = new BlockPos(5, 70, 3);
        check(MachineControl.selectControl(List.of(), null) == null, "no invented control");
        check(MachineControl.selectControl(List.of(a), null).equals(a), "one observed control is selectable");
        check(MachineControl.selectControl(List.of(a, b), null) == null, "multiple controls require identity");
        check(MachineControl.selectControl(List.of(a, b), b).equals(b), "named control disambiguates exactly");
        check(MachineControl.selectControl(List.of(a), b) == null, "missing named control never falls back");
        check(MachineControl.contains(BlockPos.ZERO, 8, new BlockPos(8, -8, 8)), "boundary matches surveyed cube");
        check(!MachineControl.contains(BlockPos.ZERO, 8, new BlockPos(9, 0, 0)), "outside cube is rejected");
        check(!MachineControl.contains(new BlockPos(Integer.MAX_VALUE, 0, 0), 8,
                new BlockPos(Integer.MIN_VALUE, 0, 0)), "distance does not wrap across integer extremes");
        var mutable = new BlockPos.MutableBlockPos(2, 3, 4);
        var request = new MachineControl.Request("minecraft:overworld", mutable, 0, "observed", true, mutable);
        mutable.set(50, 50, 50);
        check(request.center().equals(new BlockPos(2, 3, 4)), "request freezes center");
        check(request.controlPosition().equals(request.center()), "request freezes selected control");
        rejected(() -> new MachineControl.Request("minecraft:overworld", a, 9, "observed", true, null),
                "oversized control radius cannot exceed fingerprint scope");
        rejected(() -> new MachineControl.Request("minecraft:overworld", a, -1, "observed", true, null),
                "negative radius is rejected");
        rejected(() -> new MachineControl.Request("minecraft:overworld", a, 1, "", true, null),
                "missing survey fingerprint is rejected");
        rejected(() -> new MachineControl.Request("minecraft:overworld", a, 1, "observed", true, b),
                "named control outside reviewed area is rejected");
        rejected(() -> new MachineControl.Request("INVALID DIMENSION", a, 1, "observed", true, null),
                "invalid dimension is rejected");
        System.out.println("MachineControlTest: 15 checks passed");
    }

    private static void rejected(Runnable operation, String message) {
        try {
            operation.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
