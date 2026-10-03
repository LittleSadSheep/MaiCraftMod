// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import java.util.UUID;
import org.maiwithu.maicraft.core.task.suicide.SuicideRequest;
import org.maiwithu.maicraft.core.task.suicide.SuicideTaskRecord;

/** 主动寻死只能由明确目标进入，不把低饱食度或远离重生点变成自动触发条件。 */
final class SuicideAbilityAdapter {
    static final String ABILITY = "maicraft:suicide";
    private SuicideAbilityAdapter() { }

    static IntentAction adapt(Goal goal) {
        return new IntentAction.Native(new SuicideTaskRecord("suicide-" + UUID.randomUUID(),
                SuicideRequest.parse(goal.parameters())));
    }
}
