// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import org.maiwithu.maicraft.client.chat.ChatMessage;
import org.maiwithu.maicraft.core.task.chat.ChatTaskRecord;
import java.util.UUID;

final class ChatAbilityAdapter {
    static final String ABILITY = "maicraft:chat";
    private ChatAbilityAdapter() {}

    static IntentAction adapt(Goal goal) {
        return new IntentAction.Native(new ChatTaskRecord("chat-" + UUID.randomUUID(),
                ChatMessage.parse(goal.parameters())));
    }
}
