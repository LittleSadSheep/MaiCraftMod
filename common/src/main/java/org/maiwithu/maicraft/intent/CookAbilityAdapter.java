// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import java.util.UUID;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.agent.tool.api.ToolContext;
import org.maiwithu.maicraft.core.tools.work.SemanticCookApi;

/** 从当前位置准备炉子与材料；数量和来源在接单前检查，执行时直接交给同一份类型明确的任务单。 */
final class CookAbilityAdapter {
    static final String ABILITY = "maicraft:cook";
    private CookAbilityAdapter() {}

    static void validate(Goal goal) {
        var target = goal.target();
        if (target != null && (!"nearest".equals(target.kind()) || target.label() != null
                || target.position() != null || target.relation() != null)) {
            throw new IllegalArgumentException("cook starts from the player's current location; use a sequence "
                    + "with travel first. Supply the output in item_id and the device preference in recipe_preference.");
        }
        SemanticCookApi.parse(goal.parameters());
    }

    static IntentAction adapt(Goal goal, LocalPlayer player) {
        // 恢复旧任务也要重新核对要求，不能沿用旧适配器把三百件压成二百五十六件的解释。
        validate(goal);
        var context = new ToolContext("cook-" + UUID.randomUUID(), player.level().getGameTime());
        return new IntentAction.Native(SemanticCookApi.newRecord(context, goal.parameters()));
    }
}
