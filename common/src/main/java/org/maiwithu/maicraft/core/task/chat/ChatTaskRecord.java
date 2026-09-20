// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.chat;

import org.maiwithu.maicraft.client.chat.ChatMessage;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.core.task.base.NativeSubmissionTaskRecord;
import java.util.Objects;

/**
 * 保存文字并使用聊天专属的持久提交编号；打字按真实时间推进，暂停保留进度，重启不自动重发旧操作。
 */
public final class ChatTaskRecord extends NativeSubmissionTaskRecord {
    static { TaskFactory.register(ChatTaskRecord.class, ChatTask::new); }
    final ChatMessage message;

    public ChatTaskRecord(String callId, ChatMessage message) {
        super("chat", callId, NO_DEADLINE, "chat");
        this.message = Objects.requireNonNull(message);
    }
}
