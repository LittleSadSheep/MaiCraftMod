// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.chat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.game.interaction.ChatDraftScreen;
import org.maiwithu.maicraft.kernel.goal.Goal;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.param.ParamValues;
import org.maiwithu.maicraft.kernel.param.ParseResult;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.task.TaskFactories;
import org.maiwithu.maicraft.kernel.task.TickContext;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 说话能力：普通聊天与普通玩家命令照常发；管理员命令默认不执行、实例配置放开后放行；完成以本地回显为准。 */
class ChatAbilityTest {
    /** 不发话、读不到任何回显的替身：能力测试只看决定与说明，不碰发送。 */
    private static final ReadsChatEcho SILENT_ECHO = new ReadsChatEcho() {
        @Override public boolean appearsInChat(String message) { return false; }
        @Override public long mark() { return 0; }
        @Override public List<String> shownSince(long mark) { return List.of(); }
    };

    /** 不开框的聊天框替身：能力测试只看决定与说明，不碰打字。 */
    private static final ChatDraftScreen NO_BOX = new ChatDraftScreen() {
        @Override public boolean show(String draft) { return false; }
        @Override public boolean showing() { return false; }
        @Override public void close() { }
    };

    /** 能决定阶段用的上下文：只有目标。 */
    private static StepContext step(Goal goal) {
        return new StepContext() {
            @Override public Goal goal() {
                return goal;
            }

            @Override public int stepIndex() {
                return 0;
            }

            @Override public TickContext tick() {
                throw new IllegalStateException("决定阶段不应碰到每刻上下文");
            }
        };
    }

    private static ParamValues messageParam(String message) {
        ChatAbility ability = new ChatAbility(message2 -> true, SILENT_ECHO, NO_BOX, false);
        ParseResult result = ability.spec().paramSpecs()
                .parse(JsonParser.parseString("{\"message\":\"" + message + "\"}").getAsJsonObject());
        assertTrue(result.ok(), "参数应能解析：" + result.errors());
        return result.params();
    }

    private Goal goal(String message) {
        return new Goal("maicraft:chat", null, null, messageParam(message), null, List.of(), null);
    }

    @Test
    void specIsChatWithRequiredMessage() {
        ChatAbility ability = new ChatAbility(message -> true, SILENT_ECHO, NO_BOX, false);
        assertEquals("maicraft:chat", ability.spec().id());
        assertFalse(ability.spec().paramSpecs().parse(new JsonObject()).ok(),
                "缺 message 应报错");
    }

    @Test
    void normalMessageRunsChatTask() {
        StepDecision.Run run = assertInstanceOf(StepDecision.Run.class,
                new ChatAbility(message -> true, SILENT_ECHO, NO_BOX, false).decide(step(goal("大家好"))));
        assertEquals("大家好", assertInstanceOf(ChatInput.class, run.input()).message());
    }

    @Test
    void adminCommandIsRefusedWithoutRunningAnything() {
        StepDecision.Finish finish = assertInstanceOf(StepDecision.Finish.class,
                new ChatAbility(message -> true, SILENT_ECHO, NO_BOX, false).decide(step(goal("/give @s diamond"))));
        assertEquals(TaskResult.Status.FAILED, finish.result().status());
        Problem problem = finish.result().problem();
        assertEquals(Problem.Kind.REFUSED_BY_GAME, problem.kind());
        // 禁令要说清是管理员命令角色不执行、不是游戏拦的，并告诉它普通命令照常能发。
        assertTrue(problem.message().contains("管理员命令角色不执行"), problem.message());
        assertTrue(problem.suggestion().contains("普通玩家命令"), problem.suggestion());
    }

    @Test
    void ordinaryPlayerCommandRunsByDefault() {
        // 像生存玩家一样：/msg、/home 这类普通玩家命令默认就发，不用实例配置放开。
        StepDecision.Run run = assertInstanceOf(StepDecision.Run.class,
                new ChatAbility(message -> true, SILENT_ECHO, NO_BOX, false).decide(step(goal("/msg Steve 你好"))));
        assertEquals("/msg Steve 你好", assertInstanceOf(ChatInput.class, run.input()).message());
    }

    @Test
    void serverCommandTreeDecidesModAdminCommands() {
        // 名单里没有的模组命令，单人时问内置服务器的命令树：它说普通玩家用不了，就当管理员命令拒绝。
        KnowsAdminCommands tree = name -> name.equals("ftbteams");
        StepDecision.Finish finish = assertInstanceOf(StepDecision.Finish.class,
                new ChatAbility(message -> true, SILENT_ECHO, NO_BOX, false, tree)
                        .decide(step(goal("/ftbteams create 队"))));
        assertEquals(Problem.Kind.REFUSED_BY_GAME, finish.result().problem().kind());
        assertInstanceOf(StepDecision.Run.class,
                new ChatAbility(message -> true, SILENT_ECHO, NO_BOX, false, tree).decide(step(goal("/home"))));
    }

    @Test
    void maicraftCommandStaysWithTheHumanEvenWhenOpened() {
        // /maicraft 是人的客户端命令：实例放开管理员命令也不发，角色不能改自己的权限。
        StepDecision.Finish finish = assertInstanceOf(StepDecision.Finish.class,
                new ChatAbility(message -> true, SILENT_ECHO, NO_BOX, true).decide(step(goal("/maicraft port 1"))));
        assertEquals(Problem.Kind.REFUSED_BY_GAME, finish.result().problem().kind());
        assertTrue(finish.result().problem().message().contains("/maicraft"), finish.result().problem().message());
    }

    @Test
    void adminCommandRunsWhenInstanceAllowsIt() {
        // 实例配置放开后，管理员命令也不再拒绝，照常开出发话任务；命令能不能成由服务器裁决。
        StepDecision.Run run = assertInstanceOf(StepDecision.Run.class,
                new ChatAbility(message -> true, SILENT_ECHO, NO_BOX, true).decide(step(goal("/give @s diamond"))));
        assertEquals("/give @s diamond", assertInstanceOf(ChatInput.class, run.input()).message());
    }

    @Test
    void docTellsCommandPolicyAccordingToInstance() {
        // 默认：说明写明普通命令照发、管理员命令角色不执行；放开：说明与参数说明都如实写本实例连管理员命令也允许。
        ChatAbility closed = new ChatAbility(message -> true, SILENT_ECHO, NO_BOX, false);
        assertTrue(closed.spec().doc().load().contains("管理员命令角色不执行"));
        assertTrue(closed.spec().doc().load().contains("普通玩家命令照发"));
        assertFalse(closed.spec().doc().load().contains("连管理员命令也允许"));
        ChatAbility open = new ChatAbility(message -> true, SILENT_ECHO, NO_BOX, true);
        assertTrue(open.spec().doc().load().contains("连管理员命令也允许"),
                open.spec().doc().load());
        // 放开说明要进全量列表：一句话用途里也带这一句，不能只藏在 lookup 详情里。
        assertTrue(open.spec().summary().contains("连管理员命令也允许"), open.spec().summary());
        assertFalse(closed.spec().summary().contains("管理员"), closed.spec().summary());
    }

    @Test
    void factoryCreatesChatTask() {
        ChatAbility ability = new ChatAbility(message -> true, SILENT_ECHO, NO_BOX, false);
        TaskFactories factories = new TaskFactories();
        ability.registerTasks(factories);
        Task task = factories.create(new ChatInput("早上好"));
        assertInstanceOf(ChatTask.class, task);
        assertTrue(new ChatInput("早上好").describe().contains("早上好"));
    }
}
