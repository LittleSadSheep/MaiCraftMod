// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.construction;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.StandingAndWallBlockItem;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * 蓝图核对：拿蓝图对世界逐格比，纯函数，给每格一个结论。
 *
 * <p>没加载的格记没加载，不猜；有一格没加载就不给"整份一致"的结论。留给机器的格只标出来，
 * 由机器按最终结构核对。范围内没声明的格有东西只作信息，不算失败。
 */
public final class BlueprintCheck {

    private BlueprintCheck() {}

    /** 一次核对的结果：每格的结论、每种结论几格、有没有格没加载。 */
    public record Result(Map<BlockPos, CellState> states, Map<CellState, Integer> counts, boolean complete) {
        public Result {
            states = Map.copyOf(states);
            counts = Map.copyOf(counts);
        }

        /** 还要施工引擎动手的格，按蓝图顺序。 */
        public List<BlockPos> needingWork(Blueprint blueprint) {
            List<BlockPos> out = new ArrayList<>();
            for (PlannedCell cell : blueprint.cells()) {
                if (states.get(cell.pos()).needsWork()) out.add(cell.pos());
            }
            return List.copyOf(out);
        }

        /** 全部已符合（留给机器的格不算在内，没加载的格算没对上）。 */
        public boolean allMatch() {
            return complete && states.values().stream().allMatch(s -> s == CellState.MATCHES || s == CellState.CHECKED_BY_MACHINE);
        }

        public int count(CellState state) {
            return counts.getOrDefault(state, 0);
        }
    }

    /** 逐格核对；{@code machineChecked} 之外的 {@link PlacedBy#MACHINE} 格同样出 CHECKED_BY_MACHINE。 */
    public static Result compare(Blueprint blueprint, ReadsBlocks world, Set<BlockPos> machineChecked) {
        Map<BlockPos, CellState> states = new LinkedHashMap<>();
        Map<CellState, Integer> counts = new EnumMap<>(CellState.class);
        boolean complete = true;
        for (PlannedCell cell : blueprint.cells()) {
            CellState state;
            if (cell.placedBy() == PlacedBy.MACHINE || machineChecked.contains(cell.pos())) {
                state = CellState.CHECKED_BY_MACHINE;
            } else if (!world.loaded(cell.pos())) {
                state = CellState.UNKNOWN;
                complete = false;
            } else {
                state = stateOf(cell, world.state(cell.pos()));
            }
            states.put(cell.pos(), state);
            counts.merge(state, 1, Integer::sum);
        }
        return new Result(states, counts, complete);
    }

    /** 范围内没声明的格里有东西的位置，只作信息；没加载的格不列。 */
    public static List<BlockPos> extras(Blueprint blueprint, ReadsBlocks world) {
        Blueprint.Bounds bounds = blueprint.bounds();
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(bounds.min(), bounds.max())) {
            if (blueprint.cellAt(pos).isPresent() || !world.loaded(pos)) continue;
            BlockState live = world.state(pos);
            if (!live.isAir() && !live.canBeReplaced()) out.add(pos.immutable());
        }
        return List.copyOf(out);
    }

    /** 一格的结论：清空看是不是空气，倒桶看是不是同种源液体，放方块看方块与点名的属性。 */
    public static CellState stateOf(PlannedCell cell, BlockState live) {
        return switch (cell.kind()) {
            case AIR -> live.isAir() ? CellState.MATCHES : CellState.WRONG_BLOCK;
            case FLUID_SOURCE -> live.getFluidState().isSource()
                    && live.getFluidState().getType() == cell.state().getFluidState().getType()
                    ? CellState.MATCHES : live.isAir() || live.canBeReplaced() ? CellState.MISSING : CellState.WRONG_BLOCK;
            case BLOCK -> blockState(cell, live);
        };
    }

    private static CellState blockState(PlannedCell cell, BlockState live) {
        BlockState wanted = cell.state();
        if (live.getBlock() != wanted.getBlock()) {
            // 火把、灯笼、旗这类立式与墙式共用一个物品：作者没点名属性时，落成另一形态也算这一格放好了。
            if (cell.required().isEmpty() && cell.item() instanceof StandingAndWallBlockItem
                    && live.getBlock().asItem() == cell.item()) {
                return CellState.MATCHES;
            }
            return live.isAir() || live.canBeReplaced() ? CellState.MISSING : CellState.WRONG_BLOCK;
        }
        for (String name : cell.required()) {
            Property<?> property = wanted.getBlock().getStateDefinition().getProperty(name);
            // 推开栅栏门会让它转向，朝向不作验收；其他点名的属性逐项比。
            if (property == BlockStateProperties.HORIZONTAL_FACING && wanted.getBlock() instanceof FenceGateBlock) continue;
            if (!live.hasProperty(property) || !live.getValue(property).equals(wanted.getValue(property))) {
                return CellState.WRONG_STATE;
            }
        }
        return CellState.MATCHES;
    }
}
