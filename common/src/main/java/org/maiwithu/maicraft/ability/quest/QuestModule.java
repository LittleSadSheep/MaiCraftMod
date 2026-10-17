// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.quest;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.ToIntFunction;

import org.maiwithu.maicraft.ability.quest.spi.QuestBookOperations;
import org.maiwithu.maicraft.ability.quest.spi.QuestBookStatus;
import org.maiwithu.maicraft.ability.quest.spi.QuestView;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.kernel.ability.AbilityDoc;
import org.maiwithu.maicraft.kernel.ability.AbilityModule;
import org.maiwithu.maicraft.kernel.ability.AbilitySpec;
import org.maiwithu.maicraft.kernel.ability.ExecutionMode;
import org.maiwithu.maicraft.kernel.ability.Listing;
import org.maiwithu.maicraft.kernel.ability.RequiredMod;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.param.ParamSpec;
import org.maiwithu.maicraft.kernel.param.ParamSpecs;
import org.maiwithu.maicraft.kernel.param.ParamType;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TaskFactories;

/**
 * 任务书能力：对 FTB 任务书里的一个条目做一次原生按钮动作——交物品、手动勾选、领奖。
 * 每一步先经联动登记表交来的任务书操作读这一项现在的样子：已经满足的直接完成，
 * 条件不对的带着事实拒绝，选择奖励缺候选就问 LLM；其余交给任务发一次请求、逐刻看反应。
 * 交什么、领哪个是 LLM 的决定，这里不自动去弄东西，也不替它选奖励。
 */
public final class QuestModule implements AbilityModule {

    /** 本能力需要的联动模组：FTB 任务没装，能力就不登记、不出现在能力清单里。 */
    static final String REQUIRED_MOD = "ftbquests";

    /** 登记表交来的任务书操作；联动模组实现，通常只有 FTB 任务一家。 */
    private final List<QuestBookOperations> books;

    public QuestModule(List<QuestBookOperations> books) {
        this.books = List.copyOf(books);
    }

    @Override
    public AbilitySpec spec() {
        // 任务书不在世界里，不接受目标对象；编号都从 lookup 的任务书资料里拿。
        return new AbilitySpec(
                "maicraft:quest",
                "对 FTB 任务书里的一个条目做一次动作：交物品、勾选、领奖",
                AbilityDoc.forAbility("quest"),
                ParamSpecs.of(
                        ParamSpec.of("operation", ParamType.CHOICE).required()
                                .choices("submit", "confirm", "claim")
                                .doc("submit 交物品 / confirm 手动勾选 / claim 领奖").build(),
                        ParamSpec.of("quest", ParamType.TEXT).required()
                                .doc("任务书里任务的编号（16 位十六进制，lookup 的任务书资料里给的）").build(),
                        ParamSpec.of("requirement", ParamType.TEXT)
                                .doc("任务里那条要求的编号；submit、confirm 必填").build(),
                        ParamSpec.of("reward", ParamType.TEXT)
                                .doc("本任务的根奖励编号；claim 必填").build(),
                        ParamSpec.of("choice", ParamType.TEXT)
                                .doc("领选择奖励时选哪个候选（Mod 提问时列出的候选编号）").build()),
                Set.of(),
                ExecutionMode.CONTROLS_PLAYER,
                Set.of(RequiredMod.of(REQUIRED_MOD)),
                List.of(),
                Listing.LISTED);
    }

    @Override
    public StepDecision decide(StepContext step) {
        QuestInput.Operation operation = QuestInput.Operation.valueOf(
                step.goal().params().text("operation").trim().toUpperCase(Locale.ROOT));
        String questId = QuestDecider.normalizeId(step.goal().params().text("quest"));
        String requirement = optionalId(step, "requirement");
        String reward = optionalId(step, "reward");
        String choice = optional(step, "choice");

        // 参数凑不到一起的不进世界：形状与搭配的问题一次报全。
        List<String> problems = QuestDecider.shapeProblems(operation, questId, requirement, reward, choice);
        if (!problems.isEmpty()) {
            String all = String.join("；", problems);
            return new StepDecision.Finish(TaskResult.failed("参数不对：" + all,
                    Problem.of(Problem.Kind.INVALID_PARAMETER, all, null)));
        }
        QuestBookOperations book = pickBook();
        if (book == null) {
            return new StepDecision.Finish(TaskResult.failed("任务书动作做不了",
                    Problem.of(Problem.Kind.UNSUPPORTED,
                            "任务书联动没有接上（FTB 任务装了，但它的联动没登记成功）")));
        }
        QuestBookStatus status = book.status();
        if (!status.usable()) {
            return new StepDecision.Finish(TaskResult.failed("任务书现在用不了",
                    Problem.of(Problem.Kind.UNSUPPORTED, status.reason())));
        }
        Optional<QuestView> view = book.quest(questId);
        if (view.isEmpty()) {
            return new StepDecision.Finish(TaskResult.failed("任务书里没有这个编号的可见条目：" + questId,
                    Problem.of(Problem.Kind.NOT_FOUND,
                            questId + " 对不上任何你看得见的条目：可能抄错了，也可能这条对你还不可见",
                            "用 lookup 的任务书资料核对编号")));
        }
        return switch (operation) {
            case SUBMIT -> QuestDecider.submit(view.get(), requirement, itemCounts(step));
            case CONFIRM -> QuestDecider.confirm(view.get(), requirement);
            case CLAIM -> QuestDecider.claim(view.get(), reward, choice, step.answers());
        };
    }

    /** 身上各物品的件数；背包读不到时回答 -1，判断就不下"不够"的结论。 */
    private static ToIntFunction<String> itemCounts(StepContext step) {
        BackpackView backpack = step.tick().player() == null ? null : step.tick().player().backpack();
        if (backpack == null) return itemId -> -1;
        return itemId -> backpack.stacks().stream()
                .filter(stack -> stack.itemId().equals(itemId))
                .mapToInt(stack -> stack.count()).sum();
    }

    /** 用哪一本任务书：先挑自己说能用的那本；都不说能用时拿第一本，把它的原因带回去。 */
    private QuestBookOperations pickBook() {
        for (QuestBookOperations book : books) {
            if (book.status().usable()) return book;
        }
        return books.isEmpty() ? null : books.get(0);
    }

    private static String optional(StepContext step, String name) {
        return step.goal().params().has(name) ? step.goal().params().text(name) : null;
    }

    /** 可选的编号参数：没给为 null，给了统一成大写。 */
    private static String optionalId(StepContext step, String name) {
        String value = optional(step, name);
        return value == null ? null : QuestDecider.normalizeId(value);
    }

    @Override
    public void registerTasks(TaskFactories factories) {
        // 每次运行新建一个发送任务；任务书操作通过闭包交给任务，不经全局单例。
        factories.register(QuestInput.class, input -> new QuestTask(input, pickBook()));
    }
}
