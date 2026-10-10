// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.quest;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.ability.quest.spi.QuestBookOperations;
import org.maiwithu.maicraft.ability.quest.spi.QuestBookStatus;
import org.maiwithu.maicraft.ability.quest.spi.QuestView;
import org.maiwithu.maicraft.game.interaction.InteractionSender;
import org.maiwithu.maicraft.game.menu.MenuActions;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.PlayerInput;
import org.maiwithu.maicraft.kernel.goal.Goal;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.goal.Question;
import org.maiwithu.maicraft.kernel.param.ParamValues;
import org.maiwithu.maicraft.kernel.param.ParseResult;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.task.TaskFactories;
import org.maiwithu.maicraft.kernel.task.TickContext;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 任务书能力的决定：形状一次报全，已满足直接完成，条件不对带事实拒绝，选择奖励缺候选就问。 */
class QuestModuleTest {

    private static final String QUEST = "1234567890ABCDEF";
    private static final String REQUIREMENT = "0102030405060708";
    private static final String REWARD = "1122334455667788";

    /** 任务书操作替身：状态、条目与发送结果都由测试摆，记下发了什么。 */
    private static final class StubBook implements QuestBookOperations {
        QuestBookStatus status = QuestBookStatus.ready();
        Optional<QuestView> view = Optional.empty();
        boolean sendResult = true;
        final List<String> sent = new ArrayList<>();

        @Override public QuestBookStatus status() {
            return status;
        }

        @Override public Optional<QuestView> quest(String questId) {
            return view;
        }

        @Override public boolean submit(String questId, String requirementId) {
            return record("submit " + questId + " " + requirementId);
        }

        @Override public boolean confirm(String questId, String requirementId) {
            return record("confirm " + questId + " " + requirementId);
        }

        @Override public boolean claim(String questId, String rewardId, String choice) {
            return record("claim " + questId + " " + rewardId + " " + choice);
        }

        private boolean record(String what) {
            sent.add(what);
            return sendResult;
        }
    }

    /** 只读背包的替身：给什么答什么。 */
    private record StubBackpack(List<BackpackStack> stacks) implements BackpackView {
        StubBackpack {
            stacks = List.copyOf(stacks);
        }

        @Override public int usedSlots() {
            return stacks.size();
        }

        @Override public int totalSlots() {
            return 36;
        }
    }

    /** 角色替身：只有背包有用。 */
    private static final class StubPlayer implements PlayerContext {
        final BackpackView backpack;

        StubPlayer(BackpackView backpack) {
            this.backpack = backpack;
        }

        @Override public BackpackView backpack() {
            return backpack;
        }

        @Override public LocalPlayer localPlayer() {
            return null;
        }

        @Override public ClientLevel level() {
            return null;
        }

        @Override public ClientPacketListener connection() {
            return null;
        }

        @Override public PlayerInput input() {
            throw new UnsupportedOperationException("任务书测试不碰输入");
        }

        @Override public InteractionSender interactionSender() {
            throw new UnsupportedOperationException("任务书测试不碰交互提交");
        }

        @Override public MenuActions menuActions() {
            throw new UnsupportedOperationException("任务书测试不碰界面操作");
        }

        @Override public long clientTick() {
            return 0;
        }

        @Override public boolean isCurrent() {
            return true;
        }

        @Override public boolean canInteractThisTick() {
            return true;
        }

        @Override public boolean tryClaimInteraction() {
            return true;
        }
    }

    /** 本刻上下文替身：刻号固定，角色由测试给。 */
    private record TestTick(PlayerContext player) implements TickContext {
        @Override public long gameTick() {
            return 1000;
        }
    }

    private final StubBook book = new StubBook();
    private final QuestModule ability = new QuestModule(List.of(book));

    private StepContext step(String paramsJson, List<String> answers, PlayerContext player) {
        ParseResult parsed = ability.spec().paramSpecs()
                .parse(JsonParser.parseString(paramsJson).getAsJsonObject());
        assertTrue(parsed.ok(), "参数应能解析：" + parsed.errors());
        Goal goal = new Goal("maicraft:quest", null, null, parsed.params(), null, List.of(), null);
        return new StepContext() {
            @Override public Goal goal() {
                return goal;
            }

            @Override public int stepIndex() {
                return 0;
            }

            @Override public TickContext tick() {
                return new TestTick(player);
            }

            @Override public List<String> answers() {
                return answers;
            }
        };
    }

    private TaskResult decideAndFinish(String paramsJson) {
        StepDecision decision = ability.decide(step(paramsJson, List.of(), null));
        return assertInstanceOf(StepDecision.Finish.class, decision).result();
    }

    /** 一个条目：一条交 8 块铁锭的要求、一份普通奖励。 */
    private static QuestView ironQuest() {
        return new QuestView(QUEST, "铁的基础", true, "",
                List.of(new QuestView.Requirement(REQUIREMENT, "item", false, true, false,
                        List.of("minecraft:iron_ingot"), 8, "把要交的物品交上去")),
                List.of(new QuestView.Reward(REWARD, false, true, List.of())),
                List.of());
    }

    private static PlayerContext playerWith(int ironIngots) {
        List<BackpackStack> stacks = new ArrayList<>();
        if (ironIngots > 0) {
            stacks.add(new BackpackStack("minecraft:iron_ingot", ironIngots, 64, false, false, false, false));
        }
        return new StubPlayer(new StubBackpack(stacks));
    }

    @Test
    void shapeProblemsAreReportedAllAtOnce() {
        TaskResult result = decideAndFinish(
                "{\"operation\":\"submit\",\"quest\":\"xyz\",\"reward\":\"" + REWARD + "\"}");
        assertEquals(TaskResult.Status.FAILED, result.status());
        Problem problem = result.problem();
        assertEquals(Problem.Kind.INVALID_PARAMETER, problem.kind());
        assertTrue(problem.message().contains("quest"), problem.message());
        assertTrue(problem.message().contains("requirement"), problem.message());
        assertTrue(problem.message().contains("reward"), problem.message());
    }

    @Test
    void allZeroIdAndWrongChoiceOperationAreRefused() {
        TaskResult zero = decideAndFinish("{\"operation\":\"claim\",\"quest\":\"" + QUEST + "\","
                + "\"reward\":\"0000000000000000\",\"choice\":\"1\"}");
        assertEquals(Problem.Kind.INVALID_PARAMETER, zero.problem().kind());
        assertTrue(zero.problem().message().contains("全零"), zero.problem().message());

        TaskResult choice = decideAndFinish(
                "{\"operation\":\"submit\",\"quest\":\"" + QUEST + "\",\"requirement\":\"" + REQUIREMENT
                        + "\",\"choice\":\"1\"}");
        assertEquals(Problem.Kind.INVALID_PARAMETER, choice.problem().kind());
        assertTrue(choice.problem().message().contains("choice"), choice.problem().message());
    }

    @Test
    void unusableBookSaysWhy() {
        book.status = QuestBookStatus.unusable("任务书还没从服务器同步过来");
        TaskResult result = decideAndFinish(
                "{\"operation\":\"confirm\",\"quest\":\"" + QUEST + "\",\"requirement\":\"" + REQUIREMENT + "\"}");
        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(Problem.Kind.UNSUPPORTED, result.problem().kind());
        assertTrue(result.problem().message().contains("同步"), result.problem().message());
    }

    @Test
    void unknownQuestIdIsNotFound() {
        book.view = Optional.empty();
        TaskResult result = decideAndFinish(
                "{\"operation\":\"claim\",\"quest\":\"" + QUEST + "\",\"reward\":\"" + REWARD + "\"}");
        assertEquals(Problem.Kind.NOT_FOUND, result.problem().kind());
        assertTrue(result.problem().message().contains(QUEST), result.problem().message());
    }

    @Test
    void completedRequirementFinishesWithoutSending() {
        book.view = Optional.of(new QuestView(QUEST, "铁的基础", true, "",
                List.of(new QuestView.Requirement(REQUIREMENT, "item", true, true, false,
                        List.of("minecraft:iron_ingot"), 0, "把要交的物品交上去")),
                List.of(), List.of()));
        TaskResult result = decideAndFinish(
                "{\"operation\":\"submit\",\"quest\":\"" + QUEST + "\",\"requirement\":\"" + REQUIREMENT + "\"}");
        assertEquals(TaskResult.Status.DONE, result.status());
        assertTrue(result.summary().contains("已经完成"), result.summary());
        assertTrue(book.sent.isEmpty(), "已满足就不发请求");
    }

    @Test
    void autoCompletingRequirementSaysHowItCompletes() {
        book.view = Optional.of(new QuestView(QUEST, "去下界", true, "",
                List.of(new QuestView.Requirement("0A0B0C0D0E0F0102", "dimension", false, false, false,
                        List.of(), 1, "到 minecraft:the_nether 这个维度")),
                List.of(), List.of()));
        TaskResult result = decideAndFinish(
                "{\"operation\":\"submit\",\"quest\":\"" + QUEST + "\",\"requirement\":\"0a0b0c0d0e0f0102\"}");
        assertEquals(Problem.Kind.NOT_POSSIBLE_HERE, result.problem().kind());
        assertTrue(result.problem().message().contains("the_nether"), result.problem().message());
        assertTrue(book.sent.isEmpty());
    }

    @Test
    void missingItemsReportWhatAndHowMany() {
        book.view = Optional.of(ironQuest());
        StepDecision decision = ability.decide(step(
                "{\"operation\":\"submit\",\"quest\":\"" + QUEST + "\",\"requirement\":\"" + REQUIREMENT.toLowerCase() + "\"}",
                List.of(), playerWith(3)));
        TaskResult result = assertInstanceOf(StepDecision.Finish.class, decision).result();
        assertEquals(Problem.Kind.NEED_ITEM, result.problem().kind());
        assertTrue(result.problem().message().contains("minecraft:iron_ingot"), result.problem().message());
        assertTrue(result.problem().message().contains("差 5"), result.problem().message());
        assertTrue(book.sent.isEmpty());
    }

    @Test
    void enoughItemsRunTheSubmitTaskAndNormalizeCase() {
        book.view = Optional.of(ironQuest());
        StepDecision decision = ability.decide(step(
                "{\"operation\":\"submit\",\"quest\":\"" + QUEST.toLowerCase() + "\",\"requirement\":\""
                        + REQUIREMENT.toLowerCase() + "\"}",
                List.of(), playerWith(8)));
        StepDecision.Run run = assertInstanceOf(StepDecision.Run.class, decision);
        QuestInput input = assertInstanceOf(QuestInput.class, run.input());
        assertEquals(QuestInput.Operation.SUBMIT, input.operation());
        assertEquals(QUEST, input.questId());
        assertEquals(REQUIREMENT, input.requirementId());
        assertTrue(input.describe().contains(REQUIREMENT));
    }

    @Test
    void lockedQuestSaysTheReasonInsteadOfSending() {
        book.view = Optional.of(new QuestView(QUEST, "被挡住的", false, "前置还没都完成",
                List.of(new QuestView.Requirement(REQUIREMENT, "item", false, true, false,
                        List.of("minecraft:iron_ingot"), 8, "把要交的物品交上去")),
                List.of(), List.of()));
        TaskResult result = decideAndFinish(
                "{\"operation\":\"submit\",\"quest\":\"" + QUEST + "\",\"requirement\":\"" + REQUIREMENT + "\"}");
        assertEquals(Problem.Kind.REFUSED_BY_GAME, result.problem().kind());
        assertTrue(result.problem().message().contains("前置"), result.problem().message());
        assertTrue(book.sent.isEmpty());
    }

    @Test
    void manualConfirmOnlyOnCheckmarkRequirements() {
        book.view = Optional.of(ironQuest());
        TaskResult notManual = decideAndFinish(
                "{\"operation\":\"confirm\",\"quest\":\"" + QUEST + "\",\"requirement\":\"" + REQUIREMENT + "\"}");
        assertEquals(Problem.Kind.NOT_POSSIBLE_HERE, notManual.problem().kind());
        assertTrue(notManual.problem().message().contains("勾选"), notManual.problem().message());

        book.view = Optional.of(new QuestView(QUEST, "点一下", true, "",
                List.of(new QuestView.Requirement("0F0E0D0C0B0A0908", "checkmark", false, true, true,
                        List.of(), 1, "在书里勾一下")),
                List.of(), List.of()));
        StepDecision decision = ability.decide(step(
                "{\"operation\":\"confirm\",\"quest\":\"" + QUEST + "\",\"requirement\":\"0f0e0d0c0b0a0908\"}",
                List.of(), null));
        assertInstanceOf(StepDecision.Run.class, decision);
    }

    @Test
    void claimedRewardFinishesWithoutSending() {
        book.view = Optional.of(new QuestView(QUEST, "铁的基础", true, "",
                List.of(), List.of(new QuestView.Reward(REWARD, true, false, List.of())), List.of()));
        TaskResult result = decideAndFinish(
                "{\"operation\":\"claim\",\"quest\":\"" + QUEST + "\",\"reward\":\"" + REWARD + "\"}");
        assertEquals(TaskResult.Status.DONE, result.status());
        assertTrue(result.summary().contains("领过"), result.summary());
        assertTrue(book.sent.isEmpty());
    }

    @Test
    void poolChildRewardIsNotClaimableDirectly() {
        book.view = Optional.of(new QuestView(QUEST, "有奖池的", true, "", List.of(),
                List.of(new QuestView.Reward(REWARD, false, true, List.of())),
                List.of("9999999999999999")));
        TaskResult result = decideAndFinish(
                "{\"operation\":\"claim\",\"quest\":\"" + QUEST + "\",\"reward\":\"9999999999999999\"}");
        assertEquals(Problem.Kind.INVALID_PARAMETER, result.problem().kind());
        assertTrue(result.problem().message().contains("奖池"), result.problem().message());
    }

    @Test
    void choiceRewardAsksAndAcceptsAnAnswerOrDescription() {
        book.view = Optional.of(new QuestView(QUEST, "挑一样", true, "", List.of(),
                List.of(new QuestView.Reward(REWARD, false, true,
                        List.of(new QuestView.Choice("1", "钻石 x3"), new QuestView.Choice("2", "铁锭 x16")))),
                List.of()));

        StepDecision ask = ability.decide(step(
                "{\"operation\":\"claim\",\"quest\":\"" + QUEST + "\",\"reward\":\"" + REWARD + "\"}",
                List.of(), null));
        Question question = assertInstanceOf(StepDecision.Ask.class, ask).question();
        assertEquals(Question.Reason.CHOOSE_ONE, question.reason());
        assertEquals(2, question.options().size());
        assertEquals("钻石 x3", question.options().get(0).meaning());

        // 回答候选编号或候选说明都认，输入统一成编号。
        for (String answer : List.of("2", "铁锭 x16")) {
            StepDecision decision = ability.decide(step(
                    "{\"operation\":\"claim\",\"quest\":\"" + QUEST + "\",\"reward\":\"" + REWARD + "\"}",
                    List.of(answer), null));
            QuestInput input = assertInstanceOf(QuestInput.class,
                    assertInstanceOf(StepDecision.Run.class, decision).input());
            assertEquals("2", input.choiceId());
        }
    }

    @Test
    void wrongChoiceIsRefusedWithTheCandidates() {
        book.view = Optional.of(new QuestView(QUEST, "挑一样", true, "", List.of(),
                List.of(new QuestView.Reward(REWARD, false, true,
                        List.of(new QuestView.Choice("1", "钻石 x3")))),
                List.of()));
        TaskResult result = decideAndFinish(
                "{\"operation\":\"claim\",\"quest\":\"" + QUEST + "\",\"reward\":\"" + REWARD
                        + "\",\"choice\":\"9\"}");
        assertEquals(Problem.Kind.INVALID_PARAMETER, result.problem().kind());
        assertTrue(result.problem().suggestion().contains("钻石 x3"), result.problem().suggestion());

        // 不是选择奖励的给了 choice 也拒。
        book.view = Optional.of(ironQuest());
        TaskResult plain = decideAndFinish(
                "{\"operation\":\"claim\",\"quest\":\"" + QUEST + "\",\"reward\":\"" + REWARD + "\",\"choice\":\"1\"}");
        assertEquals(Problem.Kind.INVALID_PARAMETER, plain.problem().kind());
        assertTrue(plain.problem().message().contains("不用给 choice"), plain.problem().message());
    }

    @Test
    void specNeedsFtbQuestsAndNoTarget() {
        assertEquals("maicraft:quest", ability.spec().id());
        assertTrue(ability.spec().targets().isEmpty(), "任务书不在世界里，不接受目标对象");
        assertTrue(ability.spec().requiredMods().stream().anyMatch(mod -> mod.modId().equals("ftbquests")));
        assertTrue(ability.spec().doc().load().contains("lookup"), ability.spec().doc().load());
        assertTrue(ability.spec().doc().load().contains("不替"), ability.spec().doc().load());
    }

    @Test
    void factoryCreatesTheTaskWithThePickedBook() {
        book.view = Optional.of(ironQuest());
        TaskFactories factories = new TaskFactories();
        ability.registerTasks(factories);
        Task task = factories.create(new QuestInput(QuestInput.Operation.SUBMIT, QUEST, REQUIREMENT, null, null));
        assertInstanceOf(QuestTask.class, task);
    }

    @Test
    void firstUsableBookWinsAmongSeveral() {
        StubBook broken = new StubBook();
        broken.status = QuestBookStatus.unusable("别家的读不了");
        QuestModule several = new QuestModule(List.of(broken, book));
        book.view = Optional.of(ironQuest());
        StepDecision decision = several.decide(step(
                "{\"operation\":\"claim\",\"quest\":\"" + QUEST + "\",\"reward\":\"" + REWARD + "\"}",
                List.of(), null));
        assertInstanceOf(StepDecision.Run.class, decision);
        assertEquals(List.of(), book.sent);
    }
}
