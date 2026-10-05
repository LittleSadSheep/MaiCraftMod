// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.explore;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.maiwithu.maicraft.intent.ExplorationIntentTest;
import org.maiwithu.maicraft.intent.ExploreInterestDecisionTest;
import org.maiwithu.maicraft.intent.TravelTransportContractTest;
import org.maiwithu.maicraft.mcp.ExplorationCatalogTest;
import org.maiwithu.maicraft.core.task.structure.StructureProfileResourcesTest;
import org.maiwithu.maicraft.core.task.structure.StructureSightingScannerTest;
import org.maiwithu.maicraft.core.task.structure.VisibleStructureEvidenceTest;
import org.maiwithu.maicraft.intent.persistence.ExplorationMemoryStoreTest;
import org.maiwithu.maicraft.mcp.McpProtocolBudgetTest;

/** 先核对方向和可见前沿，再回放公开契约与注册目录；实际走路仍交给游戏内原生寻路。 */
public final class ExplorationRegressionSuite {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        ExplorationSectorTest.main(args);
        ExplorationFrontiersTest.main(args);
        SurveyRadiusVerdictTest.main(args);
        FrontierLegBreakerTest.main(args);
        ExplorationIntentTest.main(args);
        ExploreInterestDecisionTest.main(args);
        TravelTransportContractTest.main(args);
        ExplorationCatalogTest.main(args);
        StructureProfileResourcesTest.main(args);
        // 飞机远望与步行搜索必须使用相同的可见结构判据。
        VisibleStructureEvidenceTest.run();
        StructureSightingScannerTest.main(args);
        ExplorationJournalTest.main(args);
        TerrainFeatureMemoryTest.main(args);
        StructureSightingMemoryTest.main(args);
        SurfaceColumnClassificationTest.main(args);
        WaterCrossingDecisionTest.main(args);
        // 勘察腿涉水守卫：航点不落水面、滞水楔死弃腿并如实汇报、短促涉水豁免不误伤。
        org.maiwithu.maicraft.core.task.structure.FrontierLegWaterGuardTest.main(args);
        ExplorationMemoryStoreTest.main(args);
        McpProtocolBudgetTest.main(args);
        System.out.println("ExplorationRegressionSuite: passed");
    }
}
