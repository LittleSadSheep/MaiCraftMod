// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** 检验连接器占地和加工平面，不用某种产品的固定尺寸或工位数量推导布局。 */
public final class CreateBeltGeometryTest {
    public static void main(String[] args) {
        var start = new BlockPos(2, 3, 4);
        var forward = CreateBeltGeometry.between(start, start.east(5), Direction.Axis.Z, 20);
        check(forward.cells().size() == 6 && forward.facing() == Direction.EAST && forward.processesItems(), "horizontal surface supports processing");
        check(forward.properties(start, false).get("part").equals("start")
                && forward.properties(start.east(5), false).get("part").equals("end"), "native endpoints retain their exact part roles");
        check(forward.properties(start.east(2), true).get("part").equals("pulley"), "declared intermediate shafts remain powered pulleys");
        var reverse = CreateBeltGeometry.between(start.east(5), start, Direction.Axis.Z, 20);
        check(reverse.facing() == Direction.WEST && reverse.cells().getLast().equals(start), "reverse belt preserves author direction");
        var slope = CreateBeltGeometry.between(start, start.offset(3, 3, 0), Direction.Axis.Z, 20);
        check(slope.slope().equals("upward") && !slope.processesItems() && slope.cells().size() == 4, "sloped transport is not advertised as a processing surface");
        check(CreateBeltGeometry.between(start, start.above(4), Direction.Axis.X, 20).slope().equals("vertical"), "vertical native geometry is represented");
        check(CreateBeltGeometry.between(start, start.east(4), Direction.Axis.Y, 20).slope().equals("sideways"), "vertical axles produce sideways belts");
        rejects(start, start, Direction.Axis.X, 20);
        rejects(start, start.east(20), Direction.Axis.Z, 20);
        rejects(start, start.east(3), Direction.Axis.X, 20);
        rejects(start, start.offset(2, 1, 0), Direction.Axis.Z, 20);
        rejects(start, start.offset(3, 0, 3), Direction.Axis.Y, 20);
    }
    private static void rejects(BlockPos first, BlockPos second, Direction.Axis axis, int limit) {
        try { CreateBeltGeometry.between(first, second, axis, limit); throw new AssertionError("invalid belt accepted"); }
        catch (IllegalArgumentException expected) { check(expected.getMessage().startsWith("belt_"), "precise native geometry failure"); }
    }
    private static void check(boolean condition, String detail) { if (!condition) throw new AssertionError(detail); }
}
