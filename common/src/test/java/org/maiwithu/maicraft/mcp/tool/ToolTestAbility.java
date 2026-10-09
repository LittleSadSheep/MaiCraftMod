// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.tool;

import org.maiwithu.maicraft.kernel.ability.AbilityDoc;
import org.maiwithu.maicraft.kernel.ability.AbilityModule;
import org.maiwithu.maicraft.kernel.ability.AbilitySpec;
import org.maiwithu.maicraft.kernel.ability.ExecutionMode;
import org.maiwithu.maicraft.kernel.ability.Listing;
import org.maiwithu.maicraft.kernel.ability.RequiredMod;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.goal.TargetKind;
import org.maiwithu.maicraft.kernel.param.ParamSpec;
import org.maiwithu.maicraft.kernel.param.ParamSpecs;
import org.maiwithu.maicraft.kernel.param.ParamType;
import org.maiwithu.maicraft.kernel.result.TaskResult;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * MCP 工具测试用的替身能力：只有规格，决定直接给结果。几种规格覆盖工具要分辨的情形：
 * 带参数和目标对象的、按顺序做几件事的、不列出的、只改记忆的。
 */
final class ToolTestAbility implements AbilityModule {
    private final AbilitySpec spec;
    private final boolean takesSteps;
    StepDecision decision = new StepDecision.Finish(TaskResult.done("做完了"));

    private ToolTestAbility(AbilitySpec spec, boolean takesSteps) {
        this.spec = spec;
        this.takesSteps = takesSteps;
    }

    /** 像 use 一样：对看到的东西或坐标做几次，可以指定手上拿什么。 */
    static ToolTestAbility use() {
        return new ToolTestAbility(spec("maicraft:use", "对一个方块或实体用一下手上的东西",
                ParamSpecs.of(
                        ParamSpec.of("item", ParamType.ITEM_OR_TAG).doc("手上拿什么").build(),
                        ParamSpec.of("count", ParamType.INTEGER).range(1, 64).defaultValue(1).doc("做几次").build()),
                Set.of(TargetKind.SEEN, TargetKind.POSITION), ExecutionMode.CONTROLS_PLAYER, Listing.LISTED), false);
    }

    static ToolTestAbility sleep() {
        return new ToolTestAbility(spec("maicraft:sleep", "睡觉", ParamSpecs.EMPTY,
                Set.of(TargetKind.HERE, TargetKind.LANDMARK), ExecutionMode.CONTROLS_PLAYER, Listing.LISTED), false);
    }

    static ToolTestAbility sequence() {
        return new ToolTestAbility(spec("maicraft:sequence", "按顺序做几件事", ParamSpecs.EMPTY,
                Set.of(), ExecutionMode.CONTROLS_PLAYER, Listing.LISTED), true);
    }

    static ToolTestAbility remember() {
        return new ToolTestAbility(spec("maicraft:remember", "记住或忘掉一个地点", ParamSpecs.of(
                        ParamSpec.of("name", ParamType.TEXT).required().doc("地点名").build()),
                Set.of(TargetKind.HERE, TargetKind.POSITION), ExecutionMode.MEMORY_ONLY, Listing.LISTED), false);
    }

    /** 需要某些联动模组才可用的能力，例如读机器。 */
    static ToolTestAbility needsMods(String id, String... modIds) {
        Set<RequiredMod> mods = new HashSet<>();
        for (String modId : modIds) mods.add(RequiredMod.of(modId));
        return new ToolTestAbility(new AbilitySpec(id, "读机器", AbilityDoc.forAbility(id.substring(id.indexOf(':') + 1)),
                ParamSpecs.EMPTY, Set.of(), ExecutionMode.READ_ONLY, mods, List.of(), Listing.LISTED), false);
    }

    static ToolTestAbility hidden() {
        return new ToolTestAbility(spec("maicraft:debug_probe", "调试用，不列出", ParamSpecs.EMPTY,
                Set.of(), ExecutionMode.READ_ONLY, Listing.HIDDEN), false);
    }

    private static AbilitySpec spec(String id, String summary, ParamSpecs params, Set<TargetKind> targets,
                                    ExecutionMode mode, Listing listing) {
        return new AbilitySpec(id, summary, AbilityDoc.forAbility(id.substring(id.indexOf(':') + 1)),
                params, targets, mode, Set.of(), List.of(), listing);
    }

    @Override public AbilitySpec spec() {
        return spec;
    }

    @Override public StepDecision decide(StepContext step) {
        return decision;
    }

    @Override public boolean acceptsSteps() {
        return takesSteps;
    }
}
