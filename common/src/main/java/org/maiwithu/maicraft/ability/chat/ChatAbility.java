// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.chat;

import org.maiwithu.maicraft.game.interaction.ChatDraftScreen;
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
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TaskFactories;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 说话能力：向游戏聊天发一句话，对全体玩家可见。
 * 完成以聊天栏出现自己那条（本地回显）为准；回显读不到就按已提交但没能确认收场。
 *
 * <p>以 / 开头的是游戏命令。角色像生存玩家一样玩：/msg、/home、/help 这类普通玩家命令照发；
 * /tp、/give、/gamemode 这类管理员命令默认不执行（开局直接拒绝，这是本角色自己的规矩，不是游戏的拦截），
 * 实例配置（所有者在配置文件里改，不经任何工具参数）放开后管理员命令也按命令发送，
 * 以角色自己的权限交给服务器裁决——能不能成，服务器说了算。/maicraft 永远留给人。
 * 哪条算管理员命令见 CommandKinds。读别人的聊天与听消息归感知侧，本能力只管发。
 */
public final class ChatAbility implements AbilityModule {

    private final SendsChatMessage sender;
    private final ReadsChatEcho echo;
    private final ChatDraftScreen drafts;
    /** 本实例是否放开管理员命令；来自所有者的实例配置，任何工具参数都改不了它。普通玩家命令不受它管。 */
    private final boolean allowGameCommands;
    /** 服务器的命令树：单人时认得出模组自带的管理员命令；拿不到时只按名单认。 */
    private final KnowsAdminCommands adminCommands;

    /** 没有命令树可问的一份：只按名单认管理员命令（离线测试、连远程服务器）。 */
    public ChatAbility(SendsChatMessage sender, ReadsChatEcho echo, ChatDraftScreen drafts, boolean allowGameCommands) {
        this(sender, echo, drafts, allowGameCommands, KnowsAdminCommands.NONE);
    }

    public ChatAbility(SendsChatMessage sender, ReadsChatEcho echo, ChatDraftScreen drafts, boolean allowGameCommands,
            KnowsAdminCommands adminCommands) {
        this.sender = sender;
        this.echo = echo;
        this.drafts = Objects.requireNonNull(drafts, "drafts");
        this.allowGameCommands = allowGameCommands;
        this.adminCommands = Objects.requireNonNull(adminCommands, "adminCommands");
    }

    @Override
    public AbilitySpec spec() {
        // 参数说明、能力说明与一句话用途都随实例而变：放开了就如实告知 LLM 命令会按命令发送，
        // 全量列表也看得到这一点；不放开时保持现行文案。
        String messageDoc = allowGameCommands
                ? "要发到游戏聊天或游戏命令栏的话，全体可见；以 / 开头的按游戏命令发，本实例连管理员命令也放行，以角色自己的权限执行"
                : "要发到游戏聊天的话，全体玩家可见；以 / 开头的按游戏命令发：/msg、/home、/help 这类普通玩家命令照发，"
                        + "/tp、/give、/gamemode 这类管理员命令角色不执行";
        String summary = allowGameCommands
                ? "向游戏聊天发一句话，或发一条游戏命令（对全体玩家可见；本实例连管理员命令也允许执行）"
                : "向游戏聊天发一句话，或发一条普通玩家命令（对全体玩家可见）";
        AbilityDoc doc = AbilityDoc.forAbility("chat");
        if (allowGameCommands) {
            doc = doc.withExtraNote("本实例连管理员命令也允许执行：`message` 以 `/` 开头时不论哪种命令都不拒绝"
                    + "（/maicraft 除外），以角色自己的权限交给服务器执行；上面「管理员命令角色不执行」"
                    + "的边界在本实例不适用。");
        }
        return new AbilitySpec(
                "maicraft:chat",
                summary,
                doc,
                ParamSpecs.of(
                        ParamSpec.of("message", ParamType.TEXT).required()
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
        // 命令的规矩放在决定阶段，判断只读、无副作用：/maicraft 留给人；管理员命令默认不碰，实例放开才发；
        // 普通玩家命令像生存玩家一样照发，能不能成由服务器裁决。
        if (message.startsWith("/") && CommandKinds.reserved(message)) {
            return new StepDecision.Finish(TaskResult.failed("没有把话发出去",
                    Problem.of(Problem.Kind.REFUSED_BY_GAME,
                            "/maicraft 是留给人的客户端命令，角色不能改自己的权限或 Mod 设置",
                            "要改 Mod 设置请人在游戏里敲")));
        }
        if (message.startsWith("/") && !allowGameCommands && CommandKinds.admin(message, adminCommands)) {
            return new StepDecision.Finish(TaskResult.failed("没有把话发出去",
                    Problem.of(Problem.Kind.REFUSED_BY_GAME,
                            "管理员命令角色不执行：/" + CommandKinds.commandName(message)
                                    + " 要管理员权限，本实例没放开",
                            "像生存玩家一样靠能力去做：去哪用 travel、要东西用 obtain、改世界用 build；"
                                    + "普通玩家命令（/msg、/home、/help 这类）照常能发")));
        }
        return new StepDecision.Run(new ChatInput(message));
    }

    @Override
    public void registerTasks(TaskFactories factories) {
        factories.register(ChatInput.class, input -> {
            // 每次运行新建发话任务；聊天框、发送入口与回显通过闭包交给任务，不经全局单例。
            return new ChatTask(input.message(), sender, echo, drafts);
        });
    }
}
