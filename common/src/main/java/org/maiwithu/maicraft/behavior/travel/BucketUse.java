// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.behavior.approach.ApproachTarget;
import org.maiwithu.maicraft.behavior.construction.ConstructionSeams;
import org.maiwithu.maicraft.behavior.construction.ConstructionServices;
import org.maiwithu.maicraft.behavior.construction.PlannedCell;
import org.maiwithu.maicraft.behavior.interaction.InteractionResult;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 用一次桶：走到那格跟前 → 桶换到主手 → 朝那格倒（或舀）→ 等游戏确认。
 * 靠近、换手、出手都走施工引擎的接缝，浇筑自己不写第二份；出手的结论留给调用方看现场记账。
 * 身上没有要的那种桶按缺东西失败，不替调用方去弄。
 */
final class BucketUse implements Action {

    /** 倒出去还是舀起来。 */
    enum Kind { POUR, SCOOP }

    private enum Stage { APPROACH, HOLD, CLICK, DONE }

    private final ConstructionServices construction;
    private final Permissions permissions;
    private final Kind kind;
    private final BlockPos target;
    private final String bucketItem;
    /** 倒出去的流体源状态（舀时不用）。 */
    private final BlockState poured;
    private final InteractionConfirmation confirmation;
    private final String what;
    private Stage stage = Stage.APPROACH;
    private Action current;
    private ConstructionSeams.Clicks.Click click;
    private InteractionResult interaction;

    BucketUse(ConstructionServices construction, Permissions permissions, Kind kind, BlockPos target,
            String bucketItem, BlockState poured, InteractionConfirmation confirmation, String what) {
        this.construction = construction;
        this.permissions = permissions;
        this.kind = kind;
        this.target = target.immutable();
        this.bucketItem = bucketItem;
        this.poured = poured;
        this.confirmation = confirmation;
        this.what = what;
    }

    /** 出手后的结论；还没出手、或出手动作自己没给结论时为 null。 */
    InteractionResult interaction() {
        return interaction;
    }

    @Override
    public ActionStatus tick(TickContext context) {
        if (current == null) {
            ActionStatus prepared = prepare();
            if (prepared != null) return prepared;
        }
        ActionStatus status = current.tick(context);
        if (status instanceof ActionStatus.Running) return status;
        current.close();
        current = null;
        if (status instanceof ActionStatus.Failed failed) {
            return ActionStatus.failed(Problem.of(failed.problem().kind(),
                    what + "没做成：" + failed.problem().message(), failed.problem().suggestion()));
        }
        if (stage == Stage.CLICK) {
            interaction = click.result() == null ? null : click.result().get();
            stage = Stage.DONE;
            return ActionStatus.done();
        }
        stage = stage == Stage.APPROACH ? Stage.HOLD : Stage.CLICK;
        return ActionStatus.progressed();
    }

    // 按阶段备下一个小动作：桶已在主手就直接出手；身上没有这种桶按缺东西失败。
    private ActionStatus prepare() {
        switch (stage) {
            case APPROACH -> current = construction.close().toward(ApproachTarget.ofBlock(target), permissions);
            case HOLD -> {
                switch (construction.hand().hold(bucketItem)) {
                    case ConstructionSeams.HoldPlan.Ready ready -> {
                        stage = Stage.CLICK;
                        return prepare();
                    }
                    case ConstructionSeams.HoldPlan.Move move -> current = move.action();
                    case ConstructionSeams.HoldPlan.NotCarried notCarried -> {
                        return ActionStatus.failed(Problem.of(Problem.Kind.NEED_ITEM,
                                what + "：身上没有 " + bucketItem));
                    }
                    case ConstructionSeams.HoldPlan.Cannot cannot -> {
                        return ActionStatus.failed(cannot.problem());
                    }
                }
            }
            case CLICK -> {
                click = kind == Kind.POUR
                        ? construction.clicks().pour(PlannedCell.fluid(target, poured), confirmation)
                        : construction.clicks().scoop(target, confirmation);
                current = click.action();
            }
            case DONE -> {
                return ActionStatus.done();
            }
        }
        return null;
    }

    @Override
    public void pause() {
        if (current != null) current.pause();
    }

    @Override
    public void close() {
        if (current != null) current.close();
        current = null;
    }

    @Override
    public Interruptibility interruptibility() {
        return current == null ? Interruptibility.BETWEEN_ACTIONS : current.interruptibility();
    }

    @Override
    public String describe() {
        return switch (stage) {
            case APPROACH -> what + "：走到 " + target.toShortString() + " 跟前";
            case HOLD -> what + "：把 " + bucketItem + " 换到主手";
            case CLICK -> what + "：" + (kind == Kind.POUR ? "倒进 " : "舀 ") + target.toShortString();
            case DONE -> what + "：做完";
        };
    }
}
