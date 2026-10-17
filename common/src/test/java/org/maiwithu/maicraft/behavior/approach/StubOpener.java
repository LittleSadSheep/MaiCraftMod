// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.approach;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 站位补救替身：记录被请求补救的位置；可选地交出一个会做完的动作，或不肯救（回答 empty）。
 */
final class StubOpener implements StandOpener {

    final List<RejectedSpot> asked = new ArrayList<>();
    private final boolean willing;

    StubOpener(boolean willing) {
        this.willing = willing;
    }

    @Override public Optional<Action> rescue(RejectedSpot blocked, ApproachTarget target) {
        asked.add(blocked);
        return willing ? Optional.of(new Action() {
            @Override public ActionStatus tick(TickContext context) {
                return ActionStatus.done();
            }

            @Override public String describe() {
                return "替身补救";
            }
        }) : Optional.empty();
    }
}
