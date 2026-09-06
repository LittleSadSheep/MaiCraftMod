// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

/** Actual visibility-policy checks and architecture guards; live UI clicks still need a game. */
public final class GuiRegressionSuite {
    public static void main(String[] args) throws Exception {
        MenuVisibilityTest.main(args);
        WindowControlTest.main(args);
        VisibleMenuSessionTest.main(args);
        LegacyInventoryBoundaryTest.main(args);
        GuiBoundaryAuditTest.main(args);
        System.out.println("GuiRegressionSuite: passed");
    }
}
