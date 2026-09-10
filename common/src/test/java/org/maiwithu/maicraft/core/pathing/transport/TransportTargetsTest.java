package org.maiwithu.maicraft.core.pathing.transport;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.maiwithu.maicraft.core.pathing.calc.NavGoal;
import org.maiwithu.maicraft.core.pathing.goal.GoalCompiler;

/**
 * 从不同导航目标中枚举可站的交通落点，检查高度容差、完整身体碰撞、邻格伸入的形状、未知目标和分次搜索预算。
 */
public final class TransportTargetsTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        exactAndComposite();
        interactionGoals();
        boundedAndUnknownGoals();
        landingGeometry();
        protrudingShapes();
        System.out.println("TransportTargetsTest: passed");
    }

    private static void exactAndComposite() {
        Scene scene = new Scene();
        var exact = scan(scene, NavGoal.exact(BlockPos.ZERO));
        check(exact.destinations().size() == 1 && exact.destinations().getFirst().landingPoint().y == 0,
                "exact empty feet has a real support endpoint");
        check(scan(scene, NavGoal.exact(new BlockPos(0, 2, 0))).destinations().isEmpty(),
                "an exact target above the real floor has no static landing");
        var vicinity = scan(scene, NavGoal.nearGround(new BlockPos(0, 2, 0), 3, 2));
        check(!vicinity.destinations().isEmpty() && vicinity.destinations().stream().allMatch(d -> d.feet().getY() == 0),
                "approximate height must contribute the actual supported layer to flight candidates");
        check(scan(scene, NavGoal.nearGround(new BlockPos(0, 3, 0), 3, 2)).destinations().isEmpty(),
                "candidate enumeration must enforce the requested height tolerance");
        check(!NavGoal.nearGround(BlockPos.ZERO, 3, 1).semanticFingerprint().equals(
                NavGoal.nearGround(BlockPos.ZERO, 3, 2).semanticFingerprint()), "height tolerance changes must invalidate live route intent");
        scene.blocks.put(BlockPos.ZERO, Blocks.CRAFTING_TABLE.defaultBlockState());
        check(scan(scene, NavGoal.exact(BlockPos.ZERO)).destinations().isEmpty(),
                "an occupied exact goal must never become its block center or an invented nearby landing");
        NavGoal composite = NavGoal.composite(List.of(NavGoal.exact(BlockPos.ZERO),
                NavGoal.exact(new BlockPos(8, 0, 0)), NavGoal.exact(new BlockPos(2, 0, 0))));
        var choices = scan(scene, composite).destinations();
        check(choices.size() == 2 && choices.get(0).feet().getX() == 2 && choices.get(1).feet().getX() == 8,
                "every composite member contributes and results are sorted by physical approach distance");
        LongSet forbidden = new LongOpenHashSet();
        forbidden.add(new BlockPos(2, 1, 0).asLong());
        var snapshot = targets(composite, forbidden);
        forbidden.clear();
        finish(scene, snapshot);
        check(snapshot.destinations().size() == 1 && snapshot.destinations().getFirst().feet().getX() == 8,
                "forbidden body/head cells are frozen and cannot be bypassed by changing the caller's set");
    }

    private static void interactionGoals() {
        Scene scene = new Scene();
        scene.blocks.put(BlockPos.ZERO, Blocks.CRAFTING_TABLE.defaultBlockState());
        for (NavGoal goal : List.of(NavGoal.getToBlock(BlockPos.ZERO), NavGoal.adjacent(BlockPos.ZERO),
                NavGoal.nearGround(BlockPos.ZERO, 2), NavGoal.mineStance(BlockPos.ZERO))) {
            var targets = scan(scene, goal);
            check(!targets.destinations().isEmpty(), "interaction has at least one real nearby stance");
            for (var destination : targets.destinations()) {
                check(goal.isAt(destination.feet()) && !destination.feet().equals(BlockPos.ZERO),
                        "enumerated interaction endpoints obey the real goal and avoid its occupied body cell");
            }
        }
        var mining = scan(scene, NavGoal.mineColumn(new BlockPos(2, 4, 0), 4));
        check(mining.destinations().size() == 1 && mining.destinations().getFirst().feet().equals(new BlockPos(2, 0, 0)),
                "mining column scans its whole permitted stance band for real support");
        var column = scan(scene, NavGoal.column(2, 0));
        check(column.destinations().size() == 1 && column.destinations().getFirst().feet().getY() == 0,
                "column searches actual height rather than representative center Y");
        var level = scan(scene, NavGoal.yLevel(0));
        check(!level.destinations().isEmpty() && level.truncated(), "finite Y-plane sampling declares its limit");
    }

    private static void boundedAndUnknownGoals() {
        Scene scene = new Scene();
        var huge = targets(NavGoal.near(BlockPos.ZERO, 1000), LongSets.emptySet());
        int before = huge.examinedCandidates();
        huge.tick(scene, pos -> true);
        check(huge.examinedCandidates() - before <= 128, "one tick respects the candidate ceiling");
        finish(scene, huge);
        check(huge.truncated() && huge.destinations().size() <= 32 && huge.examinedCandidates() <= 8192,
                "large goal regions expose bounded results instead of scanning the whole volume");
        var unknown = scan(scene, new NavGoal() {
            public boolean isAt(BlockPos feet) { return true; }
            public double heuristic(BlockPos from) { return 0; }
            public BlockPos center() { return BlockPos.ZERO; }
        });
        check(unknown.complete() && unknown.hasUnknownEvidence() && unknown.destinations().isEmpty(),
                "custom goals are explicitly unknown, never guessed from center");
        var unloaded = targets(NavGoal.exact(BlockPos.ZERO), LongSets.emptySet());
        unloaded.tick(scene, pos -> pos.getX() != 1);
        check(unloaded.complete() && unloaded.hasUnloadedEvidence() && unloaded.destinations().isEmpty(),
                "unloaded adjacent collision owners make the landing unknown");
    }

    private static void landingGeometry() {
        Scene scene = new Scene();
        BlockPos floor = BlockPos.ZERO.below();
        scene.blocks.put(floor, Blocks.STONE_SLAB.defaultBlockState());
        check(probe(scene).destination().landingPoint().y == -0.5,
                "bottom-slab floor height remains fractional while logical feet stays above it");
        scene.blocks.put(BlockPos.ZERO.above(), Blocks.STONE.defaultBlockState());
        check(probe(scene).destination() == null, "head clearance uses the whole 1.8-high standing body");
        scene.blocks.clear();
        for (Block block : new Block[]{Blocks.WATER, Blocks.LAVA, Blocks.FIRE, Blocks.POWDER_SNOW, Blocks.SWEET_BERRY_BUSH}) {
            scene.blocks.put(BlockPos.ZERO, block.defaultBlockState());
            check(probe(scene).destination() == null, "hazardous or fluid-filled feet cannot be a landing");
        }
        scene.blocks.clear();
        scene.blocks.put(floor, Blocks.MAGMA_BLOCK.defaultBlockState());
        check(probe(scene).destination() == null, "damaging support is rejected despite its full collision cube");
        scene.blocks.put(floor, Blocks.AIR.defaultBlockState());
        check(probe(scene).destination() == null, "air is not a static landing floor");
    }

    // 临时加入碰撞能伸到邻格的测试方块，防止只查脚下与头顶中心格而漏掉侧面障碍；随后恢复注册表状态。
    private static void protrudingShapes() throws Exception {
        var holders = MappedRegistry.class.getDeclaredField("unregisteredIntrusiveHolders");
        var frozen = MappedRegistry.class.getDeclaredField("frozen");
        holders.setAccessible(true);
        frozen.setAccessible(true);
        Object priorHolders = holders.get(BuiltInRegistries.BLOCK);
        boolean priorFrozen = frozen.getBoolean(BuiltInRegistries.BLOCK);
        holders.set(BuiltInRegistries.BLOCK, new IdentityHashMap<>());
        frozen.setBoolean(BuiltInRegistries.BLOCK, false);
        try {
            Scene scene = new Scene();
            scene.blocks.put(new BlockPos(1, 0, 0), new Shaped(Block.box(-8, 0, 0, 4, 24, 16)).defaultBlockState());
            check(probe(scene).destination() == null, "neighbor protrusion blocks an otherwise empty feet/head column");
            scene.blocks.clear();
            scene.blocks.put(new BlockPos(0, 2, 0), new Shaped(Block.box(0, -8, 0, 16, 4, 16)).defaultBlockState());
            check(probe(scene).destination() == null, "collision owned above the nominal head cell is checked");
            scene.blocks.clear();
            scene.blocks.put(BlockPos.ZERO, new Shaped(Block.box(0, 2, 0, 16, 12, 16)).defaultBlockState());
            check(probe(scene).destination() == null, "pathfindable partial machines remain solid body obstacles");
            scene.blocks.clear();
            scene.blocks.put(new BlockPos(1, 0, 0), new Shaped(Block.box(-4, 0, 0, 4, 24, 16)).defaultBlockState());
            check(probe(scene).destination() == null, "full player width detects an edge collision away from center");
        } finally {
            holders.set(BuiltInRegistries.BLOCK, priorHolders);
            frozen.setBoolean(BuiltInRegistries.BLOCK, priorFrozen);
        }
    }

    private static TransportLanding.Probe probe(Scene scene) {
        return TransportLanding.inspect(scene, pos -> true, BlockPos.ZERO, 0.6, 1.8, LongSets.emptySet());
    }
    private static TransportTargets targets(NavGoal goal, LongSet forbidden) {
        return new TransportTargets(new GoalCompiler.Compiled(goal, LongSets.emptySet()), new Vec3(0.5, 0, 0.5),
                0.6, 1.8, -64, 320, forbidden);
    }
    private static TransportTargets scan(Scene scene, NavGoal goal) {
        var targets = targets(goal, LongSets.emptySet());
        finish(scene, targets);
        return targets;
    }
    private static void finish(Scene scene, TransportTargets targets) {
        int ticks = 0;
        while (!targets.tick(scene, pos -> true)) check(++ticks <= 8192, "bounded discovery terminates");
    }
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
    private static final class Scene implements BlockGetter {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();
        public BlockState getBlockState(BlockPos pos) {
            return blocks.getOrDefault(pos, (pos.getY() == -1 ? Blocks.STONE : Blocks.AIR).defaultBlockState());
        }
        public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        public BlockEntity getBlockEntity(BlockPos pos) { throw new AssertionError("static landing does not read block entities"); }
        public int getHeight() { return 384; }
        public int getMinBuildHeight() { return -64; }
    }
    private static final class Shaped extends Block {
        private final VoxelShape collision;
        Shaped(VoxelShape shape) { super(Properties.of().dynamicShape()); collision = shape; }
        public VoxelShape getCollisionShape(BlockState state, BlockGetter view, BlockPos pos, CollisionContext context) { return collision; }
        public boolean isPathfindable(BlockState state, PathComputationType type) { return true; }
    }
}
