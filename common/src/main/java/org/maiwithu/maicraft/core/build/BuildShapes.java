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
 * 参数化建造几何:形状算子(盒/周界墙/线/柱/球/撒点)与屋顶引擎。
 * 全是无状态纯函数,产出目标格集——`build` 工具是它的一个调用方,
 * 集成测试把它当几何库直接验。
 */
public final class BuildShapes {

    private BuildShapes() {}

    /**
     * 一次调用展开后的总格数上限。
     *
     * <p>此前是 4096,而一栋正常房子 5000~8000 格——等于逼着整栋建筑拆成好几次
     * 调用。生存模式的盘料只盘当前这一批,拆开就意味着墙已经砌好了才发现屋顶的
     * 料不够,留下半成品空壳。<b>"整栋一次规划"和这个上限是绑死的</b>,要求前者
     * 就必须给够后者。
     */
    public static final int MAX_TOTAL_CELLS = 16384;

    /** 形状展开为格集(去重、上限封顶)。公开静态,测试直接验几何。 */
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

    /** 平面撒点:y1 平面、x1,z1..x2,z2 矩形内按密度取格。位置哈希决定取舍——
     *  确定性(同参数同结果),测试与断点续建都friendly。 */
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
     * 屋顶:半砖三态砌出的连续坡面 + 垂脊 + 正脊 + 檐口。
     *
     * <p>做法是从四栋手工中式建筑(悬山、歇山、庑殿、攒尖)里逐格量出来的,不是推的。
     * 三条量出来的事实决定了整个引擎:
     *
     * <ol>
     *   <li><b>坡面主料是半砖,不是楼梯。</b>四栋里半砖比楼梯多 6~43 倍,而且
     *       bottom/top/double 三态的占比四栋几乎一致(37/27/35)。砌法是同一个
     *       高度上<b>下半砖当踏面、双层砖当立面</b>交替,顶面每格升半格,连一级
     *       整块的台阶都没有。此前这里是"下半砖 + 上半砖",顶面轮廓一样,但上半砖
     *       底下那半格是空的——从底下看是一排悬空的砖。</li>
     *   <li><b>举架量的是每格的抬升,不是每层的收分。</b>量出来的顶面高度序列是
     *       每格抬一个半砖(五举),接近脊时抬两个(十举),平均 1.2,即屋顶高
     *       ≈ 0.6 × 半跨。所以这里<b>按每格到檐口的距离直接定高度</b>,不再逐层
     *       收分——四条垂脊(到两边檐口等距的那条对角线)也就自然落出来了,而逐层
     *       收分的写法必须另外拼角,拼一次错一次。</li>
     *   <li><b>脊高出屋面,不齐平。</b>四栋的脊都是异色实心块压在瓦面之上:庑殿、
     *       攒尖的四条垂脊是一格宽的正 45° 对角线,悬山两端是外探的博风板,正脊
     *       再高出两格。此前脊是嵌进最后一层里的,所以四坡顶怎么调都不像中式。</li>
     * </ol>
     *
     * <p>结构定死,换料换风格:石砖 + 铜是中式,深板岩 + 深色橡木是哥特,陶瓦是
     * 地中海。所以每一个结构件都单独收一个 palette 参数。
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
     * 举架:从檐口往脊,每格屋面的顶面到多高(以<b>半砖</b>计)。
     *
     * <p>清式《工程做法》的举架把东亚屋面那条凹曲线量化成逐步加陡的举高比:檐口起于
     * "五举"(0.5),脊步收到"十举"(1.0)。翻成方块就是<b>每格抬升几个半砖</b>:
     * 五举抬一个,十举抬两个。
     *
     * <p>存档里量出来的顶面序列(悬山,半跨 13)是
     * {@code 4,4,5,6,7,8,9,10,11,12,14,15,17}——前段每格抬一,近脊抬二,平均 1.2,
     * 也就是屋顶高 ≈ 0.6 × 半跨。下面这条 {@code 1 + 0.6f²} 的抬升量累出来正是这个
     * 数,而且中段会自然出现一二相间,和量到的一样。
