// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.construction;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import org.maiwithu.maicraft.behavior.approach.ApproachTarget;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 收回临时方块：先收站着够得着的，再沿自己的柱逐格下撤，每格拆前确认下一格落得稳；
 * 走不到或拆了会摔的留着记进结果；六百刻没有一块收回就停。施工结束、失败、取消都走这里。
 */
final class CleanupWork extends ConstructionWork {

    /** 多少刻没收回一块就不再试。 */
    static final long STALL_TICKS = 600;

    private enum Step { APPROACH, DIG }

    private final Permissions permissions;
    private final Deque<BlockPos> queue = new ArrayDeque<>();
    private final List<BlockPos> left = new ArrayList<>();
    private boolean planned;
    private BlockPos removing;
    private String removingBlock = "unknown";
    private Step step;
    private long lastRemovalTick = Long.MIN_VALUE;

    CleanupWork(ConstructionServices services, ConstructionSite site, Records records, Permissions permissions) {
        super(services, site, records);
        this.permissions = permissions;
    }

    /** 没收回的临时方块。 */
    List<BlockPos> left() {
        List<BlockPos> all = new ArrayList<>(left);
        all.addAll(queue);
        return all;
    }

    @Override protected ActionStatus advance(TickContext context) {
        if (!planned) plan(context);
        if (removing != null && step == Step.APPROACH) return dig();
        if (removing != null && step == Step.DIG) return removed(context);
        if (context.gameTick() - lastRemovalTick > STALL_TICKS) {
            records.attempt("收回临时方块", "六百刻没有收回一块，剩下的留着");
            return ActionStatus.done();
        }
        return nextBlock();
    }

    private void plan(TickContext context) {
        planned = true;
        lastRemovalTick = context.gameTick();
        Set<BlockPos> placed = new HashSet<>(services.ledger().temporaries());
        BlockPos feet = services.site().feet();
        queue.addAll(TemporaryBlocks.removalOrder(placed, feet == null ? BlockPos.ZERO : feet));
    }

    // 下一块：已经没了的直接销账；在自己脚下那一柱里的先看下一格落得稳；拆不了的留着。
    private ActionStatus nextBlock() {
        while (!queue.isEmpty()) {
            BlockPos pos = queue.poll();
            if (!services.site().loaded(pos)) {
                left.add(pos);
                continue;
            }
            if (!TemporaryBlocks.solid(services.site(), pos)) {
                services.ledger().temporaryRemoved(pos);
                continue;
            }
            BlockPos feet = services.site().feet();
            boolean underFeet = feet != null && pos.equals(feet.below());
            if (underFeet && !TemporaryBlocks.safeToDescend(services.site(), pos)) {
                records.attempt("拆脚下的临时方块 " + pos.toShortString(), "下面落不稳，留着");
                left.add(pos);
                continue;
            }
            if (services.digs() == null) {
                left.add(pos);
                continue;
            }
            removing = pos;
            removingBlock = BuiltInRegistries.BLOCK.getKey(services.site().state(pos).getBlock()).toString();
            if (underFeet) return dig();
            step = Step.APPROACH;
            begin(services.close().toward(ApproachTarget.ofBlock(pos), permissions), "走到临时方块 " + pos.toShortString() + " 跟前");
            return ActionStatus.progressed();
        }
        return ActionStatus.done();
    }

    private ActionStatus dig() {
        Optional<Action> digging = services.digs().dig(removing);
        if (digging.isEmpty()) {
            left.add(removing);
            removing = null;
            return nextBlock();
        }
        step = Step.DIG;
        begin(digging.get(), "收回临时方块 " + removing.toShortString());
        return ActionStatus.progressed();
    }

    private ActionStatus removed(TickContext context) {
        services.ledger().temporaryRemoved(removing);
        records.change(new Change(Change.Kind.BLOCK_BROKEN, removingBlock, 1, "收回临时方块 " + removing.toShortString()));
        lastRemovalTick = context.gameTick();
        removing = null;
        return ActionStatus.progressed();
    }

    @Override protected ActionStatus failed(TickContext context, ActionStatus.Failed failure) {
        records.attempt("收回临时方块 " + removing.toShortString(), failure.problem().message());
        left.add(removing);
        removing = null;
        return nextBlock();
    }
}
