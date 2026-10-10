// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.construction;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Rotation;

/**
 * 蓝图：落到世界里的逐格目标清单。设计编译出来的格是相对设计原点的偏移，经 {@link #at} 加上锚点与
 * 朝向才成为蓝图；逐格给的、结构文件导入的、单格的，也都走同一个入口。
 *
 * <p>蓝图没写的格子不是"必须为空"；要清空的格子写 {@link CellKind#AIR}。
 * 构造时按位置建一份表，按位置找格是常数时间；一栋十几万格的房子核对时不会在客户端线程上卡住。
 */
public final class Blueprint {

    private final String dimension;
    private final BlockPos anchor;
    private final List<PlannedCell> cells;
    private final Map<BlockPos, PlannedCell> byPosition;

    /**
     * @param dimension 维度 ID，例如 minecraft:overworld
     * @param anchor    锚点：设计原点落在的格
     * @param cells     全部计划格，位置是绝对坐标，同一格只出现一次
     */
    public Blueprint(String dimension, BlockPos anchor, List<PlannedCell> cells) {
        this.dimension = Objects.requireNonNull(dimension, "dimension");
        this.anchor = Objects.requireNonNull(anchor, "anchor").immutable();
        this.cells = List.copyOf(cells);
        if (this.cells.isEmpty()) throw new IllegalArgumentException("蓝图至少要有一格");
        Map<BlockPos, PlannedCell> index = new LinkedHashMap<>();
        for (PlannedCell cell : this.cells) {
            if (index.put(cell.pos(), cell) != null) {
                throw new IllegalArgumentException("蓝图里同一格写了两次：" + cell.pos().toShortString());
            }
        }
        byPosition = Map.copyOf(index);
    }

    /**
     * 把相对格落到锚点：偏移绕锚点转 {@code turn}，有朝向的方块状态一起转，再加上锚点坐标。
     * 两个相对格转完撞到同一格说明设计本身重叠，按蓝图重复格拒绝。
     */
    public static Blueprint at(String dimension, BlockPos anchor, Rotation turn, List<PlannedCell> relativeCells) {
        return new Blueprint(dimension, anchor, relativeCells.stream().map(cell -> cell.placedAt(anchor, turn)).toList());
    }

    /** 只有一格的蓝图：锚点就是那一格。 */
    public static Blueprint single(String dimension, PlannedCell cell) {
        return new Blueprint(dimension, cell.pos(), List.of(cell));
    }

    public String dimension() {
        return dimension;
    }

    public BlockPos anchor() {
        return anchor;
    }

    public List<PlannedCell> cells() {
        return cells;
    }

    /** 按位置找计划格。 */
    public Optional<PlannedCell> cellAt(BlockPos pos) {
        return Optional.ofNullable(byPosition.get(pos));
    }

    /** 包围盒：最小角与最大角都在计划格上。 */
    public Bounds bounds() {
        return boundsOf(cells);
    }

    /** 一组计划格（可以是相对格）的包围盒。 */
    public static Bounds boundsOf(List<PlannedCell> cells) {
        if (cells.isEmpty()) throw new IllegalArgumentException("没有格就没有包围盒");
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (PlannedCell cell : cells) {
            BlockPos p = cell.pos();
            minX = Math.min(minX, p.getX()); minY = Math.min(minY, p.getY()); minZ = Math.min(minZ, p.getZ());
            maxX = Math.max(maxX, p.getX()); maxY = Math.max(maxY, p.getY()); maxZ = Math.max(maxZ, p.getZ());
        }
        return new Bounds(new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ));
    }

    /** 从空地建起整份蓝图要每种物品几件；已经对了的格不在这里扣，那是核对之后的事。 */
    public Map<String, Integer> materials() {
        return materialsOf(cells);
    }

    /** 一组计划格（可以是相对格）从空地建起要每种物品几件。 */
    public static Map<String, Integer> materialsOf(List<PlannedCell> cells) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (PlannedCell cell : cells) {
            int count = cell.materialCount();
            if (count > 0) out.merge(BuiltInRegistries.ITEM.getKey(cell.item()).toString(), count, Integer::sum);
        }
        return Map.copyOf(out);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Blueprint that && dimension.equals(that.dimension) && anchor.equals(that.anchor) && cells.equals(that.cells);
    }

    @Override
    public int hashCode() {
        return Objects.hash(dimension, anchor, cells);
    }

    @Override
    public String toString() {
        return "Blueprint[" + dimension + " @" + anchor.toShortString() + ", " + cells.size() + " 格]";
    }

    /** 包围盒的两个角，都包含在内。 */
    public record Bounds(BlockPos min, BlockPos max) {
        public boolean contains(BlockPos pos) {
            return pos.getX() >= min.getX() && pos.getX() <= max.getX()
                    && pos.getY() >= min.getY() && pos.getY() <= max.getY()
                    && pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ();
        }

        /** 包围盒里的格数（含没声明的）。 */
        public long volume() {
            return ((long) max.getX() - min.getX() + 1) * ((long) max.getY() - min.getY() + 1)
                    * ((long) max.getZ() - min.getZ() + 1);
        }
    }
}
