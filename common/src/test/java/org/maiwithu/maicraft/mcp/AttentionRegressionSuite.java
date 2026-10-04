// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.maiwithu.maicraft.intent.AttentionFeedTest;
import org.maiwithu.maicraft.intent.ReminderBoardTest;
import org.maiwithu.maicraft.client.runtime.LowLightCombatReminderTest;
import org.maiwithu.maicraft.client.runtime.FoodSupplyReminderTest;
import org.maiwithu.maicraft.client.runtime.CombatEquipmentReminderTest;
import org.maiwithu.maicraft.client.runtime.SleepReminderTest;
import org.maiwithu.maicraft.intent.IntentAttentionEvidenceTest;
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
        // 持续游戏风险须在工具读取后继续保留，事件节流不能删掉模型所需的最新依据。
        ReminderBoardTest.main(args);
        // 短时间内反复遇袭与当前低光共同成立才建议补光，离场和过期后须撤下。
        LowLightCombatReminderTest.main(args);
        // 持续口粮不足提醒建立农业供给，短暂清包和已有充足食物不应触发。
        FoodSupplyReminderTest.main(args);
        // 明显战斗伤势提示盔甲与远程装备准备，血量同步迟到和混合伤害不能重复或错误计数。
        CombatEquipmentReminderTest.main(args);
        // 睡眠提示按昼夜换文案，个人休息统计不能被世界日期替代，四条提醒须能共同返回。
        SleepReminderTest.main(args);
        ChatFlowTest.main(args);
        AttentionWaitTest.main(args);
        // 大份任务证据按路径与分页找回，不能因宿主压缩上下文而只能重新执行动作。
        JsonReadbackTest.main(args);
        ResponseArchiveTest.main(args);
        McpProtocolBudgetTest.main(args);
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // 机器失败已有的现场和库存随通知完整交付，避免额外观察再重发任务。
        IntentAttentionEvidenceTest.main(args);
        // 各宿主先发现内联对象再提交目标，非法嵌套参数必须在进入游戏动作前被拒绝。
        McpSchemaCompatibilityTest.main(args);
        // 已存的大蓝图和失败历史可分页找回，常规状态须保留当前待答问题与消费不确定性。
        TaskViewTest.main(args);
        // 背包和当前主手都携带组件身份，半成品无需先投进机器才能查看进度。
        InventoryComponentFactsTest.main(args);
        PlanViewTest.main(args);
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
        // on_failure=continue 让被容忍的失败接续兄弟步骤，但部分失败仍以 FAILED 终态结算。
        org.maiwithu.maicraft.intent.SequenceToleratedFailureTest.main(args);
        // 聊天恢复后必须沿用已经保存的操作身份，不能因新建会话就再次发送。
        ChatDurableCheckpointTest.main(args);
        // 收到的聊天保留作者与截断事实，不能冒充任务事件，也不接收动作栏洪泛。
        ChatMonitorTest.main(args);
        AttentionSnapshotTest.main(args);
        // 任一维度死亡后，承接任务被取消、替换或丢失都不能让重生问题从回执与等待中消失。
        DeathDecisionVisibilityTest.main(args);
        // 端口被占时按端口递增让行，同机多开客户端各自拿到可用端点；全部被占须响亮失败。
        McpPortFallbackHttpTest.main(args);
        AttentionHttpTest.main(args);
        // 未订阅注意流的宿主也从每次工具结果拿到风险提醒，失败与知识旁路均不能遗漏。
        ReminderHttpTest.main(args);
    }
}
