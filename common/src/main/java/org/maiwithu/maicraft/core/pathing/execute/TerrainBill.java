// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.pathing.execute;

import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.movement.IMovement;
import baritone.utils.BlockStateInterface;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 一张"动地形"的清单:哪些格要挖(按方块种类分组、保留坐标)、哪些格要放。
 * 两处用同一种说法——规划出来的路<b>会</b>动什么(无路验尸,喂给模型决定要不要授权),
 * 和执行器<b>真</b>动了什么(任务回执,事后如实相告)。语言面向工具回执(英文),
 * 坐标点名,方块按种类归堆,模型一眼能分出"两块木板一块玻璃"和"十一块石头"。
 */
public final class TerrainBill {

    /** 每种方块最多点名多少个坐标,其余计数——清单是给人判断的,不是给人数的。 */
    private static final int COORDS_PER_KIND = 6;

    private final Map<Block, List<BlockPos>> breaks = new LinkedHashMap<>();
    private final Map<Block, List<BlockPos>> places = new LinkedHashMap<>();

    /**
     * Terrain budget for an embedded Baritone path, evaluated against the same frozen block
     * snapshot that produced the path. No live-world lookup is allowed here: doing so after an
     * asynchronous search would mix two different world states and could name the wrong block in
     * a failure receipt.
     */
    public static TerrainBill planned(IPath path, BlockStateInterface frozenBlocks) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(frozenBlocks, "frozenBlocks");
        TerrainBill bill = new TerrainBill();
        for (IMovement raw : path.movements()) {
            if (!(raw instanceof baritone.pathing.movement.Movement movement)) {
                throw new IllegalArgumentException(
                        "embedded Baritone path contains an unsupported movement: "
                                + raw.getClass().getName());
            }
            for (BlockPos pos : movement.toBreak(frozenBlocks)) {
                bill.addBreak(pos, frozenBlocks.get0(pos));
            }
            for (BlockPos pos : movement.toPlace(frozenBlocks)) {
                // The planner knows the cell but intentionally does not choose a concrete
                // throwaway material. Material selection belongs to the later semantic task.
                bill.addPlace(pos, null);
            }
        }
        return bill;
    }

    public void addBreak(BlockPos pos, BlockState was) {
        breaks.computeIfAbsent(was.getBlock(), k -> new ArrayList<>()).add(pos.immutable());
    }

    /** @param placed 放上去的方块;规划阶段还不知道会选哪种耗材,传 null */
    public void addPlace(BlockPos pos, Block placed) {
        places.computeIfAbsent(placed, k -> new ArrayList<>()).add(pos.immutable());
    }

    /** 并入另一张清单(任务把历次导航的账汇总成一次旅程的账)。 */
    public void addAll(TerrainBill other) {
        other.breaks.forEach((k, v) -> breaks.computeIfAbsent(k, x -> new ArrayList<>()).addAll(v));
        other.places.forEach((k, v) -> places.computeIfAbsent(k, x -> new ArrayList<>()).addAll(v));
    }

    public boolean isEmpty() {
        return breaks.isEmpty() && places.isEmpty();
    }

    public int breakCount() {
        return breaks.values().stream().mapToInt(List::size).sum();
    }

    /** Whether execution has recorded a native break at this exact world cell. */
    public boolean broke(BlockPos pos) {
        if (pos == null) return false;
        for (List<BlockPos> cells : breaks.values()) {
            if (cells.contains(pos)) return true;
        }
        return false;
    }

    public int placeCount() {
        return places.values().stream().mapToInt(List::size).sum();
    }

    /**
     * 清单正文,例如
     * {@code break 2 oak_planks (120,64,-33; 120,65,-33) and 1 glass (122,65,-33), and place 2 blocks}。
     * 空清单返回空串。
     */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        if (!breaks.isEmpty()) {
            sb.append("break ").append(kinds(breaks));
        }
        if (!places.isEmpty()) {
            if (sb.length() > 0) {
                sb.append(", and ");
            }
            sb.append("place ");
            if (places.size() == 1 && places.containsKey(null)) {
                sb.append(placeCount()).append(placeCount() == 1 ? " block" : " blocks");
            } else {
                sb.append(kinds(places));
            }
        }
        return sb.toString();
    }

    private static String kinds(Map<Block, List<BlockPos>> byKind) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<Block, List<BlockPos>> e : byKind.entrySet()) {
            List<BlockPos> cells = e.getValue();
            StringBuilder part = new StringBuilder();
            part.append(cells.size()).append(' ')
                    .append(e.getKey() == null ? "block" : BuiltInRegistries.BLOCK.getKey(e.getKey()).getPath())
                    .append(" (");
            for (int i = 0; i < Math.min(COORDS_PER_KIND, cells.size()); i++) {
                if (i > 0) {
                    part.append("; ");
                }
                BlockPos p = cells.get(i);
                part.append(p.getX()).append(',').append(p.getY()).append(',').append(p.getZ());
            }
            if (cells.size() > COORDS_PER_KIND) {
                part.append("; +").append(cells.size() - COORDS_PER_KIND).append(" more");
            }
            part.append(')');
            parts.add(part.toString());
        }
        if (parts.size() <= 1) {
            return parts.isEmpty() ? "" : parts.get(0);
        }
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
    }
}
