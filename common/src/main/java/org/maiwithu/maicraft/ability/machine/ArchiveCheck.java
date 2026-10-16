// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.ability.machine.spi.Installation;
import org.maiwithu.maicraft.ability.machine.spi.MachineType;
import org.maiwithu.maicraft.ability.machine.spi.PartCell;
import org.maiwithu.maicraft.behavior.construction.Blueprint;
import org.maiwithu.maicraft.behavior.construction.BlueprintCheck;
import org.maiwithu.maicraft.behavior.construction.PlannedCell;
import org.maiwithu.maicraft.behavior.construction.ReadsBlocks;

/**
 * 整机比对：拿档案的整机蓝图对现场比。普通格逐格出 MATCHES / WRONG_BLOCK / WRONG_STATE / MISSING / UNKNOWN，
 * 安装段与部件的格由认领它们的机器类型按最终结构核对；蓝图范围里没声明的格有东西记作 EXTRA，只作信息。
 * 有一格没加载就不给"整机一致"的结论。结构一致不等于能转，转不转看各台机器的运行状态。
 */
final class ArchiveCheck {

    /** 一段安装段或一个部件核对的结论。 */
    record EntryCheck(String what, String where, boolean done) {
    }

    /**
     * 比对结论：完整没有、每种结论几格、没对上的每一格、多出来的格、安装段与部件。
     *
     * @param complete     每一格都看到了（没有没加载的）
     * @param counts       每种结论几格
     * @param differences  没对上的每一格，按蓝图顺序，不截断
     * @param extra        蓝图范围里没声明、却有东西的格（只作信息）
     * @param installations 每段安装段装成没有
     * @param parts        每个部件装上没有
     */
    record Result(boolean complete, Map<String, Integer> counts, List<BlueprintCheck.Difference> differences, List<BlockPos> extra,
                  List<EntryCheck> installations, List<EntryCheck> parts) {

        Result {
            counts = Map.copyOf(counts);
            differences = List.copyOf(differences);
            extra = List.copyOf(extra);
            installations = List.copyOf(installations);
            parts = List.copyOf(parts);
        }

        /** 结构与机器条目全对、每格都看到了。 */
        boolean allMatch() {
            return complete && differences.isEmpty() && installations.stream().allMatch(EntryCheck::done)
                    && parts.stream().allMatch(EntryCheck::done);
        }
    }

    private ArchiveCheck() {
    }

    /** 比对用的蓝图：偏移落锚点，安装段占的格标成由机器核对，不冒充逐格放好。 */
    static Blueprint targetPlan(MachineBlueprint blueprint, BlockPos anchor, String dimension) {
        Blueprint laid = Blueprint.at(dimension, anchor, Rotation.NONE, blueprint.cells());
        Set<BlockPos> machineCells = new LinkedHashSet<>();
        for (MachineBlueprint.Segment segment : blueprint.installations()) {
            segment.offsets().forEach(offset -> machineCells.add(anchor.offset(offset)));
        }
        List<PlannedCell> adjusted = new ArrayList<>();
        for (PlannedCell cell : laid.cells()) {
            adjusted.add(machineCells.contains(cell.pos()) ? cell.byMachine() : cell);
        }
        return new Blueprint(laid.dimension(), anchor, adjusted);
    }

    /** 拿档案对现场比一遍。 */
    static Result compare(MachineArchive archive, MachineServices services) {
        // 逐格的部分和建筑的整栋比对是同一份；机器自己再核对安装段与部件。
        Blueprint target = targetPlan(archive.blueprint(), archive.anchor(), archive.dimension());
        BlueprintCheck.Report report = BlueprintCheck.report(target, worldOf(services));
        return new Result(report.complete(), report.counts(), report.differences(), report.extra(),
                installations(archive, services), parts(archive, services));
    }

    private static List<EntryCheck> installations(MachineArchive archive, MachineServices services) {
        List<EntryCheck> out = new ArrayList<>();
        for (MachineBlueprint.Segment segment : archive.blueprint().installations()) {
            Installation absolute = new Installation(segment.kind(),
                    segment.offsets().stream().map(archive.anchor()::offset).toList(), segment.roles());
            boolean done = services.machineTypes().stream().anyMatch(type -> type.installed(absolute));
            out.add(new EntryCheck(segment.kind(), absolute.cells().getFirst().toShortString() + " → "
                    + absolute.cells().getLast().toShortString(), done));
        }
        return out;
    }

    private static List<EntryCheck> parts(MachineArchive archive, MachineServices services) {
        List<EntryCheck> out = new ArrayList<>();
        for (MachineBlueprint.Part part : archive.blueprint().parts()) {
            PartCell absolute = new PartCell(archive.anchor().offset(part.offset()), part.side(), part.itemId());
            boolean done = services.machineTypes().stream().anyMatch((MachineType type) -> type.mounted(absolute));
            out.add(new EntryCheck(part.itemId(), absolute.host().toShortString() + " 的" + part.side().getName()
                    + "面", done));
        }
        return out;
    }

    private static ReadsBlocks worldOf(MachineServices services) {
        return new ReadsBlocks() {
            @Override public boolean loaded(BlockPos pos) {
                return services.world().stateAt(pos).isPresent();
            }

            @Override public BlockState state(BlockPos pos) {
                return services.world().stateAt(pos).orElse(Blocks.AIR.defaultBlockState());
            }
        };
    }
}
