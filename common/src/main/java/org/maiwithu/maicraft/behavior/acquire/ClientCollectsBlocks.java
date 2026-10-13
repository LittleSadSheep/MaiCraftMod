// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;
import org.maiwithu.maicraft.behavior.approach.ApproachTarget;
import org.maiwithu.maicraft.behavior.interaction.spi.BreakAccelerator;
import org.maiwithu.maicraft.behavior.inventory.PicksUpDrops;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 把方块收进包的生产实现：靠近交给站位与靠近的模型，挖交给原生挖掘，
 * 捡掉落物交给"只捡这一下带出来的"那套走过去捡。一批相邻的格子一格一格靠近、挖掉，整批挖完才去捡——
 * 像真人砍一棵树、挖一片矿，不是挖一格捡一格；收一格就是一格的批。
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
        return collectBatch(List.of(cell), permissions);
    }

    @Override
    public Optional<Action> collectBatch(List<BlockPos> cells, Permissions permissions) {
        return collectBatch(cells, permissions, null);
    }

    @Override
    public Optional<Action> collectBatch(List<BlockPos> cells, Permissions permissions, Predicate<BlockPos> chainable) {
        if (cells.isEmpty()) return Optional.empty();
        return Optional.of(new Collect(cells.stream().map(BlockPos::immutable).toList(), permissions, chainable));
    }

    /** 每一格：靠近 → 挖（能连锁就连锁，连带挖掉的格跳过）；整批挖完 → 捡一次，一步做完才走下一步。 */
    private final class Collect implements Action {

        private enum Stage { APPROACH, DIG, PICK_UP, DONE }

        /** 一格挖了这么久（刻）还没挖下来就跳过它挖下一格：看不着、够不着的那一格不把整批卡住。 */
        private static final int DIG_GIVE_UP_TICKS = 20 * 15;

        private final List<BlockPos> cells;
        private final Permissions permissions;
        /** 连锁挖到的格能不能挖；为空时一格一格普通地挖。 */
        private final Predicate<BlockPos> chainable;
        private int index;
        private Stage stage = Stage.APPROACH;
        private Action step;
        /** 第一下动手前脚边原有的掉落物；整批只记一次，捡的时候只捡这之后新冒出来的。 */
        private Set<Integer> dropsBefore;
        /** 这一格已经挖了几刻；跳过了几格。 */
        private int digTicks;
        private int skipped;

        Collect(List<BlockPos> cells, Permissions permissions, Predicate<BlockPos> chainable) {
            this.cells = cells;
            this.permissions = permissions;
            this.chainable = chainable;
        }

        private BlockPos cell() {
            return cells.get(Math.min(index, cells.size() - 1));
        }

        @Override public ActionStatus tick(TickContext context) {
            if (stage == Stage.DONE) return ActionStatus.done();
            // 这一格已经没了（上一格连锁时一起挖掉了）：不走过去，接着下一格。
            if (step == null && stage == Stage.APPROACH && digs.gone(context, cell())) return skipGone();
            if (step == null) {
                Optional<Action> next = begin();
                if (next.isEmpty()) return advance();
                step = next.get();
            }
            ActionStatus status = step.tick(context);
            // 一格挖了十五秒还没下来（树冠里的原木看不着又开不出路、高处够不着）：跳过这一格挖下一格，
            // 整批挖完照样去捡。原先一直挖同一格，一分钟没进展整个拿东西失败，前面挖下的也没捡。
            if (status instanceof ActionStatus.Running && stage == Stage.DIG && ++digTicks > DIG_GIVE_UP_TICKS) {
                skipped++;
                return advance();
            }
            if (status instanceof ActionStatus.Running) return status;
            if (status instanceof ActionStatus.Failed failed) {
                return ActionStatus.failed(stage == Stage.APPROACH
                        ? Problem.of(Problem.Kind.UNREACHABLE,
                                "走不到 " + cell().toShortString() + " 跟前：" + failed.problem().message(), null)
                        : failed.problem());
            }
            return advance();
        }

        // 这一步要做的动作：靠近按任务许可；第一下挖之前记下脚边原有的掉落物；捡只捡这之后新冒出来的。
        private Optional<Action> begin() {
            return switch (stage) {
                case APPROACH -> Optional.of(close.toward(ApproachTarget.ofBlock(cell()), permissions));
                case DIG -> {
                    if (dropsBefore == null) dropsBefore = drops.nearby();
                    // 连锁时整条矿脉、整根树干一起挖掉，掉落物集中在这一格，整批挖完一起捡。
                    yield chainable == null ? digs.dig(cell())
                            : digs.digChained(cell(), null, BreakAccelerator.ChainShape.CONNECTED, chainable);
                }
                // 一下都没挖（每一格都被连锁或别人挖掉了）：没有这一下掉出来的东西要捡。
                case PICK_UP -> dropsBefore == null ? Optional.empty() : Optional.of(drops.pickUpNewSince(dropsBefore));
                case DONE -> Optional.empty();
            };
        }

        // 这一格已经空了：算作收完，挪到下一格；全批都没了就去捡。
        private ActionStatus skipGone() {
            index++;
            stage = index < cells.size() ? Stage.APPROACH : Stage.PICK_UP;
            return ActionStatus.progressed();
        }

        // 这一步做完（或这一步不用做）：收尾手上的子动作，换下一步——挖完一格还有下一格就去靠近下一格，
        // 整批挖完才去捡。
        private ActionStatus advance() {
            if (stage == Stage.DIG && step == null) {
                return ActionStatus.failed(Problem.of(Problem.Kind.UNSUPPORTED, "挖方块的现场动作没接上", null));
            }
            closeStep();
            digTicks = 0;
            stage = switch (stage) {
                case APPROACH -> Stage.DIG;
                case DIG -> {
                    index++;
                    yield index < cells.size() ? Stage.APPROACH : Stage.PICK_UP;
                }
                case PICK_UP, DONE -> Stage.DONE;
            };
            // 一格都没挖下来（每格都跳过了）：如实说挖不下来，不冒充收完。
            if (stage == Stage.PICK_UP && skipped >= cells.size()) {
                return ActionStatus.failed(Problem.of(Problem.Kind.STUCK,
                        "这 " + cells.size() + " 格每格挖了十五秒都没挖下来（看不着或够不着），换个地方挖", null));
            }
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
            String which = cells.size() == 1 ? cell().toShortString()
                    : cell().toShortString() + "（第 " + (Math.min(index, cells.size() - 1) + 1) + "/" + cells.size() + " 格）";
            return switch (stage) {
                case APPROACH -> "走到 " + which + " 跟前";
                case DIG -> "挖 " + which;
                case PICK_UP -> "捡起这 " + cells.size() + " 格掉出的东西";
                case DONE -> "收完了 " + cells.size() + " 格";
            };
        }
    }
}
