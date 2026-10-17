// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.timer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.maiwithu.maicraft.kernel.ability.AbilityDoc;
import org.maiwithu.maicraft.kernel.ability.AbilityModule;
import org.maiwithu.maicraft.kernel.ability.AbilitySpec;
import org.maiwithu.maicraft.kernel.ability.ExecutionMode;
import org.maiwithu.maicraft.kernel.ability.Listing;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.param.ParamSpec;
import org.maiwithu.maicraft.kernel.param.ParamSpecs;
import org.maiwithu.maicraft.kernel.param.ParamType;
import org.maiwithu.maicraft.kernel.param.ParamValues;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TaskFactories;

/**
 * 计时能力：LLM 想"炉子烧十分钟再回来看"时，设一个"过 N 秒叫我"，附一句备注；不占身体，execute 当场返回，
 * 手上的主任务照常做，角色这期间可以干别的。到点在任务事件流里发一条 timer_due，events 等得到。
 *
 * <p>和 wait 分开：wait 是站在原地等一个条件，占着身体；这里只把一个时刻记进计时表，不成为主任务、不打断手上的事，
 * 放进按顺序做事的目标里，轮到它才设。秒数按游戏时间算，换世界、断线时没到点的清掉。
 */
public final class TimerAbility implements AbilityModule {

    /** 最长能设多久：一小时游戏时间（三个整天）。再久的事该记成地点或在聊天里说，不该压在计时上。 */
    static final int MAX_SECONDS = 3600;
    /** 备注最长多少字：一句话说清到时看什么、做什么；超了如实拒，不悄悄截断。 */
    static final int MAX_NOTE_CHARS = 200;

    private final Timers timers;

    public TimerAbility(Timers timers) {
        this.timers = Objects.requireNonNull(timers, "timers");
    }

    @Override
    public AbilitySpec spec() {
        return new AbilitySpec(
                "maicraft:timer",
                "不占身体的计时：过 N 秒（游戏时间）在事件里叫你一声并带回备注，角色照常干别的；不是红石或模组的计时器方块",
                AbilityDoc.forAbility("timer"),
                ParamSpecs.of(
                        ParamSpec.of("operation", ParamType.CHOICE).defaultValue("set")
                                .choices("set", "cancel", "list")
                                .doc("set 设一个计时（默认），cancel 按 timer_id 取消一个，list 看还挂着哪些").build(),
                        ParamSpec.of("after_seconds", ParamType.INTEGER).range(1, MAX_SECONDS)
                                .doc("只用于 set：过多少秒（游戏时间，单人游戏暂停时不走）叫你").build(),
                        ParamSpec.of("note", ParamType.TEXT)
                                .doc("只用于 set：到点时原样带回的一句备注（最多 " + MAX_NOTE_CHARS
                                        + " 字），写清到时要看什么、做什么决定").build(),
                        ParamSpec.of("timer_id", ParamType.INTEGER).range(1, Integer.MAX_VALUE)
                                .doc("只用于 cancel：要取消的计时编号（set 的结果里给的）").build()),
                Set.of(),
                ExecutionMode.MEMORY_ONLY,
                Set.of(),
                List.of(),
                Listing.LISTED);
    }

    @Override
    public StepDecision decide(StepContext step) {
        ParamValues params = step.goal().params();
        String operation = params.has("operation") ? params.text("operation") : "set";
        // 先把参数和操作对不上的地方一次说全：小模型常把 set 的参数带进 cancel，或者忘了写备注，一轮改完。
        List<String> wrong = mismatched(operation, params);
        if (!wrong.isEmpty()) {
            return new StepDecision.Finish(TaskResult.failed("计时的参数和操作对不上",
                    Problem.of(Problem.Kind.INVALID_PARAMETER, String.join("；", wrong),
                            "设计时写 after_seconds 和 note；取消写 operation=cancel 和 timer_id；看挂着哪些写 operation=list")));
        }
        return switch (operation) {
            case "cancel" -> new StepDecision.Run(TimerInput.cancel((int) params.integer("timer_id")));
            case "list" -> new StepDecision.Run(TimerInput.list());
            default -> new StepDecision.Run(TimerInput.set((int) params.integer("after_seconds"),
                    params.text("note").trim()));
        };
    }

    // 逐条列出参数和操作对不上的地方：该给的没给、不该给的给了、备注太长。纯函数。
    private static List<String> mismatched(String operation, ParamValues params) {
        List<String> wrong = new ArrayList<>();
        boolean set = "set".equals(operation);
        boolean cancel = "cancel".equals(operation);
        if (set && !params.has("after_seconds")) wrong.add("设计时要给 after_seconds");
        if (set && (!params.has("note") || params.text("note").isBlank())) wrong.add("设计时要给 note：到点时带回的一句备注");
        if (set && params.has("note") && params.text("note").trim().length() > MAX_NOTE_CHARS) {
            wrong.add("note 有 " + params.text("note").trim().length() + " 字，最多 " + MAX_NOTE_CHARS + " 字");
        }
        if (cancel && !params.has("timer_id")) wrong.add("取消要给 timer_id");
        if (!set && params.has("after_seconds")) wrong.add("after_seconds 只用于 set");
        if (!set && params.has("note")) wrong.add("note 只用于 set");
        if (!cancel && params.has("timer_id")) wrong.add("timer_id 只用于 cancel");
        return wrong;
    }

    @Override
    public void registerTasks(TaskFactories factories) {
        // 每次运行新建计时任务；计时表由启动侧建好递进来，客户端全程一份。
        factories.register(TimerInput.class, input -> new TimerTask(input, timers));
    }
}
