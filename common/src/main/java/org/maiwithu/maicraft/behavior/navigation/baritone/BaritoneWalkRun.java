// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import baritone.api.IBaritone;
import baritone.api.event.events.PathEvent;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.type.EventState;
import baritone.api.utils.IInputOverrideHandler;
import it.unimi.dsi.fastutil.longs.LongSet;
import baritone.api.utils.input.Input;
import baritone.behavior.PathingBehavior;
import baritone.pathing.movement.Movement;
import baritone.pathing.path.PathExecutor;
import baritone.utils.BlockStateInterface;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.maiwithu.maicraft.behavior.inventory.ClientMovesToMainhand;
import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.behavior.navigation.WalkReport;
import org.maiwithu.maicraft.behavior.navigation.WalkRun;
import org.maiwithu.maicraft.behavior.navigation.calc.NavGoal;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.game.player.PlayerInput;
import org.maiwithu.maicraft.kernel.task.Action;
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

    private static final Logger LOG = LoggerFactory.getLogger(BaritoneWalkRun.class);

    /**
     * 在路上连续这么多个游戏刻没有四分之一格的位移，就承认走不下去。比执行段单步超时（预计耗时再加 100 刻）长：
     * 先让执行段放弃这一步、由卡住记忆切门或绕开，再轮到这里判走不通。
     */
    private static final int STALL_LIMIT_TICKS = 200;

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
    /** 引擎在路上按下的左右键：挖开挡路的、垫方块、开门，都经它交给原生交互并等确认。 */
    private final EngineClicks clicks = new EngineClicks();
    /** 走路镜头的航向：偏差大才转、小修正不动、每刻最多转几度，不逐格点头。 */
    private final NavigationCameraCourse course = new NavigationCameraCourse();
    /** 共用的换手：垫料只在主背包时经背包界面挪到快捷栏，引擎才垫得上。 */
    private final ClientMovesToMainhand hands = new ClientMovesToMainhand(() -> this.lastContext);
    /** 正在把垫料挪到快捷栏的动作；没在挪为 null。 */
    private Action scaffoldMove;
    /** 会摔伤的一跳靠什么接住：出发点备料、空中放下、落地收回；意外掉下去就地接手。 */
    private final WalkLanding landing = new WalkLanding();
    /** 不许挖时走不通的只读复算："假如允许挖垫有没有路、要动哪些格"，只进说明不执行。 */
    private final TerrainProbe probe;
    /** 人按 F8 收回角色时路线被撤了：交回后第一刻重新算路、进展计时重新起算。 */
    private boolean routeDropped;
    /** 最近一刻的角色上下文；暂停、放弃时用它停挖、交还没等到确认的右键。 */
    private PlayerContext lastContext;
    /** 这一趟在哪些面前格反复卡住：第一次先切途经的门，第二次列为障碍绕开，障碍用完才如实走不通。 */
    private final NavigationStallMemory stalls = new NavigationStallMemory();
    /** 刚列了新障碍、还没能安全撤路线：落地站稳后重算一次绕行。 */
    private boolean replanWhenSafe;
    /** 引擎本刻请求的按键是否读出来过；用于区分排队与真正在走。 */
    private boolean engineFailedPath;
    private boolean activated;
    private boolean abandoned;
    /** 最近一次被推进的客户端刻：拥有身体却连续几刻没人推进，说明它的任务被暂停或已丢下它。 */
    private long lastDrivenTick = Long.MIN_VALUE;

    /** 连续这么多刻没人推进，就把身体让给排队的运行。 */
    private static final long IDLE_BEFORE_YIELD_TICKS = 2;

    BaritoneWalkRun(BaritoneInternals owner, GoalCompiler.Compiled target, TerrainPermit permit) {
        this.owner = owner;
        this.target = target;
        this.permit = permit;
        this.goal = target.goal();
        this.engineGoal = new MaiCraftGoalAdapter(goal);
        this.progress = new WalkRunProgress(goal);
        this.probe = new TerrainProbe(engineGoal, permit);
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

    /** 身体不许进的格子：这一趟卡住两次被列为障碍的面前格。 */
    LongSet noEntryCells() {
        return stalls.obstacles();
    }

    /** 卡住后为这扇门（下半格）登记的目标开关状态；没有登记为 null，按门板朝向判断。 */
    Boolean passageOpen(BlockPos passage) {
        return stalls.wantOpen(passage);
    }

    /**
     * 记下这段路上自己垫的一格：只有右键得到游戏确认、那一格确实长出了方块才记。
     * 垫了什么如实进结算结果，收不收回由 LLM 决定。
     */
    private void recordPlaced(BlockPos position) {
        if (permit.changes() == Permissions.BlockChanges.NONE) return;
        if (!placements.contains(position)) {
            placements.add(position.immutable());
        }
    }

    /** 这段路垫上的方块格子。 */
    List<BlockPos> placements() {
        // 落地救援放下后没收回、留在原地的也算这段路改过的格子。
        List<BlockPos> all = new ArrayList<>(placements);
        all.addAll(landing.leftInPlace());
        return all;
    }

    /** 这段路上每次落地救援的经过：放了什么、收没收回、掉没掉血。 */
    List<String> landings() {
        return landing.notes();
    }

    /** 落地救援正在进行：寻路引擎的通用超时不能把它丢下。 */
    boolean landingInProgress() {
        return landing.inProgress();
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

    /** 这一刻有人推进它。 */
    void markDriven(long clientTick) {
        lastDrivenTick = clientTick;
    }

    /** 拥有身体却已经连续几刻没人推进：任务被生存需求暂停，或调用方没收尾就丢下了它。 */
    boolean idleAt(long clientTick) {
        return clientTick - lastDrivenTick >= IDLE_BEFORE_YIELD_TICKS;
    }

    /**
     * 把身体让给排队的运行：按"已停下"结算，松开引擎。被暂停的任务恢复后读到"已停下"，
     * 会像打断后那样从原地重新上路；没人再管的运行就此结束，不再挡住后面所有的走到。
     * 交出身体放在 finally 里：结算出了岔子也必须交出去，否则这个运行永远占着身体，
     * 之后每一次走到都会撞上同一个岔子、全部走不了，只能重启游戏。
     *
     * @param now 接手的那个运行这一刻的角色上下文：还没迈步的运行用它读交出时脚下的格子
     */
    void yieldPlayer(PlayerContext now) {
        try {
            clicks.stop(lastContext != null ? lastContext : now);
            progress.stopWhereLastSeen(feetOf(now));
        } finally {
            owner.release(this);
            finishEngineQuietly();
        }
    }

    // 角色此刻脚下的格子；不在世界里为 null。
    private static BlockPos feetOf(PlayerContext context) {
        return context == null || context.localPlayer() == null ? null : context.localPlayer().blockPosition();
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

    void drivePlayer(PlayerContext context) {
        lastContext = context;
        LocalPlayer player = context.localPlayer();
        var pathing = owner.pathing();
        IBaritone engine = owner.engine();
        resumeAfterHuman(engine, context);
        if (stagingScaffold(context, player, pathing, engine)) return; // 垫料只在主背包：先挪到快捷栏再走
        if (landingHolds(context, player, pathing, engine)) return; // 落地救援占着身体：出发前备料、落地后收回
        boolean routePresent = pathing.getCurrent() != null;
        boolean calculating = pathing.getInProgress().isPresent();

        // 引擎的搜索与路线核对在后台线程之外还依赖本刻的事件推进；先推进再读回。
        var events = TickEvent.createNextProvider();
        engine.getGameEventHandler().onTick(events.apply(EventState.PRE, TickEvent.Type.IN));
        engine.getGameEventHandler().onPostTick(events.apply(EventState.POST, TickEvent.Type.IN));

        routePresent = pathing.getCurrent() != null;
        calculating = pathing.getInProgress().isPresent();
        BlockPos feet = player.blockPosition();
        handleStall(pathing, engine, context.level(), feet);
        boolean onGround = player.onGround();
        boolean inWater = player.isInWater();

        boolean noPath = concludeNoPath(engine, routePresent, feet, context.clientTick());
        watchProgress(context, player, engine, routePresent, calculating || probe.waiting(), feet);
        progress.observe(new WalkRunProgress.Observation(feet, onGround, inWater, routePresent, calculating, noPath));

        if (progress.stopRequested() && onGround && pathing.isSafeToCancel()) {
            // 落地后才真正撤路线：空中撤掉会让执行段把重力全交给身体，摔出可以避免的伤。
            pathing.forceCancel();
        }

        // 走不通：把起点那几格、手边的垫料、许可与附近的岩浆补进问题，LLM 据此调整，而不是只拿到一句"没有路"。
        var spare = owner.spareScaffold(player);
        progress.explainFailure(problem -> WalkFailureFacts.enrich(probe.explain(problem, !spare.isEmpty()),
                context.level(), player, permit, spare));
        if (progress.done()) {
            finishRun(context, player, engine, pathing);
            return;
        }
        steer(context, player, engine, routePresent, calculating);
        engineFailedPath = false;
    }

    // 取走执行段这一刻记下的卡住位置交给卡住记忆；刚列了新障碍的，落地站稳后撤掉旧路线按新的禁入格重算绕行。
    private void handleStall(PathingBehavior pathing, IBaritone engine, ClientLevel level, BlockPos feet) {
        MovementStall stall = pathing.consumeStall();
        if (stall != null) {
            onMovementStalled(stall, level, feet);
        }
        if (replanWhenSafe && pathing.isSafeToCancel()) {
            replanWhenSafe = false;
            pathing.forceCancel();
            engine.getCustomGoalProcess().setGoalAndPath(engineGoal);
        }
    }

    // 执行段放弃了一步：同一面前格第一次卡住，先切换这一步途经的门（没有门就原样重试）；
    // 第二次仍卡住就把面前格列为障碍、重算绕行；障碍用完就如实走不通。整个过程不额外挖也不额外放。
    private void onMovementStalled(MovementStall stall, ClientLevel level, BlockPos feet) {
        if (stall.front() == null || progress.done() || level == null) return;
        BlockPos passage = null;
        boolean open = false;
        for (BlockPos cell : stall.passageCells()) {
            if (!level.isLoaded(cell)) continue;
            BlockState state = level.getBlockState(cell);
            if (!handOpenable(state)) continue;
            BlockPos key = MovementStall.passageKey(cell, state);
            if (stalls.wantOpen(key) != null) continue;
            passage = key;
            open = !state.getValue(BlockStateProperties.OPEN);
            break;
        }
        BlockPos front = stall.front();
        // 这一格这次卡住会被算数（不是已经列了障碍、正等重算的那格）：换个办法重来，算一次真实进展。
        boolean counted = !stalls.obstacles().contains(front.asLong());
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("cause", stall.cause().name().toLowerCase(Locale.ROOT));
        facts.put("movement", stall.movement());
        facts.put("from", stall.src().toShortString());
        facts.put("to", stall.dest().toShortString());
        facts.put("feet_block", level.isLoaded(front) ? level.getBlockState(front).toString() : "unloaded");
        facts.put("head_block", level.isLoaded(front.above()) ? level.getBlockState(front.above()).toString() : "unloaded");
        facts.put("stalled_ticks", stall.ticks());
        NavigationStallMemory.Decision decision = stalls.observe(front, passage, open, facts);
        if (counted && decision != NavigationStallMemory.Decision.EXHAUSTED) {
            displacement.confirm(lastDrivenTick);
        }
        switch (decision) {
            case RETRY -> LOG.info("[maicraft-path] 在 {} 前卡住了一次，原样再试；{}", front.toShortString(), facts);
            case TOGGLE_PASSAGE -> LOG.info("[maicraft-path] 在 {} 前卡住了，先把门 {} 切到 open={} 再试",
                    front.toShortString(), passage.toShortString(), open);
            case OBSTACLE -> {
                LOG.warn("[maicraft-path] 在 {} 前卡住了 {} 次，列为障碍重算绕行；{}",
                        front.toShortString(), NavigationStallMemory.ATTEMPTS, facts);
                NavigationProtection.install(sacredCells(), noEntryCells(), Integer.MIN_VALUE);
                replanWhenSafe = true;
            }
            case EXHAUSTED -> progress.fail(Problem.of(Problem.Kind.STUCK,
                    "在 " + front.toShortString() + " 前又卡住了，已经绕开了 " + NavigationStallMemory.MAX_OBSTACLES
                            + " 个卡住的格子 " + stalls.obstacleCells() + "，路上没挖也没垫",
                    "看看那几格挡着什么，或者把 change_blocks 放开再走"), feet);
        }
    }

    // 徒手能开关的：木门这类（铁门要红石）和栅栏门。
    private static boolean handOpenable(BlockState state) {
        return state.getBlock() instanceof DoorBlock door && door.type().canOpenByHand()
                || state.getBlock() instanceof FenceGateBlock;
    }

    // 引擎按着左右键时真的去挖、去点：挖开一格、垫上一块都算真实进展，挖掘推进中也不算原地卡住，
    // 免得挖一块硬石头的工夫就被"长时间没有位移"判成走不下去。
    private void workBlocks(PlayerContext context, IInputOverrideHandler input) {
        boolean left = input.isInputForcedDown(Input.CLICK_LEFT);
        boolean right = input.isInputForcedDown(Input.CLICK_RIGHT);
        // 镜头还没转到引擎要的瞄点就不下手：准星路过的别的格不能挖、不能点。
        boolean aimed = owner.precisionAimSettled(context.localPlayer(), context.clientTick());
        EngineClicks.Doing doing = clicks.tick(context, left, right, aimed);
        clicks.takePlaced().ifPresent(this::recordPlaced);
        if (doing != EngineClicks.Doing.NOTHING) {
            displacement.confirm(context.clientTick());
        }
    }

    // 普通走路的镜头锁在路线航向上：朝下一个路点算出航向，偏差超过二十五度才开始转、缩到十五度以内就停、
    // 每刻最多转九度，路线里的小修正不转镜头；俯仰平地固定略向下，潜泳时跟着实际潜泳方向。
    // 不追引擎每刻给的方块中心瞄点——那会压低视线、随靠近越来越陡、每段交接弹回，逐格点头。
    private void lookAlongRoute(IBaritone engine, PlayerInput playerInput,
                                PlayerContext context, LocalPlayer player, boolean routePresent) {
        Vec3 anchor = null;
        var executor = engine.getPathingBehavior().getCurrent();
        boolean submerged = false;
        float submergedPitch = 0.0f;
        if (routePresent && executor != null) {
            var positions = executor.getPath().positions();
            if (!positions.isEmpty()) {
                int index = Math.clamp(executor.getPosition() + 1, 0, positions.size() - 1);
                anchor = Vec3.atBottomCenterOf(positions.get(index));
            }
            if (executor instanceof PathExecutor pathExecutor && pathExecutor.submergedWaterTravelActive()) {
                submerged = true;
                submergedPitch = pathExecutor.submergedWaterCameraPitch();
            }
        }
        if (anchor == null) return;
        Vec3 delta = anchor.subtract(player.getEyePosition());
        float rawYaw = (float) (Math.atan2(delta.z, delta.x) * (180 / Math.PI)) - 90.0f;
        float yaw = course.target(rawYaw, context.clientTick());
        float pitch = NavigationCameraCourse.pitch(submergedPitch, submerged);
        playerInput.requestNavigationLook(yaw, pitch, context.clientTick());
    }

    // 人交回了角色：从当时的位置重新算路；人操作的那段不算"长时间没进展"。
    private void resumeAfterHuman(IBaritone engine, PlayerContext context) {
        if (!routeDropped) return;
        routeDropped = false;
        displacement.restart(context.clientTick());
        engine.getCustomGoalProcess().setGoalAndPath(engineGoal);
    }

    // 运行结束：停挖、交还没等到确认的右键，松开全部按键，把身体交回给下一个动作或玩家。
    private void finishRun(PlayerContext context, LocalPlayer player, IBaritone engine, PathingBehavior pathing) {
        clicks.stop(context);
        context.input().releaseAll(player);
        owner.release(this);
        finishEngine(engine, pathing);
    }

    // 按键合成：读引擎按下的方向、跳跃与潜行，交给角色输入入口；每刻续发，缺刻自动松键。
    // 空中落地救援在管这一掉时，按键与镜头归救援：往落点推、按它的意思潜行，镜头它自己转。
    private void steer(PlayerContext context, LocalPlayer player, IBaritone engine, boolean routePresent,
                       boolean calculating) {
        IInputOverrideHandler input = engine.getInputOverrideHandler();
        PlayerInput playerInput = context.input();
        if (landing.steersAirborne()) {
            playerInput.applySteering(yaw -> landing.airborneMovement(context, yaw), player.getYRot(),
                    context.clientTick());
            workBlocks(context, input);
            return;
        }
        float forward = (input.isInputForcedDown(Input.MOVE_FORWARD) ? 1F : 0F)
                - (input.isInputForcedDown(Input.MOVE_BACK) ? 1F : 0F);
        float strafe = (input.isInputForcedDown(Input.MOVE_LEFT) ? 1F : 0F)
                - (input.isInputForcedDown(Input.MOVE_RIGHT) ? 1F : 0F);
        boolean jump = input.isInputForcedDown(Input.JUMP);
        boolean sneak = input.isInputForcedDown(Input.SNEAK);
        boolean sprint = input.isInputForcedDown(Input.SPRINT);
        if (forward != 0F || strafe != 0F || jump || sneak) {
            playerInput.applyNavigationMovement(
                    new PlayerInput.Movement(forward, strafe, jump, sneak, sprint), context.clientTick());
        } else if (routePresent && !calculating) {
            // 路线在手而引擎本刻没按键：保持站立但按住路线朝向，等执行段下一刻的指令。
            playerInput.applyNavigationMovement(PlayerInput.Movement.STOPPED, context.clientTick());
        }
        lookAlongRoute(engine, playerInput, context, player, routePresent);
        workBlocks(context, input);
    }

    // 落地救援这一刻占不占身体：出发前备料、落地后收回时站定，引擎不推进（这几刻站着不算没进展）；
    // 出发前备不成就不往下跳，按危险如实结束这一趟。
    private boolean landingHolds(PlayerContext context, LocalPlayer player, PathingBehavior pathing,
                                 IBaritone engine) {
        return switch (landing.beforeEngine(context, player, pathing)) {
            case ENGINE -> false;
            case HOLD -> {
                displacement.restart(context.clientTick());
                yield true;
            }
            case BLOCKED -> {
                progress.fail(Problem.of(Problem.Kind.DANGER, landing.blockedReason(),
                        "把落地要用的东西放进背包或快捷栏再走，或换一个不用往下跳的目标"), player.blockPosition());
                finishRun(context, player, engine, pathing);
                yield true;
            }
        };
    }

    // 位移与派发观察用于卡住判断：位置累计变化算真实进展，原地踏步不算；搜索或复算在算时不算派发落空。
    private void watchProgress(PlayerContext context, LocalPlayer player, IBaritone engine, boolean routePresent,
                               boolean working, BlockPos feet) {
        Vec3 position = player.position();
        displacement.observe(position.x, position.y, position.z, context.clientTick());
        boolean progressed = displacement.recent(context.clientTick(), 1);
        var dispatchAction = dispatch.observe(context.clientTick(), true, routePresent || working, progressed);
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
    }

    // 引擎说没有路：不许挖的先别认输，做一次"假如允许挖垫"的只读复算，等它回来再定——
    // 复算出不用改地形也有路就在原任务里重新算路，否则按走不通收场、把要动哪些格写进说明。
    // 复算期间引擎又给出了路线（看守重启了搜索），复算作废，照常走。
    private boolean concludeNoPath(IBaritone engine, boolean routePresent, BlockPos feet, long tick) {
        if (routePresent) {
            probe.cancel();
            return false;
        }
        if (!engineFailedPath && !probe.waiting()) return false;
        return switch (probe.step(engine, feet, tick)) {
            case NOT_NEEDED, CONCLUDED -> true;
            case WAITING -> false;
            case RESTART -> {
                engine.getCustomGoalProcess().setGoalAndPath(engineGoal);
                displacement.restart(tick);
                yield false;
            }
        };
    }

    // 挪垫料：没在挪时，站在地上、路线这一两步要垫方块、垫料只在主背包才开始挪；挪的这几刻引擎不推进，角色站定。
    // 挪不成（界面没确认）就如实走不通，写明是哪一步没确认；不在没挪成时让引擎去垫一块够不着的料。
    private boolean stagingScaffold(PlayerContext context, LocalPlayer player, PathingBehavior pathing,
                                    IBaritone engine) {
        if (scaffoldMove == null) {
            if (progress.done() || !player.onGround()) return false;
            var swap = owner.scaffoldStaging(player);
            if (swap.isEmpty() || !nextStepsPlace(engine, pathing)) return false;
            scaffoldMove = hands.actionToHotbar(swap.get().sourceSlot(), swap.get().hotbarSlot(),
                    "把垫脚的 " + swap.get().itemId() + " 挪到快捷栏").orElse(null);
            if (scaffoldMove == null) return false;
            engine.getInputOverrideHandler().clearAllKeys();
        }
        context.input().applyNavigationMovement(PlayerInput.Movement.STOPPED, context.clientTick());
        ActionStatus status = scaffoldMove.tick(TickContext.of(context.level().getGameTime(), context));
        if (status instanceof ActionStatus.Running) return true;
        scaffoldMove = null;
        // 开背包挪料这几刻站着不动是正常的，不算进"长时间没有进展"。
        displacement.restart(context.clientTick());
        if (status instanceof ActionStatus.Failed failed) {
            progress.fail(Problem.of(Problem.Kind.STUCK, "要垫方块而垫料在主背包里，挪到快捷栏时"
                    + failed.problem().message(), null), player.blockPosition());
        }
        return false;
    }

    // 路线当前这一步和下一步要不要垫方块：引擎走完一步会在同一刻接着走下一步，只看当前一步会晚一刻。
    private static boolean nextStepsPlace(IBaritone engine, PathingBehavior pathing) {
        PathExecutor executor = pathing.getCurrent();
        if (executor == null) return false;
        var movements = executor.getPath().movements();
        var blocks = new BlockStateInterface(engine.getPlayerContext());
        int from = Math.max(0, executor.getPosition());
        for (int index = from; index < Math.min(movements.size(), from + 2); index++) {
            if (movements.get(index) instanceof Movement movement) {
                movement.resetBlockCache();
                if (!movement.toPlace(blocks).isEmpty()) return true;
            }
        }
        return false;
    }

    /**
     * 人按 F8 收回了角色：立刻撤掉路线、松开引擎按键、停挖，不让引擎自己的刻接着执行；
     * 这一趟不结算，人交回后从当时的位置重新算路接着走。
     */
    void dropRoute() {
        var pathing = owner.pathing();
        if (pathing != null) pathing.forceCancel();
        owner.engine().getInputOverrideHandler().clearAllKeys();
        clicks.stop(null);
        course.reset();
        // 挪垫料挪到一半也放下：背包界面要是还开着，人自己关；交回后要垫时重新看一遍再挪。
        scaffoldMove = null;
        probe.cancel();
        landing.stop(lastContext, "人接回了角色");
        routeDropped = true;
    }

    private void finishEngine(IBaritone engine, PathingBehavior pathing) {
        probe.cancel();
        landing.stop(lastContext, "这一趟走路结束了");
        pathing.forceCancel();
        engine.getInputOverrideHandler().clearAllKeys();
        NavigationProtection.clear();
    }

    @Override
    public void pause() {
        // 生存需求打断：请求停下，安全时立刻撤路线、松开按键；停稳后这一趟按"已停下"结算，
        // 不会自己续上——调用方恢复时读到"已停下"，从原地重新上路。
        progress.requestStop();
        clicks.stop(lastContext);
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
        clicks.stop(lastContext);
        // 只收拾自己占着的引擎：已经交出身体、或还在排队的运行不能去撤别人正在走的路线。
        if (owner.owns(this)) {
            owner.release(this);
            finishEngineQuietly();
        } else {
            owner.dropQueued(this);
        }
    }

    private void finishEngineQuietly() {
        probe.cancel();
        landing.stop(lastContext, "这一趟走路结束了");
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
