// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create.transmission;

import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.maiwithu.maicraft.core.integration.create.transmission.KineticRouteGeometry.*;

public final class KineticRpmBudgetTest {
    private static int checks;
    public static void main(String[] args) {
        gainAndReductionUseTheActualTargetRequirement();
        intermediateOverspeedCannotHideBehindAUnitNetRatio();
        uncertainAndNonfiniteInputsFailClosed();
        System.out.println("KineticRpmBudgetTest: " + checks + " checks passed");
    }
    private static void gainAndReductionUseTheActualTargetRequirement() {
        Plan speedup = plan("large_cogwheel", List.of(gear(1, 4, 1, "cogwheel"), shaft(1, 5, 1)), new BlockPos(1, 6, 1));
        var ready = KineticRpmBudget.validate(speedup, 16, 32, 256);
        check(ready.targetRpm() == -32 && ready.maximumNewRpm() == 32, "16 source RPM may legitimately satisfy a 32-RPM target through its audited doubling gear");
        check(!KineticRpmBudget.accepts(speedup, 16, 64, 256), "speed gain does not fulfill a larger target requirement");
        Plan reduction = plan("cogwheel", List.of(gear(1, 4, 1, "large_cogwheel"), shaft(1, 5, 1)), new BlockPos(1, 6, 1));
        check(!KineticRpmBudget.accepts(reduction, 32, 32, 256), "a cheaper reduction is rejected before building a machine that needs the original speed");
        check(KineticRpmBudget.validate(reduction, -64, 32, 256).targetRpm() == 32, "source sign and gear reversal remain explicit");
        rejects(() -> KineticRpmBudget.validate(speedup, 256, 32, 256), "kinetic_target_overspeed");
    }
    private static void intermediateOverspeedCannotHideBehindAUnitNetRatio() {
        Plan route = plan("large_cogwheel", List.of(gear(1, 4, 1, "cogwheel"), gear(2, 4, 2, "large_cogwheel"),
                shaft(2, 5, 2), shaft(2, 6, 2)), new BlockPos(2, 7, 2));
        check(route.transmissionRatio() == 1, "the test's second gear reduces back to the source's target RPM");
        rejects(() -> KineticRpmBudget.validate(route, 256, 128, 256), "kinetic_new_node_overspeed");
        var accepted = KineticRpmBudget.validate(route, 128, 128, 256);
        check(accepted.targetRpm() == 128 && accepted.maximumNewRpm() == 256, "the complete node budget accepts the exact configured boundary");
        rejects(() -> KineticRpmBudget.validate(route, 128, 128, 200), "kinetic_new_node_overspeed");
    }
    private static void uncertainAndNonfiniteInputsFailClosed() {
        Plan disconnected = plan("large_cogwheel", List.of(shaft(5, 5, 5)), new BlockPos(5, 6, 5));
        rejects(() -> KineticRpmBudget.validate(disconnected, 16, 16, 256), "kinetic_route_ratio_unverified");
        Plan speedup = plan("large_cogwheel", List.of(gear(1, 4, 1, "cogwheel"), shaft(1, 5, 1)), new BlockPos(1, 6, 1));
        for (double speed : new double[] {0, Double.NaN, Double.POSITIVE_INFINITY}) check(!KineticRpmBudget.accepts(speedup, speed, 0, 256), "unknown source rotation never authorizes construction");
        for (double maximum : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY}) check(!KineticRpmBudget.accepts(speedup, 16, 32, maximum), "a missing native maximum is not assumed to be safe");
        check(!KineticRpmBudget.accepts(speedup, 16, Double.NaN, 256), "invalid target requirement stays explicit");
    }
    private static Plan plan(String sourceFamily, List<Placement> blocks, BlockPos target) {
        Endpoint source = new Endpoint(new BlockPos(0, 4, 0), Direction.Axis.Y, List.of(Direction.UP), sourceFamily);
        Endpoint sink = new Endpoint(target, Direction.Axis.Y, List.of(Direction.DOWN), "shaft");
        return new Plan("fixture", source, null, sink, Direction.DOWN, blocks, List.of(), Map.of());
    }
    private static Placement gear(int x, int y, int z, String family) { return new Placement(new BlockPos(x, y, z), "create:" + family, Map.of("axis", "y")); }
    private static Placement shaft(int x, int y, int z) { return gear(x, y, z, "shaft"); }
    private static void rejects(Runnable action, String code) {
        try { action.run(); throw new AssertionError("invalid RPM budget accepted"); }
        catch (IllegalArgumentException expected) { check(expected.getMessage().equals(code), "expected " + code + " but got " + expected.getMessage()); }
    }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
