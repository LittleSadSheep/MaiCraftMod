// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/**
 * 脱困放行策略：起点已处在岩浆致死邻域内时，「邻格有岩浆就禁挖」的常规
 * 保护会把从危险邻域出发的所有路线全数拒绝——起点本身就不安全，这条保护
 * 只剩下把人钉死在原地的作用。本策略只放行一类例外：破坏开口位于比检测
 * 时刻身体位置<b>离危险源更远</b>的一侧，路线因此净远离危险；开口落在身
 * 体与危险源之间或同距侧仍按原规则拒绝。
 *
 * <p>不变量：放宽只对「起点已在危险邻域」生效，身体脱出检测半径后策略
 * 整体失效，常规危险回避原样恢复；头顶岩浆的禁挖永不禁豁免——掀开头顶
 * 岩浆任何情况下都是致死方向。放宽不是无视伤害：岩浆伤害仍按原版结算，
 * 路线的目标是脱离而非穿越。</p>
 *
 * <p>检测半径是保守常量：原实机场景距源 2 格即全拒、边界值未知，取水平
 * 3 格、垂直 ±1 覆盖原版岩浆水平流动的致死范围。该边界只影响「何时进入
 * 脱困模式」，不据此标定伤害范围。</p>
 */
public final class HazardEscapePolicy {

    /** 水平检测半径(切比雪夫格数)。 */
    public static final int DETECTION_RADIUS = 3;
    private static final int VERTICAL_RANGE = 1;

    /** 无危险邻域时的空策略，所有查询都按常规规则处理。 */
    public static final HazardEscapePolicy INACTIVE = new HazardEscapePolicy(BlockPos.ZERO, BlockPos.ZERO);

    /** 检测时刻的脚部格，放行距离的比较基准。 */
    private final BlockPos origin;
    /** 距起点最近的岩浆格。 */
    private final BlockPos hazard;

    private HazardEscapePolicy(BlockPos origin, BlockPos hazard) {
        this.origin = origin;
        this.hazard = hazard;
    }

    public boolean active() {
        return this != INACTIVE;
    }

    /**
     * 扫描脚部周围小邻域内的岩浆(源或流动)，发现即进入脱困模式并冻结
     * 最近危险格。只读取 {@code loaded} 判定为已加载的格子，未加载按空气
     * 处理——未观察到的范围不参与判定，也不触发同步加载。
     */
    public static HazardEscapePolicy detect(BlockGetter view, BlockPos feet) {
        return detect(view, feet, pos -> true);
    }

    public static HazardEscapePolicy detect(BlockGetter view, BlockPos feet,
                                            java.util.function.Predicate<BlockPos> loaded) {
        BlockPos nearest = null;
        double nearestSq = Double.MAX_VALUE;
        for (int dy = -VERTICAL_RANGE; dy <= VERTICAL_RANGE; dy++) {
            for (int dz = -DETECTION_RADIUS; dz <= DETECTION_RADIUS; dz++) {
                for (int dx = -DETECTION_RADIUS; dx <= DETECTION_RADIUS; dx++) {
                    BlockPos pos = feet.offset(dx, dy, dz);
                    if (!loaded.test(pos) || !isLava(view.getBlockState(pos))) {
                        continue;
                    }
                    double sq = distanceSq(feet, pos);
                    if (sq < nearestSq) {
                        nearestSq = sq;
                        nearest = pos.immutable();
                    }
                }
            }
        }
        return nearest == null ? INACTIVE : new HazardEscapePolicy(feet.immutable(), nearest);
    }

    private static boolean isLava(BlockState state) {
        FluidState fluid = state.getFluidState();
        return fluid.is(Fluids.LAVA) || fluid.is(Fluids.FLOWING_LAVA);
    }

    private static double distanceSq(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * 是否放行在 (x,y,z) 旁挖掘：破坏点严格比基准身体位置离危险源更远才
     * 放行。{@code body} 传检测时刻的脚部格(规划与搜索一致)；执行期逐刻
     * 复核时上下文重建、基准随身体推进前移，已走过的近危险侧不会回头放行。
     */
    public boolean permitsBreakingBesideLava(BlockPos body, int x, int y, int z) {
        if (!active()) {
            return false;
        }
        return distanceSq(new BlockPos(x, y, z), hazard) > distanceSq(body, hazard);
    }

    /** 检测时刻的身体格，供执行期比较复用。 */
    public BlockPos origin() {
        return origin;
    }

    /** 最近的岩浆格，回执证据用。 */
    public BlockPos hazard() {
        return hazard;
    }

    /** 起点到最近危险源的直线距离(格)。 */
    public double hazardDistance() {
        return Math.sqrt(distanceSq(origin, hazard));
    }

    /**
     * 脱困建议：最近危险源位置与距离、净远离的罗盘方向，以及沿该方向
     * 步进 6 格的建议撤退点。方向以水平为主轴量化到八向，供调用方改提
     * travel 目标。
     */
    public Map<String, Object> evidence() {
        int awayX = origin.getX() - hazard.getX();
        int awayZ = origin.getZ() - hazard.getZ();
        String direction = compassDirection(awayX, awayZ);
        BlockPos retreat = origin.offset(
                Integer.signum(awayX) * 6, 0, Integer.signum(awayZ) * 6);
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("nearest_hazard", List.of(hazard.getX(), hazard.getY(), hazard.getZ()));
        facts.put("distance_blocks", Math.round(hazardDistance() * 10.0) / 10.0);
        facts.put("escape_direction", direction);
        facts.put("suggested_retreat", List.of(retreat.getX(), retreat.getY(), retreat.getZ()));
        return facts;
    }

    /** 单行脱困建议文本，拼进 no path 失败详情。 */
    public String detail() {
        Map<String, Object> facts = evidence();
        @SuppressWarnings("unchecked")
        var hazardPos = (java.util.List<Integer>) facts.get("nearest_hazard");
        return "hazard escape hint: nearest lava at " + hazardPos + ", "
                + facts.get("distance_blocks") + " blocks away; escape direction: "
                + facts.get("escape_direction") + ", suggested retreat "
                + facts.get("suggested_retreat");
    }

    /** 水平八向罗盘名(north = -z, east = +x)，与原版朝向约定一致。 */
    private static String compassDirection(int dx, int dz) {
        if (dx == 0 && dz == 0) {
            return "UP"; // 危险在正上方/正下方同列，任一水平方向都是远离
        }
        boolean ns = Math.abs(dz) > Math.abs(dx);
        boolean ew = Math.abs(dx) > Math.abs(dz);
        StringBuilder out = new StringBuilder();
        if (ns) {
            out.append(dz < 0 ? "SOUTH" : "NORTH");
        } else if (ew) {
            out.append(dx > 0 ? "EAST" : "WEST");
        } else {
            out.append(dx > 0 ? "EAST" : "WEST").append('_').append(dz < 0 ? "SOUTH" : "NORTH");
        }
        return out.toString();
    }
}
