// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.IntSupplier;

import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.behavior.acquire.ReadsCharacterPosition;
import org.maiwithu.maicraft.behavior.interaction.AimCheck;
import org.maiwithu.maicraft.behavior.interaction.FirstPersonScene;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 丢东西的整套做法：朝面前远处看过去，把要丢的一堆换到主手原生抛出（{@link ThrowsItems}），
 * 主手这堆丢完还要丢就换下一堆，丢够或身上没有了为止；丢完把落点登记进本会话的寻路避让，
 * 再走出拾取范围，不让自己两秒后把东西吸回来。丢弃能力和腾地方都用这一份。
 *
 * <p>只记确认过的：每一堆确认丢出的件数记一条变化；抛出没等到确认的那一下按没能确认记账——
 * 没等到确认不等于确定没丢，也不盲目重试。一件都没丢出去就出了岔子时直接失败；丢出过的先走开，
 * 再按做完收场，岔子经 {@link #endedEarly()} 交给调用方写进结果。走不出去不挡丢这件事本身，
 * 经 {@link #leaving()} 写明"可能被自己捡回"。
 */
public final class DropsItems implements Action {

    /** 抛出落点按面前几格估计：整份一抛不会落在脚边，避让登记取这个近似，半径在避让里放宽。 */
    private static final double LANDING_DISTANCE = 6.0;
    /** 抛掷前把视线抬高一点，物品沿视线飞出去更远。 */
    private static final double LOOK_UP = 3.0;
    /** 等镜头转到位的耐心（刻）；转不过去也照样丢，方向差一点不碍事。 */
    private static final int LOOK_PATIENCE_TICKS = 10;
    /** 走开几格才算离开拾取范围。 */
    private static final int STEP_AWAY_BLOCKS = 4;

    /**
     * 丢东西要用的现场部件：丢弃能力与腾地方共用一份，落点登记进同一本避让账，走路时一并绕开。
     *
     * @param scenes     第一人称现场的取法：看向哪里、手上握着什么
     * @param position   角色位置：丢的时候站在哪，走开与登记落点都以它为基准
     * @param toMainhand 换到主手的接缝；没接上传 {@code Optional.empty()}，只丢本来就在主手的
     * @param stepsAside 走开几步的接缝；没接上传 {@code Optional.empty()}，结果写明可能被自己捡回
     * @param avoidance  本会话的丢弃落点避让
     */
    public record Parts(Function<PlayerContext, FirstPersonScene> scenes, ReadsCharacterPosition position,
            Optional<MovesToMainhand> toMainhand, Optional<StepsAside> stepsAside, DropAvoidance avoidance) {

        public Parts {
            Objects.requireNonNull(scenes, "scenes");
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(toMainhand, "toMainhand");
            Objects.requireNonNull(stepsAside, "stepsAside");
            Objects.requireNonNull(avoidance, "avoidance");
        }
    }

    /** 看向前方 → 换一堆到主手 → 丢 →（还要丢就再换一堆）→ 走开 → 结束。 */
    private enum Stage { LOOK_OUT, TO_HAND, THROWING, STEP_AWAY, FINISHED }

    private final String itemId;
    private final int count;
    private final IntSupplier carried;
    private final Parts parts;
    private final TaskRecords records;
    private final String note;

    private Stage stage = Stage.LOOK_OUT;
    /** 当前阶段的动作：换手、丢一堆或走开；看向前方不需要动作。 */
    private Action current;
    private ThrowsItems throwing;
    /** 确认丢出去的总件数：一堆一堆累计。 */
    private int dropped;
    /** 丢东西时站的格子，走开与登记避让都以它为基准。 */
    private WorldPosition thrownAt;
    /** 抛出方向的近似：落点避让按它往前推几格。 */
    private Vec3 throwDirection = new Vec3(0.0, 0.0, -1.0);
    private Vec3 lookTarget;
    private int lookWaited;
    /** 丢的时候出了岔子、但已经丢出过东西：先走开，再交给调用方写进结果。 */
    private Problem endedEarly;
    /** 收场时离开得怎样：走开了，还是走不出去、可能被自己捡回。 */
    private String leaving = "";

    /**
     * @param itemId  要丢的物品注册 ID
     * @param count   要丢几件；身上不够时按身上有的丢
     * @param carried 身上此刻有几件这种东西：每换一堆前重新数，接单到执行之间库存可能变了
     * @param parts   丢东西要用的现场部件
     * @param records 发起任务的记账口：丢出的、没等到确认的都记进去
     * @param note    丢出变化的备注，写明为什么丢（例如腾地方）；丢弃能力直接下令时为 null
     */
    public DropsItems(String itemId, int count, IntSupplier carried, Parts parts, TaskRecords records, String note) {
        this.itemId = Objects.requireNonNull(itemId, "itemId");
        if (count < 1) throw new IllegalArgumentException("要丢的数量至少为 1：" + count);
        this.count = count;
        this.carried = Objects.requireNonNull(carried, "carried");
        this.parts = Objects.requireNonNull(parts, "parts");
        this.records = Objects.requireNonNull(records, "records");
        this.note = note;
    }

    /** 确认丢出去的总件数。 */
    public int dropped() {
        return dropped;
    }

    /** 丢出过东西之后出的岔子；顺顺当当丢完时为空。 */
    public Optional<Problem> endedEarly() {
        return Optional.ofNullable(endedEarly);
    }

    /** 丢完后离开得怎样的一句话：走开了几步，或走不出去、丢出的东西可能被自己捡回。 */
    public String leaving() {
        return leaving;
    }

    @Override
    public ActionStatus tick(TickContext context) {
        return switch (stage) {
            case LOOK_OUT -> lookOut(context);
            case TO_HAND -> toHand(context);
            case THROWING -> throwing(context);
            case STEP_AWAY -> stepAway(context);
            case FINISHED -> ActionStatus.done();
        };
    }

    // 朝面前远处抛：把视线抬到前方，等镜头真转过去再丢，别丢在脚边等冷却一过又吸回来。
    private ActionStatus lookOut(TickContext context) {
        PlayerContext player = context.player();
        FirstPersonScene scene = parts.scenes().apply(player);
        if (lookTarget == null) {
            player.input().halt(player.localPlayer());
            throwDirection = horizontalForward(scene);
            lookTarget = scene.eyePosition().add(throwDirection.scale(10.0)).add(0.0, LOOK_UP, 0.0);
        }
        player.input().lookAt(player.localPlayer(), lookTarget);
        if (AimCheck.settled(scene.viewVector(), scene.eyePosition(), lookTarget)
                || ++lookWaited >= LOOK_PATIENCE_TICKS) {
            beginToHand();
            return ActionStatus.progressed();
        }
        return ActionStatus.running();
    }

    // 换一堆到主手：换手的接缝没接上、或东西本来就在主手时直接丢，主手对不对由丢的动作自己核对。
    private void beginToHand() {
        current = parts.toMainhand().flatMap(moves -> moves.moveToMainhand(itemId)).orElse(null);
        stage = Stage.TO_HAND;
    }

    private ActionStatus toHand(TickContext context) {
        if (current == null) return beginThrowing();
        return switch (current.tick(context)) {
            case ActionStatus.Running running -> running;
            case ActionStatus.Done done -> beginThrowing();
            case ActionStatus.Failed failed -> stop(failed.problem());
        };
    }

    // 这一堆丢几件在动手前核对：还要丢的与身上实际有的取小；身上没有了就收场。
    private ActionStatus beginThrowing() {
        int left = Math.min(count - dropped, carried.getAsInt());
        if (left <= 0) {
            if (dropped > 0) return beginStepAway();
            return finish(ActionStatus.failed(Problem.of(Problem.Kind.NEED_ITEM, "身上没有 " + itemId, null)));
        }
        throwing = new ThrowsItems(itemId, left, parts.scenes());
        current = throwing;
        stage = Stage.THROWING;
        return ActionStatus.progressed();
    }

    // 丢一堆：丢完还要丢、身上还有就换下一堆；主手这堆丢光了也是换下一堆；没等到确认的那一下照实记。
    private ActionStatus throwing(TickContext context) {
        thrownAt = parts.position().currentPosition();
        ActionStatus status = throwing.tick(context);
        if (status instanceof ActionStatus.Running) return status;
        settleThrown(context.gameTick());
        if (status instanceof ActionStatus.Failed failed) {
            boolean handRanOut = failed.problem().kind() == Problem.Kind.NEED_ITEM && throwing.unconfirmed() == 0;
            if (handRanOut && dropped < count && carried.getAsInt() > 0) {
                beginToHand();
                return ActionStatus.progressed();
            }
            if (throwing.unconfirmed() > 0) {
                records.unconfirmed(new Change(Change.Kind.ITEM_DROPPED, itemId, throwing.unconfirmed(),
                        "抛出没等到确认，不能确定丢没丢出去"));
            }
            return stop(failed.problem());
        }
        if (dropped < count && carried.getAsInt() > 0) {
            beginToHand();
            return ActionStatus.progressed();
        }
        return beginStepAway();
    }

    // 这一堆确认丢出的件数记进变化，落点登记进避让；没等到确认的那一下也可能丢出去了，落点照样登记。
    private void settleThrown(long gameTick) {
        int thrown = throwing.thrown();
        if (thrown > 0 || throwing.unconfirmed() > 0) {
            registerLanding(gameTick);
        }
        if (thrown > 0) {
            dropped += thrown;
            records.change(new Change(Change.Kind.ITEM_DROPPED, itemId, thrown, note));
        }
    }

    // 丢的时候出了岔子：一件都没丢出去就按问题失败；丢出过（或可能丢出过）的先走开再收场。
    private ActionStatus stop(Problem problem) {
        if (dropped == 0 && (throwing == null || throwing.unconfirmed() == 0)) {
            return finish(ActionStatus.failed(problem));
        }
        endedEarly = problem;
        return beginStepAway();
    }

    private ActionStatus beginStepAway() {
        current = parts.stepsAside().flatMap(aside -> aside.stepAway(thrownAt, STEP_AWAY_BLOCKS)).orElse(null);
        stage = Stage.STEP_AWAY;
        return ActionStatus.progressed();
    }

    private ActionStatus stepAway(TickContext context) {
        if (current == null) {
            leaving = "走不出去：附近没有能走的方向，丢出的东西可能被自己捡回";
            return finish(ActionStatus.done());
        }
        return switch (current.tick(context)) {
            case ActionStatus.Running running -> running;
            case ActionStatus.Done done -> {
                leaving = "丢出去之后走开了几步";
                yield finish(ActionStatus.done());
            }
            case ActionStatus.Failed failed -> {
                leaving = "走不出去（" + failed.problem().message() + "），丢出的东西可能被自己捡回";
                yield finish(ActionStatus.done());
            }
        };
    }

    private ActionStatus finish(ActionStatus ending) {
        current = null;
        stage = Stage.FINISHED;
        return ending;
    }

    // 落点登记是近似：抛出方向前面几格外的那一格；被墙弹回来的东西常落在脚下，
    // 所以丢出时站的格子也一并登记，走开与避让两条路都认这两处。拾取有半格余量，避让半径在 DropAvoidance 放宽。
    private void registerLanding(long gameTick) {
        if (thrownAt == null) return;
        parts.avoidance().register(thrownAt, gameTick);
        parts.avoidance().register(WorldPosition.here(
                thrownAt.x() + (int) Math.round(throwDirection.x * LANDING_DISTANCE),
                thrownAt.y(),
                thrownAt.z() + (int) Math.round(throwDirection.z * LANDING_DISTANCE)), gameTick);
    }

    // 视线的水平分量；抬头低头都不改变往哪丢，正对着天或地时保持当前朝向的水平线。
    private static Vec3 horizontalForward(FirstPersonScene scene) {
        Vec3 view = scene.viewVector();
        Vec3 flat = new Vec3(view.x, 0.0, view.z);
        return flat.lengthSqr() < 1.0e-6 ? new Vec3(0.0, 0.0, -1.0) : flat.normalize();
    }

    // 被生存需求打断：换手、走开这些动作自己停下；投掷是即时交互，等待中的确认自然到期。
    @Override
    public void pause() {
        if (current != null) current.pause();
    }

    // 不再做了：还有投掷在等确认的，照实记进没能确认——丢弃不可逆，撒手不等于没发生。
    @Override
    public void close() {
        if (stage == Stage.THROWING && throwing != null && throwing.awaitingConfirmation()) {
            records.unconfirmed(new Change(Change.Kind.ITEM_DROPPED, itemId, 1,
                    "停下时还有一次投掷没等到确认，不能确定丢没丢出去"));
        }
        if (current != null) current.close();
        current = null;
    }

    @Override
    public Interruptibility interruptibility() {
        return current == null ? Interruptibility.WORKING : current.interruptibility();
    }

    @Override
    public String describe() {
        return switch (stage) {
            case LOOK_OUT -> "看向面前远处，准备丢 " + itemId;
            case TO_HAND -> "把 " + itemId + " 换到主手";
            case THROWING -> throwing.describe();
            case STEP_AWAY -> "丢完走开几步";
            case FINISHED -> "丢完了 " + itemId;
        };
    }
}
