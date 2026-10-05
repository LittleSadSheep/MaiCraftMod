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
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;

/** 使用真实视线和通道地形选择下一次原生动作；完成条件取决于剩余断面，不取决于点击次数。 */
public final class ProspectTunnelDriverTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        for (boolean descending : new boolean[]{false, true}) {
            for (int opened = 0; opened <= (descending ? 3 : 2); opened++) scenario(descending, opened);
        }
        staleAnchorResumesDigging();
        fruitlessScansTerminateHonest();
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

    /**
     * 下降隧道在实心地形里挖出首段后站位被移开：断面几何以锚定脚位为基准，沿用旧锚点会把射线
     * 对准角色已不在的位置，把“站位看不到前沿”误判成“地形无可挖”。驱动器必须按当前脚位重锚续挖。
     */
    private static void staleAnchorResumesDigging() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            BlockPos anchor = new BlockPos(8, 8, 8);
            h.position(Vec3.atBottomCenterOf(anchor)); h.player.setYRot(0);
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = 1; y < 15; y++)
                h.set(new BlockPos(x, y, z), Blocks.STONE.defaultBlockState());
            // 锚位挖出 2 格身体空间；真实隧道里角色站位总有至少这么大的空气口袋。
            for (int y = 8; y <= 9; y++) h.set(new BlockPos(8, y, 8), Blocks.AIR.defaultBlockState());
            var driver = new ProspectTunnelDriver(h.player, 2);
            try {
                check(driver.tick(128), "solid frontier is actionable at the anchor");
                // 结清首段原生下降线（触发块加两格阶梯），模拟实机“挖 3 格”后的现场。
                for (int k = 0; k < 3; k++)
                    h.set(actionOrigin(driver).relative(Direction.SOUTH, k).below(k), Blocks.AIR.defaultBlockState());
                finishAction(driver);
                // 站位侧移一格（被推移、走路到达容差、外放回收都可能造成），计划仍锚在原位。
                h.position(Vec3.atBottomCenterOf(anchor.east()));
                for (int y = 8; y <= 9; y++) h.set(new BlockPos(9, y, 8), Blocks.AIR.defaultBlockState());
                check(driver.tick(128), "displaced stance re-anchors and keeps digging");
                check(driver.failure() == null, "stance divergence must not fail the tunnel");
                BlockPos feet = PlayerNav.playerFeet(h.player);
                check(feet.equals(anchor.east()), "displacement took effect");
                // 断言第一刀落在当前站位自己的前沿上，而不是替旧锚点补刀。
                check(actionOrigin(driver) != null
                                && actionOrigin(driver).equals(feet.relative(Direction.SOUTH).below().above(2)),
                        "first cut of the re-anchored plan targets the new stance's own frontier");
            } finally { driver.close(); }
        }
    }

    /** 前沿全部不可挖（基岩断面）时按有限预算原地重扫，预算用尽才以无可挖结论诚实失败。 */
    private static void fruitlessScansTerminateHonest() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            BlockPos anchor = new BlockPos(8, 8, 8);
            h.position(Vec3.atBottomCenterOf(anchor)); h.player.setYRot(0);
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = 1; y < 15; y++)
                h.set(new BlockPos(x, y, z), Blocks.BEDROCK.defaultBlockState());
            var driver = new ProspectTunnelDriver(h.player, 2);
            try {
                check(driver.tick(128), "first fruitless scan stays running");
                check(driver.tick(128), "second fruitless scan stays running (rescan budget)");
                check(!driver.tick(128) && driver.failure() != null
                                && driver.failure().contains("no_reachable_obstacle_or_supported_passage_in_tunnel_direction"),
                        "rescan budget exhausted reports the honest no-dig verdict");
            } finally { driver.close(); }
        }
    }

    private static BlockPos actionOrigin(ProspectTunnelDriver driver) throws Exception {
        var field = ProspectTunnelDriver.class.getDeclaredField("action"); field.setAccessible(true);
        var action = (UltimineBreak) field.get(driver);
        check(action != null, "expected a pending native break");
        return action.origin();
    }

    /** 原生动作已在别处结清时，把驱动器内的在途引用摘除，让下一刻重新选择前沿。 */
    private static void finishAction(ProspectTunnelDriver driver) throws Exception {
        var field = ProspectTunnelDriver.class.getDeclaredField("action"); field.setAccessible(true);
        var action = (UltimineBreak) field.get(driver);
        field.set(driver, null);
        if (action != null) action.close();
    }
}
