// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp;

public final class AttentionRegressionSuite {
    public static void main(String[] args) throws Exception {
        org.maiwithu.maicraft.intent.AttentionFeedTest.main(args);
        AttentionWaitTest.main(args);
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        AttentionSnapshotTest.main(args);
        AttentionHttpTest.main(args);
    }
}
