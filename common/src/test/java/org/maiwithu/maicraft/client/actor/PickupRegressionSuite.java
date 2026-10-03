// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;
import org.maiwithu.maicraft.intent.CollectItemsContractTest;

/** 先初始化游戏注册表并验证取物，再验证战斗收场、剪毛观察和精确目标收取的原生边界。 */
public final class PickupRegressionSuite {
    public static void main(String[] args) throws Exception {
        DroppedItemPickupTest.main(args);
        // 主动扔掉的材料需要完整批次与跨任务避让，不能被下一趟拾取路线无意捡回。
        DropBatchPlanTest.main(args);
        DropCompanionTaskTest.main(args);
        DiscardedItemsTest.main(args);
        CombatPickupOwnershipTest.main(args);
        CombatOutcomeTest.main(args);
        ShearingDropReceiptTest.main(args);
        CollectItemsIdentityTest.main(args);
        // 模型先辨认全部掉落，再指定一堆靠近；失效引用与别人拾走都须交付真实未完成结果。
        DroppedItemObservationTest.main(args);
        CollectItemsSelectionTest.main(args);
        CollectItemsContractTest.main(args);
        // 掉落物还在下落、随水漂移时留在当前任务重寻；有限窗口后仍无路才交付失败。
        PickupNavigationRetryTest.main(args);
        System.out.println("PickupRegressionSuite: passed");
    }
}
