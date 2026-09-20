// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.maiwithu.maicraft.intent.AttentionFeedTest;
import org.maiwithu.maicraft.intent.ChatFlowTest;
import org.maiwithu.maicraft.intent.McpTaskLifecycleTest;

// 注意事件和等待功能的回归入口；纯事件测试先跑，需要 Minecraft 注册信息的测试在初始化后运行。
public final class AttentionRegressionSuite {
    public static void main(String[] args) throws Exception {
        AttentionFeedTest.main(args);
        ChatFlowTest.main(args);
        AttentionWaitTest.main(args);
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // 重复请求和恢复取消必须只改变对应任务记录，不能抢占玩家或打断另一件工作。
        McpTaskLifecycleTest.main(args);
        AttentionSnapshotTest.main(args);
        AttentionHttpTest.main(args);
    }
}
