// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

/** 单独检查公共界面恢复与菜单交接，普通游戏流程的其他失败不遮住原生收尾结论。 */
public final class GuiRecoveryRegressionSuite {
    public static void main(String[] args) throws Exception {
        // 先核对公共世界入口和收尾归属，再检查聊天、背包与真实菜单确认。
        GuiRecoveryTest.main(args); ChatRegressionSuite.main(args);
        MenuVisibilityTest.main(args); MenuConfirmationLatencyTest.main(args); VisibleMenuSessionTest.main(args);
        BackpackOpenSessionTest.main(args); InventoryMenuHandoffTest.main(args);
        // 世界动作的快捷栏暂存、腾空主手和倒桶也复用公共准备，不能误关正在使用的背包。
        MachineMenuHandParkingTest.main(args); FirstPersonGateExtendedHotbarTest.main(args);
        EquipRoutingTest.main(args); FluidPlacementTaskTest.main(args); TargetedDropEvidenceTest.main(args);
        System.out.println("GuiRecoveryRegressionSuite: passed");
    }
}
