// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.remember;

import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.ResultDetails;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 记地点任务：把名字与位置写进世界记忆，或把一个名字忘掉。不移动角色、不读地形、不核实那里
 * 能不能站人——这些交给真正去那里的能力。写完当场结束，世界的改变只有记忆里多（少）了一条。
 *
 * <p>同名覆盖是本来的规矩：覆盖前记在哪已在输入里，随结果交给 LLM 对照。
 */
public final class RememberPlaceTask extends PhasedTask<RememberPlaceTask.Phase> {

    /** 只有一个阶段：写（删）完即结束。 */
    public enum Phase { WRITE }

    private final RememberPlaceInput input;
    private final WorldMemory memory;

    public RememberPlaceTask(RememberPlaceInput input, WorldMemory memory) {
        // 一刻内完成的小事，卡住判定只作兜底，不会真的等到。
        super("记地点", Phase.WRITE, new ProgressTracker(20L, Long.MAX_VALUE));
        this.input = input;
        this.memory = memory;
    }

    /** 结果细节：地点名、做了哪种操作，记住时有 position，改记或忘掉时有 previous_position。 */
    record RememberDetails(String name, String operation, WorldPosition position, WorldPosition previousPosition)
            implements ResultDetails {}

    @Override protected Action enter(Phase phase) {
        // 写记忆是一刻内的小事，不需要动作。
        return null;
    }

    @Override protected Next<Phase> tick(Phase phase, TickContext context) {
        if (input.forget()) {
            memory.forgetPlace(input.name());
            return Next.done(TaskResult.builder(TaskResult.Status.DONE, "已忘掉地点「" + input.name() + "」")
                    .details(new RememberDetails(input.name(), "forget", null, input.previous()))
                    .build());
        }
        // 记住：同名用新位置覆盖；覆盖前记在哪随结果说明，位置没变的情况在决定阶段就当场完成不进这里。
        memory.remember(input.name(), input.position());
        return Next.done(TaskResult.builder(TaskResult.Status.DONE,
                        "已把「" + input.name() + "」记在 " + describe(input.position()))
                .details(new RememberDetails(input.name(), "remember", input.position(), input.previous()))
                .build());
    }

    @Override protected ResultDetails details() {
        // 结果在这里一定已经写完；细节与 tick 里给出去的一致。
        return new RememberDetails(input.name(), input.forget() ? "forget" : "remember",
                input.position(), input.previous());
    }

    private static String describe(WorldPosition position) {
        return "(" + position.x() + ", " + position.y() + ", " + position.z()
                + (position.dimension() == null ? "" : "，" + position.dimension()) + ")";
    }
}
