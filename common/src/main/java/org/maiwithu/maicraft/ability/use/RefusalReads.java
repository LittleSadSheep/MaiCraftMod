// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.use;

import java.util.Objects;
import java.util.Optional;

import org.maiwithu.maicraft.behavior.interaction.ClientGameRefusals;

/**
 * 游戏拒绝读端的接缝实现：把用东西能力问到的"游戏的拒绝与不作为"转给玩家行为层的读端。
 * 动作栏文案与服务端确认流都在那里汇总，这里只做接缝形状的换算。
 */
final class RefusalReads implements UseSeams.ReadsGameRefusal {

    private final ClientGameRefusals refusals;

    RefusalReads(ClientGameRefusals refusals) {
        this.refusals = Objects.requireNonNull(refusals);
    }

    @Override
    public Optional<String> latestMessage() {
        return refusals.latestMessage();
    }

    @Override
    public boolean interactionWasUnhandled() {
        return refusals.interactionWasUnhandled();
    }
}
