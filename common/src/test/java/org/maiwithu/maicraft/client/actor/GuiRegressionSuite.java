// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

/** Actual visibility-policy checks and architecture guards; live UI clicks still need a game. */
public final class GuiRegressionSuite {
    public static void main(String[] args) throws Exception {
        MenuVisibilityTest.main(args);
        WindowControlTest.main(args);
        ControlProtocolTest.main(args);
        BodyControlInputTest.main(args);
        org.maiwithu.maicraft.client.lightnav.LightNavProtocolTest.main(args);
        CompanionCancellationTest.main(args);
        org.maiwithu.maicraft.core.inventory.StockEvidenceTest.main(args);
        org.maiwithu.maicraft.core.integration.create.CreateStockObservationTest.main(args);
        org.maiwithu.maicraft.core.integration.ae2.Ae2ScreenAccessTest.main(args);
        org.maiwithu.maicraft.core.integration.ae2.Ae2WaterBucketFillTest.main(args);
        org.maiwithu.maicraft.core.integration.ae2.Ae2FixedTerminalDiscoveryTest.main(args);
        VisibleMenuSessionTest.main(args);
        LegacyInventoryBoundaryTest.main(args);
        GuiBoundaryAuditTest.main(args);
        System.out.println("GuiRegressionSuite: passed");
    }
}
