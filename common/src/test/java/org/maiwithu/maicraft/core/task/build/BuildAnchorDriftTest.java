// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.integration.physics.PhysicalObstacleSnapshot;

/** 半阶主方向虽可走，余速侧偏仍须有完整的原生碰撞与禁入检查。 */
public final class BuildAnchorDriftTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var h = new InteractionWorldTestHarness()) {
            var dimensions=Entity.class.getDeclaredField("dimensions");dimensions.setAccessible(true);
            dimensions.set(h.player,EntityDimensions.scalable(.6f,1.8f));
            h.set(new BlockPos(4,1,6),Blocks.STONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING,Direction.SOUTH));
            Vec3 from=new Vec3(4.5,1.5,6.19), target=new Vec3(4.69,2,6.5), drift=new Vec3(4.73,1.5,6.51);
            h.position(from);
            check(safe(h,from,target,drift),"the original half-step and its supported drift are geometrically valid");
            // 石块只碰余速的身体盒，主方向终点仍在墙前；不能只验证正常目标就放行这次移动。
            h.set(new BlockPos(5,2,6),Blocks.STONE.defaultBlockState());
            check(safe(h,from,target,from) && !safe(h,from,target,drift),
                    "a wall touched only by projected drift must still reject the half-step");
            // 同样是余速受阻，真实石墙必须报告实际碰撞格，不能只说站位不存在。
            var collision = BuildAnchorStepGeometry.check(h.player,h.level::isLoaded,LongSets.emptySet(),p->true,
                    PhysicalObstacleSnapshot.EMPTY,from,target,drift,h.player.getBbHeight());
            check(collision.rejection().equals("anchor_block_collision") && collision.cell().equals(new BlockPos(5,2,6)),
                    "微调回执保留碰撞方块位置");
            h.set(new BlockPos(5,2,6),Blocks.AIR.defaultBlockState());
            check(!BuildAnchorStepGeometry.safe(h.player,h.level::isLoaded,LongSets.singleton(new BlockPos(5,2,6).asLong()),
                            p->true,PhysicalObstacleSnapshot.EMPTY,from,target,drift),
                    "an empty but forbidden drift cell is protected exactly like a collision");
            var protection = BuildAnchorStepGeometry.check(h.player,h.level::isLoaded,LongSets.singleton(new BlockPos(5,2,6).asLong()),
                    p->true,PhysicalObstacleSnapshot.EMPTY,from,target,drift,h.player.getBbHeight());
            check(protection.rejection().equals("anchor_body_cell_forbidden"), "空保护格与实体石墙具有不同的恢复诊断");
            check(h.player.position().equals(from) && h.blockUses()==0 && h.itemUses()==0,
                    "these geometric checks neither move the body nor submit an action");
        }
        System.out.println("BuildAnchorDriftTest: passed");
    }
    private static boolean safe(InteractionWorldTestHarness h,Vec3 from,Vec3 target,Vec3 drift) {
        return BuildAnchorStepGeometry.safe(h.player,h.level::isLoaded,LongSets.emptySet(),p->true,PhysicalObstacleSnapshot.EMPTY,from,target,drift);
    }
    private static void check(boolean valid,String message) { if(!valid)throw new AssertionError(message); }
}
