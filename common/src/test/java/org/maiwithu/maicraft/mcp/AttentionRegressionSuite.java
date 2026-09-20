// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.maiwithu.maicraft.intent.AttentionFeedTest;
import org.maiwithu.maicraft.intent.ChatFlowTest;
import org.maiwithu.maicraft.intent.McpTaskLifecycleTest;
import org.maiwithu.maicraft.intent.WaitGoalTest;
import org.maiwithu.maicraft.intent.GoalCheckpointCompatibilityTest;
import org.maiwithu.maicraft.intent.persistence.CheckpointCapacityTest;
import org.maiwithu.maicraft.intent.PriorResultResolverTest;
import org.maiwithu.maicraft.intent.SequenceSkipTest;
import org.maiwithu.maicraft.intent.ChatDurableCheckpointTest;
import org.maiwithu.maicraft.client.chat.ChatMonitorTest;

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
        // 先等足游戏时间，再根据实际条件完成目标；暂停和顺序执行都要保留这一约定。
        WaitGoalTest.main(args);
        // 新请求收紧参数时，旧等待历史仍须可读、可取消，不能堵住其余能力的检查点。
        GoalCheckpointCompatibilityTest.main(args);
        // 任务编号和恢复记录必须完整保存，超出容量时让调用者看到明确失败。
        CheckpointCapacityTest.main(args);
        // 后续目标只能引用已确认且可区分的位置，失败和含糊结果不能生成新的移动目的地。
        PriorResultResolverTest.main(args);
        // 明确跳过允许继续清单，但查询、通知和恢复不能把被略过的目标算作实际成功。
        SequenceSkipTest.main(args);
        // 聊天恢复后必须沿用已经保存的操作身份，不能因新建会话就再次发送。
        ChatDurableCheckpointTest.main(args);
        // 收到的聊天保留作者与截断事实，不能冒充任务事件，也不接收动作栏洪泛。
        ChatMonitorTest.main(args);
        AttentionSnapshotTest.main(args);
        AttentionHttpTest.main(args);
    }
}
