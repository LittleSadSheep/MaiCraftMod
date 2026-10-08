// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.chat;

import org.maiwithu.maicraft.kernel.ability.AbilityDoc;
import org.maiwithu.maicraft.kernel.ability.AbilityModule;
import org.maiwithu.maicraft.kernel.ability.AbilitySpec;
import org.maiwithu.maicraft.kernel.ability.ExecutionMode;
import org.maiwithu.maicraft.kernel.ability.Listing;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.param.Param;
import org.maiwithu.maicraft.kernel.param.ParamSpec;
import org.maiwithu.maicraft.kernel.param.ParamType;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TaskFactories;

import java.util.List;
import java.util.Set;

/**
 * 说话能力：向游戏聊天发一句话，对全体玩家可见。
 * 完成以聊天栏出现自己那条（本地回显）为准；回显读不到就按已提交但没能确认收场。
 *
 * <p>角色不执行游戏命令：以 / 开头的是给游戏的命令，不是聊天，开局直接拒绝；
 * 这是本角色自己的规矩，不是游戏的拦截，所以不提交、也不做变通。
 * 读别人的聊天与听消息归感知侧，本能力只管发。
 */
public final class ChatAbility implements AbilityModule {

    private final SendsChatMessage sender;
    private final ReadsChatEcho echo;

    public ChatAbility(SendsChatMessage sender, ReadsChatEcho echo) {
        this.sender = sender;
        this.echo = echo;
    }

    @Override
    public AbilitySpec spec() {
        return new AbilitySpec(
                "maicraft:chat",
                "向游戏聊天发一句话（对全体玩家可见）",
                AbilityDoc.forAbility("chat"),
                ParamSpec.of(
                        Param.of("message", ParamType.TEXT).required()
                                .doc("要发到游戏聊天的话，全体玩家可见；以 / 开头的是游戏命令，角色不执行").build()),
                Set.of(),
                ExecutionMode.CONTROLS_PLAYER,
                Set.of(),
                List.of(),
                Listing.LISTED);
    }

    @Override
    public StepDecision decide(StepContext step) {
        String message = step.goal().params().text("message");
        if (message.startsWith("/")) {
            // 指令禁令：普通聊天照常，命令一律不碰。判断只读、无副作用，放在决定阶段。
            return new StepDecision.Finish(TaskResult.failed("没有把话发出去",
                    Problem.of(Problem.Kind.REFUSED_BY_GAME,
                            "角色不执行游戏命令：以 / 开头的是给游戏的命令，不是聊天",
                            "把要说的话直接作为 message 发，不要以斜杠开头")));
        }
        return new StepDecision.Run(new ChatInput(message));
    }

    @Override
    public void registerTasks(TaskFactories factories) {
        factories.register(ChatInput.class, input -> {
            // 每次运行新建发话任务；发送入口与回显通过闭包交给任务，不经全局单例。
            return new ChatTask(input.message(), sender, echo);
        });
    }
}
