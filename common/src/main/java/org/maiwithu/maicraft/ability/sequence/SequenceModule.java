// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.sequence;

import org.maiwithu.maicraft.kernel.ability.AbilityDoc;
import org.maiwithu.maicraft.kernel.ability.AbilityModule;
import org.maiwithu.maicraft.kernel.ability.AbilitySpec;
import org.maiwithu.maicraft.kernel.ability.ExecutionMode;
import org.maiwithu.maicraft.kernel.ability.Listing;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.param.ParamSpecs;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;

import java.util.List;
import java.util.Set;

/**
 * 按顺序做事能力：一步做完再做下一步；某一步没做成时按这一步的 on_failure 停下或接着做。
 *
 * <p>本能力没有参数和目标对象，只接受 steps。目标推进看到目标带步骤就逐步跑，
 * 不会来问本能力怎么决定；会问到这里说明调用方写错了，如实按程序错误结束。
 */
public final class SequenceModule implements AbilityModule {

    @Override
    public AbilitySpec spec() {
        return new AbilitySpec(
                "maicraft:sequence",
                "按顺序做几件事：steps 里每一步是一个完整的目标",
                AbilityDoc.forAbility("sequence"),
                ParamSpecs.of(),
                Set.of(),
                ExecutionMode.CONTROLS_PLAYER,
                Set.of(),
                List.of(),
                Listing.LISTED);
    }

    @Override
    public boolean acceptsSteps() {
        return true;
    }

    @Override
    public StepDecision decide(StepContext step) {
        return new StepDecision.Finish(TaskResult.failed("没有按顺序做事",
                Problem.of(Problem.Kind.INTERNAL_ERROR,
                        "sequence 的步骤由目标推进逐步跑，不该来问能力怎么决定")));
    }
}
