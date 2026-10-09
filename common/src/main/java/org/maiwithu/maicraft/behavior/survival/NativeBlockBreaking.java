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
 * <p>只挖指定的那一格，不顺手拆别的格子：刨出时头就在那一格里，用不着拆遮挡。
 * 挖掘器第一次真正下手时才创建：创建它需要当刻的角色，而任务对象可能提前一刻就被造出来。
 */
public final class NativeBlockBreaking implements BlockBreaking {

    private final InteractionSender sender;
    private final MenuActions menuActions;
    private BlockDigger digger;
    private BlockPos cell;
    /** 最近一刻的角色上下文；收尾时还属于本刻就用它停手，过期了就把停手留到下一刻。 */
    private PlayerContext lastContext;

    public NativeBlockBreaking(InteractionSender sender, MenuActions menuActions) {
        this.sender = sender;
        this.menuActions = menuActions;
    }

    @Override
    public void aimAt(BlockPos target) {
        cell = target.immutable();
    }

    @Override
    public ActionStatus tick(TickContext context) {
        PlayerContext player = context.player();
        lastContext = player;
        if (cell == null) {
            return ActionStatus.running();
        }
        if (digger == null) {
            digger = new BlockDigger(player.localPlayer(), sender, menuActions, player.input());
        }
        // 挖得动就推进；一格真挖开了才算进展。暂时够不着（NO_SHOT）不算失败也不算进展，
        // 由刨出任务按"多久没进展算卡住"统一收口。
        return switch (digger.digTargetStep(player, cell)) {
            case PROGRESSING, NO_SHOT -> ActionStatus.running();
            case BROKE_TARGET, BROKE_OCCLUDER -> ActionStatus.progressed();
        };
    }

    @Override
    public void pause() {
        stopDigging();
    }

    @Override
    public void close() {
        stopDigging();
    }

    // 停手：结清这次挖掘占着的交互与按键；上下文已过期时由挖掘器把停挖留到下一刻。
    private void stopDigging() {
        if (digger != null) {
            digger.cancel(lastContext != null && lastContext.isCurrent() ? lastContext : null);
        }
    }

    @Override
    public String describe() {
        return digger == null || digger.current() == null ? "还没开始挖" : "正在挖 " + digger.current().toShortString();
    }
}
