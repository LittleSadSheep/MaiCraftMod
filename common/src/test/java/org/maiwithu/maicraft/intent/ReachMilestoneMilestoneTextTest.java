// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import java.util.List;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;

/**
 * milestone 缺席或不可识别时，reach_milestone 必须落进枚举可用里程碑的决策回合。
 * 未识别文本曾因不可变 List 的 contains(null) 抛 NPE，被安全网吞成一行
 * "semantic step failed safely: NullPointerException"，调用方无从学到正确用法。
 */
public final class ReachMilestoneMilestoneTextTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        try (var world = new InteractionWorldTestHarness()) {
            // 实机场景：outcome 自由文本 + target=current_place 无 label，parameters 里没有 milestone。
            var goal = new Goal("maicraft:reach_milestone", "达成铁器里程碑：铁剑与铁桶入库",
                    new Goal.SemanticTarget("current_place", null, null, null),
                    "{}", "{}", List.of(), List.of());
            SemanticGoalContract.validate(goal, Set.of("maicraft:reach_milestone"));
            var action = AbilityAdapter.adapt(goal, world.player, IntentRuntime.get());
            if (!(action instanceof IntentAction.Decision decision))
                throw new AssertionError("milestone 缺席应返回决策回合，实际 "
                        + action.getClass().getSimpleName());
            String question = decision.snapshot().question();
            for (String name : List.of("nether", "stronghold", "defeat_dragon", "elytra")) {
                if (!question.contains(name))
                    throw new AssertionError("决策问题应枚举可用里程碑，缺 " + name + ": " + question);
            }
            // 大小写变体走既有规范化路径，不被判为不可识别。
            var cased = new Goal("maicraft:reach_milestone", "进入下界", null,
                    "{\"milestone\":\"Nether\"}", "{}", List.of(), List.of());
            var tool = AbilityAdapter.adapt(cased, world.player, IntentRuntime.get());
            if (!(tool instanceof IntentAction.Tool toolAction)
                    || !"reach_milestone".equals(toolAction.toolName()))
                throw new AssertionError("Nether 应规范化后产出 reach_milestone 工具调用，实际 " + tool);
            if (!toolAction.argumentsJson().contains("\"milestone\":\"nether\""))
                throw new AssertionError("Nether 应规范化为 nether: " + toolAction.argumentsJson());
        }
        System.out.println("ReachMilestoneMilestoneTextTest: passed");
    }
}
