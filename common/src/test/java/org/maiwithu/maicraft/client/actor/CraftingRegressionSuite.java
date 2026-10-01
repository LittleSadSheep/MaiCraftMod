package org.maiwithu.maicraft.client.actor;

import org.maiwithu.maicraft.core.task.acquire.AcquisitionRecipePlanningTest;
import org.maiwithu.maicraft.core.task.acquire.RecipeMaterialPlanTest;
import org.maiwithu.maicraft.core.task.craft.CraftSurfaceFailureTest;

/** 合成独立回归集中核对标签、选料、原生摆料与延迟产物，避免其他游戏模块失败遮住合成结论。 */
public final class CraftingRegressionSuite {
    public static void main(String[] args) throws Exception {
        CraftingRecipeBookBoundaryTest.main(args);
        CraftingPlacementPlanTest.main(args);
        CraftingGridPlacementTest.main(args);
        CraftingResultSynchronizationTest.main(args);
        // 最后回放完整任务：两批真实入包、失败返料及配方簿超时的准确原因。
        CraftingTaskTagTest.main(args);
        // 摆料修复还须保持原有菜单同步、工作台准备和递归取材的匹配规则。
        MenuConfirmationLatencyTest.main(args);
        MenuVisibilityTest.main(args);
        CraftSurfaceFailureTest.main(args);
        AcquisitionRecipePlanningTest.main(args);
        RecipeMaterialPlanTest.main(args);
        System.out.println("CraftingRegressionSuite: passed");
    }
}
