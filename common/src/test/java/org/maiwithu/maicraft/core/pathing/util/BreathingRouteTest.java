package org.maiwithu.maicraft.core.pathing.util;

import it.unimi.dsi.fastutil.longs.LongSets;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

/** 低氧逃生应沿水下转弯抵达开口，不能穿水墙、含水半砖，或把未知区块当作空气出口。 */
public final class BreathingRouteTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var scene = new Pool(); Vec3 start = new Vec3(2.5, 1, 2.5);
        var view = BreathingRoute.geometry(scene, p -> true, LongSets.emptySet(), .6, 1.8, 1.62);
        check(BreathingRoute.ascent(view, start) == null, "sealed ceiling refuses blind upward movement");
        var route = solve(view, start);
        check(route != null && route.reachesAir() && route.points().stream().anyMatch(p -> p.z > 7), "escape retains the detour around the submerged wall");
        check(view.breathable(route.points().getLast()) && route.requiredAir(1) > route.ticks(), "route proves breathable arrival and budgets turning/ascent time with reserve");
        for (int i = 1; i < route.points().size(); i++) check(view.clear(route.points().get(i - 1), route.points().get(i)), "all route edges fit the full body");
        Vec3 next = route.points().get(1); scene.changes.put(BlockPos.containing(next), Blocks.STONE.defaultBlockState());
        check(!view.clear(start, next), "changed obstruction invalidates the pending edge before new input");
        scene.changes.clear(); scene.waterloggedRoof = true;
        check(BreathingRoute.ascent(view, start) == null, "waterlogged slab remains a solid ceiling");
        check(solve(view, start) != null, "the real opening remains reachable beside a waterlogged roof");
        var unknown = BreathingRoute.geometry(scene, p -> p.getX() < 7, LongSets.emptySet(), .6, 1.8, 1.62);
        check(solve(unknown, start) == null, "an unloaded opening is not a proved escape route");
        scene.sealed = true;
        check(solve(view, start) == null, "fully sealed water remains an explicit bounded search failure");
        scene.sealed = false;
        Vec3 belowOpening = new Vec3(9.5, 1, 3.5);
        var ascent = BreathingRoute.ascent(view, belowOpening);
        check(ascent != null && ascent.reachesAir() && ascent.points().size() == 2, "a verified open column permits immediate upward movement");
        System.out.println("BreathingRouteTest: passed");
    }
    private static BreathingRoute.Route solve(BreathingRoute.View view, Vec3 start) {
        var search = new BreathingRoute.Search(start); int slices = 0;
        while (!search.done() && slices++ < 5000) search.advance(view, 64);
        check(search.done() && search.expanded() <= 4096, "escape search yields and remains bounded");
        return search.result();
    }
    // 池顶只有右侧小开口；隔墙逼角色先往南绕行，再返回开口，直指开口必然撞墙。
    private static final class Pool implements BlockGetter {
        final Map<BlockPos, BlockState> changes = new HashMap<>();
        boolean waterloggedRoof, sealed;
        public BlockState getBlockState(BlockPos p) {
            if (changes.containsKey(p)) return changes.get(p);
            if (p.getX() < 0 || p.getX() > 12 || p.getZ() < 0 || p.getZ() > 12 || p.getY() == 0) return Blocks.STONE.defaultBlockState();
            if (p.getX() == 5 && p.getZ() <= 7 && p.getY() < 5) return Blocks.STONE.defaultBlockState();
            if (p.getY() == 5 && (sealed || p.getX() < 8 || p.getX() > 10 || p.getZ() < 2 || p.getZ() > 4))
                return waterloggedRoof ? Blocks.STONE_SLAB.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true) : Blocks.STONE.defaultBlockState();
            return (p.getY() > 0 && p.getY() < 5 ? Blocks.WATER : Blocks.AIR).defaultBlockState();
        }
        public FluidState getFluidState(BlockPos p) { return getBlockState(p).getFluidState(); }
        public BlockEntity getBlockEntity(BlockPos p) { return null; }
        public int getHeight() { return 32; }
        public int getMinBuildHeight() { return 0; }
    }
    private static void check(boolean value, String detail) { if (!value) throw new AssertionError(detail); }
}
