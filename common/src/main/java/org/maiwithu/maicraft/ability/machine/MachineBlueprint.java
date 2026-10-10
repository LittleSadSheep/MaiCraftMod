// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.behavior.construction.CellKind;
import org.maiwithu.maicraft.behavior.construction.PlannedCell;

/**
 * 机器蓝图：建造的逐格清单加上机器的四种附加条目。审阅与施工都认这一种格式。
 *
 * <p>位置全部是相对锚点的偏移，落到锚点才成为世界坐标。逐格清单复用建造的计划格
 * （放方块、清空、倒桶），省略的格子保留；安装段与部件只写位置，怎么装由认领它们的机器类型来。
 *
 * @param cells         逐格清单：每格放什么、清空还是倒桶
 * @param parts         部件：装在宿主方块某一面上的一块东西（AE2 线缆上的终端、总线）
 * @param installations 原生安装段：只能用模组自己的方式成型的一组格（两轴之间的传送带）
 * @param settings      放好之后要改的设置（Mekanism 侧面、分拣过滤、样板）
 * @param processes     声明的工序：机器格 offset 加要做的产物；只给审阅用，不是施工目标
 */
record MachineBlueprint(List<PlannedCell> cells, List<Part> parts, List<Segment> installations,
                        List<Setting> settings, List<Process> processes) {

    MachineBlueprint {
        cells = List.copyOf(cells);
        parts = List.copyOf(parts);
        installations = List.copyOf(installations);
        settings = List.copyOf(settings);
        processes = List.copyOf(processes);
    }

    /** 部件条目：宿主在哪、装在哪一面、用什么物品装。 */
    record Part(BlockPos offset, Direction side, String itemId) {

        Part {
            Objects.requireNonNull(offset, "offset");
            Objects.requireNonNull(side, "side");
            Objects.requireNonNull(itemId, "itemId");
        }
    }

    /** 原生安装段：种类加这一段占的相对格。 */
    record Segment(String kind, List<BlockPos> offsets) {

        Segment {
            Objects.requireNonNull(kind, "kind");
            offsets = List.copyOf(offsets);
        }
    }

    /** 一条安装后设置。 */
    record Setting(BlockPos offset, String key, String value) {

        Setting {
            Objects.requireNonNull(offset, "offset");
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
        }
    }

    /** 一条声明工序：哪格的机器、做什么。 */
    record Process(BlockPos offset, String item) {

        Process {
            Objects.requireNonNull(offset, "offset");
            Objects.requireNonNull(item, "item");
        }
    }

    /** 按位置找逐格清单里的格；蓝图没写的格给空。 */
    Optional<PlannedCell> cellAt(BlockPos offset) {
        return cells.stream().filter(cell -> cell.pos().equals(offset)).findFirst();
    }

    /** 宿主格的方块状态：部件能不能装、安装段靠不靠得上都以它判；那一格不是放方块给空。 */
    Optional<BlockState> hostStateAt(BlockPos offset) {
        return cellAt(offset)
                .filter(cell -> cell.kind() == CellKind.BLOCK)
                .map(PlannedCell::state);
    }
}
