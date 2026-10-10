// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.design;

import static org.maiwithu.maicraft.ability.design.DesignFormat.bad;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonObject;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.ability.design.DesignExpansion.Leaf;
import org.maiwithu.maicraft.ability.design.DesignTransform.Box;
import org.maiwithu.maicraft.ability.design.DesignTransform.Point;

/**
 * 逐格采样：组件展开后，对每片叶子的包围盒里每一格看格中心在不在图元里、有没有被切割体挖掉、该涂什么材料；
 * 屋顶按自己的做法直接给格。空心内腔和切割孔只补还没被别的对象占用的空气，玻璃、框架这些独立对象仍可填进去。
 * 不同材料叠在同一格时默认后写的赢并记下冲突；overlap_policy 为 error 时直接拒绝。
 */
public final class DesignSampling {

    private static final JsonObject AIR = air();

    record Raster(Leaf leaf, Paint paint, List<Leaf> cutters) {}

    /** 采样前的准备：展开结果、要采样的图元、屋顶叶子、预计的工作量。 */
    public record Plan(DesignExpansion model, List<Raster> meshes, List<Leaf> roofs, long work) {}

    private record Cell(JsonObject state, String owner, boolean voidOnly) {}

    /** 一处叠加冲突：哪一格、先后两个对象、先后两种状态。 */
    public record Overlap(Point offset, String previousObject, String incomingObject, JsonObject previousState, JsonObject incomingState) {}

    /** 采样结果：按 y、z、x 排好的格与它的稀疏状态，以及叠加统计。 */
    public record Sampled(Plan plan, Map<Point, JsonObject> cells, int overlapCells, long overlapEvents, List<Overlap> overlapExamples) {}

    private DesignSampling() {}

    /** 展开并算工作量，不进逐格循环；只校验时用这个。 */
    public static Plan plan(JsonObject drawing) {
        DesignExpansion model = DesignExpansion.expand(drawing);
        List<Raster> meshes = new ArrayList<>();
        List<Leaf> roofs = new ArrayList<>();
        Map<String, JsonObject> cache = new HashMap<>();
        long work = 0;
        for (var leaf : model.leaves) {
            if (leaf.roof()) {
                roofs.add(leaf);
                work = add(work, leaf.bounds().volume());
                continue;
            }
            Paint paint = new Paint(model, leaf, cache);
            if (model.cutterLeaves.contains(leaf.name())) continue;
            // 图元内部判定也逐面比，和空腔、涂装、切割一起计费，大空心模型的第一轮检查不能漏算。
            var cutters = model.cutters(leaf);
            long cost = 1L + leaf.shape().faces().size() + paint.sampleCost();
            for (var cutter : cutters) cost += cutter.shape().faces().size();
            try {
                work = add(work, Math.multiplyExact(leaf.bounds().volume(), cost));
            } catch (ArithmeticException exceeded) {
                throw bad("采样工作量溢出");
            }
            meshes.add(new Raster(leaf, paint, cutters));
        }
        return new Plan(model, List.copyOf(meshes), List.copyOf(roofs), work);
    }

    private static long add(long work, long more) {
        long total = Math.addExact(work, more);
        if (total > DesignLimits.MAX_VOXEL_WORK) throw bad("图纸的采样工作量超过上限 " + DesignLimits.MAX_VOXEL_WORK + "，拆开画或缩小尺寸");
        return total;
    }

    /** 逐格采样整张图纸。 */
    public static Sampled sample(JsonObject drawing) {
        Plan plan = plan(drawing);
        boolean strict = drawing.has("overlap_policy") && drawing.get("overlap_policy").getAsString().equals("error");
        Overlaps overlaps = new Overlaps(strict);
        Map<Point, Cell> cells = new LinkedHashMap<>();
        for (Raster mesh : plan.meshes) sampleMesh(mesh, cells, overlaps);
        Map<String, JsonObject> cache = new HashMap<>();
        for (Leaf roof : plan.roofs) {
            var states = Roof.cells(roof, (material, cell) -> MaterialStates.resolve(plan.model, material, roof, cell, cache));
            for (var entry : states.entrySet()) put(cells, entry.getKey(), entry.getValue(), roof.name(), overlaps);
        }
        if (cells.isEmpty()) throw bad("图纸里没有一个格中心落在对象里；加厚或换位置");
        Map<Point, JsonObject> ordered = new LinkedHashMap<>();
        cells.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparingInt(Point::y).thenComparingInt(Point::z).thenComparingInt(Point::x)))
                .forEach(entry -> ordered.put(entry.getKey(), entry.getValue().state));
        return new Sampled(plan, ordered, overlaps.conflicts.size(), overlaps.events, List.copyOf(overlaps.examples));
    }

    private static void sampleMesh(Raster mesh, Map<Point, Cell> cells, Overlaps overlaps) {
        Box bounds = mesh.leaf.bounds();
        for (int y = bounds.from().y(); y <= bounds.to().y(); y++) {
            DesignFormat.checkInterrupted();
            for (int z = bounds.from().z(); z <= bounds.to().z(); z++) {
                for (int x = bounds.from().x(); x <= bounds.to().x(); x++) {
                    Point at = new Point(x, y, z);
                    Vec3 center = new Vec3(x + .5, y + .5, z + .5);
                    Vec3 local = mesh.leaf.transform().inverse(center);
                    if (!mesh.leaf.shape().contains(local)) continue;
                    boolean cut = false;
                    for (var cutter : mesh.cutters) {
                        if (cutter.contains(center)) {
                            cut = true;
                            break;
                        }
                    }
                    put(cells, at, cut ? null : mesh.paint.at(local, at), mesh.leaf.name(), overlaps);
                }
            }
        }
    }

    private static void put(Map<Point, Cell> cells, Point at, JsonObject state, String owner, Overlaps overlaps) {
        if (!cells.containsKey(at) && cells.size() >= DesignLimits.MAX_CELLS) throw bad("图纸展开后超过 " + DesignLimits.MAX_CELLS + " 格，拆开画");
        if (state == null) {
            cells.putIfAbsent(at, new Cell(AIR, owner, true));
            return;
        }
        Cell prior = cells.get(at);
        if (prior != null && !prior.voidOnly && !prior.state.equals(state)) overlaps.record(at, prior, state, owner);
        cells.put(at, new Cell(state, owner, false));
    }

    /** 叠加冲突的账：几格、几次、前十六个例子。 */
    private static final class Overlaps {
        private final boolean strict;
        private final Set<Point> conflicts = new HashSet<>();
        private final List<Overlap> examples = new ArrayList<>();
        private long events;

        Overlaps(boolean strict) {
            this.strict = strict;
        }

        void record(Point at, Cell prior, JsonObject state, String owner) {
            events++;
            conflicts.add(at);
            if (strict) throw bad("不同材料在同一格叠加：" + at + "，" + prior.owner + " 与 " + owner);
            if (examples.size() < 16) examples.add(new Overlap(at, prior.owner, owner, prior.state.deepCopy(), state.deepCopy()));
        }
    }

    private static JsonObject air() {
        JsonObject state = new JsonObject();
        state.addProperty("block_id", "minecraft:air");
        state.add("properties", new JsonObject());
        return state;
    }
}
