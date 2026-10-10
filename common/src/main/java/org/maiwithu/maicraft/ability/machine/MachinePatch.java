// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.construction.PlannedCell;

/**
 * 机器补丁合并：修改已有机器时，把新给的蓝图并到档案冻结的整机蓝图上，纯函数。
 *
 * <p>合并规则：逐格清单按位置并，新声明的格覆盖同位旧目标，档案里有而补丁没提的格保留；
 * 部件按宿主加面替换，安装段按起始格替换，装后设置按格加键替换，声明工序整表换成补丁的。
 */
final class MachinePatch {

    private MachinePatch() {
    }

    /** 把补丁并到旧蓝图上；两份的偏移都是相对同一锚点的。 */
    static MachineBlueprint merge(MachineBlueprint existing, MachineBlueprint patch) {
        Map<BlockPos, PlannedCell> cells = new LinkedHashMap<>();
        for (PlannedCell cell : existing.cells()) {
            cells.put(cell.pos(), cell);
        }
        for (PlannedCell cell : patch.cells()) {
            cells.put(cell.pos(), cell);
        }
        Map<String, MachineBlueprint.Part> parts = new LinkedHashMap<>();
        for (MachineBlueprint.Part part : existing.parts()) {
            parts.put(part.offset() + "@" + part.side(), part);
        }
        for (MachineBlueprint.Part part : patch.parts()) {
            parts.put(part.offset() + "@" + part.side(), part);
        }
        Map<BlockPos, MachineBlueprint.Segment> segments = new LinkedHashMap<>();
        for (MachineBlueprint.Segment segment : existing.installations()) {
            segments.put(segment.offsets().get(0), segment);
        }
        for (MachineBlueprint.Segment segment : patch.installations()) {
            segments.put(segment.offsets().get(0), segment);
        }
        Map<String, MachineBlueprint.Setting> settings = new LinkedHashMap<>();
        for (MachineBlueprint.Setting setting : existing.settings()) {
            settings.put(setting.offset() + "@" + setting.key(), setting);
        }
        for (MachineBlueprint.Setting setting : patch.settings()) {
            settings.put(setting.offset() + "@" + setting.key(), setting);
        }
        return new MachineBlueprint(List.copyOf(cells.values()), List.copyOf(parts.values()),
                List.copyOf(segments.values()), List.copyOf(settings.values()),
                patch.processes().isEmpty() ? existing.processes() : List.copyOf(patch.processes()));
    }

    /** 合并后有没有真的变化：一格未动、一条未改时，machine_build 按"开始时已满足"对待。 */
    static boolean changed(MachineBlueprint merged, MachineBlueprint existing) {
        return !same(merged, existing);
    }

    private static boolean same(MachineBlueprint left, MachineBlueprint right) {
        if (left.cells().size() != right.cells().size()
                || left.parts().size() != right.parts().size()
                || left.installations().size() != right.installations().size()
                || left.settings().size() != right.settings().size()) {
            return false;
        }
        List<PlannedCell> leftCells = new ArrayList<>(left.cells());
        List<PlannedCell> rightCells = new ArrayList<>(right.cells());
        leftCells.sort((a, b) -> a.pos().asLong() < b.pos().asLong() ? -1 : 1);
        rightCells.sort((a, b) -> a.pos().asLong() < b.pos().asLong() ? -1 : 1);
        return leftCells.equals(rightCells)
                && left.parts().equals(right.parts())
                && left.installations().equals(right.installations())
                && left.settings().equals(right.settings());
    }
}
