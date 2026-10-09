// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import baritone.api.IBaritone;
import baritone.api.event.events.PathEvent;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.type.EventState;
import baritone.api.utils.IInputOverrideHandler;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import baritone.api.utils.input.Input;
import baritone.behavior.PathingBehavior;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.behavior.navigation.WalkReport;
import org.maiwithu.maicraft.behavior.navigation.WalkRun;
import org.maiwithu.maicraft.behavior.navigation.calc.NavGoal;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.game.player.PlayerInput;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.result.Problem;

/**
 * 一次走到目标在内嵌 Baritone 上的运行：每刻推进引擎的计算、读回路线与进度，
 * 把引擎请求的按键合成角色的移动与跳跃输入，并按身体的真实状态判断到达、
 * 泅渡与停稳。停下分两步——先记下请求，身体落回地面才取消路线并交出身体；
 * 路线走不过去或长时间没有真实进展时如实失败，不冒充到达。
 */
final class BaritoneWalkRun implements WalkRun {

    /** 在路上连续这么多个游戏刻没有四分之一格的位移，就承认走不下去。 */
    private static final int STALL_LIMIT_TICKS = 100;

    private final BaritoneInternals owner;
    private final GoalCompiler.Compiled target;
    private final TerrainPermit permit;
    private final NavGoal goal;
    private final MaiCraftGoalAdapter engineGoal;
    // 逐刻判断必须拿到真实目标才能建：字段初始化会抢在构造赋值之前跑，拿到空目标，
    // 所以上路运行挪进构造函数、在目标赋值之后再建。
    private final WalkRunProgress progress;
    private final NavigationProgress displacement = new NavigationProgress();
    private final NavigationDispatchWatchdog dispatch = new NavigationDispatchWatchdog();
    /** 这段路垫上的临时方块；逐格进结算结果，不自动收回。 */
    private final List<BlockPos> placements = new ArrayList<>();
    /** 引擎本刻请求的按键是否读出来过；用于区分排队与真正在走。 */
    private boolean engineFailedPath;
    private boolean activated;
    private boolean abandoned;

    BaritoneWalkRun(BaritoneInternals owner, GoalCompiler.Compiled target, TerrainPermit permit) {
        this.owner = owner;
        this.target = target;
        this.permit = permit;
        this.goal = target.goal();
        this.engineGoal = new MaiCraftGoalAdapter(goal);
        this.progress = new WalkRunProgress(goal);
    }

    NavGoal goal() {
        return goal;
    }

    TerrainPermit permit() {
        return permit;
    }

    LongSet sacredCells() {
        return target.sacred();
    }

    LongSet forbiddenBodyCells() {
        return LongSets.EMPTY_SET;
    }

    /**
     * 引擎报告一格方块变了：变化后是实心方块（不是空气、不是水与岩浆）就记成这段路垫上的临时方块；
     * 挖掉的变化（变成空气）不记。垫了什么如实进结算结果，收不收回由 LLM 决定。
     */
    void recordPlacedIfSolid(BlockPos position, BlockState after) {
        if (permit.changes() == Permissions.BlockChanges.NONE) return;
        if (!after.isAir() && !after.liquid() && !placements.contains(position)) {
            placements.add(position.immutable());
        }
    }

    /** 这段路垫上的方块格子。 */
    List<BlockPos> placements() {
        return placements;
    }

    /** 上路：把目标交给引擎并让它开始第一次算路。 */
    void activate(PlayerContext context) {
        activated = true;
        owner.pathing().forceCancel();
        owner.engine().getCustomGoalProcess().setGoalAndPath(engineGoal);
    }

    /** 仍在排队：还没有身体，如实报告正在算路。 */
    void observeQueued() {
        if (abandoned && !progress.done()) {
            progress.fail(Problem.of(Problem.Kind.STUCK, "排队等待上路时被更新的走到请求取代", null), null);
        }
    }

    /** 排队期间被更新的请求顶掉：结算自己，不再占队位。 */
    void abandon() {
        abandoned = true;
        owner.dropQueued(this);
        observeQueued();
    }

    private void onEnginePathEvent(PathEvent event) {
        if (event == PathEvent.CALC_FAILED || event == PathEvent.NEXT_CALC_FAILED) {
            engineFailedPath = true;
        }
    }

    void onPathEvent(PathEvent event) {
        onEnginePathEvent(event);
    }

    @Override
    public WalkReport report() {
        return progress.report();
    }

    @Override
    public boolean stop() {
        progress.requestStop();
        // 只有身体落地站稳后才算停住；此刻是否停稳看最近的走到情况。
        return progress.done() && progress.report().state() == WalkReport.State.STOPPED;
    }

    @Override
    public ActionStatus tick(TickContext tick) {
        // 排队、上路与身体驱动都由实现方统一裁决；这里只根据最近一刻的情况回答动作状态。
        owner.drive(this, tick.player());
        var report = progress.report();
        return switch (report.state()) {
            case ARRIVED, STOPPED -> ActionStatus.done();
            case FAILED -> ActionStatus.failed(report.problem());
            default -> ActionStatus.running();
        };
    }

    void driveBody(PlayerContext context) {

        LocalPlayer player = context.localPlayer();
        var pathing = owner.pathing();
        IBaritone engine = owner.engine();
        boolean routePresent = pathing.getCurrent() != null;
        boolean calculating = pathing.getInProgress().isPresent();

        // 引擎的搜索与路线核对在后台线程之外还依赖本刻的事件推进；先推进再读回。
        var events = TickEvent.createNextProvider();
        engine.getGameEventHandler().onTick(events.apply(EventState.PRE, TickEvent.Type.IN));
        engine.getGameEventHandler().onPostTick(events.apply(EventState.POST, TickEvent.Type.IN));

        routePresent = pathing.getCurrent() != null;
        calculating = pathing.getInProgress().isPresent();
        BlockPos feet = player.blockPosition();
        boolean onGround = player.onGround();
        boolean inWater = player.isInWater();

        // 位移与派发观察用于卡住判断：位置累计变化算真实进展，原地踏步不算。
        Vec3 position = player.position();
        displacement.observe(position.x, position.y, position.z, context.clientTick());
        boolean progressed = displacement.recent(context.clientTick(), 1);
        var dispatchAction = dispatch.observe(context.clientTick(), true, routePresent || calculating, progressed);
        if (dispatchAction == NavigationDispatchWatchdog.Action.RESTART && !routePresent) {
            engine.getCustomGoalProcess().setGoalAndPath(engineGoal);
        } else if (dispatchAction == NavigationDispatchWatchdog.Action.FAIL) {
            progress.fail(Problem.of(Problem.Kind.INTERNAL_ERROR, "寻路引擎长时间没有给出路线", null), feet);
        }
        if (progress.stopRequested() && displacement.stalledTicks(context.clientTick()) > STALL_LIMIT_TICKS
                && progress.report().state() == WalkReport.State.ON_THE_WAY) {
            progress.fail(Problem.of(Problem.Kind.STUCK, "停下的路上长时间没有进展", null), feet);
        } else if (!progress.stopRequested() && routePresent
                && displacement.stalledTicks(context.clientTick()) > STALL_LIMIT_TICKS) {
            progress.fail(Problem.of(Problem.Kind.STUCK, "沿路线行走时长时间没有进展", null), feet);
        }

        progress.observe(new WalkRunProgress.Observation(
                feet, onGround, inWater, routePresent, calculating, engineFailedPath && !routePresent));

        if (progress.stopRequested() && onGround && pathing.isSafeToCancel()) {
            // 落地后才真正撤路线：空中撤掉会让执行段把重力全交给身体，摔出可以避免的伤。
            pathing.forceCancel();
        }

        // 按键合成：读引擎按下的方向、跳跃与潜行，交给角色输入入口；每刻续发，缺刻自动松键。
        IInputOverrideHandler input = engine.getInputOverrideHandler();
        float forward = (input.isInputForcedDown(Input.MOVE_FORWARD) ? 1F : 0F)
                - (input.isInputForcedDown(Input.MOVE_BACK) ? 1F : 0F);
        float strafe = (input.isInputForcedDown(Input.MOVE_LEFT) ? 1F : 0F)
                - (input.isInputForcedDown(Input.MOVE_RIGHT) ? 1F : 0F);
        boolean jump = input.isInputForcedDown(Input.JUMP);
        boolean sneak = input.isInputForcedDown(Input.SNEAK);
        boolean sprint = input.isInputForcedDown(Input.SPRINT);
        PlayerInput playerInput = context.input();
        if (progress.done()) {
            // 运行结束：松开全部按键，把身体交回给下一个动作或玩家。
            playerInput.releaseAll(player);
            owner.release(this);
            finishEngine(engine, pathing);
            return;
        }
        if (forward != 0F || strafe != 0F || jump || sneak) {
            playerInput.applyNavigationMovement(
                    new PlayerInput.Movement(forward, strafe, jump, sneak, sprint), context.clientTick());
        } else if (routePresent && !calculating) {
            // 路线在手而引擎本刻没按键：保持站立但按住路线朝向，等执行段下一刻的指令。
            playerInput.applyNavigationMovement(PlayerInput.Movement.STOPPED, context.clientTick());
        }
        lookAlongRoute(engine, playerInput, context, player, routePresent);
        engineFailedPath = false;
    }

    // 普通走路用缓慢改变的路线朝向：镜头不追随引擎每刻的方块中心瞄准点，减少逐格点头。
    private void lookAlongRoute(IBaritone engine, PlayerInput playerInput,
                                PlayerContext context, LocalPlayer player, boolean routePresent) {
        Vec3 anchor = null;
        var executor = engine.getPathingBehavior().getCurrent();
        if (routePresent && executor != null) {
            var positions = executor.getPath().positions();
            if (!positions.isEmpty()) {
                int index = Math.clamp(executor.getPosition() + 1, 0, positions.size() - 1);
                anchor = Vec3.atBottomCenterOf(positions.get(index));
            }
        }
        if (anchor == null) return;
        Vec3 delta = anchor.subtract(player.getEyePosition());
        float yaw = (float) (Math.atan2(delta.z, delta.x) * (180 / Math.PI)) - 90.0f;
        playerInput.requestNavigationLook(yaw, 8.0f, context.clientTick());
    }

    private void finishEngine(IBaritone engine, PathingBehavior pathing) {
        pathing.forceCancel();
        engine.getInputOverrideHandler().clearAllKeys();
        NavigationProtection.clear();
    }

    @Override
    public void pause() {
        // 生存需求打断：松开按键、撤路线，保留运行与目标，恢复时重新算路。
        progress.requestStop();
        var pathing = owner.pathing();
        if (activated && pathing != null && pathing.isSafeToCancel()) {
            pathing.forceCancel();
            owner.engine().getInputOverrideHandler().clearAllKeys();
        }
    }

    @Override
    public void close() {
        if (!progress.done()) {
            progress.fail(Problem.of(Problem.Kind.STUCK, "走到被任务放弃", null), null);
        }
        owner.release(this);
        finishEngineQuietly();
    }

    private void finishEngineQuietly() {
        var pathing = owner.pathing();
        if (pathing == null) return;
        pathing.forceCancel();
        owner.engine().getInputOverrideHandler().clearAllKeys();
        NavigationProtection.clear();
    }

    @Override
    public Interruptibility interruptibility() {
        // 身体在空中或正在跳时停下不安全；落地站稳后可以随时交出。
        var report = progress.report();
        if (progress.done()) return Interruptibility.BETWEEN_ACTIONS;
        return report.state() == WalkReport.State.ON_THE_WAY ? Interruptibility.UNSAFE_TO_STOP
                : Interruptibility.WORKING;
    }

    @Override
    public String describe() {
        // 没有目标时只通报走到本身的状态，不去读目标内容，避免空目标崩了通报。
        if (goal == null) {
            return report().state() == WalkReport.State.FAILED
                    ? "走不下去：" + report().problem().message()
                    : "走到没有拿到目标";
        }
        var report = progress.report();
        return switch (report.state()) {
            case PLANNING -> "正在算路（目标 " + goal.center().toShortString() + "）";
            case ON_THE_WAY -> "在路上，走向 " + goal.center().toShortString();
            case ARRIVED -> "已到达 " + goal.center().toShortString();
            case STOPPED -> "已停下" + (report.feet() != null ? "于 " + report.feet().toShortString() : "");
            case FAILED -> "走不下去：" + report.problem().message();
        };
    }
}
