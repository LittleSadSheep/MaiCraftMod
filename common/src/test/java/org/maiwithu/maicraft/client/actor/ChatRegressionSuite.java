// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import org.maiwithu.maicraft.client.chat.ChatTypingTest;
import org.maiwithu.maicraft.intent.ChatAbilityTest;
import org.maiwithu.maicraft.intent.ChatDurableCheckpointTest;

/** 独立验证聊天的界面准备、草稿交接与发送身份，其他游戏操作用例失败时仍能判断聊天链路。 */
public final class ChatRegressionSuite {
    public static void main(String[] args) throws Exception {
        // 先确认挡路界面能自行收尾，再验证人的输入接管和原消息暂停续发。
        ChatGuiPreparationTest.main(args);
        ChatTypingTest.main(args);
        ChatSessionTest.main(args);
        ChatAbilityTest.main(args);
        // 清理界面不能提前预约消息，恢复后的旧发送编号仍不允许再次提交。
        ChatSubmissionHistoryTest.main(args);
        ChatDurableCheckpointTest.main(args);
        System.out.println("ChatRegressionSuite: passed");
    }
}
