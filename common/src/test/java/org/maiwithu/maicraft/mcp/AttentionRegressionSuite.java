// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp;

// 注意事件和等待功能的回归入口；纯事件测试先跑，需要 Minecraft 注册信息的测试在初始化后运行。
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
