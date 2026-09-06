package org.maiwithu.maicraft.core.pathing.transport;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.core.pathing.calc.NavGoal;
import org.maiwithu.maicraft.core.pathing.goal.GoalCompiler;

/** Bounded discovery of dry, collision-verified static transport endpoints. No live world is retained. */
public final class TransportTargets {
    private static final int MAX_GOALS = 256, MAX_CANDIDATES = 8192, MAX_RESULTS = 32;
    private static final int MAX_RADIUS = 8, MAX_PER_TICK = 128;
    private final ArrayDeque<Region> pending = new ArrayDeque<>();
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

    /** Pure constructor used by geometry tests; all arguments are logical values. */
    TransportTargets(GoalCompiler.Compiled compiled, Vec3 origin, double width, double height,
                     int minY, int maxY, LongSet forbiddenBodyCells) {
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

    /** Returns true when this bounded search is finished, including explicit incomplete evidence. */
    public boolean tick(LocalPlayerContext context) {
        context.requireCurrent();
        return tick(context.level(), context.level()::isLoaded);
    }

    boolean tick(BlockGetter view, Predicate<BlockPos> loaded) {
        long deadline = System.nanoTime() + 1_000_000L;
        int budget = 0;
        while (!pending.isEmpty() && budget < MAX_PER_TICK && (budget == 0 || System.nanoTime() < deadline)) {
            if (examined >= MAX_CANDIDATES) {
                truncated = true;
                pending.clear();
                break;
            }
            Region region = pending.removeFirst();
            BlockPos feet = region.next();
            if (region.remaining()) pending.addLast(region);
            examined++;
            budget++;
            if (!region.goal.isAt(feet) || !visited.add(feet.asLong())) continue;
            var probe = TransportLanding.inspect(view, loaded, feet, width, height, forbidden);
            unloaded |= probe.unloaded();
            unknown |= probe.unknown();
            if (probe.destination() != null) {
                destinations.add(probe.destination());
                destinations.sort(Comparator.comparingDouble(d -> d.landingPoint().distanceToSqr(origin)));
                if (destinations.size() > MAX_RESULTS) {
                    destinations.removeLast();
                    truncated = true;
                }
            }
        }
        return complete();
    }

    public List<Destination> destinations() { return List.copyOf(destinations); }
    public boolean complete() { return pending.isEmpty(); }
    public boolean hasUnloadedEvidence() { return unloaded; }
    public boolean hasUnknownEvidence() { return unknown; }
    public boolean truncated() { return truncated; }
    public int examinedCandidates() { return examined; }
    public String diagnostic() {
        return "static targets=" + destinations.size() + ", examined=" + examined + ", complete=" + complete()
                + ", unloaded=" + unloaded + ", unknown=" + unknown + ", truncated=" + truncated;
    }

    private void add(NavGoal goal) {
        int currentY = Math.max(minY + 1, Math.min(maxY - 1, (int) Math.floor(origin.y)));
        if (goal instanceof NavGoal.Exact exact) {
            region(goal, exact.goal, 0, exact.goal.getY(), exact.goal.getY());
        } else if (goal instanceof NavGoal.Column column) {
            region(goal, new BlockPos(column.x, currentY, column.z), 0, minY + 1, maxY - 1);
        } else if (goal instanceof NavGoal.YLevel level) {
            region(goal, BlockPos.containing(origin.x, level.level, origin.z), MAX_RADIUS, level.level, level.level);
            truncated = true; // A Y plane has no finite exhaustive neighborhood.
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
            near(goal, near.radius, true);
        } else {
            unknown = true; // Custom/moving/avoidance goals cannot become a guessed static center.
        }
    }

    private void near(NavGoal goal, double radius, boolean ground) {
        if (!Double.isFinite(radius) || radius < 0) { unknown = true; return; }
        int bounded = (int) Math.min(MAX_RADIUS, Math.ceil(radius));
        truncated |= radius > MAX_RADIUS;
        int vertical = ground ? 1 : bounded;
        region(goal, goal.center(), bounded, goal.center().getY() - vertical, goal.center().getY() + vertical);
    }

    private void region(NavGoal goal, BlockPos center, int radius, int low, int high) {
        low = Math.max(minY + 1, low);
        high = Math.min(maxY - 1, high);
        if (low <= high) pending.add(new Region(goal, center, radius, low, high));
    }

    /** Lazy box enumeration, with axis coordinates nearest the anchor visited first. */
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
