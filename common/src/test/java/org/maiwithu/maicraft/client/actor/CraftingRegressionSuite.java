package org.maiwithu.maicraft.client.actor;

/** 合成独立回归集中核对标签、选料、原生摆料与延迟产物，避免其他游戏模块失败遮住合成结论。 */
public final class CraftingRegressionSuite {
    public static void main(String[] args) throws Exception {
        CraftingRecipeBookBoundaryTest.main(args);
        CraftingPlacementPlanTest.main(args);
        CraftingGridPlacementTest.main(args);
        CraftingResultSynchronizationTest.main(args);
        System.out.println("CraftingRegressionSuite: passed");
    }
}
