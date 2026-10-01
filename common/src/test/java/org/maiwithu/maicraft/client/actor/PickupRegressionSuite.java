// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

/** 先初始化游戏注册表并验证取物，再验证战斗收场、剪毛观察和精确目标收取的原生边界。 */
public final class PickupRegressionSuite {
    public static void main(String[] args) throws Exception {
        DroppedItemPickupTest.main(args);
        CombatPickupOwnershipTest.main(args);
        CombatOutcomeTest.main(args);
        ShearingDropReceiptTest.main(args);
        CollectItemsIdentityTest.main(args);
        System.out.println("PickupRegressionSuite: passed");
    }
}
