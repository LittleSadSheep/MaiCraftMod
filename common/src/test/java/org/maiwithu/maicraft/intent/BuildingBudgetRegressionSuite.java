// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

/** 按实际配置文件依次检查建筑、预览、导入、保存和协议入口；每组结束后恢复默认预算。 */
public final class BuildingBudgetRegressionSuite {
    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
        org.maiwithu.maicraft.core.build.BuildingBudgetsTest.main(args);
        BuildingBudgetContractTest.main(args);
        org.maiwithu.maicraft.client.preview.PreviewBuildingBudgetTest.main(args);
        org.maiwithu.maicraft.core.blueprint.BlueprintImportBudgetTest.main(args);
        org.maiwithu.maicraft.core.blueprint.BuildingPersistenceBudgetTest.main(args);
        org.maiwithu.maicraft.core.blueprint.BuildingCoordinateBudgetTest.main(args);
        org.maiwithu.maicraft.intent.persistence.IntentPersistenceBudgetTest.main(args);
        org.maiwithu.maicraft.intent.persistence.IntentRecoveryBudgetTest.main(args);
        org.maiwithu.maicraft.mcp.McpConfigBudgetTest.main(args);
        System.out.println("BuildingBudgetRegressionSuite: passed");
    }
}
