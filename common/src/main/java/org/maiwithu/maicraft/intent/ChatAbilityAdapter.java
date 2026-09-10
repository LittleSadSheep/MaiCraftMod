// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import org.maiwithu.maicraft.client.chat.ChatMessage;
import org.maiwithu.maicraft.core.task.chat.ChatTaskRecord;
import java.util.UUID;

// 把 chat 目标中的文字和打字间隔读成 ChatMessage，再建立一次聊天任务；具体输入框操作由执行层负责。
final class ChatAbilityAdapter {
    static final String ABILITY = "maicraft:chat";
    private ChatAbilityAdapter() {}

    static IntentAction adapt(Goal goal) {
        return new IntentAction.Native(new ChatTaskRecord("chat-" + UUID.randomUUID(),
                ChatMessage.parse(goal.parameters())));
    }
}
