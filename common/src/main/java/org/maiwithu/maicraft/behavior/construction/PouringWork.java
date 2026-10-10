// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.construction;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.behavior.approach.ApproachTarget;
import org.maiwithu.maicraft.behavior.interaction.InteractionResult;
import org.maiwithu.maicraft.behavior.interaction.InteractionVerdict;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 倒桶：实心格都放完后处理流体格。先用空桶收走声明范围内不该在的源液体，再逐格倒桶；
 * 倒进去变成了别的东西（黑曜石、圆石）如实记变化，不判失败。源格被原版自补的跳过。
 */
final class PouringWork extends ConstructionWork {

    private static final String EMPTY_BUCKET = "minecraft:bucket";

    private enum Step { HOLD, APPROACH, CLICK }

    private final Permissions permissions;
    private final Deque<BlockPos> strays;
    private final Deque<PlannedCell> pours;
    private PlannedCell cell;
    private BlockPos stray;
    private Step step;
    private BlockState before;
    private ConstructionSeams.Clicks.Click click;

    PouringWork(ConstructionServices services, ConstructionSite site, Records records, Permissions permissions,
                List<BlockPos> strays, List<PlannedCell> pours) {
        super(services, site, records);
        this.permissions = permissions;
        this.strays = new ArrayDeque<>(strays);
        this.pours = new ArrayDeque<>(pours);
    }

    @Override protected ActionStatus advance(TickContext context) {
        if (stray != null || cell != null) {
            return switch (step) {
                case HOLD -> approach();
                case APPROACH -> clickNow();
                case CLICK -> settle();
            };
        }
        return next();
    }

    // 先收不该在的源液体，再倒；要倒的格已经是源液体（原版自补了）就跳过。
    private ActionStatus next() {
        while (!strays.isEmpty()) {
            stray = strays.poll();
            if (!services.site().loaded(stray) || !services.site().state(stray).getFluidState().isSource()) continue;
            if (!services.site().creative() && services.site().carried(EMPTY_BUCKET) <= 0) {
                site.problem("没有空桶收不走源液体", stray);
                continue;
            }
            return hold(EMPTY_BUCKET);
        }
        stray = null;
        while (!pours.isEmpty()) {
            cell = pours.poll();
            if (!site.needsWork(cell.pos()) || !services.site().loaded(cell.pos())) continue;
            if (services.site().state(cell.pos()).getFluidState().isSource()) {
                site.poured(cell);
                continue;
            }
            String bucket = BuiltInRegistries.ITEM.getKey(cell.item()).toString();
            if (!services.site().creative() && services.site().carried(bucket) <= 0) {
                site.problem("缺 " + bucket, cell.pos());
                continue;
            }
            return hold(bucket);
        }
        cell = null;
        return ActionStatus.done();
    }

    private ActionStatus hold(String itemId) {
        return switch (services.hand().hold(itemId)) {
            case ConstructionSeams.HoldPlan.Ready ready -> approach();
            case ConstructionSeams.HoldPlan.Move move -> {
                step = Step.HOLD;
                begin(move.action(), "把 " + itemId + " 换到手上");
                yield ActionStatus.progressed();
            }
            case ConstructionSeams.HoldPlan.NotCarried notCarried -> {
                site.problem("缺 " + itemId, target());
                stray = null;
                cell = null;
                yield next();
            }
            case ConstructionSeams.HoldPlan.Cannot cannot -> {
                records.attempt("把 " + itemId + " 换到手上", cannot.problem().message());
                stray = null;
                cell = null;
                yield next();
            }
        };
    }

    private ActionStatus approach() {
        step = Step.APPROACH;
        begin(services.close().toward(ApproachTarget.ofBlock(target()), permissions), "走到 " + target().toShortString() + " 跟前");
        return ActionStatus.progressed();
    }

    private ActionStatus clickNow() {
        BlockPos pos = target();
        before = services.site().state(pos);
        InteractionConfirmation confirmation = InteractionConfirmation.blockChanged(pos, before);
        click = stray != null ? services.clicks().scoop(pos, confirmation) : services.clicks().pour(cell, confirmation);
        step = Step.CLICK;
        begin(click.action(), (stray != null ? "收走 " : "倒进 ") + pos.toShortString());
        return ActionStatus.progressed();
    }

    private ActionStatus settle() {
        InteractionResult result = click.result() == null ? null : click.result().get();
        BlockPos pos = target();
        String what = stray != null ? EMPTY_BUCKET : BuiltInRegistries.ITEM.getKey(cell.item()).toString();
        if (result != null && result.verdict() == InteractionVerdict.APPLIED) {
            BlockState now = services.site().state(pos);
            records.change(new Change(Change.Kind.BLOCK_CHANGED, BuiltInRegistries.BLOCK.getKey(now.getBlock()).toString(), 1,
                    (stray != null ? "收走了 " : "倒进了 ") + pos.toShortString() + "，现在是 " + now.getBlock().getName().getString()));
            records.change(new Change(Change.Kind.ITEM_CONSUMED, what, 1, null));
            if (cell != null) site.poured(cell);
        } else if (result == null || result.verdict() == InteractionVerdict.NOT_APPLIED) {
            records.attempt((stray != null ? "收走 " : "倒进 ") + pos.toShortString(), result == null ? "没有结论" : "没生效：" + result.scene());
            if (cell != null) site.problem("倒不进去", pos);
        } else {
            records.unconfirmed(new Change(Change.Kind.ITEM_CONSUMED, what, 1, pos.toShortString() + "：" + result.scene()));
            if (cell != null) site.problem("倒桶结果没能确认", pos);
        }
        stray = null;
        cell = null;
        return next();
    }

    private BlockPos target() {
        return stray != null ? stray : cell.pos();
    }

    @Override protected ActionStatus failed(TickContext context, ActionStatus.Failed failure) {
        records.attempt(describe(), failure.problem().message());
        if (cell != null) site.problem(failure.problem().message(), cell.pos());
        stray = null;
        cell = null;
        return next();
    }
}
