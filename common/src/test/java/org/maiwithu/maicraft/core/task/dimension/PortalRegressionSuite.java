// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.dimension;

/** Portal preparation contracts and native-action boundaries without opening a game window. */
public final class PortalRegressionSuite {
    public static void main(String[] args) throws Exception {
        NetherPortalFrameTest.main(args);
        EndPortalFrameTest.main(args);
        System.out.println("PortalRegressionSuite: passed");
    }
}
