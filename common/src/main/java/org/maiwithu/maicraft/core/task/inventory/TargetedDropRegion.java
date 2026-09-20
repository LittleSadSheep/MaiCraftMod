// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.inventory;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;

/** 冻结上层实际审查过的格并支持只收窄的约束；硬接收域与瞄准偏好各持一份，包围盒里的缺角始终不是已审查格。 */
public final class TargetedDropRegion {
    private final List<BlockPos> cells;
    private final AABB bounds;
    private final AABB landingBounds;

    private TargetedDropRegion(List<BlockPos> cells, AABB landingBounds) {
        this.cells = List.copyOf(cells); this.landingBounds = landingBounds;
        AABB total = new AABB(cells.getFirst());
        for (BlockPos cell : cells) total = total.minmax(new AABB(cell));
        bounds = total;
    }

    public static TargetedDropRegion ofCells(Collection<BlockPos> cells) {
        if (cells == null || cells.isEmpty() || cells.size() > 32)
            throw new IllegalArgumentException("targeted_drop_requires_1_to_32_reviewed_cells");
        var frozen = new LinkedHashSet<BlockPos>();
        for (BlockPos cell : cells) {
            if (cell == null) throw new IllegalArgumentException("targeted_drop_receiver_cell_missing");
            frozen.add(cell.immutable());
        }
        return new TargetedDropRegion(List.copyOf(frozen), null);
    }

    public List<BlockPos> cells() { return cells; }
    public AABB landingBounds() { return landingBounds; }

    public TargetedDropRegion narrowTo(AABB constraint) {
        if (constraint == null || !finite(constraint)) throw new IllegalArgumentException("targeted_drop_invalid_landing_constraint");
        AABB narrowed = overlap(bounds, constraint);
        if (landingBounds != null && narrowed != null) narrowed = overlap(narrowed, landingBounds);
        if (narrowed == null) throw new IllegalArgumentException("targeted_drop_native_input_neighborhood_has_no_receiver");
        var selected = new ArrayList<BlockPos>();
        for (BlockPos cell : cells) if (overlap(new AABB(cell), narrowed) != null) selected.add(cell);
        if (selected.isEmpty()) throw new IllegalArgumentException("targeted_drop_native_input_neighborhood_has_no_receiver");
        return new TargetedDropRegion(selected, narrowed);
    }

    /** 坐标统一采用 ItemEntity 的底部 position；预测首次入水和真实入池回执都受同一约束。 */
    public boolean contains(Vec3 position) {
        return cells.contains(BlockPos.containing(position)) && (landingBounds == null || landingBounds.contains(position));
    }

    // 外扩盒只用于索引附近实体；计入回执前必须再逐格检查 contains，不能借此接纳池外或缺角物品。
    public AABB observationBounds() { return bounds.inflate(.35, .1, .35); }

    AABB part(BlockPos cell) { return landingBounds == null ? new AABB(cell) : overlap(new AABB(cell), landingBounds); }

    private static AABB overlap(AABB first, AABB second) {
        double x = Math.max(first.minX, second.minX), y = Math.max(first.minY, second.minY), z = Math.max(first.minZ, second.minZ);
        double maxX = Math.min(first.maxX, second.maxX), maxY = Math.min(first.maxY, second.maxY), maxZ = Math.min(first.maxZ, second.maxZ);
        return x < maxX && y < maxY && z < maxZ ? new AABB(x,y,z,maxX,maxY,maxZ) : null;
    }
    private static boolean finite(AABB box) {
        return Double.isFinite(box.minX) && Double.isFinite(box.minY) && Double.isFinite(box.minZ)
                && Double.isFinite(box.maxX) && Double.isFinite(box.maxY) && Double.isFinite(box.maxZ);
    }
}
