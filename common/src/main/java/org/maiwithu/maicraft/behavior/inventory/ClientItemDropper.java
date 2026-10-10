// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.behavior.acquire.ReadsCharacterPosition;
import org.maiwithu.maicraft.behavior.acquire.spi.ReportsUnconfirmed;
import org.maiwithu.maicraft.behavior.interaction.FirstPersonScene;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 丢弃执行接缝的读端：把背包里要腾出去的一堆换到主手，用原生投掷整份丢出去。
 *
 * <p>丢是真实的世界变化：每一份投掷都等游戏确认扣减，确认丢出的才记进变化；
 * 抛掷没等到确认的那几件照交回接缝原样交回——没等到确认不等于确定没丢，也不盲目再丢。
 * 丢完把落点登记进本会话的丢弃避让（往前推几格的近似，加上脚下），再朝背对落点的方向走开几步，
 * 不让两秒后的拾取冷却把刚丢的又吸回来、白腾一场。走不出去不挡丢这件事本身，照常收场。
 */
public final class ClientItemDropper implements ItemDropper, ReportsUnconfirmed, AbandonsStep {

    /** 抛出落点按面前几格估计：与丢弃能力同一套近似，避让半径在 DropAvoidance 里放宽。 */
    private static final double LANDING_DISTANCE = 6.0;
    /** 走开几格才算离开拾取范围：与丢弃能力同一条玩家常识。 */
    private static final int STEP_AWAY_BLOCKS = 4;
    /** 两次调用隔了这么久就算"这一步没人管了"：撒手旧的，下次重新看背包。 */
    private static final long STALE_AFTER_TICKS = 200;

    /** 丢弃的先后顺序。 */
    private enum Stage { IDLE, TO_HAND, THROWING, STEPPING_ASIDE }

    private final ClientMovesToMainhand movesToMainhand;
    private final DropAvoidance avoidance;
    private final Optional<StepsAside> stepsAside;
    private final ReadsCharacterPosition position;
    private final Supplier<PlayerContext> contexts;

    private Stage stage = Stage.IDLE;
    private String itemId = "";
    private int planned;
    private Action toHand;
    private ThrowsItems throwing;
    private Action steppingAside;
    private WorldPosition thrownAt;
    /** 确认丢出去的件数。 */
    private int thrown;
    private long lastDrivenTick = Long.MIN_VALUE;
    /** 点出去了却没能确认结果的交互，一句一条。 */
    private final List<String> unconfirmed = new ArrayList<>();

    public ClientItemDropper(ClientMovesToMainhand movesToMainhand, DropAvoidance avoidance,
            Optional<StepsAside> stepsAside, ReadsCharacterPosition position, Supplier<PlayerContext> contexts) {
        this.movesToMainhand = Objects.requireNonNull(movesToMainhand, "movesToMainhand");
        this.avoidance = Objects.requireNonNull(avoidance, "avoidance");
        this.stepsAside = Objects.requireNonNull(stepsAside, "stepsAside");
        this.position = Objects.requireNonNull(position, "position");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
    }

    @Override
    public SpaceStepResult drop(String itemId, int count, TickContext tick) {
        // 隔了太久才被再次调用：手上的东西、脚下的位置都可能变了，没等到确认的投掷先交回，重新来。
        if (lastDrivenTick != Long.MIN_VALUE && tick.gameTick() - lastDrivenTick > STALE_AFTER_TICKS) {
            abandonStep();
        }
        lastDrivenTick = tick.gameTick();
        if (stage == Stage.IDLE) {
            this.itemId = itemId;
            this.planned = count;
            this.thrown = 0;
            this.stage = Stage.TO_HAND;
        }
        return switch (stage) {
            case IDLE -> SpaceStepResult.WORKING;
            case TO_HAND -> toHand(tick);
            case THROWING -> throwing(tick);
            case STEPPING_ASIDE -> steppingAside(tick);
        };
    }

    // 把要丢的换到主手：已经在主手就直接丢，身上没有就做不了。
    private SpaceStepResult toHand(TickContext tick) {
        if (toHand == null) {
            Optional<Action> move = movesToMainhand.actionToMainhand(itemId);
            if (move.isEmpty()) {
                if (!movesToMainhand.carried(itemId)) {
                    resetAttempt();
                    return SpaceStepResult.cannotDo("身上没有 " + itemId + "，丢不了");
                }
                // 已经在主手上：直接进丢的阶段。
                beginThrowing();
                return SpaceStepResult.WORKING;
            }
            toHand = move.get();
        }
        ActionStatus status = toHand.tick(tick);
        if (status instanceof ActionStatus.Done) {
            toHand = null;
            beginThrowing();
            return SpaceStepResult.WORKING;
        }
        if (status instanceof ActionStatus.Failed failed) {
            resetAttempt();
            return SpaceStepResult.cannotDo("把 " + itemId + " 换到主手失败：" + failed.problem().message());
        }
        return SpaceStepResult.WORKING;
    }

    // 换手告一段落：记下丢的时候站在哪，开始逐份丢。
    private void beginThrowing() {
        thrownAt = position.currentPosition();
        throwing = new ThrowsItems(itemId, planned, FirstPersonScene::of);
        stage = Stage.THROWING;
    }

    // 逐份丢出去：确认丢出的累计，没等到确认的交回一句；丢完登记落点、走开防捡回。
    private SpaceStepResult throwing(TickContext tick) {
        ActionStatus status = throwing.tick(tick);
        if (status instanceof ActionStatus.Running) return SpaceStepResult.WORKING;
        thrown = throwing.thrown();
        if (throwing.unconfirmed() > 0) {
            unconfirmed.add("腾地方丢 " + itemId + "：抛出的 " + throwing.unconfirmed() + " 件没等到确认，"
                    + "不能确定丢没丢出去");
        }
        if (status instanceof ActionStatus.Failed failed && thrown <= 0) {
            resetAttempt();
            return SpaceStepResult.cannotDo("丢 " + itemId + " 没有丢出去：" + failed.problem().message());
        }
        // 有确认丢出的就是腾出了地方（哪怕没丢完整格）：登记落点，走开两步防捡回。
        registerLanding(tick.gameTick());
        if (stepsAside.isEmpty()) {
            Change dropped = droppedChange();
            resetAttempt();
            return SpaceStepResult.done(dropped);
        }
        steppingAside = stepsAside.flatMap(aside -> aside.stepAway(thrownAt, STEP_AWAY_BLOCKS)).orElse(null);
        stage = Stage.STEPPING_ASIDE;
        return SpaceStepResult.WORKING;
    }

    // 走开几步：走不出去也不挡丢这件事本身，照常按丢成了收场，风险写进类的说明里。
    private SpaceStepResult steppingAside(TickContext tick) {
        if (steppingAside == null) {
            Change dropped = droppedChange();
            resetAttempt();
            return SpaceStepResult.done(dropped);
        }
        ActionStatus status = steppingAside.tick(tick);
        if (status instanceof ActionStatus.Running) return SpaceStepResult.WORKING;
        Change dropped = droppedChange();
        resetAttempt();
        return SpaceStepResult.done(dropped);
    }

    private Change droppedChange() {
        return new Change(Change.Kind.ITEM_DROPPED, itemId, thrown, null);
    }

    // 落点登记是近似：脚下这一格，加上面前几格外的一格；被墙弹回来的东西常落在脚下，两处都登记。
    private void registerLanding(long gameTick) {
        if (thrownAt == null) return;
        avoidance.register(thrownAt, gameTick);
        // 视线的水平分量决定抛多远；读不到视线就只登记脚下。
        PlayerContext player = contexts.get();
        if (player == null || player.localPlayer() == null) return;
        Vec3 view = FirstPersonScene.of(player).viewVector();
        Vec3 flat = new Vec3(view.x, 0.0, view.z);
        if (flat.lengthSqr() < 1.0e-6) return;
        Vec3 direction = flat.normalize();
        avoidance.register(WorldPosition.here(
                thrownAt.x() + (int) Math.round(direction.x * LANDING_DISTANCE),
                thrownAt.y(),
                thrownAt.z() + (int) Math.round(direction.z * LANDING_DISTANCE)), gameTick);
    }

    @Override
    public List<String> unconfirmedFacts() {
        return List.copyOf(unconfirmed);
    }

    @Override
    public void abandonStep() {
        // 没等到确认的投掷先交回：丢是破坏性行为，撒手不等于没发生。
        if (throwing != null && (throwing.unconfirmed() > 0 || throwing.hasThrowInFlight())) {
            unconfirmed.add("腾地方丢 " + itemId + "：停下时还有投掷没等到确认，不能确定丢没丢出去");
        }
        resetAttempt();
    }

    // 回到起点：下一次调用重新看背包、重新换手。
    private void resetAttempt() {
        stage = Stage.IDLE;
        itemId = "";
        planned = 0;
        toHand = null;
        throwing = null;
        steppingAside = null;
        thrownAt = null;
        thrown = 0;
        lastDrivenTick = Long.MIN_VALUE;
    }
}
