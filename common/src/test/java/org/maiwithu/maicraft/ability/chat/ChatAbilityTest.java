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
import org.maiwithu.maicraft.kernel.task.TickResult;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 说话能力：普通聊天照常发；游戏命令默认不执行、实例配置放开后放行；完成以本地回显为准。 */
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
    void slashCommandIsRefusedWithoutRunningAnything() {
        StepDecision.Finish finish = assertInstanceOf(StepDecision.Finish.class,
                new ChatAbility(message -> true, SILENT_ECHO, NO_BOX, false).decide(step(goal("/give @s diamond"))));

        assertEquals(TaskResult.Status.FAILED, finish.result().status());
        Problem problem = finish.result().problem();
        assertEquals(Problem.Kind.REFUSED_BY_GAME, problem.kind());
        // 禁令要说清是角色不执行游戏命令，不是游戏拦的。
        assertTrue(problem.message().contains("角色不执行游戏命令"), problem.message());
    }

    @Test
    void slashCommandRunsWhenInstanceAllowsIt() {
        // 实例配置放开后，以 / 开头的消息不再拒绝，照常开出发话任务；命令能不能成由服务器裁决。
        StepDecision.Run run = assertInstanceOf(StepDecision.Run.class,
                new ChatAbility(message -> true, SILENT_ECHO, NO_BOX, true).decide(step(goal("/give @s diamond"))));
        assertEquals("/give @s diamond", assertInstanceOf(ChatInput.class, run.input()).message());
    }

    @Test
    void docTellsCommandPolicyAccordingToInstance() {
        // 默认：说明写明角色不执行游戏命令；放开：说明与参数说明都如实写本实例允许执行游戏命令。
        ChatAbility closed = new ChatAbility(message -> true, SILENT_ECHO, NO_BOX, false);
        assertTrue(closed.spec().doc().load().contains("角色不执行游戏命令"));
        assertFalse(closed.spec().doc().load().contains("本实例允许执行游戏命令"));

        ChatAbility open = new ChatAbility(message -> true, SILENT_ECHO, NO_BOX, true);
        assertTrue(open.spec().doc().load().contains("本实例允许执行游戏命令"),
                open.spec().doc().load());
        // 放开说明要进全量列表：一句话用途里也带这一句，不能只藏在 lookup 详情里。
        assertTrue(open.spec().summary().contains("本实例允许执行游戏命令"), open.spec().summary());
        assertFalse(closed.spec().summary().contains("游戏命令"), closed.spec().summary());
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
