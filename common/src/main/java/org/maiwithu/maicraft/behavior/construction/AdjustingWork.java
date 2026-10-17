// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.construction;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.maiwithu.maicraft.behavior.approach.ApproachTarget;
import org.maiwithu.maicraft.behavior.interaction.InteractionVerdict;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 收尾时的小调整：作者点名了开关状态的门、活板门、栅栏门，放好后开关不对的，走过去按一次。
 * 只按一次，还不对就如实记在结果里；别的状态差异不在这里调。
 */
final class AdjustingWork extends ConstructionWork {

    private enum Step { APPROACH, CLICK }

    private final Permissions permissions;
    private final Deque<PlannedCell> queue;
    private PlannedCell cell;
    private Step step;
    private ConstructionSeams.Clicks.Click click;

    AdjustingWork(ConstructionServices services, ConstructionSite site, TaskRecords records, Permissions permissions, List<PlannedCell> doors) {
        super(services, site, records);
        this.permissions = permissions;
        this.queue = new ArrayDeque<>(doors);
    }

    /** 这一格是不是点名了开关状态、现在开关不对的门类方块。 */
    static boolean adjustableDoor(PlannedCell cell, BlockState live) {
        var block = cell.state().getBlock();
        boolean door = block instanceof DoorBlock || block instanceof TrapDoorBlock || block instanceof FenceGateBlock;
        return door && cell.required().contains("open") && live.is(block)
                && live.hasProperty(BlockStateProperties.OPEN)
                && !live.getValue(BlockStateProperties.OPEN).equals(cell.state().getValue(BlockStateProperties.OPEN));
    }

    @Override protected ActionStatus advance(TickContext context) {
        if (cell != null && step == Step.APPROACH) return clickNow();
        if (cell != null && step == Step.CLICK) return settle();
        if (queue.isEmpty()) return ActionStatus.done();
        cell = queue.poll();
        step = Step.APPROACH;
        begin(services.close().toward(ApproachTarget.ofBlock(cell.pos()), permissions), "走到 " + cell.pos().toShortString() + " 跟前");
        return ActionStatus.progressed();
    }

    private ActionStatus clickNow() {
        BlockState before = services.site().state(cell.pos());
        click = services.clicks().use(cell.pos(), InteractionConfirmation.blockChanged(cell.pos(), before));
        step = Step.CLICK;
        begin(click.action(), "开关 " + cell.pos().toShortString());
        return ActionStatus.progressed();
    }

    private ActionStatus settle() {
        var result = click.result() == null ? null : click.result().get();
        if (result != null && result.verdict() == InteractionVerdict.APPLIED) {
            records.change(new Change(Change.Kind.BLOCK_CHANGED, cell.state().getBlock().getName().getString(), 1,
                    "按了一下 " + cell.pos().toShortString() + " 的开关"));
        } else {
            records.attempt("开关 " + cell.pos().toShortString(), result == null ? "没有结论" : result.scene());
        }
        cell = null;
        return ActionStatus.progressed();
    }

    @Override protected ActionStatus failed(TickContext context, ActionStatus.Failed failure) {
        records.attempt("开关 " + cell.pos().toShortString(), failure.problem().message());
        cell = null;
        return advance(context);
    }
}
