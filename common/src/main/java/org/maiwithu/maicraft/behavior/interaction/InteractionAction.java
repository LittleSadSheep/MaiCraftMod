// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.interaction;

import org.maiwithu.maicraft.game.interaction.Interaction;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 把一次游戏接口层的原生交互包成任务可以逐刻推进的动作：交互还在做就继续，做完了算完成，
 * 游戏没让它生效就把原因如实交给上层判断。
 *
 * <p>交互自身负责瞄准、出手与确认；本动作只做结果换算。失败时先让交互停手（撤回挖掘、松开使用），
 * 再把失败交出去，不留挂着等确认的旧操作。
 */
public final class InteractionAction implements Action {

    private final Interaction interaction;
    private final String description;

    public InteractionAction(Interaction interaction, String description) {
        this.interaction = interaction;
        this.description = description;
    }

    @Override
    public ActionStatus tick(TickContext context) {
        return switch (interaction.tick(context.player())) {
            case RUNNING -> ActionStatus.running();
            case DONE -> ActionStatus.done();
            case FAILED -> {
                // 失败先结清这次交互占着的确认与按键，别让下一个动作撞上还没结束的旧操作。
                interaction.stop(context.player());
                yield ActionStatus.failed(Problem.of(
                        Problem.Kind.REFUSED_BY_GAME, description + "没能生效：" + interaction.failReason()));
            }
        };
    }

    @Override
    public String describe() {
        return description;
    }
}
