// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

import org.maiwithu.maicraft.game.interaction.PendingInteraction;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 选中快捷栏的一格：像真人滚一下滚轮，经角色的交互提交入口发原生的选中，等服务端确认才算选上。
 * 选中在等确认时只推进不重发；这一刻交互机会被占了就下一刻再来。
 */
public final class HotbarSelection implements Action {

    /** 等选中确认的期限（刻）；到点按没能确认收场。 */
    private static final int CONFIRM_TIMEOUT_TICKS = 40;

    private final int slot;
    private PendingInteraction pending;

    /** @param slot 快捷栏第几格（0–8） */
    public HotbarSelection(int slot) {
        if (slot < 0 || slot > 8) throw new IllegalArgumentException("快捷栏只有 0–8 格：" + slot);
        this.slot = slot;
    }

    @Override public ActionStatus tick(TickContext context) {
        PlayerContext player = context.player();
        if (player == null || player.localPlayer() == null) {
            return ActionStatus.failed(Problem.of(Problem.Kind.WRONG_TIME, "这一刻掌握不到角色，选不了快捷栏", null));
        }
        if (player.localPlayer().getInventory().selected == slot && pending == null) {
            return ActionStatus.done();
        }
        var sender = player.interactionSender();
        if (sender == null || !player.canInteractThisTick()) return ActionStatus.running();
        if (pending == null) {
            pending = sender.selectHotbar(player, slot, CONFIRM_TIMEOUT_TICKS);
        } else if (!pending.terminal()) {
            pending = sender.poll(player, pending);
        }
        if (!pending.terminal()) return ActionStatus.running();
        return pending.status() == PendingInteraction.Status.CONFIRMED_APPLIED
                ? ActionStatus.done()
                : ActionStatus.failed(Problem.of(Problem.Kind.STUCK, "选中快捷栏第 " + slot + " 格没能确认", null));
    }

    @Override public String describe() {
        return "选中快捷栏第 " + slot + " 格";
    }
}
