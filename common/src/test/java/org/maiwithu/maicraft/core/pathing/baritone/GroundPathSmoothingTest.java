package org.maiwithu.maicraft.core.pathing.baritone;

import baritone.api.IBaritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.movement.IMovement;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.movements.MovementAscend;
import baritone.pathing.movement.movements.MovementDiagonal;
import baritone.pathing.movement.movements.MovementTraverse;
import baritone.utils.pathing.PathBase;
import it.unimi.dsi.fastutil.longs.LongSets;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.integration.physics.PhysicalObstacleSnapshot;

// 在简化地形上检查能否把折线路段改成直走；覆盖中途墙、缺地板、危险支撑、未知区域、移动结构和读取预算，不只比较起终点。
public final class GroundPathSmoothingTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        Scene scene = new Scene();
        GroundCorridor corridor = corridor(scene, PhysicalObstacleSnapshot.EMPTY);
        Vec3 from = new Vec3(.5, 0, .5), to = new Vec3(8.5, 0, 3.5);
        check(corridor.clear(from, to), "an open 8:3 bearing must be continuously walkable");
        scene.blocks.put(new BlockPos(4, 0, 2), Blocks.STONE.defaultBlockState());
        check(!corridor.clear(from, to), "a wall between valid endpoints must block the body sweep");
        scene.blocks.clear(); scene.blocks.put(new BlockPos(4, -1, 2), Blocks.AIR.defaultBlockState());
        scene.blocks.put(new BlockPos(4, -1, 1), Blocks.AIR.defaultBlockState());
        check(!corridor.clear(from, to), "a full-width floor gap cannot be skipped by line-of-sight");
        scene.blocks.clear(); scene.blocks.put(new BlockPos(4, -1, 2), Blocks.MAGMA_BLOCK.defaultBlockState());
        check(!corridor.clear(from, to), "support hazards remain hazardous when body space is air");
        scene.blocks.clear(); scene.blocks.put(new BlockPos(4, 1, 2), Blocks.OAK_FENCE.defaultBlockState());
        check(!corridor.clear(from, to), "head and partial collision shapes must be tested");
        scene.blocks.clear();
        check(!new GroundCorridor(scene, p -> p.getX() != 4, .6, 1.8, LongSets.emptySet(), PhysicalObstacleSnapshot.EMPTY)
                .clear(from, to), "unknown middle chunks cannot become a shortcut");
        var forbidden = new it.unimi.dsi.fastutil.longs.LongOpenHashSet(); forbidden.add(new BlockPos(4, 0, 2).asLong());
        check(!new GroundCorridor(scene, p -> true, .6, 1.8, forbidden, PhysicalObstacleSnapshot.EMPTY).clear(from, to),
                "live protected body cells apply between endpoints");
        var dynamic = new PhysicalObstacleSnapshot(List.of(new AABB(4, 0, 0, 5, 2, 5)), 1, 0, "observed");
        check(!corridor(scene, dynamic).clear(from, to) && corridor.clear(from, to), "moving structures invalidate and release the corridor");
        check(!corridor.clear(from, to.add(0, .5, 0)), "height transitions remain with their original movement");

        IPlayerContext context = (IPlayerContext) Proxy.newProxyInstance(IPlayerContext.class.getClassLoader(),
                new Class<?>[]{IPlayerContext.class}, (proxy, method, values) -> null);
        IBaritone baritone = (IBaritone) Proxy.newProxyInstance(IBaritone.class.getClassLoader(), new Class<?>[]{IBaritone.class},
                (proxy, method, values) -> { if (method.getName().equals("getPlayerContext")) return context; throw new AssertionError(method); });
        List<IMovement> steps = new ArrayList<>(); BetterBlockPos cell = new BetterBlockPos(0, 0, 0);
        for (int x = 1; x <= 8; x++) {
            BetterBlockPos next = new BetterBlockPos(x, 0, Math.max(0, x - 5));
            Movement move = next.z == cell.z ? new MovementTraverse(baritone, cell, next)
                    : new MovementDiagonal(baritone, cell, net.minecraft.core.Direction.EAST, net.minecraft.core.Direction.SOUTH, 0);
            move.override(5); steps.add(move); cell = next;
        }
        // 调用真实直线化入口后，检查起终点、时间预算和途中有效位置仍与原路线一致。
        IPath raw = path(steps), smooth = GroundPathSmoothing.smooth(baritone, raw, corridor, BlockPos.ZERO);
        check(smooth.length() == 2 && smooth.movements().getFirst() instanceof MovementGroundStraight,
                "cardinal-then-diagonal route should become one actual movement");
        smooth.sanityCheck();
        check(smooth.getSrc().equals(raw.getSrc()) && smooth.getDest().equals(raw.getDest())
                && smooth.ticksRemainingFrom(0) == raw.ticksRemainingFrom(0), "lowering must preserve endpoints and its timeout budget");
        MovementGroundStraight straight = (MovementGroundStraight) smooth.movements().getFirst();
        for (int i = 0; i <= 40; i++) {
            BetterBlockPos foot = new BetterBlockPos(BlockPos.containing(from.lerp(to, i / 40.0)));
            check(straight.getValidPositions().contains(foot), "long-line recovery cannot classify intermediate feet as off path");
        }
        scene.blocks.put(new BlockPos(4, 0, 2), Blocks.STONE.defaultBlockState());
        check(GroundPathSmoothing.smooth(baritone, raw, corridor, BlockPos.ZERO).length() > 2,
                "a route around a new wall cannot be compressed through that wall");
        scene.blocks.clear();
        var ascend = new MovementAscend(baritone, cell, cell.east().above()); ascend.override(9); steps.add(ascend);
        IPath toStep = GroundPathSmoothing.smooth(baritone, path(steps), corridor, BlockPos.ZERO);
        check(toStep.movements().getLast() == ascend && toStep.movements().get(toStep.movements().size() - 2) == steps.get(7),
                "keep the step and its last approach movement for existing jump/interaction timing");
        IPath anchored = GroundPathSmoothing.smooth(baritone, raw, corridor, new BlockPos(3, 0, 0));
        check(anchored.positions().contains(new BlockPos(3, 0, 0)), "path installation cannot erase the live rejoin anchor");
        steps.clear(); cell = new BetterBlockPos(0, 0, 0);
        for (int x = 1; x <= 32; x++) {
            BetterBlockPos next = new BetterBlockPos(x, 0, Math.max(0, x - 15));
            Movement move = next.z == cell.z ? new MovementTraverse(baritone, cell, next)
                    : new MovementDiagonal(baritone, cell, net.minecraft.core.Direction.EAST, net.minecraft.core.Direction.SOUTH, 0);
            move.override(5); steps.add(move); cell = next;
        }
        IPath longDirect = GroundPathSmoothing.smooth(baritone, path(steps), corridor, BlockPos.ZERO);
        check(longDirect.length() == 2 && longDirect.getDest().equals(new BlockPos(32, 0, 17)),
                "a 32:17 goal must be direct from the first step, without retaining a 12-block cardinal prefix");
        steps.clear(); cell = new BetterBlockPos(0, 0, 0);
        for (int x = 1; x <= 8; x++) {
            BetterBlockPos next = new BetterBlockPos(x, 0, 0);
            var move = new MovementTraverse(baritone, cell, next); move.override(5); steps.add(move); cell = next;
        }
        scene.blocks.put(new BlockPos(1, 0, 1), Blocks.STONE.defaultBlockState());
        IPath offset = GroundPathSmoothing.smooth(baritone, path(steps), corridor(scene, PhysicalObstacleSnapshot.EMPTY),
                BlockPos.ZERO, new Vec3(.5, 0, .95));
        check(!(offset.movements().getFirst() instanceof MovementGroundStraight),
                "a legal offset start cannot be replaced by a clear cell center when its actual shortcut clips the adjacent wall");
        scene.blocks.clear();
        // 耗尽地形读取额度时应保留原路线，而不是把没有完成检查当作无法到达。
        var limited = corridor(scene, PhysicalObstacleSnapshot.EMPTY); scene.reads = 0;
        for (int i = 0; i < 100 && !limited.exhausted(); i++) limited.clear(from, new Vec3(120.5, 0, .5));
        check(limited.exhausted() && scene.reads <= 16384, "all lowering probes share a finite native block-read budget");
        check(GroundPathSmoothing.smooth(baritone, raw, limited, BlockPos.ZERO) == raw,
                "observation exhaustion keeps the executable original path instead of claiming it unreachable");
        System.out.println("GroundPathSmoothingTest: passed");
    }

    private static GroundCorridor corridor(Scene world, PhysicalObstacleSnapshot physical) {
        return new GroundCorridor(world, p -> true, .6, 1.8, LongSets.emptySet(), physical);
    }
    private static IPath path(List<IMovement> moves) {
        List<IMovement> copy = List.copyOf(moves); var positions = new ArrayList<BetterBlockPos>(); positions.add(copy.getFirst().getSrc());
        copy.forEach(m -> positions.add(m.getDest()));
        return new PathBase() {
            public List<IMovement> movements() { return copy; }
            public List<BetterBlockPos> positions() { return positions; }
            public Goal getGoal() { return null; }
            public int getNumNodesConsidered() { return 100; }
        };
    }
    private static void check(boolean test, String detail) { if (!test) throw new AssertionError(detail); }
    static final class Scene implements BlockGetter {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();
        int reads;
        public BlockState getBlockState(BlockPos pos) { reads++; return blocks.getOrDefault(pos, (pos.getY() == -1 ? Blocks.STONE : Blocks.AIR).defaultBlockState()); }
        public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        public int getHeight() { return 384; }
        public int getMinBuildHeight() { return -64; }
    }
}
