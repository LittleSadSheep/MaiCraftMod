// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.pathing.baritone;

import baritone.Baritone;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.event.events.PathEvent;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.WorldEvent;
import baritone.api.event.events.type.EventState;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.behavior.LookBehavior;
import baritone.behavior.PathingBehavior;
import baritone.pathing.path.PathExecutor;
import baritone.pathing.movement.movements.MovementFall;
import baritone.utils.InputOverrideHandler;
import java.util.List;
import java.util.function.BiFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.HitResult;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.Constants;
import org.maiwithu.maicraft.core.pathing.goal.GoalCompiler;
import org.maiwithu.maicraft.core.pathing.moves.TerrainPermit;
import org.maiwithu.maicraft.core.pathing.settings.ScaffoldMaterials;
import org.maiwithu.maicraft.entity.InputDriver;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.CollisionGeometry;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.DefaultBodyControlPort;
import org.maiwithu.maicraft.core.integration.create.ContraptionObstacles;
import org.maiwithu.maicraft.core.integration.physics.PhysicalObstacleSnapshot;
import org.maiwithu.maicraft.core.pathing.baritone.landing.AirLandingControl;
import org.maiwithu.maicraft.core.pathing.baritone.landing.LandingAssistPlan;
import org.maiwithu.maicraft.core.pathing.baritone.landing.LandingAssistPolicy;
import org.maiwithu.maicraft.core.pathing.calc.NavGoal;
import org.maiwithu.maicraft.core.pathing.debug.NavigationPathSnapshot;
import org.maiwithu.maicraft.core.pathing.execute.NavigationStep;
import org.maiwithu.maicraft.core.pathing.moves.movements.BuildPlacementRegistry;
import org.maiwithu.maicraft.core.pathing.transport.TransportRuntime;

/**
 * 全局只保留一个实际操纵玩家的 Baritone 实例。每次由当前任务提出驱动要求，再在角色更新末尾统一推进导航和输入。
 * 新导航要等旧导航能安全交接；这里也负责世界切换、保护范围更新、行走视角以及游泳／落地时的输入衔接。
 */
public final class EmbeddedBaritoneRuntime {
    private static long lastSwimDriveTick = Long.MIN_VALUE;
    private static final EmbeddedBaritoneActionBridge ACTIONS =
            new EmbeddedBaritoneActionBridge();
    private static final EmbeddedBuildScaffoldSelection BUILD_SCAFFOLDS = new EmbeddedBuildScaffoldSelection();
    private static IBaritone backend;
    private static EmbeddedBaritoneNavigator owner;
    private static ClientLevel world;
    private static LocalPlayerContext tickingContext;
    private static EmbeddedBaritoneNavigator pendingPolicyOwner;
    private static GoalCompiler.Compiled pendingPolicyGoal;
    private static PendingStart pendingStart;
    private static volatile PhysicalObstacleSnapshot physicalObstacles =
            PhysicalObstacleSnapshot.EMPTY;
    private static long physicalObservationTick = Long.MIN_VALUE;
    private static Vec3 physicalObservationOrigin;
    private static PhysicalObstacleSnapshot sableObstacles=
            PhysicalObstacleSnapshot.EMPTY;

    public static PhysicalObstacleSnapshot physicalObstacles() {
        return physicalObstacles;
    }

    // 大型物理结构观察每五刻或移动四格后刷新一次；Create 移动结构每次重新合入，供路线避障使用。
    private static void refreshPhysicalObstacles(LocalPlayer player) {
        long tick = player.level().getGameTime();
        if (physicalObservationOrigin == null || tick < physicalObservationTick
                || tick - physicalObservationTick >= 5 || physicalObservationOrigin.distanceToSqr(player.position()) >= 16) {
            sableObstacles = PhysicalObstacleSnapshot.capture(player.clientLevel,player.position());
            physicalObservationTick=tick; physicalObservationOrigin=player.position();
        }
        physicalObstacles=sableObstacles.plus(ContraptionObstacles.capture(player.clientLevel,player.position()));
    }

    private record PendingStart(
            EmbeddedBaritoneNavigator navigator,
            GoalCompiler.Compiled compiled,
            TerrainPermit permit,
            boolean sprintAllowed,
            EmbeddedBaritonePolicy.Snapshot policy) {
        private PendingStart(EmbeddedBaritoneNavigator navigator, GoalCompiler.Compiled compiled,
                             TerrainPermit permit, boolean sprintAllowed) {
            this(navigator, compiled, permit, sprintAllowed,
                    EmbeddedBaritonePolicy.capture(compiled.sacred(),
                            navigator.protectedMutationCells(), navigator.forbiddenBodyCells(), navigator.minimumFeetY()));
        }
    }

    private EmbeddedBaritoneRuntime() {}

    /** 先退役旧身体的活动路线和排队请求，再让本刻的新任务争取控制；死亡后不能等待旧角色落地。 */
    public static void observeBody(LocalPlayer current) {
        requireClientThread();
        if (owner != null && !owner.belongsTo(current)) {
            var previous = owner;
            previous.preempted("the local-player body or world was replaced");
            // abandon只清导航状态和旧回执，不通过InputDriver停止已经不存在的身体，也不操作新角色。
            abandon(previous);
        }
        if (pendingStart != null && !pendingStart.navigator().belongsTo(current)) {
            pendingStart.navigator().preempted("the queued navigation's body or world was replaced");
            pendingStart = null;
        }
        if (pendingPolicyOwner != null && !pendingPolicyOwner.belongsTo(current)) {
            pendingPolicyOwner = null; pendingPolicyGoal = null;
        }
        if (backend != null && current != null && current.clientLevel != null) syncWorld(backend, current.clientLevel);
    }

    /** 可选的实时诊断查询，不会为此启动原本空闲的 Baritone 实例。 */
    public static Map<String, Object> diagnosticState() {
        requireClientThread();
        var result = new LinkedHashMap<String, Object>();
        result.put("has_owner", owner != null);
        result.put("pending_owner", pendingStart != null);
        result.put("last_drive_tick", lastSwimDriveTick);
        result.put("physical_obstacles", Map.of("state", physicalObstacles.state(),
                "boxes", physicalObstacles.boxes().size(), "block_reads", physicalObstacles.blockReads(),
                "conservative_structures", physicalObstacles.conservativeStructures(), "game_time", physicalObservationTick));
        if (backend == null) return result;
        var pathing = (PathingBehavior) backend.getPathingBehavior();
        result.put("planning", pathing.getInProgress().isPresent());
        result.put("safe_to_cancel", pathing.isSafeToCancel());
        var executor = pathing.getCurrent();
        if (executor == null) return result;
        int index = executor.getPosition();
        var movements = executor.getPath().movements();
        result.put("path_index", index);
        result.put("path_length", movements.size());
        if (index >= 0 && index < movements.size()) {
            var movement = movements.get(index);
            result.put("movement", movement.getClass().getSimpleName());
            result.put("from", movement.getSrc().toShortString());
            result.put("to", movement.getDest().toShortString());
        }
        result.put("input", backend.getInputOverrideHandler().isInputForcedDown(Input.MOVE_FORWARD));
        return result;
    }

    /** 开发者覆盖层只观察当前选中的执行器，不读取搜索工作线程。 */
    public static NavigationPathSnapshot debugPath() {
        requireClientThread();
        if (owner == null || backend == null || world != Minecraft.getInstance().level) return null;
        var executor = ((PathingBehavior) backend.getPathingBehavior()).getCurrent();
        if (executor == null) return null;
        var positions = executor.getPath().positions();
        if (positions.isEmpty()) return null;
        int cursor = Math.clamp(executor.getPosition(), 0, positions.size() - 1);
        int start = Math.max(0, cursor - 1);
        var points = positions.subList(start, Math.min(positions.size(), start + 512)).stream()
                .map(pos -> Vec3.atBottomCenterOf(pos).add(0, 0.08, 0)).toList();
        var destination = Vec3.atBottomCenterOf(positions.getLast()).add(0, 0.08, 0);
        var next = Vec3.atBottomCenterOf(
                positions.get(Math.min(cursor + 1, positions.size() - 1))).add(0, 0.08, 0);
        return new NavigationPathSnapshot(
                points, cursor - start, destination, next);
    }

    static void startOrUpdate(
            EmbeddedBaritoneNavigator navigator,
            GoalCompiler.Compiled compiled,
            TerrainPermit permit,
            boolean sprintAllowed) {
        requireClientThread();
        observeBody(Minecraft.getInstance().player);
        if (!navigator.belongsTo(Minecraft.getInstance().player)) {
            navigator.preempted("navigation cannot acquire a replaced local-player body"); return;
        }
        IBaritone baritone = backend();
        syncWorld(baritone, Minecraft.getInstance().level);
        // 已有别的导航占用身体时，把新请求排为待接手，并让旧导航先到达安全停止位置。
        if (owner != null && owner != navigator) {
            EmbeddedBaritoneNavigator previous = owner;
            queuePendingStart(new PendingStart(navigator, compiled, permit, sprintAllowed));
            previous.preemptWhenSafe(
                    "another MaiCraft navigation requested the first-person body");
            if (owner == null) promotePendingStart(baritone);
            return;
        }

        if (owner == null) {
            queuePendingStart(new PendingStart(navigator, compiled, permit, sprintAllowed));
            promotePendingStart(baritone);
            return;
        }

        configure(baritone, permit, sprintAllowed);
        boolean policyChanged = EmbeddedBaritonePolicy.install(
                compiled.sacred(),
                navigator.protectedMutationCells(),
                navigator.forbiddenBodyCells(), navigator.minimumFeetY());
        PathingBehavior pathing = (PathingBehavior) baritone.getPathingBehavior();
        // 只有目标在移动、保护范围没有变时才保留路径；真正的安全策略变更仍走下方安全交接与强制重规划。
        if (navigator.tracksMovingGoal() && !policyChanged && pendingPolicyOwner != navigator) {
            baritone.getCustomGoalProcess().updateGoalAndPath(new MaiCraftGoalAdapter(compiled.goal()));
            return;
        }
        if (!pathing.isSafeToCancel()) {
            pendingPolicyOwner = navigator;
            pendingPolicyGoal = compiled;
            return;
        }
        replaceActiveRoute(navigator, compiled, currentContext(),
                "the navigation goal contract was refreshed");
    }

    static boolean refreshPolicy(
            EmbeddedBaritoneNavigator navigator,
            GoalCompiler.Compiled compiled) {
        if (owner != navigator) return false;
        boolean changed = EmbeddedBaritonePolicy.install(
                compiled.sacred(),
                navigator.protectedMutationCells(),
                navigator.forbiddenBodyCells(), navigator.minimumFeetY());
        if (!changed || backend == null) return changed;
        PathingBehavior pathing = (PathingBehavior) backend.getPathingBehavior();
        if (!pathing.isSafeToCancel()) {
            pendingPolicyOwner = navigator;
            pendingPolicyGoal = compiled;
            return true;
        }
        replaceActiveRoute(navigator, compiled, currentContext(),
                "navigation safety policy changed while a route was active");
        return true;
    }

    /**
     * 为当前所有者提交一次只读的地形变化路线复核。策略会随失败的保留地形计算一起冻结；返回路线仅作诊断证据，绝不会安装到实时执行器中。
     */
    static EmbeddedBaritoneTerrainProbe.ProbeFuture submitTerrainProbe(
            EmbeddedBaritoneNavigator navigator,
            BlockPos start,
            NavGoal goal) {
        requireClientThread();
        if (owner != navigator || backend == null) return null;
        return EmbeddedBaritoneTerrainProbe.submit(
                backend, start, goal, EmbeddedBaritonePolicy.snapshot());
    }

    static EmbeddedBaritonePolicy.Snapshot policySnapshot(
            EmbeddedBaritoneNavigator navigator) {
        requireClientThread();
        return owner == navigator ? EmbeddedBaritonePolicy.snapshot() : null;
    }

    /** 在一次有效的 MaiCraft 角色 tick 结束时恰好调用一次。 */
    public static void tick(LocalPlayerContext context, boolean schedulerAllowsBodyWork) {
        requireClientThread();
        observeBody(context.player());
        lastDrivenOwner = null;
        IBaritone baritone = backend;
        if (baritone == null) return;
        syncWorld(baritone, context.level());
        promotePendingStart(baritone);

        EmbeddedBaritoneNavigator current = owner;
        if (current == null) {
            // 内嵌后端不拥有本 tick 的身体控制权。语义任务可能刚释放导航，就立刻发出自己的近距离移动指令，例如拾取掉落物、接近传送门或交接船只。
            // 此处若执行停止，会让休眠后端成为最后写入者并抹掉合法指令。
            baritone.getInputOverrideHandler().clearAllKeys();
            ((LookBehavior) baritone.getLookBehavior()).clearTarget();
            ACTIONS.suspendAny(context,
                    "the navigation owner released its first-person body");
            return;
        }
        // 任务请求终态后可能已丢弃最后一个 PlayerNav 引用。若上个 tick 已抵达安全移动边界，就在此结算；若角色仍在空中，即使常规调度工作已切换，也只保留已选中的移动。
        // 清除其转向会让清理过程变成可以避免的坠落。
        current.settlePendingFailureAtSafeBoundary();
        if (owner != current) {
            promotePendingStart(baritone);
            return;
        }
        boolean requested = current.consumeDriveRequest();
        // 普通推进需要本次调度允许且导航提出驱动请求；已经失去调用者但仍要安全落地的旧导航，可继续必要收尾。
        boolean drive = current.requiresOrphanContinuation()
                || (schedulerAllowsBodyWork && requested);
        if (!drive) {
            BUILD_SCAFFOLDS.cancel(current);
            lastSwimDriveTick = Long.MIN_VALUE;
            baritone.getInputOverrideHandler().clearAllKeys();
            ((LookBehavior) baritone.getLookBehavior()).clearTarget();
            ACTIONS.suspend(context, current,
                    "navigation did not retain physical drive this tick");
            return;
        }

        if (pendingPolicyOwner == current
                && pendingPolicyGoal != null
                && ((PathingBehavior) baritone.getPathingBehavior()).isSafeToCancel()) {
            replaceActiveRoute(current, pendingPolicyGoal, context,
                    "a deferred navigation contract became safe to apply");
            if (!context.mutationAvailable()) {
                baritone.getInputOverrideHandler().clearAllKeys();
                ((LookBehavior) baritone.getLookBehavior()).clearTarget();
                return;
            }
        }

        try {
            if (prepareBuildScaffold(context, current)) {
                baritone.getInputOverrideHandler().clearAllKeys();
                InputDriver.halt(context.player());
                return;
            }
            refreshPhysicalObstacles(context.player());
            lastSwimDriveTick = context.level().getGameTime();
            tickingContext = context;
            BiFunction<EventState, TickEvent.Type, TickEvent> events =
                    TickEvent.createNextProvider();
            baritone.getGameEventHandler().onTick(
                    events.apply(EventState.PRE, TickEvent.Type.IN));
            baritone.getGameEventHandler().onPostTick(
                    events.apply(EventState.POST, TickEvent.Type.IN));
            lastDrivenOwner = current;
            lastDrivenRevision = context.tickRevision();
        } catch (RuntimeException failure) {
            Constants.LOG.error("[maicraft-path] embedded Baritone tick failed", failure);
            current.internalFailure("embedded pathing tick failed: "
                    + failure.getClass().getSimpleName());
            release(current);
        } finally {
            tickingContext = null;
        }
        if (owner == current) current.settlePendingFailureAtSafeBoundary();
        if (owner != current) promotePendingStart(baritone);
    }

    static void suspend(EmbeddedBaritoneNavigator navigator) {
        if (owner != navigator || backend == null) return;
        BUILD_SCAFFOLDS.cancel(navigator);
        lastSwimDriveTick = Long.MIN_VALUE;
        backend.getInputOverrideHandler().clearAllKeys();
        ((LookBehavior) backend.getLookBehavior()).clearTarget();
        ACTIONS.suspend(currentContext(), navigator, "navigation suspended");
    }

    /**
     * 在保留已计算路线的同时，仅将身体控制输出让给优先级更高的第一人称行为；此方法本身不写入身体状态，由获胜行为负责本 tick 的控制。
     */
    public static void suspendActivePhysicalOutputs() {
        requireClientThread();
        lastSwimDriveTick = Long.MIN_VALUE;
        if (backend == null || owner == null) return;
        backend.getInputOverrideHandler().clearAllKeys();
        ((LookBehavior) backend.getLookBehavior()).clearTarget();
        ACTIONS.suspend(currentContext(), owner,
                "navigation yielded to another first-person winner");
    }

    /**
     * 判断活动路线是否处于可进行物理交接的节点。坠落或已启动的跑酷移动必须保留转向，直到进入可取消状态；先清除按键再询问能否交接，会让调度抢占造成可避免的坠落。
     */
    public static boolean canSafelySuspendActive() {
        requireClientThread();
        return backend == null || owner == null
                || ((PathingBehavior) backend.getPathingBehavior()).isSafeToCancel();
    }

    /** 路线停止后仍可能持有着陆动作和物品选择交易。 */
    public static boolean yieldActiveForExternalAction(LocalPlayer player) {
        requireClientThread();
        observeBody(player);
        LocalPlayerContext context = ClientRuntime.requireContext(player);
        if (owner != null) owner.settlePendingFailureAtSafeBoundary();
        if (owner != null && owner.requiresOrphanContinuation()) return false;
        return owner == null ? context.mutationAvailable() : owner.yieldForExternalAction();
    }

    /** 普通跳跃会继续保持轨迹，直到脚位确实越过目标支撑面。 */
    public static boolean canHandOffMissedLanding(LocalPlayer player) {
        requireClientThread();
        if (player == null || owner == null || backend == null || world != player.clientLevel
                || backend.getPlayerContext().player() != player || player.onGround()
                || player.getDeltaMovement().y >= 0
                || TransportRuntime.occupied()) return false;
        var executor = backend.getPathingBehavior().getCurrent();
        if (executor == null) return false;
        var movements = executor.getPath().movements();
        int cursor = executor.getPosition();
        if (cursor < 0 || cursor >= movements.size() || movements.get(cursor) instanceof MovementFall) return false;
        var support = movements.get(cursor).getDest().below();
        if (!world.isLoaded(support)) return false;
        double height = CollisionGeometry.supportHeight(world, support);
        return Double.isFinite(height) && height > 0 && player.getBoundingBox().minY < support.getY() + height - 0.01;
    }

    /** 仅在 MLG 解析出合法后续动作后、写入本 tick 输入前调用。 */
    // 确认已经错过原落脚面后，解除旧路线的控制，把后续救援交给专门落地逻辑。
    public static boolean handOffMissedLanding(LocalPlayer player) {
        if (!canHandOffMissedLanding(player)) return false;
        var previous = owner;
        previous.detachForLandingRescue();
        release(previous);
        return true;
    }

    static void release(EmbeddedBaritoneNavigator navigator) {
        requireClientThread();
        if (owner != navigator) return;
        BUILD_SCAFFOLDS.cancel(navigator);
        if (backend != null) {
            backend.getPathingBehavior().forceCancel();
            backend.getInputOverrideHandler().clearAllKeys();
            ((LookBehavior) backend.getLookBehavior()).clearTarget();
            ACTIONS.suspend(currentContext(), navigator, "navigation ended");
            ((PathingBehavior) backend.getPathingBehavior()).discardPendingPathEvents();
        }
        EmbeddedBaritonePolicy.clear();
        pendingPolicyOwner = null;
        pendingPolicyGoal = null;
        CAMERA_COURSE.reset();
        owner = null;
    }

    public static void bodyGone() {
        requireClientThread();
        lastDrivenOwner = null;
        if (owner != null) owner.preempted("the local-player body or world disappeared");
        if (pendingStart != null) {
            pendingStart.navigator().preempted(
                    "the local-player body or world disappeared before body acquisition");
        }
        if (backend != null) {
            backend.getPathingBehavior().forceCancel();
            backend.getInputOverrideHandler().clearAllKeys();
            ((LookBehavior) backend.getLookBehavior()).clearTarget();
            ACTIONS.suspend(currentContext(), owner,
                    "the local-player body or world disappeared");
            ((PathingBehavior) backend.getPathingBehavior()).discardPendingPathEvents();
            backend.getGameEventHandler().onWorldEvent(
                    new WorldEvent(null, EventState.POST));
        }
        ACTIONS.bodyGone();
        BUILD_SCAFFOLDS.reset();
        owner = null;
        world = null;
        pendingPolicyOwner = null;
        pendingPolicyGoal = null;
        pendingStart = null;
        EmbeddedBaritonePolicy.clear();
        CAMERA_COURSE.reset();
    }

    static void abandon(EmbeddedBaritoneNavigator navigator) {
        requireClientThread();
        if (pendingStart != null && pendingStart.navigator() == navigator) pendingStart = null;
        if (owner != navigator) return;
        if (backend != null) {
            // 内嵌 BlockBreakHelper 只记录本地停止请求；手动接管或世界退出后的原生回执失效由角色边界处理。
            backend.getPathingBehavior().forceCancel();
            backend.getInputOverrideHandler().clearAllKeys();
            ((LookBehavior) backend.getLookBehavior()).clearTarget();
            ((PathingBehavior) backend.getPathingBehavior()).discardPendingPathEvents();
        }
        ACTIONS.bodyGone();
        BUILD_SCAFFOLDS.reset();
        owner = null; pendingPolicyOwner = null; pendingPolicyGoal = null;
        EmbeddedBaritonePolicy.clear();
        CAMERA_COURSE.reset();
    }

    static boolean hasConcretePath(EmbeddedBaritoneNavigator navigator) {
        return owner == navigator && backend != null
                && backend.getPathingBehavior().getCurrent() != null;
    }

    static boolean planningInFlight(EmbeddedBaritoneNavigator navigator) {
        return owner == navigator && backend != null
                && backend.getPathingBehavior().getCurrent() == null
                && backend.getPathingBehavior().getInProgress().isPresent();
    }

    private static EmbeddedBaritoneNavigator lastDrivenOwner;
    private static long lastDrivenRevision = Long.MIN_VALUE;

    static NavigationStep executionStep(
            EmbeddedBaritoneNavigator navigator, long clientRevision) {
        requireClientThread();
        if (owner != navigator || backend == null || lastDrivenOwner != navigator
                || lastDrivenRevision != clientRevision - 1 || ACTIONS.pending()) return null;
        var executor = ((PathingBehavior) backend.getPathingBehavior()).getCurrent();
        if (executor == null) return null;
        int index = executor.getPosition();
        var movements = executor.getPath().movements();
        if (index < 0 || index >= movements.size()) return null;
        var movement = movements.get(index);
        return new NavigationStep(movement.getSrc(), movement.getDest());
    }

    /** 正在执行的水路由负责常规上浮和补气；反射链只处理空闲或挂起的身体。 */
    public static boolean managesSwimAir(LocalPlayer player) {
        if (player == null || backend == null || owner == null
                || backend.getPlayerContext().player() != player
                || lastSwimDriveTick == Long.MIN_VALUE
                || player.level().getGameTime() - lastSwimDriveTick > 1
                || !DefaultBodyControlPort.permitsWorldMovement(
                        Minecraft.getInstance().screen)) return false;
        return backend.getPathingBehavior().getCurrent() instanceof PathExecutor executor
                && executor.submergedWaterManagesAir();
    }

    static boolean isSafeToCancel(EmbeddedBaritoneNavigator navigator) {
        return owner != navigator || backend == null
                || ((PathingBehavior) backend.getPathingBehavior()).isSafeToCancel();
    }

    static BlockPos pathStart(EmbeddedBaritoneNavigator navigator, BlockPos fallback) {
        if (owner != navigator || backend == null || backend.getPlayerContext().player() == null) {
            return fallback;
        }
        return ((PathingBehavior) backend.getPathingBehavior()).pathStart();
    }

    static HitResult objectMouseOver(EmbeddedBaritoneNavigator navigator) {
        if (owner != navigator || backend == null || backend.getPlayerContext().player() == null) {
            return null;
        }
        return backend.getPlayerContext().objectMouseOver();
    }

    static MovementFall currentFall(EmbeddedBaritoneNavigator navigator) {
        if (owner != navigator || backend == null) return null;
        var executor = backend.getPathingBehavior().getCurrent();
        if (executor == null) return null;
        int index = executor.getPosition();
        var movements = executor.getPath().movements();
        return index >= 0 && index < movements.size()
                && movements.get(index) instanceof MovementFall fall ? fall : null;
    }

    /** 已调度的坠落会持有物品和恢复控制权，直到移动稳定完成。 */
    public static boolean ownsActiveLandingAssist(LocalPlayer player) {
        if (player == null || owner == null || backend == null || world != player.clientLevel
                || backend.getPlayerContext().player() != player) return false;
        MovementFall fall = currentFall(owner);
        if (fall == null) return false;
        var assist = fall.landingAssist();
        if (assist != null && (!assist.complete() || assist.cleanupPending())) return true;
        var boat = fall.landingBoat();
        return boat != null && (!boat.failed() || boat.cleanupPending());
    }

    /** 角色租约有效期间，由适配后的上游输入行为调用。 */
    public static void applyActionState(InputOverrideHandler input) {
        if (BUILD_SCAFFOLDS.pending()) return;
        LocalPlayerContext context = tickingContext;
        EmbeddedBaritoneNavigator current = owner;
        if (context == null || current == null) return;
        ACTIONS.tick(context, current, input);
    }

    /** 供上游移动代码兼容使用的快捷栏选择钩子。 */
    public static boolean ensureHotbarSelected(LocalPlayer player, int slot) {
        LocalPlayerContext context = tickingContext;
        EmbeddedBaritoneNavigator current = owner;
        return context != null && current != null
                && ACTIONS.ensureHotbarSelected(context, current, player, slot);
    }

    /** 建造任务会保留永久材料，即使唯一多余的脚手架方块不在快捷栏中也一样。 */
    public static boolean selectBuildScaffold(LocalPlayer player, boolean select) {
        var choice = BuildPlacementRegistry.scaffoldChoice(player);
        if (choice == null) return false;
        if (!select) return true;
        if (tickingContext == null || owner == null || ACTIONS.pending()) return false;
        if (choice.inventorySlot() < 9 && !BUILD_SCAFFOLDS.pending())
            return ACTIONS.ensureHotbarSelected(tickingContext, owner, player, choice.inventorySlot());
        if (!player.onGround() && !BUILD_SCAFFOLDS.pending()) return false;
        return BUILD_SCAFFOLDS.select(tickingContext, owner, player);
    }

    private static boolean prepareBuildScaffold(LocalPlayerContext context, EmbeddedBaritoneNavigator current) {
        if (BUILD_SCAFFOLDS.pending()) return !BUILD_SCAFFOLDS.advance(context, current);
        if (current.permit() != TerrainPermit.TERRAFORM || !context.player().onGround() || ACTIONS.pending()
                || !BuildPlacementRegistry.hasScaffoldMaterialPolicy()) return false;
        var choice = BuildPlacementRegistry.scaffoldChoice(context.player());
        if (choice == null || choice.inventorySlot() < 9) return false;
        // 角色落地时先准备材料，避免 Baritone 在材料选择完成前启动依赖放置的跳跃。
        return !BUILD_SCAFFOLDS.select(context, current, context.player());
    }

    /** 为上游取消逻辑提供兼容钩子；此处不会调用游戏模式接口。 */
    public static void requestStopBreaking() {
        ACTIONS.requestStopBreaking();
    }

    /** 角色租约有效期间，由适配后的上游输入行为调用。 */
    // 把 Baritone 请求的按键交给角色输入层；游泳、收回落地用品或乘船救援有更具体的控制要求时，采用这些要求。
    public static void applyInputState(InputOverrideHandler input) {
        if (backend == null || owner == null) return;
        var player = backend.getPlayerContext().player();
        if (player == null) return;
        if (BUILD_SCAFFOLDS.pending()) { InputDriver.halt(player); return; }
        var currentExecutor = backend.getPathingBehavior().getCurrent();
        PathExecutor executor = currentExecutor instanceof PathExecutor pathExecutor
                ? pathExecutor : null;
        float forward = (input.isInputForcedDown(Input.MOVE_FORWARD) ? 1F : 0F)
                - (input.isInputForcedDown(Input.MOVE_BACK) ? 1F : 0F);
        float strafe = (input.isInputForcedDown(Input.MOVE_LEFT) ? 1F : 0F)
                - (input.isInputForcedDown(Input.MOVE_RIGHT) ? 1F : 0F);
        boolean jump = input.isInputForcedDown(Input.JUMP);
        boolean sneak = input.isInputForcedDown(Input.SNEAK);
        boolean sprint = input.isInputForcedDown(Input.SPRINT)
                || (executor != null && executor.isSprinting());
        // 游泳阶段同时管理疾跑和深度；上浮或补气需要角色保持直立姿态。
        if (player.isInWater()) {
            if (executor != null && executor.submergedWaterTravelActive()) {
                sprint = executor.submergedWaterSprinting() && forward > 0.5F;
            } else if (forward > 0.5F) sprint = true;
            if (executor != null) {
                int vertical = executor.waterVerticalIntent();
                if (vertical < 0) {
                    sneak = true;
                    jump = false;
                } else if (vertical > 0) {
                    jump = true;
                    sneak = false;
                } else if (executor.submergedWaterTravelActive()) {
                    // 上游通用水中浮动逻辑会请求 JUMP；当选定路线要求保持巡航深度时，中性垂直意图表示维持深度，而不是浮出水面。
                    jump = false;
                    sneak = false;
                }
            }
        }
        MovementFall assistedFall = currentFall(owner);
        if (tickingContext != null && assistedFall != null && assistedFall.landingAssist() != null
                && assistedFall.landingAssist().holdingForRecovery(tickingContext)) {
            // 通用游泳输入不能让角色跳出救援所需的水域，也不能与其恢复瞄准冲突。
            forward = 0; strafe = 0; jump = false; sneak = assistedFall.landingAssist().wantsSneak(tickingContext); sprint = false;
        }
        var landingMovement = assistedFall != null && assistedFall.landingBoat() != null
                ? assistedFall.landingBoat().movementOverride() : null;
        // Boat.interact 会拒绝副手使用。在进入上船窗口之前、下一个玩家物理或输入 tick 到来前，就要释放普通坠落时的边缘潜行。
        if(assistedFall!=null && assistedFall.landingBoat()!=null) sneak=assistedFall.landingBoat().wantsSneak();
        else if(tickingContext!=null && assistedFall!=null && assistedFall.landingAssist()!=null
                && assistedFall.landingAssist().plan().kind()==LandingAssistPlan.Kind.BOAT)
            sneak=assistedFall.landingAssist().wantsSneak(tickingContext);
        if (landingMovement==null && assistedFall!=null && assistedFall.landingAssist()!=null)
            landingMovement=assistedFall.landingAssist().movementOverride();
        if (landingMovement != null) {
            forward = landingMovement.forward(); strafe = landingMovement.strafe();
            jump = landingMovement.jumping(); sneak = landingMovement.sneaking(); sprint = landingMovement.sprinting();
        }
        if (tickingContext!=null && assistedFall!=null && assistedFall.landingAssist()!=null
                && !player.onGround() && !player.isPassenger() && landingMovement==null
                && !assistedFall.landingAssist().holdingForRecovery(tickingContext)) {
            var landing=assistedFall.landingAssist().plan().feet(); final boolean crouch=sneak;
            tickingContext.body().applySteering(yaw -> AirLandingControl
                    .movement(player,landing,yaw,crouch),player.getYRot(),tickingContext.tickRevision());
            return;
        }
        InputDriver.applyMovement(player, forward, strafe, jump, sneak, sprint);
    }

    /** 由适配后的 LookBehavior 调用；DefaultBodyControlPort 仍是唯一的镜头控制者。
     *
     * <p>移动时镜头沿已确定路线朝向，不追随 Baritone 每 tick 提供的方块中心瞄准点。原始瞄准流会压低视线对准低于眼高的格子，
     * 随角色接近而越来越陡，并在每次移动交接时弹回，造成逐格点头、全程低头的明显抖动。
     * 此处将镜头锁定在路线航向上；在路线转弯窗口内的细微方向修正不会转动可见镜头。
     * 实际移动朝向由 Baritone 的玩家旋转桥单独提供，因此镜头不会把该修正拆成第二个转向输入。
     * 只有真正转角时才提交新的可见航向，并让镜头转动一次。
     * 落地时俯仰保持接近水平的观景角度；空中时跟随瞄准，保证坠落和跳跃仍可操控。
     * 精确方块交互瞄准会绕过以上逻辑，让挖掘和放置保留准确视角。</p>
     */
    // 实际点击所需的精确瞄准直接交给视角层；普通走路用缓慢改变的路线朝向，地面视角保持略向下。
    public static void requestLook(float yaw, float pitch, boolean precisionAim) {
        if (backend == null || owner == null || backend.getPlayerContext().player() == null) return;
        var player = backend.getPlayerContext().player();
        if (precisionAim) {
            InputDriver.look(player, yaw, pitch);
            return;
        }
        var currentExecutor = backend.getPathingBehavior().getCurrent();
        PathExecutor executor = currentExecutor instanceof PathExecutor pathExecutor
                ? pathExecutor : null;
        if (executor != null && executor.submergedWaterTravelActive()) {
            pitch = executor.submergedWaterCameraPitch();
        }
        if (tickingContext == null) return;
        float courseYaw = CAMERA_COURSE.target(yaw, tickingContext.tickRevision());
        // 即使视角没有变化，也必须每 tick 续发，保持镜头租约有效；否则镜头会冻结在上一次精确瞄准留下的方向。
        float walkPitch = player.onGround()
                && !(executor != null && executor.submergedWaterTravelActive())
                ? WALK_PITCH_DEGREES
                : Mth.clamp(pitch, -90.0f, 90.0f);
        InputDriver.look(player, courseYaw, walkPitch);
    }

    /** 路线转向状态；导航所有权变化时重置。 */
    private static final NavigationCameraCourse CAMERA_COURSE = new NavigationCameraCourse();
    /** 落地行走时的俯仰角保持接近水平，避免整段旅程看起来都在低头。 */
    private static final float WALK_PITCH_DEGREES = 8.0f;

    private static IBaritone backend() {
        if (backend != null) return backend;
        IBaritone created = BaritoneAPI.getProvider().getPrimaryBaritone();
        created.getGameEventHandler().registerEventListener(new AbstractGameEventListener() {
            @Override
            public void onPathEvent(PathEvent event) {
                EmbeddedBaritoneNavigator current = owner;
                if (current != null) current.onPathEvent(event);
            }
        });
        backend = created;
        return created;
    }

    private static void configure(
            IBaritone baritone,
            TerrainPermit permit,
            boolean sprintAllowed) {
        Settings settings = BaritoneAPI.getSettings();
        settings.chatControl.value = false;
        settings.chatControlAnyway.value = false;
        settings.renderPath.value = false;
        settings.renderGoal.value = false;
        settings.notificationOnPathComplete.value = false;
        settings.disconnectOnArrival.value = false;
        settings.freeLook.value = false;
        settings.smoothLook.value = false;
        settings.randomLooking.value = 0D;
        settings.randomLooking113.value = 0D;
        configureWalking(settings, sprintAllowed);
        settings.sprintAscends.value = true;
        settings.sprintInWater.value = true;
        configureTerrain(settings, permit);
        // 当前关闭 Baritone 自行整理普通背包的行为；这条桥只直接支持已在快捷栏中的选择，不能据携带总量推定马上可用。
        settings.allowInventory.value = false;
        settings.acceptableThrowawayItems.value = ScaffoldMaterials.of(
                baritone.getPlayerContext().player());
        settings.logger.value = message -> Constants.LOG.debug(
                "[embedded-path] {}", message.getString());
    }

    static void configureWalking(Settings settings, boolean sprintAllowed) {
        // 精细施工步行保持连续落脚，不为短距换站位安排跨空跑酷；普通长途导航仍保留原有跳跃能力。
        settings.allowSprint.value = sprintAllowed;
        settings.allowParkour.value = sprintAllowed;
    }

    // 把本次许可明确写到 Baritone：能否挖、能否搭路、能否用水桶分别从这份许可决定，不沿用上一次导航的状态。
    static void configureTerrain(Settings settings, TerrainPermit permit) {
        LandingAssistPolicy.configure(permit);
        settings.allowBreak.value = permit.mayAlter();
        settings.allowBreakAnyway.value = List.of();
        settings.allowPlace.value = permit.mayAlter();
        settings.allowParkourPlace.value = permit.mayAlter();
        settings.allowDownward.value = permit.mayAlter();
        // 让旧版设置与当前路线请求保持一致。自动自救会单独捕获仅限着陆的策略，不会因此授权一般地形修改。
        settings.allowWaterBucketFall.value = permit.mayUseWaterBucket();
    }

    private static void syncWorld(IBaritone baritone, ClientLevel current) {
        if (world == current) return;
        physicalObstacles = PhysicalObstacleSnapshot.EMPTY;
        physicalObservationOrigin = null;
        baritone.getGameEventHandler().onWorldEvent(
                new WorldEvent(current, EventState.POST));
        world = current;
    }

    private static void requireClientThread() {
        if (!Minecraft.getInstance().isSameThread()) {
            throw new IllegalStateException("embedded Baritone is client-thread owned");
        }
    }

    private static LocalPlayerContext currentContext() {
        LocalPlayerContext current = tickingContext;
        if (current != null && current.isCurrent()) return current;
        return ClientRuntime.actor().activeContext().orElse(null);
    }

    private static void queuePendingStart(PendingStart next) {
        PendingStart queued = pendingStart;
        if (queued != null && queued.navigator() != next.navigator()) {
            queued.navigator().preempted(
                    "a newer MaiCraft navigation superseded it before body acquisition");
        }
        pendingStart = next;
    }

    private static void promotePendingStart(IBaritone baritone) {
        if (owner != null || pendingStart == null) return;
        PendingStart next = pendingStart;
        pendingStart = null;
        if (!next.navigator().canAcquireRuntimeOwnership()) return;

        EmbeddedBaritoneNavigator navigator = next.navigator();
        owner = navigator;
        configure(baritone, next.permit(), next.sprintAllowed());
        // 激活可能发生在语义父任务的 ThreadLocal 作用域关闭之后；排队请求持有父任务提交时捕获的不可变策略。
        EmbeddedBaritonePolicy.installSnapshot(next.policy());
        pendingPolicyOwner = null;
        pendingPolicyGoal = null;
        CAMERA_COURSE.reset();
        refreshPhysicalObstacles(baritone.getPlayerContext().player());
        baritone.getCustomGoalProcess().setGoalAndPath(
                new MaiCraftGoalAdapter(next.compiled().goal()));
    }

    private static void replaceActiveRoute(
            EmbeddedBaritoneNavigator navigator,
            GoalCompiler.Compiled compiled,
            LocalPlayerContext context,
            String reason) {
        if (backend == null || owner != navigator) return;
        ACTIONS.suspend(context, navigator, reason);
        backend.getPathingBehavior().forceCancel();
        ((PathingBehavior) backend.getPathingBehavior()).discardPendingPathEvents();
        backend.getCustomGoalProcess().setGoalAndPath(
                new MaiCraftGoalAdapter(compiled.goal()));
        pendingPolicyOwner = null;
        pendingPolicyGoal = null;
    }
}
