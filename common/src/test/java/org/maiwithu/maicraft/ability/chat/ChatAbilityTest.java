// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.chat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.goal.Goal;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.param.Params;
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

/** 说话能力：普通聊天照常发，游戏命令一律不执行；完成以本地回显为准。 */
class ChatAbilityTest {

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

    private static Params messageParam(String message) {
        ChatAbility ability = new ChatAbility(message2 -> {}, message2 -> false);
        ParseResult result = ability.spec().params()
                .parse(JsonParser.parseString("{\"message\":\"" + message + "\"}").getAsJsonObject());
        assertTrue(result.ok(), "参数应能解析：" + result.errors());
        return result.params();
    }

    private Goal goal(String message) {
        return new Goal("maicraft:chat", null, null, messageParam(message), null, List.of(), null);
    }

    @Test
    void specIsChatWithRequiredMessage() {
        ChatAbility ability = new ChatAbility(message -> {}, message -> false);
        assertEquals("maicraft:chat", ability.spec().id());
        assertFalse(ability.spec().params().parse(new JsonObject()).ok(),
                "缺 message 应报错");
    }

    @Test
    void normalMessageRunsChatTask() {
        StepDecision.Run run = assertInstanceOf(StepDecision.Run.class,
                new ChatAbility(message -> {}, message -> false).decide(step(goal("大家好"))));
        assertEquals("大家好", assertInstanceOf(ChatInput.class, run.input()).message());
    }

    @Test
    void slashCommandIsRefusedWithoutRunningAnything() {
        StepDecision.Finish finish = assertInstanceOf(StepDecision.Finish.class,
                new ChatAbility(message -> {}, message -> false).decide(step(goal("/give @s diamond"))));

        assertEquals(TaskResult.Status.FAILED, finish.result().status());
        Problem problem = finish.result().problem();
        assertEquals(Problem.Kind.REFUSED_BY_GAME, problem.kind());
        // 禁令要说清是角色不执行游戏命令，不是游戏拦的。
        assertTrue(problem.message().contains("角色不执行游戏命令"), problem.message());
    }

    @Test
    void factoryCreatesChatTask() {
        ChatAbility ability = new ChatAbility(message -> {}, message -> false);
        TaskFactories factories = new TaskFactories();
        ability.registerTasks(factories);
        Task task = factories.create(new ChatInput("早上好"));
        assertInstanceOf(ChatTask.class, task);
        assertTrue(new ChatInput("早上好").describe().contains("早上好"));
    }
}
