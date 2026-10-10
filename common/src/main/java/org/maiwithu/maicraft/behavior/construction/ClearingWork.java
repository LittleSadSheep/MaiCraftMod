// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.construction;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.behavior.approach.ApproachTarget;
import org.maiwithu.maicraft.behavior.permission.PermissionCheck;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 清障：要清的格从上往下，一格一格问许可、走过去、挖掉。受保护的格记下来不动，挖不动的记做不了；
 * 挖掉一格算一次真实进展。掉落物等角色自己捡，这里不管。
 */
final class ClearingWork extends ConstructionWork {

    private enum Step { APPROACH, DIG }

    private final Permissions permissions;
    private final Deque<PlannedCell> queue;
    private PlannedCell cell;
    private Step step;
    private BlockState before;

    ClearingWork(ConstructionServices services, ConstructionSite site, Records records, Permissions permissions, List<PlannedCell> cells) {
        super(services, site, records);
        this.permissions = permissions;
        this.queue = new ArrayDeque<>(cells);
    }

    @Override protected ActionStatus advance(TickContext context) {
        if (cell != null && step == Step.APPROACH) return dig();
        if (cell != null && step == Step.DIG) return settle();
        return nextCell();
    }

    // 挑下一格：没加载的跳过；受保护的、挖不动的记下来；都没问题就走过去。
    private ActionStatus nextCell() {
        while (!queue.isEmpty()) {
            cell = queue.poll();
            BlockPos pos = cell.pos();
            if (!services.site().loaded(pos)) continue;
            before = services.site().state(pos);
            if (before.isAir()) continue;
            if (services.site().unbreakable(pos)) {
                site.exclude(pos, CellEnding.IMPOSSIBLE, "挖不动（基岩或世界边界）");
                continue;
            }
            Optional<Problem> refused = services.guards().allows(PermissionCheck.WorldAction.DIG_BLOCK, pos, blockId(before));
            if (refused.isPresent()) {
                site.exclude(pos, CellEnding.PROTECTED, refused.get().message());
                continue;
            }
            if (services.digs() == null) {
                site.unreachable(pos, "挖掘没接上，清不了");
                continue;
            }
            step = Step.APPROACH;
            begin(services.close().toward(ApproachTarget.ofBlock(pos), permissions), "走到 " + pos.toShortString() + " 跟前");
            return ActionStatus.progressed();
        }
        cell = null;
        return ActionStatus.done();
    }

    private ActionStatus dig() {
        Optional<Action> digging = services.digs().dig(cell.pos());
        if (digging.isEmpty()) {
            site.unreachable(cell.pos(), "挖掘没接上，清不了");
            cell = null;
            return nextCell();
        }
        step = Step.DIG;
        begin(digging.get(), "挖掉 " + cell.pos().toShortString());
        return ActionStatus.progressed();
    }

    private ActionStatus settle() {
        records.change(new Change(Change.Kind.BLOCK_BROKEN, blockId(before), 1, "清障 " + cell.pos().toShortString()));
        site.cleared(cell);
        cell = null;
        return ActionStatus.progressed();
    }

    @Override protected ActionStatus failed(TickContext context, ActionStatus.Failed failure) {
        // 这一格走不到或挖不下去：记一笔，接着清别的；不让一格卡住整个工地。
        records.attempt("清掉 " + cell.pos().toShortString(), failure.problem().message());
        site.unreachable(cell.pos(), failure.problem().message());
        cell = null;
        return nextCell();
    }

    private static String blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }
}
