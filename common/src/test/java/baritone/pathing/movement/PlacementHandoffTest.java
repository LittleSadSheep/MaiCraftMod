package baritone.pathing.movement;

import java.util.HashMap;
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

/** 回放轮座旁停稳、跨格落脚、边缘潜行和半砖站位；只验证真实碰撞支撑，不伪造角色走路。 */
public final class PlacementHandoffTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var scene = new Scene();
        scene.blocks.put(new BlockPos(498, 77, 115), Blocks.SMOOTH_STONE.defaultBlockState());
        scene.blocks.put(new BlockPos(499, 77, 115), Blocks.SMOOTH_STONE.defaultBlockState());
        // 用户现场身体横跨两个地面格；完整托住时可以停止等待垫块并重新规划。
        var body = new AABB(498.56, 78, 115.32, 499.16, 79.5, 115.92);
        check(PlacementHandoff.supported(scene, body, pos -> true), "two ground cells support the whole body");
        scene.blocks.remove(new BlockPos(499, 77, 115));
        check(!PlacementHandoff.supported(scene, body, pos -> true), "edge crouching cannot release its route");
        check(!PlacementHandoff.supported(scene, body, pos -> pos.getX() != 499), "unknown support is not safe ground");
        // 半砖只在真实的半格顶面承重，悬在它上方不能冒充已经落地。
        scene.blocks.put(new BlockPos(499, 77, 115), Blocks.SMOOTH_STONE.defaultBlockState());
        scene.blocks.put(new BlockPos(498, 77, 115), Blocks.STONE_SLAB.defaultBlockState());
        check(!PlacementHandoff.supported(scene, body, pos -> true), "a lower slab does not support y=78");
        var slabBody = new AABB(498.2, 77.5, 115.2, 498.8, 79.3, 115.8);
        check(PlacementHandoff.supported(scene, slabBody, pos -> true), "the actual slab top supports a stopped body");
        scene.blocks.put(new BlockPos(498, 77, 115), Blocks.WATER.defaultBlockState());
        check(!PlacementHandoff.supported(scene, slabBody, pos -> true), "water is not grounded handoff support");
        System.out.println("PlacementHandoffTest: passed");
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static final class Scene implements BlockGetter {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();
        public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        public BlockState getBlockState(BlockPos pos) { return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState()); }
        public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        public int getHeight() { return 256; }
        public int getMinBuildHeight() { return 0; }
    }
}
