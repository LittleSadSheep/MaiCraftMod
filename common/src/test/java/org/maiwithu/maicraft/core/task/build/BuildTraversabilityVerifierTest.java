// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.util.List;
import java.util.function.Predicate;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.material.FluidState;

/** 用模拟世界检查分批验收：大房间、单步续查、隔墙、缺失区块和长码头；不启动游戏或让角色实走。 */
public final class BuildTraversabilityVerifierTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        TestWorld open = new TestWorld(false, 0);
        var large = contract(32, 0, 31, 1087, null);
        var result = scan(open, pos -> true, large, 8);
        check(result.valid(), "a volume above the former cap must complete across ticks");
        check(((Number) result.evidence().get("reachable_interior_cells")).intValue() == 1024,
                "every connected floor cell must be retained across slices");
        check(open.entryReads < 15, "yielding must not repeatedly scan the entrance prefix");

        TestWorld sliced = new TestWorld(false, 0);
        var singleCellSlices = scan(sliced, pos -> true, large, 1);
        check(singleCellSlices.evidence().equals(result.evidence()), "slice size must not change the verdict");

        var blocked = scan(new TestWorld(true, 0), pos -> true, large, 8);
        check(!blocked.valid() && blocked.code().equals("interior_floor_unreachable"),
                "a solid two-block partition must still disconnect the final waypoint");

        var missingChunk = scan(new TestWorld(false, 1),
                pos -> !(Math.floorDiv(pos.getX(), 16) == 1 && Math.floorDiv(pos.getZ(), 16) == 0),
                contract(16, 1, 16, 64, null), 1);
        check(!missingChunk.valid() && missingChunk.code().equals("interior_observation_unloaded"),
                "unaligned bounds must check every intersected chunk, not just the diagonal corners");

        var dock = new BuildTraversabilityContract.DockPath(cell(32, 64, 31), cell(2080, 64, 31));
        var withDock = scan(new TestWorld(false, 0), pos -> true, contract(32, 0, 31, 64, dock), 8);
        check(withDock.valid() && ((Number) withDock.evidence().get("dock_centerline_cells")).intValue() == 2049,
                "a long dock must resume its walking-line cursor across ticks");
        System.out.println("BuildTraversabilityVerifierTest: passed");
    }

    // 按给定预算反复推进真实验收器，检查每次读格次数有上限，并确认完成后的查询结果稳定。
    private static BuildTraversabilityVerifier.Result scan(TestWorld world, Predicate<BlockPos> loaded,
            BuildTraversabilityContract contract, int budget) {
        var scan = BuildTraversabilityVerifier.begin(world, loaded, contract);
        BuildTraversabilityVerifier.Result result = null;
        int slices = 0;
        while (result == null && slices++ < 100_000) {
            int before = world.reads;
            result = scan.tick(budget);
            check(world.reads - before <= Math.max(40, budget * 40),
                    "one slice performed an unbounded prefix, line, or flood scan");
        }
        check(result != null, "the finite scan must eventually settle");
        check(scan.tick(budget) == result, "completed polling must be stable");
        return result;
    }

    private static BuildTraversabilityContract contract(int maxX, int minZ, int maxZ, int maxY,
            BuildTraversabilityContract.DockPath dock) {
        return new BuildTraversabilityContract(cell(-1, 64, minZ), cell(0, 64, minZ), cell(1, 64, minZ),
                new BuildTraversabilityContract.Bounds(1, 64, minZ, maxX, maxY, maxZ),
                List.of(cell(maxX, 64, maxZ)), null, dock);
    }

    private static BuildTraversabilityContract.Cell cell(int x, int y, int z) {
        return new BuildTraversabilityContract.Cell(x, y, z);
    }

    // 场景有一整层石地板、木门和可选完整隔墙；没有覆盖铁门控制或头部半砖等碰撞细节。
    private static final class TestWorld implements BlockGetter {
        private final boolean partition;
        private final int entranceZ;
        private int reads, entryReads;

        private TestWorld(boolean partition, int entranceZ) {
            this.partition = partition;
            this.entranceZ = entranceZ;
        }

        @Override public BlockState getBlockState(BlockPos pos) {
            reads++;
            if (pos.equals(new BlockPos(1, 64, entranceZ))) entryReads++;
            if (pos.getY() == 63) return Blocks.STONE.defaultBlockState();
            if (pos.getX() == 0 && pos.getZ() == entranceZ && (pos.getY() == 64 || pos.getY() == 65)) {
                return Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.EAST)
                        .setValue(DoorBlock.HALF, pos.getY() == 64 ? DoubleBlockHalf.LOWER : DoubleBlockHalf.UPPER);
            }
            if (partition && pos.getX() == 16 && (pos.getY() == 64 || pos.getY() == 65)) {
                return Blocks.STONE.defaultBlockState();
            }
            return Blocks.AIR.defaultBlockState();
        }
        @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override public int getHeight() { return 384; }
        @Override public int getMinBuildHeight() { return -64; }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
