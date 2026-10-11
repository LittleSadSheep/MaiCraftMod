// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import org.maiwithu.maicraft.ability.machine.spi.ExchangePoint;
import org.maiwithu.maicraft.ability.machine.spi.LineMaterials;
import org.maiwithu.maicraft.ability.machine.spi.MachineType;
import org.maiwithu.maicraft.behavior.construction.PlannedCell;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 接网络的线路：从现场与机器类型落实两端的接线面、看现场哪些格能铺，交给线路规划挑一条，
 * 再把挑出的线路写成一份机器蓝图交给施工——目标有档案就并进那份档案，没有就按目标那一格起一份。
 */
final class LineRoutes {

    private final MachineServices services;
    private final MachineArchives archives;

    LineRoutes(MachineServices services, MachineArchives archives) {
        this.services = services;
        this.archives = archives;
    }

    /** 这些格上能接这种线的面：机器类型给的进出口里介质对得上、分面的那几面。 */
    List<RoutePlanner.Attach> attaches(Collection<BlockPos> cells, LineMaterials materials) {
        List<RoutePlanner.Attach> attaches = new ArrayList<>();
        for (BlockPos cell : cells) {
            Optional<BlockState> state = services.world().stateAt(cell);
            if (state.isEmpty()) continue;
            MachineType type = services.claiming(state.get());
            if (type == null) continue;
            for (ExchangePoint point : type.exchangePoints(state.get(), cell)) {
                if (point.side() == null || point.medium() != materials.medium()) continue;
                attaches.add(new RoutePlanner.Attach(cell.relative(point.side()), point.side().getAxis(), cell));
            }
        }
        return attaches;
    }

    /** 这些格里能当跨越一头的（已有的链条输送机这类）。 */
    List<BlockPos> spanEnds(Collection<BlockPos> cells, LineMaterials materials) {
        if (materials.span().isEmpty()) return List.of();
        LineMaterials.Span span = materials.span().get();
        return cells.stream().filter(cell -> services.world().stateAt(cell).map(span::isEnd).orElse(false)).toList();
    }

    /** 现场：空气、草这类能直接放的格才铺；岩浆不铺；没加载的不当空格。 */
    RoutePlanner.Ground ground() {
        return new RoutePlanner.Ground() {
            @Override public boolean free(BlockPos at) {
                return services.world().stateAt(at).map(LineRoutes::placeable).orElse(false);
            }

            @Override public Optional<BlockState> state(BlockPos at) {
                return services.world().stateAt(at);
            }
        };
    }

    static boolean placeable(BlockState state) {
        if (state.getFluidState().is(FluidTags.LAVA)) return false;
        return state.isAir() || state.canBeReplaced();
    }

    /**
     * 这次能不能新放一个跨越的头：整台机器里这种方块已有几个，加上这一个不超过实例配置的上限就行；
     * 没给上限的方块不限。目标没有档案时只数这次新放的。
     */
    boolean spanAllowed(LineMaterials materials, MachineArchive archive) {
        if (materials.span().isEmpty()) return false;
        String block = BuiltInRegistries.BLOCK.getKey(materials.span().get().end().getBlock()).toString();
        Integer limit = services.blockLimits().get(block);
        if (limit == null) return true;
        long existing = archive == null ? 0 : archive.blueprint().cells().stream()
                .filter(cell -> BuiltInRegistries.BLOCK.getKey(cell.state().getBlock()).toString().equals(block))
                .count();
        return existing + 1 <= limit;
    }

    /**
     * 把线路写成施工输入：目标有档案就把线路当补丁并进去、按档案锚点施工；没有就按目标那一格起一份新档案，
     * 名字照 machine_build 的规矩起"机器@x,y,z"，这个名字已经有档案就并进它。
     */
    MachineBuildInput buildInput(RoutePlanner.Plan plan, BlockPos targetCell, String dimension,
            MachineArchive archive, Permissions permissions) {
        MachineArchive into = archive;
        String name = archive != null ? archive.name()
                : "机器@" + targetCell.getX() + "," + targetCell.getY() + "," + targetCell.getZ();
        if (into == null) into = archives.find(name).orElse(null);
        BlockPos anchor = into != null ? into.anchor() : targetCell;
        MachineBlueprint route = blueprintOf(plan, anchor);
        MachineBlueprint merged = into != null ? MachinePatch.merge(into.blueprint(), route) : route;
        WorldPosition at = new WorldPosition(anchor.getX(), anchor.getY(), anchor.getZ(),
                into != null ? into.dimension() : dimension);
        return new MachineBuildInput(false, merged, at, name, into, permissions,
                "铺一条线路接到 (" + targetCell.getX() + "," + targetCell.getY() + "," + targetCell.getZ() + ")");
    }

    /** 线路写成相对锚点的蓝图：每格线材要按轴向放的点名 axis，跨越写成一段安装段。 */
    static MachineBlueprint blueprintOf(RoutePlanner.Plan plan, BlockPos anchor) {
        List<PlannedCell> cells = new ArrayList<>();
        for (RoutePlanner.Placed placed : plan.cells()) {
            Set<String> required = placed.state().hasProperty(BlockStateProperties.AXIS) ? Set.of("axis") : Set.of();
            cells.add(PlannedCell.block(placed.pos().subtract(anchor), placed.state(), required));
        }
        List<MachineBlueprint.Segment> installations = plan.span()
                .map(span -> List.of(new MachineBlueprint.Segment(span.kind(),
                        List.of(span.from().subtract(anchor), span.to().subtract(anchor)))))
                .orElse(List.of());
        return new MachineBlueprint(cells, List.of(), installations, List.of(), List.of());
    }

    /** 线路里每格放了什么，给结果看：方块 ID 加坐标。 */
    static List<String> describe(RoutePlanner.Plan plan) {
        List<String> lines = new ArrayList<>();
        for (RoutePlanner.Placed placed : plan.cells()) {
            lines.add(BuiltInRegistries.BLOCK.getKey(placed.state().getBlock()) + propertiesOf(placed.state())
                    + " @" + placed.pos().toShortString());
        }
        plan.span().ifPresent(span -> lines.add(span.kind() + " " + span.from().toShortString() + " → "
                + span.to().toShortString()));
        return lines;
    }

    private static String propertiesOf(BlockState state) {
        return state.hasProperty(BlockStateProperties.AXIS)
                ? "[axis=" + state.getValue(BlockStateProperties.AXIS).getSerializedName() + "]" : "";
    }

    /** 一份档案的全部格（绝对坐标）：档案范围里找要接线的机器格。 */
    static List<BlockPos> cellsOf(MachineArchive archive) {
        List<BlockPos> cells = new ArrayList<>();
        for (PlannedCell cell : archive.blueprint().cells()) cells.add(archive.anchor().offset(cell.pos()));
        return cells;
    }

    /** 来源网里在册的格：找来源时扫到的、和来源同一张网的那些。 */
    static List<BlockPos> sameNetwork(Map<BlockPos, String> candidates, String network) {
        return candidates.entrySet().stream().filter(entry -> entry.getValue().equals(network))
                .map(Map.Entry::getKey).toList();
    }
}
