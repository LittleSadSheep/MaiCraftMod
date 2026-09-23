// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** 两根轴定义一条原生传送带；几何规则服务所有产线，不知道目标产品或配方名称。 */
public final class CreateBeltGeometry {
    public record Span(BlockPos first, BlockPos second, Direction.Axis axis, List<BlockPos> cells,
                       Direction facing, String slope) {
        public Span { first = first.immutable(); second = second.immutable(); cells = List.copyOf(cells); }
        public Map<String, String> properties(BlockPos at, boolean pulley) {
            if (!cells.contains(at)) throw new IllegalArgumentException("position is outside the declared belt");
            String part = at.equals(first) ? "start" : at.equals(second) ? "end" : pulley ? "pulley" : "middle";
            return Map.of("facing", facing.getSerializedName(), "slope", slope, "part", part, "waterlogged", "false");
        }
        public boolean processesItems() { return slope.equals("horizontal"); }
    }
    private CreateBeltGeometry() {}

    public static Span between(BlockPos first, BlockPos second, Direction.Axis axis, int maximumLength) {
        // 两轴同轴、轴向垂直于带长，路径只能直行或保持四十五度斜率；长度沿用原生严格小于上限的规则。
        if (axis == null || maximumLength < 2 || first.equals(second)
                || first.distSqr(second) >= (long) maximumLength * maximumLength)
            throw new IllegalArgumentException("belt_endpoints_or_length_invalid");
        BlockPos delta = second.subtract(first);
        int x = delta.getX(), y = delta.getY(), z = delta.getZ();
        int same = (Math.abs(x) == Math.abs(y) ? 1 : 0) + (Math.abs(y) == Math.abs(z) ? 1 : 0)
                + (Math.abs(z) == Math.abs(x) ? 1 : 0);
        if (axis.choose(x, y, z) != 0 || same != 1 || axis == Direction.Axis.Y && x != 0 && z != 0)
            throw new IllegalArgumentException("belt_axes_or_slope_incompatible");
        Direction.Axis travel = x == 0 ? Direction.Axis.Z : Direction.Axis.X;
        boolean positive = x == 0 && z == 0 ? y > 0 : travel.choose(x, 0, z) > 0;
        if (x == z) travel = axis == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X;
        Direction facing = Direction.get(positive ? Direction.AxisDirection.POSITIVE : Direction.AxisDirection.NEGATIVE, travel);
        String slope = axis == Direction.Axis.Y ? "sideways" : y == 0 ? "horizontal"
                : x == 0 && z == 0 ? "vertical" : y > 0 ? "upward" : "downward";
        int distance = Math.max(Math.abs(x), Math.max(Math.abs(y), Math.abs(z)));
        List<BlockPos> cells = new ArrayList<>();
        for (int step = 0; step <= distance; step++)
            cells.add(first.offset(Integer.signum(x) * step, Integer.signum(y) * step, Integer.signum(z) * step));
        return new Span(first, second, axis, cells, facing, slope);
    }
}
