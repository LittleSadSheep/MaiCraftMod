// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.drop;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import org.maiwithu.maicraft.behavior.acquire.OffhandContents;
import org.maiwithu.maicraft.behavior.acquire.ReadsCharacterPosition;
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

import net.minecraft.world.phys.Vec3;

/**
 * 丢东西的任务：先朝面前远处看一眼，把要丢的换到主手，按实际持有限量整份抛出；
 * 丢完把落点登记进本会话的寻路避让，再原地走出去拾取范围，不让自己两秒后把东西吸回来。
 *
 * <p>数量以执行时身上实际持有的为准：请求超量就按持有的丢，partial 结束写清差额。
 * 抛掷没等到确认时按没能确认的交互如实记账——没等到确认不等于确定没丢，也不盲目重试；
 * 走不出去时在结果里写明"可能被自己捡回"。
 */
final class DropTask extends PhasedTask<DropTask.Phase> {

    /** 抛出落点按面前几格估计：整份一抛不会落在脚边，避让登记取这个近似，半径在避让里放宽。 */
    private static final double LANDING_DISTANCE = 6.0;
    /** 抛掷前把视线抬高一点，物品沿视线飞出去更远。 */
    private static final double LOOK_UP = 3.0;
    /** 走开几格才算离开拾取范围。 */
    private static final int STEP_AWAY_BLOCKS = 4;

    /** 任务的进度：看向前方 → 换到主手 → 丢 → 走开。 */
    enum Phase { LOOK_OUT, TO_HAND, THROWING, STEP_AWAY }

    private final DropInput input;
    private final BackpackView backpack;
    private final OffhandContents offhand;
    private final ReadsCharacterPosition position;
    private final Function<PlayerContext, FirstPersonScene> scenes;
    private final Optional<MovesToMainhand> toMainhand;
    private final Optional<StepsAside> stepsAside;
    private final DropAvoidance avoidance;

    /** 实际要丢的数量：以动手时身上真正持有的为准。 */
    private int toDrop;
    /** 丢东西时站的格子，走开与登记避让都以它为基准。 */
    private WorldPosition thrownAt;
    /** 抛出方向的近似：落点避让按它往前推几格。 */
    private Vec3 throwDirection = new Vec3(0.0, 0.0, -1.0);

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
        if (phase == Phase.THROWING) {
            // 数量在动手前最后核对一次：接单到执行之间库存可能已经变少，以现场为准。
            toDrop = Math.min(input.count(), Math.max(1, carried()));
            return new ThrowsItems(input.itemId(), toDrop, scenes);
        }
        if (phase == Phase.TO_HAND) {
            return toMainhand.flatMap(moves -> moves.moveToMainhand(input.itemId())).orElse(null);
        }
        if (phase == Phase.STEP_AWAY) {
            return stepsAside.flatMap(aside -> aside.stepAway(thrownAt, STEP_AWAY_BLOCKS)).orElse(null);
        }
        return null;
    }

    @Override
    protected Next<Phase> tick(Phase phase, TickContext context) {
        return switch (phase) {
            // 朝面前远处抛：把视线抬到前方，别丢在脚边等冷却一过又吸回来。
            case LOOK_OUT -> {
                PlayerContext player = context.player();
                player.input().halt(player.localPlayer());
                FirstPersonScene scene = scenes.apply(player);
                Vec3 forward = horizontalForward(scene);
                throwDirection = forward;
                player.input().lookAt(player.localPlayer(),
                        scene.eyePosition().add(forward.scale(10.0)).add(0.0, LOOK_UP, 0.0));
                recordProgress("看向面前远处");
                yield Next.go(Phase.TO_HAND, "看向前方了");
            }
            case TO_HAND -> {
                if (action() == null) {
                    // 换到主手的接缝没接上：东西本来就在主手时照样能丢，交给丢的动作自己核对。
                    yield Next.go(Phase.THROWING, "没有换手的接缝，直接试着丢");
                }
                yield runActionThen(context, () -> Next.go(Phase.THROWING, "换到主手了"));
            }
            case THROWING -> tickThrowing(context);
            case STEP_AWAY -> tickStepAway(context);
        };
    }

    private Next<Phase> tickThrowing(TickContext context) {
        if (action() == null) {
            return Next.fail(Problem.of(Problem.Kind.NEED_ITEM,
                    "主手上没有" + input.itemId() + "，丢东西要先把要丢的换到主手", null));
        }
        thrownAt = position.currentPosition();
        return switch (runAction(context)) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Done done -> {
                // 丢出的每一份都确认过了：按确认的总数记一条变化，账和背包对得上。
                recordChange(new Change(Change.Kind.ITEM_DROPPED, input.itemId(),
                        toDrop - throwsLeft(), null));
                registerLanding(context.gameTick());
                yield Next.go(Phase.STEP_AWAY, toDrop < input.count()
                        ? "身上只有 " + toDrop + " 件，丢出这些"
                        : "丢出去了，走开防捡回");
            }
            case ActionStatus.Failed failed -> settleAfterThrow(failed.problem(), context.gameTick());
        };
    }

    private Next<Phase> tickStepAway(TickContext context) {
        if (action() == null) {
            return Next.done(settled("走不出去：附近没有能走的方向，丢出的东西可能被自己捡回"));
        }
        return switch (runAction(context)) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Done done -> Next.done(settled("丢出去之后走开了几步"));
            case ActionStatus.Failed failed ->
                    Next.done(settled("走不出去（" + failed.problem().message() + "），丢出的东西可能被自己捡回"));
        };
    }

    // 抛掷没做完：没能确认的件数如实记账，不盲目重试；请求量比持有多的差额也写清。
    private Next<Phase> settleAfterThrow(Problem cause, long gameTick) {
        int confirmed = toDrop - throwsLeft();
        registerLanding(gameTick);
        if (confirmed > 0) {
            recordChange(new Change(Change.Kind.ITEM_DROPPED, input.itemId(), confirmed, null));
        }
        if (confirmed < toDrop) {
            recordUnconfirmed(new Change(Change.Kind.ITEM_DROPPED, input.itemId(), toDrop - confirmed,
                    "抛出没等到确认，不能确定丢没丢出去"));
        }
        int shortfall = input.count() - toDrop;
        TaskResult.Builder builder = TaskResult.builder(TaskResult.Status.PARTIAL,
                "丢了 " + confirmed + " 件 " + input.itemId() + "，没有全部确认丢出");
        if (shortfall > 0) builder.remaining("还差 " + shortfall + " 件（身上只有这些）");
        if (confirmed < toDrop) builder.remaining("还剩 " + (toDrop - confirmed) + " 件没等到确认");
        return Next.done(builder.build());
    }

    private int throwsLeft() {
        return action() instanceof ThrowsItems throwing ? throwing.remaining() : 0;
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

    private TaskResult settled(String closing) {
        int confirmed = toDrop - throwsLeft();
        int shortfall = input.count() - toDrop;
        TaskResult.Builder builder = TaskResult.builder(
                confirmed >= input.count() ? TaskResult.Status.DONE : TaskResult.Status.PARTIAL,
                "丢出了 " + confirmed + " 件 " + input.itemId() + "（" + closing + "）");
        if (shortfall > 0) {
            builder.remaining("还差 " + shortfall + " 件" + input.itemId() + "（身上只有这些）");
        }
        return builder.build();
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
