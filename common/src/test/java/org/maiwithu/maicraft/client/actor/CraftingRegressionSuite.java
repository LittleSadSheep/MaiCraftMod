package org.maiwithu.maicraft.client.actor;

import org.maiwithu.maicraft.core.task.acquire.AcquisitionRecipePlanningTest;
import org.maiwithu.maicraft.core.task.acquire.RecipeMaterialPlanTest;
import org.maiwithu.maicraft.core.task.acquire.NearbyRecipePreferenceTest;
import org.maiwithu.maicraft.core.task.acquire.AcquisitionPrerequisiteRefreshTest;
import org.maiwithu.maicraft.core.task.craft.CraftSurfaceFailureTest;
import org.maiwithu.maicraft.core.task.craft.CraftingWorkstationPlanningTest;
import org.maiwithu.maicraft.intent.CraftAbilityWorkstationTest;

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
        // 随身工作台需走原生摆放；同父类的制箭台、锻造台不能引出伪造的工作面材料树。
        CraftingWorkstationPlanningTest.main(args);
        // 两个公开入口在同一背包状态下必须派同一普通工具配方，不能要求调用者绕路换能力。
        CraftAbilityWorkstationTest.main(args);
        AcquisitionRecipePlanningTest.main(args);
        // 工作台或中间材料稍后到包时，立即收起更深的旧备料分支。
        AcquisitionPrerequisiteRefreshTest.main(args);
        RecipeMaterialPlanTest.main(args);
        // 空背包优先利用真实野生材料；LLM 软偏好能引导路线并在失败后继续选择可用替代品。
        NearbyRecipePreferenceTest.main(args);
        System.out.println("CraftingRegressionSuite: passed");
    }
}
