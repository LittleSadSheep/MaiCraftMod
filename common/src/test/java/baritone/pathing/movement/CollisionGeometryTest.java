package baritone.pathing.movement;

import baritone.utils.BlockStateInterface;
import java.util.HashMap;
import java.util.IdentityHashMap;
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
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import sun.misc.Unsafe;

/** Actual collision queries, including the dimensions read from Create Addition 1.6.0. */
public final class CollisionGeometryTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        // Bootstrap has frozen registration. Fixtures need private holders, not global registration.
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
        var memoryField = Unsafe.class.getDeclaredField("theUnsafe"); memoryField.setAccessible(true);
        var memory = (Unsafe) memoryField.get(null);
        var bsi = (BlockStateInterface) memory.allocateInstance(BlockStateInterface.class);
        var access = BlockStateInterface.class.getDeclaredField("access"); access.setAccessible(true); access.set(bsi, scene);
        // Alternator UP/DOWN: two stacked non-full shapes, entirely inside their own cells.
        var alternator = new Shaped(Shapes.or(Block.box(0, 3, 0, 16, 13, 16), Block.box(2, 2, 2, 14, 14, 14)));
        // Large connector facing EAST: the UP shape (5,0,5)-(11,7,11) rotated toward WEST.
        var connector = new Shaped(Block.box(9, 5, 5, 16, 11, 11));
        for (var machine : new Shaped[]{alternator, connector}) {
            for (int y = 0; y <= 1; y++) {
                BlockPos pos = new BlockPos(1, y, 0);
                scene.blocks.put(pos, machine.defaultBlockState());
                check(machine.defaultBlockState().isPathfindable(PathComputationType.LAND), "fixture preserves misleading pathfindable=true");
                check(!MovementHelper.canWalkThroughPosition(bsi, 1, y, 0, machine.defaultBlockState()), "partial machine was treated as air");
                check(!MovementHelper.fullyPassablePosition(bsi, 1, y, 0, machine.defaultBlockState()), "parkour admitted a partial machine");
            }
        }
        scene.blocks.clear();
        check(new CollisionGeometry(scene, false, null, null).clear(0, 0, 0, 1, 0, 0), "ordinary floor");
        check(scene.reads < 250, "flat movement repeated a large neighborhood scan");
        scene.blocks.put(new BlockPos(1, -1, 0), Blocks.STONE_SLAB.defaultBlockState());
        check(CollisionGeometry.supportHeight(scene, new BlockPos(1, -1, 0)) == 0.5, "bottom slab support height");
        check(new CollisionGeometry(scene, false, null, null).clear(0, 0, 0, 1, 0, 0), "ordinary slab route");
        scene.blocks.put(new BlockPos(1, 0, 0), Blocks.STONE_STAIRS.defaultBlockState());
        check(MovementHelper.canWalkOnBlockState(Blocks.STONE_STAIRS.defaultBlockState()) == baritone.pathing.precompute.Ternary.YES,
                "ordinary stairs lost their support behavior");
        check(new CollisionGeometry(scene, false, null, null).clear(0, 0, 0, 1, 1, 0), "ordinary stair ascent");
        scene.blocks.clear();
        scene.blocks.put(BlockPos.ZERO, Blocks.OAK_FENCE.defaultBlockState());
        check(CollisionGeometry.supportHeight(scene, BlockPos.ZERO) == 1.5, "fence collision was flattened to one block");
        check(!MovementHelper.isBlockNormalCube(scene, BlockPos.ZERO, Blocks.OAK_FENCE.defaultBlockState()), "fence was accepted as a full support cube");
        scene.blocks.clear();
        // A neighboring block protrudes over the path although every route cell itself is air.
        scene.blocks.put(new BlockPos(1, 0, 1), new Shaped(Block.box(0, 0, -12, 16, 24, 4)).defaultBlockState());
        check(!new CollisionGeometry(scene, false, null, null).clear(0, 0, 0, 1, 0, 0), "neighbor protrusion did not block the body sweep");
        check(new CollisionGeometry(scene, false, null, null).clear(0, 0, -1, 1, 0, -1), "an unobstructed detour was rejected");
        scene.blocks.clear();
        scene.blocks.put(new BlockPos(-1, 0, 0), new Shaped(Block.box(0, 0, 0, 22, 32, 16)).defaultBlockState());
        check(!new CollisionGeometry(scene, false, null, null).clear(0, 0, 0, 1, 0, 0), "centered body should overlap the protrusion");
        check(new CollisionGeometry(scene, false, new Vec3(0.8, 0, 0.5), BlockPos.ZERO).clear(0, 0, 0, 1, 0, 0),
                "a legal actual starting position was replaced with a colliding cell center");
        var live = new CollisionGeometry(scene, false, null, null);
        check(!live.clear(0, 0, 0, 1, 0, 0), "live obstruction");
        scene.blocks.clear();
        check(live.clear(0, 0, 0, 1, 0, 0), "live geometry retained stale cached collision");
        var contextual = new Shaped(Shapes.block()) {
            public VoxelShape getCollisionShape(BlockState state, BlockGetter view, BlockPos pos, CollisionContext context) {
                if (view == null || pos == null) throw new AssertionError("null geometry context");
                return pos.getX() == 0 ? Shapes.block() : Block.box(0, 0, 0, 16, 12, 16);
            }
        };
        check(MovementHelper.isBlockNormalCube(scene, BlockPos.ZERO, contextual.defaultBlockState()), "contextual full support");
        check(!MovementHelper.isBlockNormalCube(scene, BlockPos.ZERO.east(), contextual.defaultBlockState()), "support cached only by state");
        System.out.println("CollisionGeometryTest: passed");
        } finally {
            holders.set(BuiltInRegistries.BLOCK, priorHolders);
            frozen.setBoolean(BuiltInRegistries.BLOCK, priorFrozen);
        }
    }

    private static class Shaped extends Block {
        private final VoxelShape collision;
        Shaped(VoxelShape shape) { super(Properties.of().dynamicShape()); collision = shape; }
        public VoxelShape getCollisionShape(BlockState state, BlockGetter view, BlockPos pos, CollisionContext context) { return collision; }
        public boolean isPathfindable(BlockState state, PathComputationType type) { return true; }
    }
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
    private static final class Scene implements BlockGetter {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();
        int reads;
        public BlockState getBlockState(BlockPos pos) {
            reads++;
            return blocks.getOrDefault(pos, (pos.getY() == -1 ? Blocks.STONE : Blocks.AIR).defaultBlockState());
        }
        public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        public BlockEntity getBlockEntity(BlockPos pos) { throw new AssertionError("navigation must not read live block entities"); }
        public int getHeight() { return 384; }
        public int getMinBuildHeight() { return -64; }
    }
}
