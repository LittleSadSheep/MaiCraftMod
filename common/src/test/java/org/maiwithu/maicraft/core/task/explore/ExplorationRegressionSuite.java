package org.maiwithu.maicraft.core.task.explore;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.maiwithu.maicraft.intent.ExplorationIntentTest;
import org.maiwithu.maicraft.intent.TravelTransportContractTest;
import org.maiwithu.maicraft.mcp.ExplorationCatalogTest;

/** 先核对方向和可见前沿，再回放公开契约与注册目录；实际走路仍交给游戏内原生寻路。 */
public final class ExplorationRegressionSuite {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        ExplorationSectorTest.main(args);
        ExplorationFrontiersTest.main(args);
        ExplorationIntentTest.main(args);
        TravelTransportContractTest.main(args);
        ExplorationCatalogTest.main(args);
        System.out.println("ExplorationRegressionSuite: passed");
    }
}
