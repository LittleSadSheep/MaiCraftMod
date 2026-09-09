package org.maiwithu.maicraft.core.pathing.execute;

import org.maiwithu.maicraft.entity.InputDriver;
import net.minecraft.client.player.LocalPlayer;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * 玩家已经坐在船上时，先规划一段同高度水路并划向终点。最多搜索四千零九十六次，找不到目标就驶向已找到的最近水格。
 * 这里的 ARRIVED 只表示这段水路结束，不保证目标已到达或旁边真有岸；后续由移动任务决定。
 */
public final class BoatNav {

    public enum Status { RUNNING, ARRIVED, FAILED }

    /**
     * 单次水路搜索最多展开四千零九十六个节点；当前到达这段终点后不会在本类重新规划。
     */
    private static final int NODE_BUDGET = 4096;
    /** 对当前路点无进展多少刻判搁浅。 */
    private static final int STUCK_TICKS = 60;
    /** 路点算到达的水平距离平方。 */
    private static final double WAYPOINT_REACHED_SQ = 1.2 * 1.2;
    /** 离目标水平这么近的水格就算终点(船身宽,贴不到格心)。 */
    private static final int GOAL_NEAR = 2;
    /** 直线水路检查的采样步长(格)。 */
    private static final double LINE_STEP = 0.5;

    private final LocalPlayer player;
    private final BlockPos target;

    /** 找不到水面时先等船稳多少刻:刚下水、刚上人时船在颠簸,一刻的空窗不是结论。 */
    private static final int SETTLE_GRACE_TICKS = 30;

    private boolean planned;
    private int settleGrace;
    private int surfaceY;
    private List<BlockPos> path = List.of();
    private int wpIndex;
    private double bestWpDistSq = Double.MAX_VALUE;
    private int noProgressTicks;
    private String failReason = "boat navigation failed";

    public BoatNav(LocalPlayer player, BlockPos target) {
        this.player = player;
        this.target = target.immutable();
    }

    public String failReason() {
        return failReason;
    }

    /**
     * 最近二十刻没有持续停滞就算仍有进展，外层移动任务据此延长允许行进的时间。
     */
    public boolean progressing() {
        return noProgressTicks <= 20;
    }

    public Status tick() {
        Entity vehicle = player.getVehicle();
        if (!(vehicle instanceof Boat boat)) {
            failReason = "no longer in a boat";
            return Status.FAILED;
        }
        // 只在开头规划一次；刚下水暂时读不到水时最多等三十刻。
        if (!planned) {
            Integer surface = waterSurfaceAt(boat.blockPosition());
            if (surface == null) {
                if (++settleGrace <= SETTLE_GRACE_TICKS) {
                    return Status.RUNNING;   // 船还在颠,等它稳
                }
                failReason = "the boat is not on water";
                return Status.FAILED;
            }
            planned = true;
            Status pre = plan(boat, surface);
            if (pre != null) {
                return pre;
            }
        }
        // 贪心推进:直线水路能看到的最远路点就是当前路点——网格锯齿在这一步消失
        Vec3 at = boat.position();
        for (int i = path.size() - 1; i > wpIndex; i--) {
            if (clearWaterLine(at, path.get(i))) {
                wpIndex = i;
                bestWpDistSq = Double.MAX_VALUE;
                break;
            }
        }
        if (wpIndex >= path.size()) {
            InputDriver.haltVehicle(player);
            return Status.ARRIVED;
        }
        BlockPos wp = path.get(wpIndex);
        double distSq = horizontalDistSq(at, wp);
        if (distSq <= WAYPOINT_REACHED_SQ) {
            wpIndex++;
            bestWpDistSq = Double.MAX_VALUE;
            if (wpIndex >= path.size()) {
                InputDriver.haltVehicle(player);
                return Status.ARRIVED;
            }
            wp = path.get(wpIndex);
            distSq = horizontalDistSq(at, wp);
        }
        // 搁浅判定:对当前路点的最近距离长期不缩短。撞上冰面、被推上岸都落在这儿。
        // 不断接近路点就清掉停滞计数；超过六十刻没进展则停止划桨，交给外层处理。
        if (distSq < bestWpDistSq - 0.05) {
            bestWpDistSq = distSq;
            noProgressTicks = 0;
        } else if (++noProgressTicks > STUCK_TICKS) {
            InputDriver.haltVehicle(player);
            failReason = "the boat stopped making headway (beached or blocked)";
            return Status.FAILED;
        }
        InputDriver.steerVehicle(player, Vec3.atBottomCenterOf(wp));
        return Status.RUNNING;
    }

    /** 收桨。任务层中途弃船(取消/让位)时调,别让船带着按下的前进键漂走。 */
    public void stop() {
        InputDriver.haltVehicle(player);
    }

    // ------------------------------------------------------------------
    // 规划
    // ------------------------------------------------------------------

    /**
     * 在最初找到的水面高度搜索八个方向，斜走还要求两侧格可通行；找不到目标就保存这次搜索中最接近目标的路径。
     */
    private Status plan(Boat boat, int surface) {
        BlockPos feet = boat.blockPosition();
        surfaceY = surface;
        int sx = feet.getX();
        int sz = feet.getZ();
        if (nearTarget(sx, sz)) {
            path = List.of();
            return Status.ARRIVED;
        }

        // 平面 A*。key = (x<<32|z),8 邻接,对角要求两正交都可行。
        Map<Long, long[]> nodes = new HashMap<>();   // key -> {parentKey, gCost(双精度位)}
        PriorityQueue<long[]> open = new PriorityQueue<>(
                (a, b) -> Double.compare(Double.longBitsToDouble(a[1]), Double.longBitsToDouble(b[1])));
        long startKey = key(sx, sz);
        nodes.put(startKey, new long[]{startKey, Double.doubleToLongBits(0)});
        open.add(new long[]{startKey, Double.doubleToLongBits(heuristic(sx, sz))});
        long bestKey = startKey;
        double bestH = heuristic(sx, sz);
        int expanded = 0;
        Long goalKey = null;

        while (!open.isEmpty() && expanded < NODE_BUDGET) {
            long cur = open.poll()[0];
            expanded++;
            int cx = unpackX(cur);
            int cz = unpackZ(cur);
            if (nearTarget(cx, cz)) {
                goalKey = cur;
                break;
            }
            double h = heuristic(cx, cz);
            if (h < bestH) {
                bestH = h;
                bestKey = cur;
            }
            double g = Double.longBitsToDouble(nodes.get(cur)[1]);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    int nx = cx + dx;
                    int nz = cz + dz;
                    if (!cruisable(nx, nz)) continue;
                    if (dx != 0 && dz != 0
                            && (!cruisable(cx + dx, cz) || !cruisable(cx, cz + dz))) continue;
                    double ng = g + (dx != 0 && dz != 0 ? 1.4142 : 1.0);
                    long nk = key(nx, nz);
                    long[] seen = nodes.get(nk);
                    if (seen != null && Double.longBitsToDouble(seen[1]) <= ng) continue;
                    nodes.put(nk, new long[]{cur, Double.doubleToLongBits(ng)});
                    open.add(new long[]{nk, Double.doubleToLongBits(ng + heuristic(nx, nz))});
                }
            }
        }

        long endKey = goalKey != null ? goalKey : bestKey;
        if (endKey == startKey) {
            failReason = "no open water leads anywhere from here";
            return Status.FAILED;
        }
        List<BlockPos> cells = new ArrayList<>();
        for (long k = endKey; k != startKey; k = nodes.get(k)[0]) {
            cells.add(new BlockPos(unpackX(k), surfaceY, unpackZ(k)));
        }
        java.util.Collections.reverse(cells);
        path = cells;
        wpIndex = 0;
        org.maiwithu.maicraft.core.Constants.LOG.debug(
                "[maicraft-boat] 航线 {} 点,{} 展开,{}", path.size(), expanded,
                goalKey != null ? "直达目标水域" : "至最近靠岸点");
        return null;
    }

    /**
     * 当前只检查这一格有水、上方两格没有碰撞；没有按船的宽度检查两侧，也没检查含水格本身的实体碰撞。
     */
    private boolean cruisable(int x, int z) {
        Level level = player.level();
        BlockPos at = new BlockPos(x, surfaceY, z);
        if (!level.getFluidState(at).is(FluidTags.WATER)) {
            return false;
        }
        return level.getBlockState(at.above()).getCollisionShape(level, at.above()).isEmpty()
                && level.getBlockState(at.above(2)).getCollisionShape(level, at.above(2)).isEmpty();
    }

    /**
     * 从船所在格向下最多看三格，取第一个有水的高度；不是从这里再向上搜索整片水的真正表面。
     */
    private Integer waterSurfaceAt(BlockPos feet) {
        Level level = player.level();
        for (int dy = 0; dy <= 3; dy++) {
            BlockPos at = feet.below(dy);
            if (level.getFluidState(at).is(FluidTags.WATER)) {
                return at.getY();
            }
        }
        return null;
    }

    /**
     * 沿中心线约每半格取样；通过时跳过中间路点，直接划向更远的路点。
     */
    private boolean clearWaterLine(Vec3 from, BlockPos to) {
        double tx = to.getX() + 0.5;
        double tz = to.getZ() + 0.5;
        double dx = tx - from.x;
        double dz = tz - from.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        int steps = Math.max(1, (int) (dist / LINE_STEP));
        for (int i = 1; i <= steps; i++) {
            double fx = from.x + dx * i / steps;
            double fz = from.z + dz * i / steps;
            if (!cruisable((int) Math.floor(fx), (int) Math.floor(fz))) {
                return false;
            }
        }
        return true;
    }

    private boolean nearTarget(int x, int z) {
        return Math.abs(x - target.getX()) <= GOAL_NEAR && Math.abs(z - target.getZ()) <= GOAL_NEAR;
    }

    private double heuristic(int x, int z) {
        double dx = x - target.getX();
        double dz = z - target.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static double horizontalDistSq(Vec3 at, BlockPos cell) {
        double dx = cell.getX() + 0.5 - at.x;
        double dz = cell.getZ() + 0.5 - at.z;
        return dx * dx + dz * dz;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private static int unpackX(long key) {
        return (int) (key >> 32);
    }

    private static int unpackZ(long key) {
        return (int) key;
    }
}
