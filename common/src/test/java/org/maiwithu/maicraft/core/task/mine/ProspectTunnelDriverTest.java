// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.mine;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.integration.ultimine.UltimineBreak;

/** 使用真实视线和通道地形选择下一次原生动作；完成条件取决于剩余断面，不取决于点击次数。 */
public final class ProspectTunnelDriverTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        for (boolean descending : new boolean[]{false, true}) {
            for (int opened = 0; opened <= (descending ? 3 : 2); opened++) scenario(descending, opened);
        }
        System.out.println("ProspectTunnelDriverTest: live headroom and native hit faces passed");
    }
    private static void scenario(boolean descending, int opened) throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            BlockPos feet = new BlockPos(8, 8, 8);
            h.position(Vec3.atBottomCenterOf(feet)); h.player.setYRot(0);
            var geometry = new ProspectTunnelPlan(feet, Direction.SOUTH, descending ? 5 : 8, 1.8, 5);
            // 在前方放入实心断面与实底，已打通的排数只改变地形；驱动器没有收到任何“挖了几次”的输入。
            for (int step = 1; step <= geometry.length(); step++) {
                h.set(geometry.foot(step).below(), Blocks.STONE.defaultBlockState());
                for (BlockPos at : geometry.clearance(step)) h.set(at, Blocks.STONE.defaultBlockState());
            }
            for (int row = 0; row < opened; row++)
                for (int step = 1; step <= geometry.length(); step++) h.set(geometry.clearance(step).get(row), Blocks.AIR.defaultBlockState());
            // 最后一个未清位置改成另一种矿物，模拟 FTB 的材料匹配在那里截断。
            if (opened < geometry.clearance(1).size()) h.set(geometry.clearance(1).get(opened), Blocks.IRON_ORE.defaultBlockState());
            var driver = new ProspectTunnelDriver(h.player, descending ? 5 : 8);
            try {
                check(driver.tick(128), "a loaded tunnel frontier remains actionable");
                if (opened == geometry.clearance(1).size()) {
                    check(!driver.breaking() && driver.evidence().get("phase").equals("walking_open_passage"), "complete headroom starts walking without another break");
                } else {
                    var field = ProspectTunnelDriver.class.getDeclaredField("action"); field.setAccessible(true);
                    var action = (UltimineBreak) field.get(driver);
                    check(action != null && action.origin().equals(geometry.clearance(1).get(opened)), "next cut repairs the actual material interruption");
                }
            } finally { driver.close(); }
        }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
