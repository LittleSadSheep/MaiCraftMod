// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.design;

import static org.maiwithu.maicraft.ability.design.DesignFormat.bad;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.ability.design.DesignTransform.Box;
import org.maiwithu.maicraft.ability.design.DesignTransform.Point;

/**
 * 参数化屋顶：从四栋手工建筑逐格量出来的中式屋顶做法。在 footprint 的矩形上起坡，长边作屋脊方向；
 * 举架曲线默认（直坡要明说），出檐最多四格；屋面用同材质的半砖表达半格的高度变化，找不到半砖就整块砌；
 * 再加可选的底衬、实心填充、山墙、脊和翘角。五种形状：悬山（xuanshan/gable）、庑殿（wudian/hip）、
 * 歇山（xieshan/half_hip）、攒尖（zuanjian/pyramid）、单坡（shed）。
 *
 * <p>location 是檐口基准高度上 footprint 的中心；格先在屋顶自己的坐标里算，再经对象变换落到图纸里。
 */
public final class Roof {

    /** 屋顶自己坐标里的一格：用哪种材料、半砖哪一档（null 整块）、与别的部位重叠时覆盖还是保留。 */
    record Plan(String material, SlabType slab, boolean overwrite) {}

    /** 一格的材料名落到实际方块状态（混色、朝向都在这里结算）。 */
    interface MaterialAt {
        JsonObject state(String material, Point cell);
    }

    private static final Set<String> HIP = Set.of("wudian", "hip", "zuanjian", "zanjian", "pyramid");
    private static final Set<String> HALF_HIP = Set.of("xieshan", "half_hip");

    private Roof() {}

    /** 屋顶引用的材料都要在材料表里。 */
    static void checkMaterials(JsonObject palette, JsonObject node) {
        for (String field : List.of("material", "gable_material", "ridge_material", "eave_material", "soffit_material")) {
            if (node.has(field)) MaterialRules.material(palette, node.get(field).getAsString());
        }
    }

    /** 屋顶落到图纸里的格范围；同时检查位置落在格网上、不超出坐标半径。 */
    static Box bounds(JsonObject node, DesignTransform transform) {
        int[] footprint = footprint(node);
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Point local : localCells(node).keySet()) {
            Point p = place(transform, local, footprint[0], footprint[1]);
            minX = Math.min(minX, p.x());
            minY = Math.min(minY, p.y());
            minZ = Math.min(minZ, p.z());
            maxX = Math.max(maxX, p.x());
            maxY = Math.max(maxY, p.y());
            maxZ = Math.max(maxZ, p.z());
        }
        int radius = DesignLimits.MAX_RADIUS;
        if (minX < -radius || minY < -radius || minZ < -radius || maxX > radius || maxY > radius || maxZ > radius) {
            throw bad("屋顶超出坐标半径 " + radius + "：" + node.get("name").getAsString());
        }
        return new Box(new Point(minX, minY, minZ), new Point(maxX, maxY, maxZ));
    }

    /** 屋顶的全部格，坐标已落到图纸里，状态已结算成 block_id 加属性。 */
    static Map<Point, JsonObject> cells(DesignExpansion.Leaf leaf, MaterialAt materials) {
        int[] footprint = footprint(leaf.node());
        Map<Point, JsonObject> out = new LinkedHashMap<>();
        for (var entry : localCells(leaf.node()).entrySet()) {
            Point cell = place(leaf.transform(), entry.getKey(), footprint[0], footprint[1]);
            Plan plan = entry.getValue();
            out.put(cell, finish(materials.state(plan.material, cell), plan.slab));
        }
        return out;
    }

    /** 方块状态按计划定档：要半砖且材料推得出半砖就放对应档位，推不出就整块砌。 */
    private static JsonObject finish(JsonObject state, SlabType slab) {
        if (slab == null) return state;
        Block block = BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse(state.get("block_id").getAsString())).orElse(null);
        Block found = block == null ? null : slabFor(block);
        if (found == null) return state;
        JsonObject result = new JsonObject();
        result.addProperty("block_id", BuiltInRegistries.BLOCK.getKey(found).toString());
        JsonObject properties = new JsonObject();
        properties.addProperty("type", slab.getSerializedName());
        result.add("properties", properties);
        return result;
    }

    private static Point place(DesignTransform transform, Point local, int width, int depth) {
        Vec3 center = transform.point(new Vec3(local.x() + .5 - width / 2.0, local.y() + .5, local.z() + .5 - depth / 2.0));
        double[] values = {center.x, center.y, center.z};
        int[] cell = new int[3];
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(values[axis] - Math.floor(values[axis]) - .5) > 1e-8) throw bad("屋顶的位置要让每一格落在格网上");
            cell[axis] = (int) Math.floor(values[axis]);
        }
        return new Point(cell[0], cell[1], cell[2]);
    }

    private static int[] footprint(JsonObject node) {
        var values = node.getAsJsonArray("footprint");
        return new int[]{values.get(0).getAsInt(), values.get(1).getAsInt()};
    }

    /** 屋顶自己坐标里的全部格：x 从 0 起沿宽、z 从 0 起沿深（出檐为负），y 从檐口基准 0 起向上。 */
    static Map<Point, Plan> localCells(JsonObject node) {
        int[] footprint = footprint(node);
        int overhang = node.has("overhang") ? node.get("overhang").getAsInt() : 0;
        int ax = -overhang, bx = footprint[0] - 1 + overhang, az = -overhang, bz = footprint[1] - 1 + overhang;
        String shape = node.has("shape") ? node.get("shape").getAsString() : "xuanshan";
        // 举架曲线是默认；直坡要明说。量过的四栋没有一栋是直的，直坡正是"看着像金字塔"的那种。
        boolean concave = !node.has("curve") || !"straight".equals(node.get("curve").getAsString());
        int cornerLift = node.has("corner_lift") ? node.get("corner_lift").getAsInt() : 0;
        String material = node.get("material").getAsString();
        String gable = optional(node, "gable_material"), ridge = optional(node, "ridge_material");
        String eave = optional(node, "eave_material"), soffit = optional(node, "soffit_material");
        boolean hollow = !node.has("hollow") || node.get("hollow").getAsBoolean();
        boolean hip = HIP.contains(shape), xieshan = HALF_HIP.contains(shape), shed = "shed".equals(shape);
        boolean ridgeAlongX = (bx - ax) >= (bz - az);
        int slopeSpan = ridgeAlongX ? (bz - az) : (bx - ax);
        // 单坡一整片倒向一侧，走全跨；其余两坡对开，各走半跨。
        int reach = shed ? slopeSpan : slopeSpan / 2;
        int[] h = surfaceHalves(reach, concave);
        // 歇山的下段四坡约占四成，上段转成双坡带山花。
        int brk = xieshan ? Math.max(1, reach * 2 / 5) : 0;
        Map<Point, Plan> out = new LinkedHashMap<>();
        for (int x = ax; x <= bx; x++) {
            for (int z = az; z <= bz; z++) {
                int dSlope = ridgeAlongX ? Math.min(z - az, bz - z) : Math.min(x - ax, bx - x);
                int dEnd = ridgeAlongX ? Math.min(x - ax, bx - x) : Math.min(z - az, bz - z);
                int d;
                if (shed) d = ridgeAlongX ? (z - az) : (x - ax);
                else if (hip) d = Math.min(dSlope, dEnd);
                else if (xieshan) d = dEnd < brk ? Math.min(dSlope, dEnd) : dSlope;
                else d = dSlope;
                d = Math.min(d, h.length - 1);
                int halves = h[d];
                // d 为零是屋檐边缘，有专门的檐口材料时在这里换料。
                boolean lip = d == 0;
                skin(out, lip && eave != null ? eave : material, x, z, halves, lip);
                if (soffit != null && !lip) soffit(out, soffit, x, z, halves);
                if (!hollow) fillUnder(out, material, x, z, halves);
                // 山花：悬山两端的三角墙；歇山在腰线那一列，坡面在那里有竖直落差。要在压脊之前填，否则脊会被实心块盖掉。
                if (gable != null && !shed && !hip) {
                    int faceTop = (halves - 1) / 2;
                    int faceBottom = xieshan ? (dEnd == brk ? h[Math.min(dSlope, brk - 1)] / 2 : faceTop) : (dEnd == 0 ? 0 : faceTop);
                    for (int gy = faceBottom; gy < faceTop; gy++) solid(out, gable, x, gy, z, false);
                }
                String spine = ridge != null ? ridge : material;
                // 垂脊：四坡到两边檐口等距的那条对角线；悬山没有垂脊，两端改作博风板，同样是异色一条，走在山面的边缘。
                boolean hipSpine = (hip || (xieshan && dEnd < brk)) && dSlope == dEnd && d < reach;
                boolean barge = !hip && !shed && dEnd == 0;
                if (hipSpine || barge) proud(out, spine, x, z, halves, 1);
                if (!shed && d >= reach) proud(out, spine, x, z, halves, 2);
            }
        }
        if (cornerLift > 0) liftCorners(out, ridge != null ? ridge : material, ax, bx, az, bz, cornerLift);
        if (out.isEmpty()) throw bad("屋顶算不出一格");
        return out;
    }

    /** 从檐口往屋脊每一步的高度，单位半格；每步至少加一，坡面不会突然降低。曲坡越往上越陡。 */
    private static int[] surfaceHalves(int reach, boolean concave) {
        int n = Math.max(1, reach) + 1;
        int[] h = new int[n];
        double acc = 1.0;
        int cur = 1;
        for (int k = 0; k < n; k++) {
            double f = reach <= 0 ? 1.0 : (double) k / reach;
            acc += concave ? 1.0 + 0.6 * f * f : 2.0;
            cur = Math.max(cur + 1, (int) Math.round(acc));
            h[k] = cur;
        }
        return h;
    }

    /** 这一列最上面的屋面：檐口用上半砖做薄边，其余按半格高度选下半砖或双层半砖。 */
    private static void skin(Map<Point, Plan> out, String material, int x, int z, int halves, boolean thin) {
        SlabType type = thin ? SlabType.TOP : (halves % 2 == 1 ? SlabType.BOTTOM : SlabType.DOUBLE);
        out.put(new Point(x, (halves - 1) / 2, z), new Plan(material, type, true));
    }

    /** 屋面下面的底衬：整层半砖或上下错开的两片，减少从屋里看到的空隙；高度不够就不放。 */
    private static void soffit(Map<Point, Plan> out, String material, int x, int z, int halves) {
        int s = halves - 2;
        if (s < 1) return;
        int y = (s - 1) / 2;
        if (s % 2 == 0) {
            out.put(new Point(x, y, z), new Plan(material, SlabType.DOUBLE, true));
        } else {
            out.put(new Point(x, y, z), new Plan(material, SlabType.BOTTOM, true));
            out.put(new Point(x, y - 1, z), new Plan(material, SlabType.TOP, true));
        }
    }

    /** 不留阁楼时把檐口基准到屋面之间填实；已有屋面的格不盖。 */
    private static void fillUnder(Map<Point, Plan> out, String material, int x, int z, int halves) {
        for (int y = 0; y < (halves - 1) / 2; y++) solid(out, material, x, y, z, false);
    }

    /** 从屋面顶上往上加 n 格脊；脊必须实心，半砖材料铺成双层。 */
    private static void proud(Map<Point, Plan> out, String material, int x, int z, int halves, int n) {
        for (int k = 0; k < n; k++) out.put(new Point(x, halves / 2 + k, z), new Plan(material, SlabType.DOUBLE, true));
    }

    /** 四个檐角向上叠 lift 格；抬两格以上时，角内相邻的两格各补一块过渡。 */
    private static void liftCorners(Map<Point, Plan> out, String material, int ax, int bx, int az, int bz, int lift) {
        int[][] corners = {{ax, az}, {bx, az}, {ax, bz}, {bx, bz}};
        for (int[] c : corners) {
            int cx = c[0], cz = c[1];
            for (int k = 1; k <= lift; k++) solid(out, material, cx, k, cz, true);
            if (lift >= 2) {
                int inx = cx == ax ? 1 : -1, inz = cz == az ? 1 : -1;
                solid(out, material, cx + inx, 1, cz, false);
                solid(out, material, cx, 1, cz + inz, false);
            }
        }
    }

    private static void solid(Map<Point, Plan> out, String material, int x, int y, int z, boolean overwrite) {
        Plan plan = new Plan(material, null, overwrite);
        if (overwrite) out.put(new Point(x, y, z), plan);
        else out.putIfAbsent(new Point(x, y, z), plan);
    }

    private static String optional(JsonObject node, String field) {
        return node.has(field) ? node.get(field).getAsString() : null;
    }

    /**
     * 按注册名猜同材质的半砖：木板、楼梯、墙换后缀，其次去掉复数的 s，再加 _slab。
     * 只是命名规则猜测，不查配方；同一命名空间里确实注册为半砖才采用，找不到返回 null。
     */
    static Block slabFor(Block block) {
        if (block instanceof SlabBlock) return block;
        var id = BuiltInRegistries.BLOCK.getKey(block);
        if (id == null) return null;
        String path = id.getPath();
        List<String> tries = new ArrayList<>();
        for (String suffix : new String[]{"_stairs", "_planks", "_wall"}) {
            if (path.endsWith(suffix)) tries.add(path.substring(0, path.length() - suffix.length()) + "_slab");
        }
        if (path.endsWith("s")) tries.add(path.substring(0, path.length() - 1) + "_slab");
        tries.add(path + "_slab");
        for (String candidate : tries) {
            Block found = BuiltInRegistries.BLOCK.getOptional(ResourceLocation.fromNamespaceAndPath(id.getNamespace(), candidate)).orElse(null);
            if (found instanceof SlabBlock) return found;
        }
        return null;
    }
}
