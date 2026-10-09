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
 * <p>角色默认不执行游戏命令：以 / 开头的是给游戏的命令，不是聊天，开局直接拒绝；
 * 这是本角色自己的规矩，不是游戏的拦截，所以不提交、也不做变通。
 * 实例配置（所有者在配置文件里改，不经任何工具参数）放开后，以 / 开头的消息按命令发送，
 * 以角色自己的权限交给服务器裁决——能不能成，服务器说了算。
 * 读别人的聊天与听消息归感知侧，本能力只管发。
 */
public final class ChatAbility implements AbilityModule {

    private final SendsChatMessage sender;
    private final ReadsChatEcho echo;
    /** 本实例是否允许执行游戏命令；来自所有者的实例配置，任何工具参数都改不了它。 */
    private final boolean allowGameCommands;

    public ChatAbility(SendsChatMessage sender, ReadsChatEcho echo, boolean allowGameCommands) {
        this.sender = sender;
        this.echo = echo;
        this.allowGameCommands = allowGameCommands;
    }

    @Override
    public AbilitySpec spec() {
        // 参数说明、能力说明与一句话用途都随实例而变：放开了就如实告知 LLM 命令会按命令发送，
        // 全量列表也看得到这一点；不放开时保持现行文案。
        String messageDoc = allowGameCommands
                ? "要发到游戏聊天或游戏命令栏的话，全体可见；以 / 开头的会作为游戏命令，以角色自己的权限执行"
                : "要发到游戏聊天的话，全体玩家可见；以 / 开头的是游戏命令，角色不执行";
        String summary = allowGameCommands
                ? "向游戏聊天发一句话（对全体玩家可见；本实例允许执行游戏命令）"
                : "向游戏聊天发一句话（对全体玩家可见）";
        AbilityDoc doc = AbilityDoc.forAbility("chat");
        if (allowGameCommands) {
            doc = doc.withExtraNote("本实例允许执行游戏命令：`message` 以 `/` 开头时不拒绝，"
                    + "作为游戏命令以角色自己的权限交给服务器执行；上面「角色不执行游戏命令」"
                    + "的边界在本实例不适用。命令的完成依据是聊天栏出现命令反馈行"
                    + "（原版命令没有自己那条回显），等不到反馈行按没能确认如实收场。");
        }
        return new AbilitySpec(
                "maicraft:chat",
                summary,
                doc,
                ParamSpec.of(
                        Param.of("message", ParamType.TEXT).required()
                                .doc(messageDoc).build()),
                Set.of(),
                ExecutionMode.CONTROLS_PLAYER,
                Set.of(),
                List.of(),
                Listing.LISTED);
    }

    @Override
    public StepDecision decide(StepContext step) {
        String message = step.goal().params().text("message");
        if (message.startsWith("/") && !allowGameCommands) {
            // 指令禁令（默认）：普通聊天照常，命令一律不碰。判断只读、无副作用，放在决定阶段。
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
