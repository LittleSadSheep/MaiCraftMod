// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.chat;

import org.maiwithu.maicraft.client.chat.ChatMessage;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;

/**
 * 保存要输入的内容，不设置世界游戏刻截止时间。打字用真实经过时间控制，暂停任务时保留进度。
 */
public final class ChatTaskRecord extends TaskRecord {
    static { TaskFactory.register(ChatTaskRecord.class, ChatTask::new); }
    final ChatMessage message;

    public ChatTaskRecord(String callId, ChatMessage message) {
        super("chat", callId, NO_DEADLINE);
        this.message = java.util.Objects.requireNonNull(message);
    }
}
