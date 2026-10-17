// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.remember;

import java.util.ArrayList;
import java.util.List;

import org.maiwithu.maicraft.behavior.worldmemory.RememberedRegion;
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
 * 记地点任务：把名字与位置写进世界记忆、把一块地方圈成玩家地盘，或把一个名字忘掉。不移动角色、不读地形、
 * 不核实那里能不能站人——这些交给真正去那里的能力。写完当场结束，世界的改变只有记忆里多（少）了一条。
 *
 * <p>同名覆盖是本来的规矩：覆盖前记成什么已在输入里，随结果交给 LLM 对照。
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

    /** 一块地盘的形状：中心与半径（格）。 */
    record RegionShape(WorldPosition center, int radius) {
        static RegionShape of(RememberedRegion region) {
            return region == null ? null : new RegionShape(region.center(), region.radiusBlocks());
        }
    }

    /**
     * 结果细节：名字、做了哪种操作。记地点时 position 是那个位置，圈地盘时 position 是中心、radius 是半径；
     * 改记时有 previous_position（圈地盘还有 previous_radius）；忘掉时 previous_position 是忘掉的地点，
     * forgotten_region 是忘掉的地盘。
     */
    record RememberDetails(String name, String operation, WorldPosition position, Integer radius,
            WorldPosition previousPosition, Integer previousRadius, RegionShape forgottenRegion)
            implements ResultDetails {

        /** 记地点（或忘掉只记过地点的名字）时的细节：没有半径和地盘。 */
        static RememberDetails ofPlace(String name, String operation, WorldPosition position,
                WorldPosition previousPosition) {
            return new RememberDetails(name, operation, position, null, previousPosition, null, null);
        }
    }

    @Override protected Action enter(Phase phase) {
        // 写记忆是一刻内的小事，不需要动作。
        return null;
    }

    @Override protected Next<Phase> tick(Phase phase, TickContext context) {
        return switch (input.operation()) {
            case FORGET -> forget();
            case REGION -> region();
            case REMEMBER -> remember();
        };
    }

    // 忘掉：同名的地点与地盘记过哪样忘哪样，结果写清忘掉的是哪几样。
    private Next<Phase> forget() {
        List<String> forgotten = new ArrayList<>();
        if (input.previous() != null) {
            memory.forgetPlace(input.name());
            forgotten.add("地点");
        }
        if (input.previousRegion() != null) {
            memory.forgetRegion(input.name());
            forgotten.add("地盘");
        }
        return Next.done(TaskResult.builder(TaskResult.Status.DONE,
                        "已忘掉" + String.join("和", forgotten) + "「" + input.name() + "」")
                .details(details())
                .build());
    }

    // 圈地盘：同名用新中心、新半径覆盖；记下立刻生效，保护判断把里面的方块都当受保护。
    private Next<Phase> region() {
        memory.rememberRegion(input.name(), input.position(), input.radius());
        return Next.done(TaskResult.builder(TaskResult.Status.DONE,
                        "已把「" + input.name() + "」圈成玩家地盘：中心 " + describe(input.position()) + "，半径 "
                                + input.radius() + " 格；里面的方块不拆，箱子不当成能取用的")
                .details(details())
                .build());
    }

    // 记住：同名用新位置覆盖；覆盖前记在哪随结果说明，位置没变的情况在决定阶段就当场完成不进这里。
    private Next<Phase> remember() {
        memory.remember(input.name(), input.position());
        return Next.done(TaskResult.builder(TaskResult.Status.DONE,
                        "已把「" + input.name() + "」记在 " + describe(input.position()))
                .details(details())
                .build());
    }

    @Override protected ResultDetails details() {
        // 结果在这里一定已经写完；细节与 tick 里给出去的一致。
        return switch (input.operation()) {
            case REMEMBER -> RememberDetails.ofPlace(input.name(), "remember", input.position(), input.previous());
            case REGION -> {
                RememberedRegion before = input.previousRegion();
                yield new RememberDetails(input.name(), "region", input.position(), input.radius(),
                        before == null ? null : before.center(), before == null ? null : before.radiusBlocks(), null);
            }
            case FORGET -> new RememberDetails(input.name(), "forget", null, null, input.previous(), null,
                    RegionShape.of(input.previousRegion()));
        };
    }

    static String describe(WorldPosition position) {
        return "(" + position.x() + ", " + position.y() + ", " + position.z()
                + (position.dimension() == null ? "" : "，" + position.dimension()) + ")";
    }
}
