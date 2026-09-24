// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create.transmission;

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
import net.minecraft.world.phys.Vec3;

/** 用真实方块碰撞射线检查作业层、隔墙和未知区块，避免后端把不可见的地下来源当成附近出口。 */
public final class KineticSourceScopeTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        World world = new World(); Vec3 eye = new Vec3(.5, 2.6, .5); BlockPos outlet = new BlockPos(6, 1, 0);
        world.blocks.put(outlet, Blocks.STONE.defaultBlockState());
        // 纯查询可以包含角色脚下的轮子，真正接线仍禁止把接收端本身伪装成外部来源。
        var query = new KineticSourceDiscovery(outlet, 16, 0, eye, 1, state -> true, false);
        var connection = new KineticSourceDiscovery(outlet, 16, 0, eye, 1, state -> true);
        check(query.acceptsPosition(outlet) && !connection.acceptsPosition(outlet), "区分观察中心与接线目标");
        check(query.acceptsPosition(outlet.offset(16, 4, 0)) && !query.acceptsPosition(outlet.below(5)), "水平半径不会扩大楼层范围");
        check(KineticSourceScope.visible(world, pos -> true, eye, 1, outlet), "同层可见出口可以成为候选");
        for (int y = 0; y <= 4; y++) world.blocks.put(new BlockPos(3, y, 0), Blocks.STONE.defaultBlockState());
        check(!KineticSourceScope.visible(world, pos -> true, eye, 1, outlet), "隔墙的动力不能因直线距离近而被选中");
        world.blocks.keySet().removeIf(pos -> pos.getX() == 3);
        check(!KineticSourceScope.visible(world, pos -> pos.getX() != 3, eye, 1, outlet), "未加载的途中区块不能当作空气透视");
        for (int y : new int[]{-60, 60}) {
            BlockPos remoteFloor = new BlockPos(6, y, 0); world.blocks.put(remoteFloor, Blocks.STONE.defaultBlockState());
            check(!KineticSourceScope.visible(world, pos -> true, eye, 1, remoteFloor), "不会沿地井或高空跨楼层搜索其他网络");
        }
        System.out.println("KineticSourceScopeTest: passed");
    }
    private static final class World implements BlockGetter {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();
        public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        public BlockState getBlockState(BlockPos pos) { return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState()); }
        public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        public int getHeight() { return 384; }
        public int getMinBuildHeight() { return -64; }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
