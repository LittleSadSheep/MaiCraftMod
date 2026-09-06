package org.maiwithu.maicraft.core.task.mine;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import org.maiwithu.maicraft.core.task.base.NativePickupReceipt;
import sun.misc.Unsafe;

/** Real batch membership, natural-tree evidence, collection priority and vanilla pickup geometry. */
public final class MiningBatchTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        BlockPos root = new BlockPos(0, 64, 0);
        Set<BlockPos> known = Set.of(root, root.above(), root.above(2),
                root.above().east(), root.above().east(2), root.east(2));
        MiningBatch trunk = MiningBatch.connected(root, known, true, true);
        check(trunk.targets().equals(Set.of(root, root.above(), root.above(2))) && trunk.followTrunk(),
                "a trunk batch absorbed a touching roof or neighbouring tree");
        MiningBatch decoration = MiningBatch.connected(root, known, true, false);
        check(!decoration.followTrunk(), "a log structure without natural leaves authorized extra travel");
        MiningBatch vein = MiningBatch.connected(root, Set.of(root, root.east(), root.east(3)), false, false);
        check(vein.targets().equals(Set.of(root, root.east())) && !vein.followTrunk(),
                "an ore batch expanded across an unknown/disconnected cell");
        try { trunk.targets().clear(); throw new AssertionError("batch membership is mutable"); }
        catch (UnsupportedOperationException expected) { }

        TestWorld world = new TestWorld();
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
            if (x != 0 || z != 0) world.blocks.put(root.above(2).offset(x, 0, z),
                    Blocks.BIRCH_LEAVES.defaultBlockState().setValue(LeavesBlock.DISTANCE, 1));
        }
        check(MiningBatch.hasNaturalCrown(trunk.targets(), world, pos -> true),
                "a loaded natural crown did not authorize finishing the current upright trunk");
        world.blocks.replaceAll((pos, state) -> state.setValue(LeavesBlock.PERSISTENT, true));
        check(!MiningBatch.hasNaturalCrown(trunk.targets(), world, pos -> true),
                "player-placed persistent foliage became natural-tree evidence");
        world.blocks.replaceAll((pos, state) -> state.setValue(LeavesBlock.PERSISTENT, false));
        check(!MiningBatch.hasNaturalCrown(trunk.targets(), world, pos -> false),
                "an unloaded crown was treated as observed tree evidence");
        world.blocks.put(root.below(), Blocks.STONE.defaultBlockState());
        check(MiningBatch.safeDropLanding(root, world, pos -> true),
                "ordinary log drops falling onto the known tree-base floor interrupted the batch");
        world.blocks.put(root.below(), Blocks.LAVA.defaultBlockState());
        check(!MiningBatch.safeDropLanding(root, world, pos -> true), "lava below falling loot was ignored");
        world.blocks.clear();
        check(!MiningBatch.safeDropLanding(root, world, pos -> true), "an unproved cliff fall was treated as safe");

        check(!MiningBatch.shouldCollect(19, 24, 1, 20, false), "one safe drop interrupted the working batch");
        check(MiningBatch.shouldCollect(19, 24, 5, 20, false), "enough owned loose output caused needless extra mining");
        check(MiningBatch.shouldCollect(24, 24, 0, 0, false), "a satisfied quantity started another batch");
        check(MiningBatch.shouldCollect(0, 24, 1, 0, true), "a dangerous or expiring drop did not preempt batching");
        check(MiningBatch.shouldCollect(0, 24, 1, 1200, false), "loot was left behind indefinitely to extend a batch");
        pickupUsesTheRealTouchEnvelope();
        System.out.println("MiningBatchTest: passed");
    }

    private static void pickupUsesTheRealTouchEnvelope() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Unsafe memory = (Unsafe) field.get(null);
        LocalPlayer player = (LocalPlayer) memory.allocateInstance(LocalPlayer.class);
        ItemEntity item = (ItemEntity) memory.allocateInstance(ItemEntity.class);
        player.setBoundingBox(new AABB(-0.3, 64, -0.3, 0.3, 65.8, 0.3));
        item.setBoundingBox(new AABB(1.2, 64, 0.1, 1.45, 64.25, 0.35));
        check(NativePickupReceipt.insideVanillaTouchEnvelope(player, item),
                "an item whose box touches the vanilla envelope was made to require a walk");
        item.setBoundingBox(new AABB(0, 67, 0, 0.25, 67.25, 0.25));
        check(!NativePickupReceipt.insideVanillaTouchEnvelope(player, item),
                "same horizontal cell was mistaken for an actual pickup overlap");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class TestWorld implements BlockGetter {
        private final Map<BlockPos, BlockState> blocks = new HashMap<>();
        public BlockState getBlockState(BlockPos pos) { return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState()); }
        public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        public int getHeight() { return 384; }
        public int getMinBuildHeight() { return -64; }
    }
}
