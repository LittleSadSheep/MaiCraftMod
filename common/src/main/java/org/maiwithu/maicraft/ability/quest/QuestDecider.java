// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.quest;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.ToIntFunction;

import org.maiwithu.maicraft.ability.quest.spi.QuestView;
import org.maiwithu.maicraft.kernel.goal.Question;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;

/**
 * 任务书动作的判断：参数形状、这一项现在的样子、身上的东西够不够，给出结论——
 * 已经满足就直接完成，条件不对就带着事实拒绝，选择奖励缺候选就向 LLM 提问，其余开出发送任务。
 * 只读给进来的条目与数量回答，不碰任务书接口，也不控制角色。
 */
final class QuestDecider {

    /** FTB 的对象编号：16 位十六进制、全零是"没有这个东西"。 */
    private static final String ID_SHAPE = "[0-9A-Fa-f]{16}";

    private QuestDecider() {}

    /** 编号统一成 FTB 的写法（大写十六进制）；候选编号不在其列，保持原样。 */
    static String normalizeId(String id) {
        return id.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * 参数凑不凑得到一起：不进世界就能发现的问题一次报全。
     * 返回问题列表；为空表示形状都对，可以往下读任务书。
     *
     * @param requirement submit、confirm 必填；claim 不接受
     * @param reward      claim 必填；submit、confirm 不接受
     * @param choice      只在 claim 时给
     */
    static List<String> shapeProblems(QuestInput.Operation operation, String questId,
            String requirement, String reward, String choice) {
        List<String> problems = new ArrayList<>();
        shapeOf("quest", questId, problems);
        switch (operation) {
            case SUBMIT, CONFIRM -> {
                if (requirement == null) {
                    problems.add("operation=" + operation.chinese() + " 要给 requirement（任务里那条要求的编号）");
                } else {
                    shapeOf("requirement", requirement, problems);
                }
                if (reward != null) {
                    problems.add("operation=" + operation.chinese() + " 不接受 reward，领奖才用 reward");
                }
            }
            case CLAIM -> {
                if (reward == null) {
                    problems.add("operation=claim 要给 reward（这个任务自己的根奖励编号）");
                } else {
                    shapeOf("reward", reward, problems);
                }
                if (requirement != null) {
                    problems.add("operation=claim 不接受 requirement，交物品和勾选才用 requirement");
                }
            }
        }
        if (choice != null && operation != QuestInput.Operation.CLAIM) {
            problems.add("choice 只在领奖（operation=claim）时给");
        }
        return problems;
    }

    /** 一个对象编号的形状：16 位十六进制；全零是"没有这个东西"，也当形状不对报出来。 */
    private static void shapeOf(String name, String id, List<String> problems) {
        if (!id.matches(ID_SHAPE)) {
            problems.add(name + " 要是 16 位十六进制的编号（任务书资料里给的），收到的是 " + id);
        } else if (Long.parseUnsignedLong(id, 16) == 0) {
            problems.add(name + " 不能是全零编号：全零表示没有这个东西");
        }
    }

    /**
     * 提交物品：这条要求得是交物品的，身上的东西得够；都齐了才开出发送任务。
     *
     * @param itemCounts 按物品 ID 数身上的件数；回答 -1 表示背包现在读不到，跳过够不够的检查
     */
    static StepDecision submit(QuestView view, String requirementId, ToIntFunction<String> itemCounts) {
        Optional<QuestView.Requirement> found = findRequirement(view, requirementId);
        if (found.isEmpty()) return requirementNotFound(view, requirementId);
        QuestView.Requirement requirement = found.get();
        if (requirement.completed()) {
            return alreadyDone(view, "这条要求开始时就已经完成，不用再交");
        }
        StepDecision locked = ifLocked(view);
        if (locked != null) return locked;
        if (!requirement.submittable()) {
            return refuse(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                    "「" + view.title() + "」的要求 " + requirement.id() + " 不是交物品的：这条要求"
                            + howCompleted(requirement)));
        }
        long missing = shortOf(requirement, itemCounts);
        if (missing > 0) {
            return refuse(Problem.of(Problem.Kind.NEED_ITEM,
                    "身上的东西还不够交：要 " + String.join(" 或 ", requirement.acceptedItems())
                            + " " + requirement.remaining() + " 件，身上还差 " + missing + " 件",
                    "先想办法把东西凑齐（例如 obtain）再来提交；交什么是你的决定，这里不自动去弄"));
        }
        return new StepDecision.Run(new QuestInput(QuestInput.Operation.SUBMIT, view.questId(), requirement.id(),
                null, null));
    }

    /** 手动勾选：只对手动勾选的要求；完成了就是已满足。 */
    static StepDecision confirm(QuestView view, String requirementId) {
        Optional<QuestView.Requirement> found = findRequirement(view, requirementId);
        if (found.isEmpty()) return requirementNotFound(view, requirementId);
        QuestView.Requirement requirement = found.get();
        if (requirement.completed()) {
            return alreadyDone(view, "这条要求开始时就已经完成，不用再勾");
        }
        StepDecision locked = ifLocked(view);
        if (locked != null) return locked;
        if (!requirement.manualCheck()) {
            return refuse(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                    "「" + view.title() + "」的要求 " + requirement.id() + " 不是手动勾选的：这条要求"
                            + howCompleted(requirement)));
        }
        return new StepDecision.Run(new QuestInput(QuestInput.Operation.CONFIRM, view.questId(), requirement.id(),
                null, null));
    }

    /**
     * 领奖：只领这个任务自己的根奖励；选择奖励要先选一样，没指明就问 LLM（候选编号在提问里给）。
     * 指明了（参数给的、或回答里认得出的）就照它领；认不上候选的按参数错误拒绝，把候选重新列出来。
     */
    static StepDecision claim(QuestView view, String rewardId, String choice, List<String> answers) {
        Optional<QuestView.Reward> found = view.rewards().stream()
                .filter(reward -> reward.id().equalsIgnoreCase(rewardId)).findFirst();
        if (found.isEmpty()) {
            if (view.nestedRewardIds().stream().anyMatch(nested -> nested.equalsIgnoreCase(rewardId))) {
                return refuse(Problem.of(Problem.Kind.INVALID_PARAMETER,
                        rewardId + " 是奖池里的子奖励，不是能单独领的奖励",
                        "领这份任务的根奖励（任务书资料条目页的奖励一栏里列的编号）"));
            }
            return refuse(Problem.of(Problem.Kind.NOT_FOUND,
                    "「" + view.title() + "」上没有这份可见的奖励：" + rewardId, null));
        }
        QuestView.Reward reward = found.get();
        if (reward.claimed()) {
            return new StepDecision.Finish(TaskResult.done(
                    "「" + view.title() + "」的奖励已经领过了（不能证明当时领的就是这次想选的）"));
        }
        StepDecision locked = ifLocked(view);
        if (locked != null) return locked;
        if (!reward.claimable()) {
            return refuse(Problem.of(Problem.Kind.REFUSED_BY_GAME,
                    "「" + view.title() + "」的要求还没都完成，奖励 " + reward.id() + " 现在领不了"));
        }
        String given = givenChoice(choice, answers);
        if (!reward.needsChoice()) {
            if (given != null) {
                return refuse(Problem.of(Problem.Kind.INVALID_PARAMETER,
                        "奖励 " + reward.id() + " 不是选择奖励，不用给 choice"));
            }
            return new StepDecision.Run(new QuestInput(QuestInput.Operation.CLAIM, view.questId(), null,
                    reward.id(), null));
        }
        if (given == null) {
            return askWhich(view, reward);
        }
        String picked = matchChoice(reward.choices(), given);
        if (picked == null) {
            return refuse(Problem.of(Problem.Kind.INVALID_PARAMETER,
                    "choice 只能是这份奖励的候选编号之一，收到的是 " + given,
                    "从这里挑一个：" + choicesText(reward.choices())));
        }
        return new StepDecision.Run(new QuestInput(QuestInput.Operation.CLAIM, view.questId(), null,
                reward.id(), picked));
    }

    /** 选择奖励没指明候选：列出候选问 LLM，Mod 不替它选。 */
    private static StepDecision askWhich(QuestView view, QuestView.Reward reward) {
        List<Question.Option> options = new ArrayList<>();
        for (QuestView.Choice candidate : reward.choices()) {
            options.add(new Question.Option(candidate.id(), candidate.description()));
        }
        return new StepDecision.Ask(new Question(Question.Reason.CHOOSE_ONE,
                "「" + view.title() + "」的奖励 " + reward.id() + " 是选择奖励，领哪一样？回答候选编号。",
                options));
    }

    /** 这次按哪个候选：参数给的优先，没给就看最近一次回答。都没有为 null。 */
    private static String givenChoice(String choice, List<String> answers) {
        if (choice != null) return choice.trim();
        return answers.isEmpty() ? null : answers.get(answers.size() - 1).trim();
    }

    /** 按候选编号或候选说明认出选的是哪一个；认不上为 null。 */
    private static String matchChoice(List<QuestView.Choice> choices, String given) {
        for (QuestView.Choice candidate : choices) {
            if (candidate.id().equals(given) || candidate.description().equals(given)) {
                return candidate.id();
            }
        }
        return null;
    }

    private static String choicesText(List<QuestView.Choice> choices) {
        List<String> parts = new ArrayList<>();
        for (QuestView.Choice candidate : choices) {
            parts.add(candidate.id() + "=" + candidate.description());
        }
        return String.join("、", parts);
    }

    private static Optional<QuestView.Requirement> findRequirement(QuestView view, String requirementId) {
        return view.requirements().stream()
                .filter(requirement -> requirement.id().equalsIgnoreCase(requirementId)).findFirst();
    }

    private static StepDecision requirementNotFound(QuestView view, String requirementId) {
        return refuse(Problem.of(Problem.Kind.NOT_FOUND,
                "「" + view.title() + "」上没有这条要求：" + requirementId
                        + "（条目页的要求一栏里列的是它的要求编号）", null));
    }

    private static StepDecision alreadyDone(QuestView view, String why) {
        return new StepDecision.Finish(TaskResult.done("「" + view.title() + "」：" + why));
    }

    /** 条目还不能开始：任务书自己会拒绝，先把原因带回去，不白发请求。 */
    private static StepDecision ifLocked(QuestView view) {
        if (view.canStart()) return null;
        return refuse(Problem.of(Problem.Kind.REFUSED_BY_GAME,
                "「" + view.title() + "」还不能开始：" + view.lockedReason(), null));
    }

    private static StepDecision refuse(Problem problem) {
        return new StepDecision.Finish(TaskResult.failed("任务书动作没有做成", problem));
    }

    /** 身上还差几件：数接受的几种物品加起来；背包读不到（-1）就不下结论。 */
    private static long shortOf(QuestView.Requirement requirement, ToIntFunction<String> itemCounts) {
        long have = 0;
        boolean known = true;
        for (String itemId : requirement.acceptedItems()) {
            int count = itemCounts.applyAsInt(itemId);
            if (count < 0) known = false;
            else have += count;
        }
        return known ? Math.max(0, requirement.remaining() - have) : 0;
    }

    /** 这条要求怎么完成的一句话，拒绝时把出路写清楚。 */
    private static String howCompleted(QuestView.Requirement requirement) {
        return requirement.howCompleted().isBlank() ? "不由提交完成" : "是这样完成的：" + requirement.howCompleted();
    }
}
