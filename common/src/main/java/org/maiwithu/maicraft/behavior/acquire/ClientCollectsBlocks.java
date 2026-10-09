// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;
import org.maiwithu.maicraft.behavior.approach.InteractionTarget;
import org.maiwithu.maicraft.behavior.inventory.PicksUpDrops;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 把一格方块收进包的生产实现：靠近交给站位与靠近的模型，挖交给原生挖掘，
 * 捡掉落物交给"只捡这一下带出来的"那套走过去捡。
 *
 * <p>每一步都是这个动作手上的一个子动作：被打断时暂停手上那一步，收尾时一并收尾，
 * 挖到一半的挖掘、走到一半的路都不会悬着。
 */
public final class ClientCollectsBlocks implements CollectsBlocks {

    private final BringsPlayerClose close;
    private final DigsBlocks digs;
    private final PicksUpDrops drops;

    public ClientCollectsBlocks(BringsPlayerClose close, DigsBlocks digs, PicksUpDrops drops) {
        this.close = Objects.requireNonNull(close, "close");
        this.digs = Objects.requireNonNull(digs, "digs");
        this.drops = Objects.requireNonNull(drops, "drops");
    }

    @Override
    public Optional<Action> collect(BlockPos cell, Permissions permissions) {
        return Optional.of(new Collect(cell.immutable(), permissions));
    }

    /** 靠近 → 挖 → 捡，一步做完才走下一步。 */
    private final class Collect implements Action {

        private enum Stage { APPROACH, DIG, PICK_UP, DONE }

        private final BlockPos cell;
        private final Permissions permissions;
        private Stage stage = Stage.APPROACH;
        private Action step;
        private Set<Integer> dropsBefore = Set.of();

        Collect(BlockPos cell, Permissions permissions) {
            this.cell = cell;
            this.permissions = permissions;
        }

        @Override public ActionStatus tick(TickContext context) {
            if (stage == Stage.DONE) return ActionStatus.done();
            if (step == null) {
                Optional<Action> next = begin();
                if (next.isEmpty()) return advance();
                step = next.get();
            }
            ActionStatus status = step.tick(context);
            if (status instanceof ActionStatus.Running) return status;
            if (status instanceof ActionStatus.Failed failed) {
                return ActionStatus.failed(stage == Stage.APPROACH
                        ? Problem.of(Problem.Kind.UNREACHABLE,
                                "走不到 " + cell.toShortString() + " 跟前：" + failed.problem().message(), null)
                        : failed.problem());
            }
            return advance();
        }

        // 这一步要做的动作：靠近按任务许可；挖之前记下脚边原有的掉落物；捡只捡这一下新冒出来的。
        private Optional<Action> begin() {
            return switch (stage) {
                case APPROACH -> Optional.of(close.toward(InteractionTarget.ofBlock(cell), permissions));
                case DIG -> {
                    dropsBefore = drops.nearby();
                    yield digs.dig(cell);
                }
                case PICK_UP -> Optional.of(drops.pickUpNewSince(dropsBefore));
                case DONE -> Optional.empty();
            };
        }

        // 这一步做完（或这一步不用做）：收尾手上的子动作，换下一步。
        private ActionStatus advance() {
            if (stage == Stage.DIG && step == null) {
                return ActionStatus.failed(Problem.of(Problem.Kind.UNSUPPORTED, "挖方块的现场动作没接上", null));
            }
            closeStep();
            stage = Stage.values()[stage.ordinal() + 1];
            return stage == Stage.DONE ? ActionStatus.done() : ActionStatus.progressed();
        }

        private void closeStep() {
            if (step != null) {
                step.close();
                step = null;
            }
        }

        @Override public void pause() {
            if (step != null) step.pause();
        }

        @Override public void close() {
            closeStep();
        }

        @Override public Interruptibility interruptibility() {
            return step == null ? Interruptibility.BETWEEN_ACTIONS : step.interruptibility();
        }

        @Override public String describe() {
            return switch (stage) {
                case APPROACH -> "走到 " + cell.toShortString() + " 跟前";
                case DIG -> "挖 " + cell.toShortString();
                case PICK_UP -> "捡起 " + cell.toShortString() + " 掉出的东西";
                case DONE -> "收完了 " + cell.toShortString();
            };
        }
    }
}
