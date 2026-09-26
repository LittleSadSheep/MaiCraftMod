package org.maiwithu.maicraft.core.pathing.transport;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import org.maiwithu.maicraft.core.pathing.util.ClientSurfaceHeight;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.core.pathing.calc.NavGoal;
import org.maiwithu.maicraft.core.pathing.goal.GoalCompiler;

/** 在目标范围里找可安全站稳的静态落点；分刻检查已加载方块，不把未知或被挡住的位置当落点。 */
public final class TransportTargets {
    private static final int MAX_GOALS = 256, MAX_CANDIDATES = 8192, MAX_RESULTS = 32;
    private static final int MAX_RADIUS = 8, MAX_PER_TICK = 128;
    private final ArrayDeque<Region> pending = new ArrayDeque<>();
    private final ArrayDeque<SurfaceColumn> surfaceColumns = new ArrayDeque<>();
    private ForwardTravelGoal forward;
    private record SurfaceColumn(ForwardTravelGoal goal, BlockPos column) {}
    private final LongSet forbidden;
    private final LongSet visited = new LongOpenHashSet();
    private final List<Destination> destinations = new ArrayList<>();
    private final Vec3 origin;
    private final double width, height;
    private final int minY, maxY;
    private int examined;
    private boolean unloaded, unknown, truncated;

    public record Destination(BlockPos feet, Vec3 landingPoint) {
        public Destination { feet = feet.immutable(); }
    }

    public TransportTargets(GoalCompiler.Compiled compiled, LocalPlayerContext context, LongSet forbiddenBodyCells) {
        this(compiled, context.player().position(), context.player().getBbWidth(),
                Math.max(1.8, context.player().getBbHeight()), context.level().getMinBuildHeight(),
                context.level().getMaxBuildHeight(), forbiddenBodyCells);
    }

    /** 几何测试使用的纯构造器；所有参数均为逻辑值。 */
    TransportTargets(GoalCompiler.Compiled compiled, Vec3 origin, double width, double height,
                      int minY, int maxY, LongSet forbiddenBodyCells) {
        // 先把组合目标拆成待检查的小区域；只复制目标和身体尺寸，不把活世界对象带到后续状态里。
        this.origin = origin;
        this.width = width;
        this.height = height;
        this.minY = minY;
        this.maxY = maxY;
        forbidden = new LongOpenHashSet(forbiddenBodyCells);
        ArrayDeque<NavGoal> goals = new ArrayDeque<>();
        goals.add(compiled.goal());
        int count = 0;
        while (!goals.isEmpty() && count++ < MAX_GOALS) {
            NavGoal goal = goals.removeFirst();
            if (goal instanceof NavGoal.Composite composite) {
                int available = MAX_GOALS - count - goals.size();
                if (composite.members.size() > available) truncated = true;
                for (int i = 0; i < Math.min(available, composite.members.size()); i++) goals.addLast(composite.members.get(i));
            } else {
                add(goal);
            }
        }
        if (!goals.isEmpty()) truncated = true;
    }

    /** 有界搜索完成时返回 true，包括已明确标记为证据不完整的情况。 */
    public boolean tick(LocalPlayerContext context) {
        context.requireCurrent();
        return tick(context.level(), context.level()::isLoaded);
    }

    boolean tick(BlockGetter view, Predicate<BlockPos> loaded) {
        // 每刻最多查一百二十八个位置并尽量控制在一毫秒内，总数也有限；达到预算会注明截断，不代表全世界已查完。
        long deadline = System.nanoTime() + 1_000_000L;
        int budget = 0;
        while ((!pending.isEmpty() || !surfaceColumns.isEmpty()) && budget < MAX_PER_TICK && (budget == 0 || System.nanoTime() < deadline)) {
            if (examined >= MAX_CANDIDATES) {
                truncated = true;
                pending.clear(); surfaceColumns.clear();
                break;
            }
            examined++; budget++;
            BlockPos feet; NavGoal goal;
            if (!surfaceColumns.isEmpty()) {
                var column = surfaceColumns.removeFirst(); goal = column.goal();
                if (!loaded.test(column.column())) { unloaded = true; continue; }
                int y = surfaceHeight(view, loaded, column.column());
                if (y == Integer.MIN_VALUE) { unloaded = true; continue; }
                if (y <= minY || y >= maxY) continue;
                feet = new BlockPos(column.column().getX(), y, column.column().getZ());
            } else {
                Region region = pending.removeFirst(); feet = region.next(); goal = region.goal;
                if (region.remaining()) pending.addLast(region);
            }
            if (!goal.isAt(feet) || !visited.add(feet.asLong())) continue;
            var probe = TransportLanding.inspect(view, loaded, feet, width, height, forbidden);
            unloaded |= probe.unloaded();
            unknown |= probe.unknown();
            if (probe.destination() != null) {
                // 合格落点按离出发点的距离排列，最多保留三十二个，并记录还有候选被省略。
                destinations.add(probe.destination());
                destinations.sort(forward == null ? Comparator.comparingDouble(d -> d.landingPoint().distanceToSqr(origin))
                        : Comparator.comparingDouble((Destination d) -> forward.score(d.feet())).thenComparingDouble(d -> forward.remaining(d.feet())));
                if (destinations.size() > MAX_RESULTS) {
                    destinations.removeLast();
                    truncated = true;
                }
            }
        }
        return complete();
    }

    // 中继只读已加载列的真实表面，不能把悬空代表点或地底空腔选成飞行落点。
    private int surfaceHeight(BlockGetter view, Predicate<BlockPos> loaded, BlockPos column) {
        if (view instanceof ClientLevel level) return ClientSurfaceHeight.motionBlockingNoLeaves(level, column.getX(), column.getZ());
        for (int y = maxY - 1; y >= minY; y--) {
            BlockPos at = new BlockPos(column.getX(), y, column.getZ());
            if (!loaded.test(at)) return Integer.MIN_VALUE;
            if (Heightmap.Types.MOTION_BLOCKING_NO_LEAVES.isOpaque().test(view.getBlockState(at))) return y + 1;
        }
        return minY;
    }

    public List<Destination> destinations() { return List.copyOf(destinations); }
    public boolean complete() { return pending.isEmpty() && surfaceColumns.isEmpty(); }
    public boolean hasUnloadedEvidence() { return unloaded; }
    public boolean hasUnknownEvidence() { return unknown; }
    public boolean truncated() { return truncated; }
    public int examinedCandidates() { return examined; }
    public String diagnostic() {
        return "static targets=" + destinations.size() + ", examined=" + examined + ", complete=" + complete()
                + ", unloaded=" + unloaded + ", unknown=" + unknown + ", truncated=" + truncated;
    }

    private void add(NavGoal goal) {
        // 不同目标生成不同的检查范围：准确位置查一格，只给 x/z 则沿高度查，未知自定义目标不猜中心点。
        int currentY = Math.max(minY + 1, Math.min(maxY - 1, (int) Math.floor(origin.y)));
        if (goal instanceof ForwardTravelGoal progress) {
            forward = progress;
            // 采样整片前向区域，空间越窄可逐轮细化；每个落点还必须过下方完整支撑/碰撞/液体校验。
            for (int x = -ForwardTravelGoal.RANGE; x <= ForwardTravelGoal.RANGE; x += progress.sampleStep)
                for (int z = -ForwardTravelGoal.RANGE; z <= ForwardTravelGoal.RANGE; z += progress.sampleStep) {
                    BlockPos column = progress.origin.offset(x, 0, z);
                    if (!progress.isAt(column)) continue;
                    if (progress.surface) surfaceColumns.addLast(new SurfaceColumn(progress, column));
                    else region(progress, column, 0, currentY - 8, currentY + 8);
                }
        } else if (goal instanceof NavGoal.Exact exact) {
            region(goal, exact.goal, 0, exact.goal.getY(), exact.goal.getY());
        } else if (goal instanceof NavGoal.Column column) {
            int radius = (int) Math.min(MAX_RADIUS, Math.ceil(column.radius));
            truncated |= column.radius > MAX_RADIUS;
            region(goal, new BlockPos(column.x, currentY, column.z), radius, minY + 1, maxY - 1);
        } else if (goal instanceof NavGoal.YLevel level) {
            region(goal, BlockPos.containing(origin.x, level.level, origin.z), MAX_RADIUS, level.level, level.level);
            truncated = true; // Y 平面没有有限的穷尽式邻域。
        } else if (goal instanceof NavGoal.GetToBlock || goal instanceof NavGoal.MineStance) {
            region(goal, goal.center(), 1, goal.center().getY() - 2,
                    goal.center().getY() + (goal instanceof NavGoal.GetToBlock ? 1 : 0));
        } else if (goal instanceof NavGoal.Adjacent) {
            region(goal, goal.center(), 1, goal.center().getY() - 1, goal.center().getY() + 1);
        } else if (goal instanceof NavGoal.MineColumn column) {
            int below = Math.max(0, Math.min(512, column.maxBelow));
            truncated |= below != column.maxBelow;
            region(goal, column.ore, 0, column.ore.getY() - below, column.ore.getY());
        } else if (goal instanceof NavGoal.Near near) {
            near(goal, near.radius, false);
        } else if (goal instanceof NavGoal.NearGround near) {
            int vertical = (int) Math.min(512, Math.ceil(near.verticalTolerance));
            truncated |= near.verticalTolerance > 512;
            near(goal, near.radius, vertical);
        } else {
            unknown = true; // 自定义、移动或避让目标不能被猜成静态中心点。
        }
    }

    private void near(NavGoal goal, double radius, boolean ground) {
        near(goal, radius, ground ? 1 : (int) Math.min(MAX_RADIUS, Math.ceil(radius)));
    }

    private void near(NavGoal goal, double radius, int vertical) {
        // 实际最多检查水平八格；用户范围更大时保留“没有穷尽”的标记。
        if (!Double.isFinite(radius) || radius < 0) { unknown = true; return; }
        int bounded = (int) Math.min(MAX_RADIUS, Math.ceil(radius));
        truncated |= radius > MAX_RADIUS;
        region(goal, goal.center(), bounded, goal.center().getY() - vertical, goal.center().getY() + vertical);
    }

    private void region(NavGoal goal, BlockPos center, int radius, int low, int high) {
        low = Math.max(minY + 1, low);
        high = Math.min(maxY - 1, high);
        if (low <= high) pending.add(new Region(goal, center, radius, low, high));
    }

    /** 不一次生成成千上万坐标，而是记住检查序号，每次取下一个，优先从靠近参考点的轴坐标开始。 */
    private static final class Region {
        final NavGoal goal;
        final BlockPos center;
        final int radius, low, high, baseY, side, total;
        int index;

        Region(NavGoal goal, BlockPos center, int radius, int low, int high) {
            this.goal = goal;
            this.center = center.immutable();
            this.radius = radius;
            this.low = low;
            this.high = high;
            baseY = Math.max(low, Math.min(high, center.getY()));
            side = radius * 2 + 1;
            total = side * side * (high - low + 1);
        }

        BlockPos next() {
            int value = index++;
            int x = offset(value % side);
            int z = offset(value / side % side);
            int yIndex = value / (side * side);
            int paired = Math.min(baseY - low, high - baseY) * 2;
            int y = yIndex <= paired ? baseY + offset(yIndex)
                    : baseY + (high - baseY > baseY - low ? yIndex - paired / 2 : -(yIndex - paired / 2));
            return new BlockPos(center.getX() + x, y, center.getZ() + z);
        }

        boolean remaining() { return index < total; }
        private static int offset(int index) { return index % 2 == 0 ? -index / 2 : (index + 1) / 2; }
    }
}
