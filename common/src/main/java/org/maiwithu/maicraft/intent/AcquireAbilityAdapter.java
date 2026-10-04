// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import org.maiwithu.maicraft.core.tools.work.SemanticAcquireApi;

/** 取物从玩家当前位置开始；指定异地时先编排移动，不能把地点要求丢掉后在脚边随便取材。 */
final class AcquireAbilityAdapter {
    static final String ABILITY = "maicraft:acquire_items";

    private AcquireAbilityAdapter() {}

    static void validate(Goal goal) {
        // 取物从角色当前地点开始；计划阶段就拒绝异地或带限定的 nearest，避免角色在错误地点开箱或采集。
        // 数量和来源在这里校验，标签成员与实际库存留到进入游戏后的取物 API 读取。
        var target = goal.target();
        if (target != null && (!"nearest".equals(target.kind()) || target.label() != null
                || target.position() != null || target.relation() != null)) {
            throw new IllegalArgumentException("acquire_items starts from the player's current location; "
                    + "use a sequence with travel before acquiring elsewhere. Only an unqualified nearest target is supported.");
        }
        SemanticAcquireApi.validateArguments(goal.parameters());
    }

    static IntentAction adapt(Goal goal) {
        // 新请求在接单前已经验证；恢复旧任务时仍在这里复核，防止旧的含糊要求直接变成角色动作。
        validate(goal);
        return new IntentAction.Tool("acquire_items", goal.parameters().toString());
    }
}
