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
        // 参数经公开契约校验后只转换成当前身体的一张任务单；不在规划阶段靠怪或读取服务器规则。
        // 低饱食度和返程距离只解释调用动机，是否寻死由模型决定；keepInventory 与游戏模式在执行时再核实。
        return new IntentAction.Native(new SuicideTaskRecord("suicide-" + UUID.randomUUID(),
                SuicideRequest.parse(goal.parameters())));
    }
}
