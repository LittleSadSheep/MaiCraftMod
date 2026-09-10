// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import java.util.List;
import net.minecraft.core.BlockPos;

/**
 * 检查拉杆选择及菜单／拉杆请求共用的观察范围、坐标副本和格式；不执行点击或完整执行器的保护区与空手流程。
 */
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
        var region = new MachineSnapshots.Region("minecraft:overworld", BlockPos.ZERO, 8, "observed");
        check(region.contains(new BlockPos(8, -8, 8)), "boundary matches surveyed cube");
        check(!region.contains(new BlockPos(9, 0, 0)), "outside cube is rejected");
        var extreme = new MachineSnapshots.Region("minecraft:overworld", new BlockPos(Integer.MAX_VALUE, 0, 0), 8, "observed");
        check(!extreme.contains(new BlockPos(Integer.MIN_VALUE, 0, 0)), "distance does not wrap across integer extremes");
        var mutable = new BlockPos.MutableBlockPos(2, 3, 4);
        var request = new MachineControl.Request("minecraft:overworld", mutable, 0, "observed", true, mutable);
        var menu = new MachineMenu.OpenRequest("minecraft:overworld", mutable, 0, "observed", mutable);
        mutable.set(50, 50, 50);
        check(request.center().equals(new BlockPos(2, 3, 4)), "request freezes center");
        check(request.controlPosition().equals(request.center()), "request freezes selected control");
        check(menu.center().equals(request.center()) && menu.machinePosition().equals(request.center()),
                "menu requests freeze both coordinates without constructing a lever request");
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
        for (int radius : new int[]{-1, 9})
            rejected(() -> new MachineMenu.OpenRequest("minecraft:overworld", a, radius, "observed", a),
                    "menu radius must stay within the same fingerprint scope");
        rejected(() -> new MachineMenu.OpenRequest("minecraft:overworld", a, 1, "", a), "menu needs a fingerprint");
        rejected(() -> new MachineMenu.OpenRequest("INVALID DIMENSION", a, 1, "observed", a), "menu needs a valid dimension");
        rejected(() -> new MachineMenu.OpenRequest("minecraft:overworld", a, 1, "observed", b), "menu target must be inside the region");
        rejected(() -> new MachineMenu.OpenRequest("minecraft:overworld", a, 1, "observed", null), "menu still requires a selected target");
        check(new MachineControl.Request("minecraft:overworld", a, 1, "observed", false, null).controlPosition() == null,
                "a lever request still permits selection from a unique observed candidate");
        System.out.println("MachineControlTest: shared region and independent request contracts passed");
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
