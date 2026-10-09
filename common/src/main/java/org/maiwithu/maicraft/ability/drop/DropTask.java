// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.drop;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.behavior.acquire.OffhandContents;
import org.maiwithu.maicraft.behavior.acquire.ReadsCharacterPosition;
import org.maiwithu.maicraft.behavior.interaction.AimCheck;
import org.maiwithu.maicraft.behavior.interaction.FirstPersonScene;
import org.maiwithu.maicraft.behavior.inventory.DropAvoidance;
import org.maiwithu.maicraft.behavior.inventory.MovesToMainhand;
import org.maiwithu.maicraft.behavior.inventory.StepsAside;
import org.maiwithu.maicraft.behavior.inventory.ThrowsItems;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 丢东西的任务：先朝面前远处看过去，把要丢的一堆换到主手整份抛出，丢完一堆再换下一堆，
 * 丢够或身上没有了为止；丢完把落点登记进本会话的寻路避让，再原地走出去拾取范围，
 * 不让自己两秒后把东西吸回来。
 *
 * <p>数量以执行时身上实际持有的为准：请求超量就按持有的丢，partial 结束写清差额。
 * 抛掷没等到确认的那一下按没能确认的交互如实记账——没等到确认不等于确定没丢，也不盲目重试；
 * 还没丢的照实写进剩余，不混进没能确认里。走不出去时在结果里写明"可能被自己捡回"。
 */
final class DropTask extends PhasedTask<DropTask.Phase> {

    /** 抛出落点按面前几格估计：整份一抛不会落在脚边，避让登记取这个近似，半径在避让里放宽。 */
    private static final double LANDING_DISTANCE = 6.0;
    /** 抛掷前把视线抬高一点，物品沿视线飞出去更远。 */
    private static final double LOOK_UP = 3.0;
    /** 等镜头转到位的耐心（刻）；转不过去也照样丢，方向差一点不碍事。 */
    private static final int LOOK_PATIENCE_TICKS = 10;
    /** 走开几格才算离开拾取范围。 */
    private static final int STEP_AWAY_BLOCKS = 4;

    /** 任务的进度：看向前方 → 换一堆到主手 → 丢 →（还要丢就再换一堆）→ 走开。 */
    enum Phase { LOOK_OUT, TO_HAND, THROWING, STEP_AWAY }

    private final DropInput input;
    private final BackpackView backpack;
    private final OffhandContents offhand;
    private final ReadsCharacterPosition position;
    private final Function<PlayerContext, FirstPersonScene> scenes;
    private final Optional<MovesToMainhand> toMainhand;
    private final Optional<StepsAside> stepsAside;
    private final DropAvoidance avoidance;

    /** 确认丢出去的总件数：一堆一堆累计。 */
    private int dropped;
    /** 正在丢的这一堆。 */
    private ThrowsItems throwing;
    /** 丢东西时站的格子，走开与登记避让都以它为基准。 */
    private WorldPosition thrownAt;
    /** 抛出方向的近似：落点避让按它往前推几格。 */
    private Vec3 throwDirection = new Vec3(0.0, 0.0, -1.0);
    private Vec3 lookTarget;
    private int lookWaited;
    /** 丢的时候出了岔子：走开之后按它收场。 */
    private Problem endedEarly;

    DropTask(DropInput input, BackpackView backpack, OffhandContents offhand,
            ReadsCharacterPosition position, Function<PlayerContext, FirstPersonScene> scenes,
            Optional<MovesToMainhand> toMainhand, Optional<StepsAside> stepsAside,
            DropAvoidance avoidance) {
        super("丢东西", Phase.LOOK_OUT, new ProgressTracker(100, 20L * 60 * 2));
        this.input = input;
        this.backpack = Objects.requireNonNull(backpack, "backpack");
        this.offhand = Objects.requireNonNull(offhand, "offhand");
        this.position = Objects.requireNonNull(position, "position");
        this.scenes = Objects.requireNonNull(scenes, "scenes");
        this.toMainhand = Objects.requireNonNull(toMainhand, "toMainhand");
        this.stepsAside = Objects.requireNonNull(stepsAside, "stepsAside");
        this.avoidance = Objects.requireNonNull(avoidance, "avoidance");
    }

    @Override
    protected Action enter(Phase phase) {
        return switch (phase) {
            case LOOK_OUT -> null;
            case TO_HAND -> toMainhand.flatMap(moves -> moves.moveToMainhand(input.itemId())).orElse(null);
            case THROWING -> {
                // 这一堆丢几件在动手前核对：还要丢的与身上实际有的取小，接单到执行之间库存可能变了。
                int left = Math.min(input.count() - dropped, carried());
                throwing = left > 0 ? new ThrowsItems(input.itemId(), left, scenes) : null;
                yield throwing;
            }
            case STEP_AWAY -> stepsAside.flatMap(aside -> aside.stepAway(thrownAt, STEP_AWAY_BLOCKS)).orElse(null);
        };
    }

    @Override
    protected Next<Phase> tick(Phase phase, TickContext context) {
        return switch (phase) {
            case LOOK_OUT -> lookOut(context);
            case TO_HAND -> action() == null
                    // 换到主手的接缝没接上，或东西本来就在主手：交给丢的动作自己核对。
                    ? Next.go(Phase.THROWING, "东西在主手了，开始丢")
                    : toHand(context);
            case THROWING -> tickThrowing(context);
            case STEP_AWAY -> tickStepAway(context);
        };
    }

    // 朝面前远处抛：把视线抬到前方，等镜头真转过去再丢，别丢在脚边等冷却一过又吸回来。
    private Next<Phase> lookOut(TickContext context) {
        PlayerContext player = context.player();
        FirstPersonScene scene = scenes.apply(player);
        if (lookTarget == null) {
            player.input().halt(player.localPlayer());
            throwDirection = horizontalForward(scene);
            lookTarget = scene.eyePosition().add(throwDirection.scale(10.0)).add(0.0, LOOK_UP, 0.0);
        }
        player.input().lookAt(player.localPlayer(), lookTarget);
        if (AimCheck.settled(scene.viewVector(), scene.eyePosition(), lookTarget)
                || ++lookWaited >= LOOK_PATIENCE_TICKS) {
            recordProgress("看向面前远处");
            return Next.go(Phase.TO_HAND, "看向前方了");
        }
        return Next.stay();
    }

    private Next<Phase> toHand(TickContext context) {
        return switch (runAction(context)) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Done done -> Next.go(Phase.THROWING, "换到主手了");
            case ActionStatus.Failed failed -> stop(failed.problem());
        };
    }

    // 丢一堆：丢完还要丢、身上还有就换下一堆；主手这堆丢光了也是换下一堆；没等到确认的那一下照实记。
    private Next<Phase> tickThrowing(TickContext context) {
        if (throwing == null) {
            return dropped > 0 ? Next.go(Phase.STEP_AWAY, "身上没有了，丢出这些")
                    : Next.fail(Problem.of(Problem.Kind.NEED_ITEM, "身上没有 " + input.itemId(), null));
        }
        thrownAt = position.currentPosition();
        ActionStatus status = runAction(context);
        if (status instanceof ActionStatus.Running) return Next.stay();
        settleThrown(context.gameTick());
        if (status instanceof ActionStatus.Failed failed) {
            boolean handRanOut = failed.problem().kind() == Problem.Kind.NEED_ITEM && throwing.unconfirmed() == 0;
            if (handRanOut && dropped < input.count() && carried() > 0) {
                return Next.go(Phase.TO_HAND, "主手这堆丢完了，换下一堆到主手");
            }
            if (throwing.unconfirmed() > 0) {
                recordUnconfirmed(new Change(Change.Kind.ITEM_DROPPED, input.itemId(), throwing.unconfirmed(),
                        "抛出没等到确认，不能确定丢没丢出去"));
            }
            return stop(failed.problem());
        }
        if (dropped < input.count() && carried() > 0) {
            return Next.go(Phase.TO_HAND, "这一堆丢完了，换下一堆到主手");
        }
        return Next.go(Phase.STEP_AWAY, dropped < input.count()
                ? "身上只有这些，丢出 " + dropped + " 件" : "丢出去了，走开防捡回");
    }

    // 这一堆确认丢出的件数记进变化，落点登记进避让。
    private void settleThrown(long gameTick) {
        int thrown = throwing.thrown();
        if (thrown > 0 || throwing.unconfirmed() > 0) {
            registerLanding(gameTick);
        }
        if (thrown > 0) {
            dropped += thrown;
            recordChange(new Change(Change.Kind.ITEM_DROPPED, input.itemId(), thrown, null));
        }
    }

    // 丢的时候出了岔子：一件都没丢出去就按问题失败；丢出过的先走开再按部分完成收场。
    private Next<Phase> stop(Problem problem) {
        if (dropped == 0 && throwing != null && throwing.unconfirmed() == 0) {
            return Next.fail(problem);
        }
        endedEarly = problem;
        return Next.go(Phase.STEP_AWAY, "先走开，免得把丢出的捡回来");
    }

    private Next<Phase> tickStepAway(TickContext context) {
        if (action() == null) {
            return finish("走不出去：附近没有能走的方向，丢出的东西可能被自己捡回");
        }
        return switch (runAction(context)) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Done done -> finish("丢出去之后走开了几步");
            case ActionStatus.Failed failed ->
                    finish("走不出去（" + failed.problem().message() + "），丢出的东西可能被自己捡回");
        };
    }

    private Next<Phase> finish(String closing) {
        if (endedEarly != null) {
            return Next.done(TaskResult.builder(TaskResult.Status.PARTIAL, "丢出了 " + dropped + " 件 "
                            + input.itemId() + "，没有全部丢完：" + endedEarly.message() + "（" + closing + "）")
                    .problem(endedEarly).build());
        }
        TaskResult.Builder builder = TaskResult.builder(
                dropped >= input.count() ? TaskResult.Status.DONE : TaskResult.Status.PARTIAL,
                "丢出了 " + dropped + " 件 " + input.itemId() + "（" + closing + "）");
        return Next.done(builder.build());
    }

    @Override protected List<String> remaining() {
        int left = input.count() - dropped;
        return left <= 0 ? List.of()
                : List.of("还差 " + left + " 件" + input.itemId() + "没丢（身上只有这些，或丢的时候出了岔子）");
    }

    // 落点登记是近似：抛出方向前面几格外的那一格；被墙弹回来的东西常落在脚下，
    // 所以丢出时站的格子也一并登记，走开与避让两条路都认这两处。拾取有半格余量，避让半径在 DropAvoidance 放宽。
    private void registerLanding(long gameTick) {
        if (thrownAt == null) return;
        avoidance.register(thrownAt, gameTick);
        WorldPosition landing = WorldPosition.here(
                thrownAt.x() + (int) Math.round(throwDirection.x * LANDING_DISTANCE),
                thrownAt.y(),
                thrownAt.z() + (int) Math.round(throwDirection.z * LANDING_DISTANCE));
        avoidance.register(landing, gameTick);
    }

    // 视线的水平分量；抬头低头都不改变往哪丢，正对着天或地时保持当前朝向的水平线。
    private Vec3 horizontalForward(FirstPersonScene scene) {
        Vec3 view = scene.viewVector();
        Vec3 flat = new Vec3(view.x, 0.0, view.z);
        return flat.lengthSqr() < 1.0e-6 ? new Vec3(0.0, 0.0, -1.0) : flat.normalize();
    }

    // 身上（主背包加副手）现在实际有几件要丢的。
    private int carried() {
        int total = 0;
        for (var stack : backpack.stacks()) {
            if (stack.itemId().equals(input.itemId())) total += stack.count();
        }
        if (offhand != null) {
            total += offhand.heldInOffhand()
                    .filter(stack -> stack.itemId().equals(input.itemId()))
                    .map(stack -> stack.count())
                    .orElse(0);
        }
        return total;
    }
}
