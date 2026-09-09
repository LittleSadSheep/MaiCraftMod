package org.maiwithu.maicraft.core.build;

import org.maiwithu.maicraft.core.task.build.BuildTaskRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把盒子、墙、线、圆柱、球、撒点和屋顶参数展开成需要施工的位置。这里只计算计划，不改游戏世界。
 * 普通形状返回坐标；屋顶还要决定半砖放在上半、下半还是叠成整块，所以返回完整施工目标。
 */
public final class BuildShapes {

    private BuildShapes() {}

    /**
     * 一次普通建造允许的最终格数上限。shapeCells 在全部展开后检查；整次调用还由 BuildTool 再检查。
     * 这个常量本身不会提前限制枚举次数，也不会自动限制本类所有生成方法。
     */
    public static final int MAX_TOTAL_CELLS = 16384;

    /**
     * 把参数展开成不重复的坐标。box 的两个角都包含在内，hollow 只去掉六个面包住的内部。
     * 圆柱与球按格子中心到中心点的距离选格；空心圆柱只有侧壁，空心球保留球壳。
     * 计算完若为空或超过格数上限则报错。
     */
    public static List<BlockPos> shapeCells(String shape, boolean hollow,
                                       int x1, int y1, int z1,
                                       Integer x2, Integer y2, Integer z2,
                                       Integer radius, Integer height) {
        Set<BlockPos> out = new LinkedHashSet<>();
        switch (shape == null ? "" : shape) {
            case "box" -> {
                int ax = Math.min(x1, req(x2, "x2")), bx = Math.max(x1, x2);
                int ay = Math.min(y1, req(y2, "y2")), by = Math.max(y1, y2);
                int az = Math.min(z1, req(z2, "z2")), bz = Math.max(z1, z2);
                for (int y = ay; y <= by; y++) {
                    for (int x = ax; x <= bx; x++) {
                        for (int z = az; z <= bz; z++) {
                            if (hollow && x != ax && x != bx && y != ay && y != by
                                    && z != az && z != bz) {
                                continue;
                            }
                            add(out, x, y, z);
                        }
                    }
                }
            }
            case "walls" -> {
                // 周界竖墙:只有四面墙柱,不含顶底面——盖房的正确原语(空心盒的
                // 顶底面会在地基上叠出第二层地板,把门洞下半埋进屋里)。
                int ax = Math.min(x1, req(x2, "x2")), bx = Math.max(x1, x2);
                int ay = Math.min(y1, req(y2, "y2")), by = Math.max(y1, y2);
                int az = Math.min(z1, req(z2, "z2")), bz = Math.max(z1, z2);
                for (int y = ay; y <= by; y++) {
                    for (int x = ax; x <= bx; x++) {
                        for (int z = az; z <= bz; z++) {
                            if (x != ax && x != bx && z != az && z != bz) {
                                continue;
                            }
                            add(out, x, y, z);
                        }
                    }
                }
            }
            // 按最长方向的格数分段，在三个坐标上同步插值并取最近整数；相邻重复点会被集合去掉。
            case "line" -> {
                int bx = req(x2, "x2"), by = req(y2, "y2"), bz = req(z2, "z2");
                int steps = Math.max(1, Math.max(Math.abs(bx - x1),
                        Math.max(Math.abs(by - y1), Math.abs(bz - z1))));
                for (int i = 0; i <= steps; i++) {
                    add(out, Math.round(x1 + (bx - x1) * (float) i / steps),
                            Math.round(y1 + (by - y1) * (float) i / steps),
                            Math.round(z1 + (bz - z1) * (float) i / steps));
                }
            }
            case "cylinder" -> {
                int r = req(radius, "radius");
                int h = Math.max(1, height == null ? 1 : height);
                double outer = (r + 0.5) * (r + 0.5);
                double inner = (r - 0.5) * (r - 0.5);
                for (int y = y1; y < y1 + h; y++) {
                    for (int dx = -r; dx <= r; dx++) {
                        for (int dz = -r; dz <= r; dz++) {
                            double d = dx * dx + dz * dz;
                            if (d > outer || (hollow && d < inner)) {
                                continue;
                            }
                            add(out, x1 + dx, y, z1 + dz);
                        }
                    }
                }
            }
            case "sphere" -> {
                int r = req(radius, "radius");
                double outer = (r + 0.5) * (r + 0.5);
                double inner = (r - 0.5) * (r - 0.5);
                for (int dy = -r; dy <= r; dy++) {
                    for (int dx = -r; dx <= r; dx++) {
                        for (int dz = -r; dz <= r; dz++) {
                            double d = dx * dx + dy * dy + dz * dz;
                            if (d > outer || (hollow && d < inner)) {
                                continue;
                            }
                            add(out, x1 + dx, y1 + dy, z1 + dz);
                        }
                    }
                }
            }
            // 注:roof 不在此处——屋顶要逐格决定半砖三态,产出的是方块状态而不只是
            // 位置,走 roofCells。
            default -> throw new IllegalArgumentException(
                    "shape must be box, walls, line, cylinder or sphere");
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("shape resolved to zero cells");
        }
        if (out.size() > MAX_TOTAL_CELLS) {
            throw new IllegalArgumentException("shape has " + out.size() + " cells, exceeding "
                    + MAX_TOTAL_CELLS + "; split it into smaller calls");
        }
        return new ArrayList<>(out);
    }

    /**
     * 在固定高度的矩形内按密度选点。选择只由 x/z 决定，改变高度不会改变平面花纹。
     * 密度限制到 0..1，并按千分之一取整；若一个点都没有，仍补上矩形角上的一格，包括密度为零时。
     */
    public static List<BlockPos> scatterCells(int x1, int y, int z1, int x2, int z2, double density) {
        int ax = Math.min(x1, x2), bx = Math.max(x1, x2);
        int az = Math.min(z1, z2), bz = Math.max(z1, z2);
        double d = Math.max(0.0, Math.min(1.0, density));
        List<BlockPos> out = new ArrayList<>();
        for (int x = ax; x <= bx; x++) {
            for (int z = az; z <= bz; z++) {
                long h = BuildPalette.positionHash(x, 0, z);
                if (Math.floorMod(h, 1000) < (long) (d * 1000)) {
                    out.add(new BlockPos(x, y, z));
                }
            }
        }
        if (out.isEmpty()) {
            out.add(new BlockPos(ax, y, az));   // 密度过低也至少给一格,别空手而归
        }
        return out;
    }

    /**
     * 在矩形上生成屋顶，较长的一边作为屋脊方向；overhang 最多向四边各伸出四格。
     * 先按离屋檐的距离计算坡面高度，再放屋面、可选的底衬和实心填充，最后加山墙、屋脊和翘角。
     * 空心屋顶是默认值。能找到同材质半砖时用半砖形成半格高度变化；找不到时使用传入方块。
     * 各部位可以分别指定材料；同一坐标发生重叠时，下面各写入方法决定覆盖还是保留已有目标。
     */
    public static List<BuildTaskRecord.Target> roofCells(
            int x1, int y1, int z1, int x2, int z2,
            String material, String shapeArg, String curveArg,
            Integer overhangArg, Integer cornerLiftArg,
            String gableSpec, String ridgeSpec, String eaveSpec, String soffitSpec,
            Boolean hollowArg) {
        BuildPalette palette = BuildPalette.parse(material);
        int ax = Math.min(x1, x2);
        int bx = Math.max(x1, x2);
        int az = Math.min(z1, z2);
        int bz = Math.max(z1, z2);
        int y0 = y1;
        int overhang = overhangArg == null ? 0 : Math.max(0, Math.min(4, overhangArg));
        ax -= overhang;
        bx += overhang;
        az -= overhang;
        bz += overhang;

        String shape = shapeArg == null ? "xuanshan" : shapeArg;
        // 举架曲线是默认。直坡要明确要求——量过的四栋没有一栋是直的,而直坡正是
        // "看着像金字塔"的那个样子。
        boolean concave = !"straight".equals(curveArg);
        int cornerLift = cornerLiftArg == null ? 0 : Math.max(0, Math.min(3, cornerLiftArg));
        BuildPalette gable = gableSpec == null ? null : BuildPalette.parse(gableSpec);
        BuildPalette ridge = ridgeSpec == null ? null : BuildPalette.parse(ridgeSpec);
        BuildPalette eave = eaveSpec == null ? null : BuildPalette.parse(eaveSpec);
        BuildPalette soffit = soffitSpec == null ? null : BuildPalette.parse(soffitSpec);
        boolean hollow = hollowArg == null || hollowArg;

        boolean hip = switch (shape) {
            case "wudian", "hip", "zuanjian", "zanjian", "pyramid" -> true;
            default -> false;
        };
        boolean xieshan = "xieshan".equals(shape) || "half_hip".equals(shape);
        boolean shed = "shed".equals(shape);
        if (!hip && !xieshan && !shed && !"xuanshan".equals(shape) && !"gable".equals(shape)) {
            throw new IllegalArgumentException("roof shape must be xuanshan/gable, wudian/hip, "
                    + "xieshan/half_hip, zuanjian/pyramid or shed");
        }

        boolean ridgeAlongX = (bx - ax) >= (bz - az);
        int slopeSpan = ridgeAlongX ? (bz - az) : (bx - ax);
        // 单坡一整片倒向一侧,走全跨;其余是两坡对开,各走半跨
        int reach = shed ? slopeSpan : slopeSpan / 2;
        int[] h = surfaceHalves(reach, concave);
        // 歇山下段四坡约占四成,上段转双坡带山花
        int brk = xieshan ? Math.max(1, reach * 2 / 5) : 0;

        Map<Long, BuildTaskRecord.Target> out = new LinkedHashMap<>();
        for (int x = ax; x <= bx; x++) {
            for (int z = az; z <= bz; z++) {
                int dSlope = ridgeAlongX ? Math.min(z - az, bz - z) : Math.min(x - ax, bx - x);
                int dEnd = ridgeAlongX ? Math.min(x - ax, bx - x) : Math.min(z - az, bz - z);
                int d;
                if (shed) {
                    d = ridgeAlongX ? (z - az) : (x - ax);
                } else if (hip) {
                    d = Math.min(dSlope, dEnd);
                } else if (xieshan) {
                    d = dEnd < brk ? Math.min(dSlope, dEnd) : dSlope;
                } else {
                    d = dSlope;
                }
                d = Math.min(d, h.length - 1);
                int halves = h[d];

                // d=0 表示屋檐边缘，有专用檐口材料时在这里换料。
                boolean lip = d == 0;
                skinCell(out, lip && eave != null ? eave : palette, x, z, y0, halves, lip);
                if (soffit != null && !lip) {
                    soffitCell(out, soffit, x, z, y0, halves);
                }
                if (!hollow) {
                    fillUnder(out, palette, x, z, y0, halves);
                }

                // 山花:悬山两端的三角墙;歇山在腰线那一列,坡面在那里有个竖直落差。
                // 必须在压脊之前填,否则脊会被这里的实心块盖掉。
                if (gable != null && !shed && !hip) {
                    int faceTop = (halves - 1) / 2;
                    int faceBottom = xieshan
                            ? (dEnd == brk ? h[Math.min(dSlope, brk - 1)] / 2 : faceTop)
                            : (dEnd == 0 ? 0 : faceTop);
                    for (int gy = faceBottom; gy < faceTop; gy++) {
                        putSolidAt(out, gable, new BlockPos(x, y0 + gy, z), false);
                    }
                }

                BuildPalette spine = ridge != null ? ridge : palette;
                // 垂脊:四坡到两边檐口等距的那条正 45° 对角线。悬山没有垂脊,两端
                // 改作博风板——同样是异色一条,只是走在山面的边缘。
                // 到两边屋檐距离相同的对角线形成斜脊；双坡顶的两端另加边脊，最高处再加两格正脊。
                boolean hipSpine = (hip || (xieshan && dEnd < brk)) && dSlope == dEnd && d < reach;
                boolean barge = !hip && !shed && dEnd == 0;
                if (hipSpine || barge) {
                    putProud(out, spine, x, z, y0, halves, 1);
                }
                if (!shed && d >= reach) {
                    putProud(out, spine, x, z, y0, halves, 2);
                }
            }
        }
        if (cornerLift > 0) {
            liftEaveCorners(out, ridge != null ? ridge : palette, ax, bx, az, bz, y0, cornerLift);
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("roof resolved to zero cells");
        }
        return new ArrayList<>(out.values());
    }

    /**
     * 计算从檐口往屋脊走时每个位置的高度，单位是半格。cur 至少每步增加 1，避免坡面突然降低。
     * 直坡每步累计两个半格；曲坡的增量随距离增加，再四舍五入成可用半砖表达的高度。
     */
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

    /**
     * 放这一列最上面的屋面。檐口用上半砖做薄边；其余按半格高度选择下半砖或双层半砖。
     * 材料找不到对应半砖时，在同一坐标放原方块，坡面就只能按整格变化。
     */
    private static void skinCell(Map<Long, BuildTaskRecord.Target> out, BuildPalette pal,
                                 int x, int z, int y0, int halves, boolean thin) {
        BlockPos pos = new BlockPos(x, y0 + (halves - 1) / 2, z);
        BuildPalette.Entry e = pal.pick(pos);
        Block slab = slabFor(e.block());
        if (slab == null) {
            // 给的料推不出半砖(原木、玻璃之类),照整块砌:糙一点,但不漏
            out.put(pos.asLong(), new BuildTaskRecord.Target(e.block(), e.item(), pos, e.label(),
                    null, null, null));
            return;
        }
        putSlabAt(out, pos, slab,
                thin ? SlabType.TOP : (halves % 2 == 1 ? SlabType.BOTTOM : SlabType.DOUBLE));
    }

    /**
     * 在屋面下面补可选底衬。用整层半砖或上下错开的两片半砖连接底面，减少从屋内看到的空隙。
     * 高度不足以放底衬时直接跳过；找不到半砖时改放普通方块。
     */
    private static void soffitCell(Map<Long, BuildTaskRecord.Target> out, BuildPalette pal,
                                   int x, int z, int y0, int halves) {
        int s = halves - 2;
        if (s < 1) {
            return;
        }
        int y = y0 + (s - 1) / 2;
        BuildPalette.Entry e = pal.pick(new BlockPos(x, y, z));
        Block slab = slabFor(e.block());
        if (slab == null) {
            putSolidAt(out, pal, new BlockPos(x, y, z), false);
            return;
        }
        if (s % 2 == 0) {
            putSlabAt(out, new BlockPos(x, y, z), slab, SlabType.DOUBLE);
        } else {
            putSlabAt(out, new BlockPos(x, y, z), slab, SlabType.BOTTOM);
            putSlabAt(out, new BlockPos(x, y - 1, z), slab, SlabType.TOP);
        }
    }

    /**
     * 从屋面顶上开始，往上加 n 格装饰脊。选到半砖材料时用双层半砖，使这一格是实心的。
     */
    private static void putProud(Map<Long, BuildTaskRecord.Target> out, BuildPalette pal,
                                 int x, int z, int y0, int halves, int n) {
        for (int k = 0; k < n; k++) {
            BlockPos pos = new BlockPos(x, y0 + halves / 2 + k, z);
            BuildPalette.Entry e = pal.pick(pos);
            if (e.block() instanceof SlabBlock) {
                // 脊料常常也是半砖(没给 ridge_block 时就直接是屋面主料)。半砖的
                // 默认状态是下半砖,照放就是一条悬空的半砖;脊必须实心,所以铺双层。
                putSlabAt(out, pos, e.block(), SlabType.DOUBLE);
            } else {
                putSolidAt(out, pal, pos, true);
            }
        }
    }

    /** 不留阁楼时把屋面底下填实。 */
    // 非空心屋顶把基准高度到屋面之间的每格填满；已经有屋面目标的位置不覆盖。
    private static void fillUnder(Map<Long, BuildTaskRecord.Target> out, BuildPalette pal,
                                  int x, int z, int y0, int halves) {
        for (int y = y0; y < y0 + (halves - 1) / 2; y++) {
            putSolidAt(out, pal, new BlockPos(x, y, z), false);
        }
    }

    /**
     * 在四个檐角向上叠 lift 格；抬高两格以上时，再给角内相邻的两格各补一块作过渡。
     */
    private static void liftEaveCorners(Map<Long, BuildTaskRecord.Target> out, BuildPalette palette,
                                        int ax, int bx, int az, int bz, int y0, int lift) {
        int[][] corners = {{ax, az}, {bx, az}, {ax, bz}, {bx, bz}};
        for (int[] c : corners) {
            int cx = c[0];
            int cz = c[1];
            for (int k = 1; k <= lift; k++) {
                putSolidAt(out, palette, new BlockPos(cx, y0 + k, cz), true);
            }
            if (lift >= 2) {
                int inx = cx == ax ? 1 : -1;
                int inz = cz == az ? 1 : -1;
                putSolidAt(out, palette, new BlockPos(cx + inx, y0 + 1, cz), false);
                putSolidAt(out, palette, new BlockPos(cx, y0 + 1, cz + inz), false);
            }
        }
    }

    /**
     * 尝试按注册名找到同材质半砖：木板、楼梯或墙替换后缀，其次尝试去掉复数 s，再加 _slab。
     * 这是命名规则猜测，不查询配方；同一命名空间里确实注册为 SlabBlock 才采用，找不到返回 null。
     */
    private static Block slabFor(Block block) {
        if (block instanceof SlabBlock) {
            return block;
        }
        var id = BuiltInRegistries.BLOCK.getKey(block);
        if (id == null) {
            return null;
        }
        String p = id.getPath();
        List<String> tries = new ArrayList<>();
        for (String suf : new String[]{"_stairs", "_planks", "_wall"}) {
            if (p.endsWith(suf)) {
                tries.add(p.substring(0, p.length() - suf.length()) + "_slab");
            }
        }
        if (p.endsWith("s")) {
            tries.add(p.substring(0, p.length() - 1) + "_slab");
        }
        tries.add(p + "_slab");
        for (String t : tries) {
            Block b = BuiltInRegistries.BLOCK.get(
                    net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(id.getNamespace(), t));
            if (b instanceof SlabBlock) {
                return b;
            }
        }
        return null;
    }

    // 写入半砖种类和上下位置；双层半砖没有单独的“上半”提示，但状态本身仍要求 DOUBLE。
    private static void putSlabAt(Map<Long, BuildTaskRecord.Target> out, BlockPos pos,
                                  Block slab, SlabType type) {
        BlockState state = slab.defaultBlockState().setValue(SlabBlock.TYPE, type);
        // topHalf 是状态的镜像,不是自由字段:双层砖没有上下之分,那里必须是 null,
        // 否则 Target 自己的一致性校验当场拒收(它按同一条规则反推)。
        Boolean topHalf = type == SlabType.DOUBLE ? null : type == SlabType.TOP;
        out.put(pos.asLong(), new BuildTaskRecord.Target(state, slab.asItem(), pos,
                BuiltInRegistries.BLOCK.getKey(slab).getPath(), null, null, topHalf));
    }

    // 同一坐标已有目标时，由 overwrite 决定替换它还是保留原目标。
    private static void putSolidAt(Map<Long, BuildTaskRecord.Target> out, BuildPalette palette,
                                   BlockPos pos, boolean overwrite) {
        BuildPalette.Entry e = palette.pick(pos);
        BuildTaskRecord.Target t =
                new BuildTaskRecord.Target(e.block(), e.item(), pos, e.label(), null, null, null);
        if (overwrite) {
            out.put(pos.asLong(), t);
        } else {
            out.putIfAbsent(pos.asLong(), t);
        }
    }

    private static void add(Set<BlockPos> out, int x, int y, int z) {
        out.add(new BlockPos(x, y, z));
    }

    private static int req(Integer v, String name) {
        if (v == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        return v;
    }
}
