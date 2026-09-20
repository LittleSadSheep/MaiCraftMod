// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.maiwithu.maicraft.client.preview.PreviewBuildingBudgetTest;
import org.maiwithu.maicraft.core.blueprint.BlueprintImportBudgetTest;
import org.maiwithu.maicraft.core.blueprint.BuildingCoordinateBudgetTest;
import org.maiwithu.maicraft.core.blueprint.BuildingPersistenceBudgetTest;
import org.maiwithu.maicraft.core.build.BuildingBudgetsTest;
import org.maiwithu.maicraft.intent.persistence.IntentPersistenceBudgetTest;
import org.maiwithu.maicraft.intent.persistence.IntentRecoveryBudgetTest;
import org.maiwithu.maicraft.mcp.McpConfigBudgetTest;

/** 按实际配置文件依次检查建筑、预览、导入、保存和协议入口；每组结束后恢复默认预算。 */
public final class BuildingBudgetRegressionSuite {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        BuildingBudgetsTest.main(args);
        BuildingBudgetContractTest.main(args);
        PreviewBuildingBudgetTest.main(args);
        BlueprintImportBudgetTest.main(args);
        BuildingPersistenceBudgetTest.main(args);
        BuildingCoordinateBudgetTest.main(args);
        IntentPersistenceBudgetTest.main(args);
        IntentRecoveryBudgetTest.main(args);
        McpConfigBudgetTest.main(args);
        System.out.println("BuildingBudgetRegressionSuite: passed");
    }
}
