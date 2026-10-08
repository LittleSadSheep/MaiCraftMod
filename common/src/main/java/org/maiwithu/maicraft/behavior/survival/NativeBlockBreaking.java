// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.game.interaction.BlockDigger;
import org.maiwithu.maicraft.game.interaction.InteractionSender;
import org.maiwithu.maicraft.game.menu.MenuActions;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * {@link BlockBreaking} 的原生实现：每次刨出任务开一个挖掘器，把瞄准、选工具、持续挖和等确认
 * 都交给游戏接口层现成的挖掘基础代码，这里只换算本刻结果。
 *
 * <p>挖掘器第一次真正下手时才创建：创建它需要当刻的角色，而任务对象可能提前一刻就被造出来。 */
public final class NativeBlockBreaking implements BlockBreaking {

    private final InteractionSender sender;
    private final MenuActions menuActions;
    private BlockDigger digger;

    public NativeBlockBreaking(InteractionSender sender, MenuActions menuActions) {
        this.sender = sender;
        this.menuActions = menuActions;
    }

    @Override
    public ActionStatus dig(TickContext context, BlockPos cell) {
        if (digger == null) {
            PlayerContext player = context.player();
            digger = new BlockDigger(player.localPlayer(), sender, menuActions, player.input());
        }
        // 挖得动就推进；一格真挖开了才算进展。暂时够不着（NO_SHOT）不算失败也不算进展，
        // 由刨出任务按"多久没进展算卡住"统一收口。
        return switch (digger.digStep(context.player(), cell)) {
            case PROGRESSING -> ActionStatus.running();
            case BROKE_TARGET, BROKE_OCCLUDER -> ActionStatus.progressed();
            case NO_SHOT -> ActionStatus.running();
        };
    }

    @Override
    public void stop(TickContext context) {
        if (digger != null) {
            digger.cancel(context.player());
        }
    }

    @Override
    public String describe() {
        return digger == null ? "还没开始挖" : "正在挖 " + digger.current();
    }
}
