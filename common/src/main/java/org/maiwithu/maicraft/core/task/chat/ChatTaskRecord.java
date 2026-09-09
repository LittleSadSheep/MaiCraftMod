// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.chat;

import org.maiwithu.maicraft.client.chat.ChatMessage;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;

/** Typing has bounded content and wall-clock pacing; a paused task must not expire on world time. */
public final class ChatTaskRecord extends TaskRecord {
    static { TaskFactory.register(ChatTaskRecord.class, ChatTask::new); }
    final ChatMessage message;

    public ChatTaskRecord(String callId, ChatMessage message) {
        super("chat", callId, NO_DEADLINE);
        this.message = java.util.Objects.requireNonNull(message);
    }
}
