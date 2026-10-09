// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.Optional;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.game.interaction.BlockDigger;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 挖方块执行接缝的读端：把一格"挖到它消失"包成逐刻推进的动作，
 * 挖掘本体交给游戏接口层现成的挖掘器（瞄准、选工具、持续挖、等确认）。
 *
 * <p>动作第一次推进时才建挖掘器：创建它需要当刻的角色，而动作对象可能提前一刻被造出来。
 * 中途挖开的是挡视线的遮挡物时不算完成，继续对着目标挖；目标格已经是空气就直接算做完。
 */
public final class ClientDigsBlocks implements DigsBlocks {

    @Override
    public Optional<Action> dig(BlockPos target) {
        return Optional.of(new DigCellAction(target));
    }

    /** 挖掉一格的动作：目标格确认变成空气才算完成。 */
    private static final class DigCellAction implements Action {
        private final BlockPos target;
        private BlockDigger digger;

        DigCellAction(BlockPos target) {
            this.target = target;
        }

        @Override
        public ActionStatus tick(TickContext context) {
            // 目标格已经是空气：这一格没了就是做完，不再对空气出手。
            var state = context.player().level().getBlockState(target);
            if (state.isAir()) {
                stop(context);
                return ActionStatus.done();
            }
            if (digger == null) {
                digger = new BlockDigger(context.player().localPlayer(),
                        context.player().interactionSender(), context.player().menuActions(),
                        context.player().input());
            }
            // 暂时够不着（NO_SHOT）不算失败也不算进展，由调用方按"多久没进展算卡住"统一收口。
            return switch (digger.digStep(context.player(), target)) {
                case PROGRESSING, BROKE_OCCLUDER, NO_SHOT -> ActionStatus.running();
                case BROKE_TARGET -> ActionStatus.done();
            };
        }

        @Override
        public String describe() {
            return digger == null ? "还没开始挖 " + target.toShortString() : "正在挖 " + target.toShortString();
        }

        // 结清这次挖掘占着的交互与按键；动作被换下时调用方不一定会给机会，这里尽力停手。
        private void stop(TickContext context) {
            if (digger != null) {
                digger.cancel(context.player());
                digger = null;
            }
        }
    }
}
