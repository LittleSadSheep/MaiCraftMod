// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.move;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import java.util.OptionalLong;
import org.maiwithu.maicraft.client.actor.BodyControlPort;
import org.maiwithu.maicraft.client.actor.ClientActorBoundary;
import org.maiwithu.maicraft.client.actor.DefaultBodyControlPort;
import org.maiwithu.maicraft.client.actor.DefaultLocalPlayerContext;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.pathing.execute.TerrainBill;
import org.maiwithu.maicraft.core.pathing.goal.GoalCompiler;
import org.maiwithu.maicraft.core.pathing.transport.TransportMode;
import org.maiwithu.maicraft.core.pathing.transport.TransportNavigator;
import org.maiwithu.maicraft.core.pathing.transport.TransportRuntime;
import org.maiwithu.maicraft.core.pathing.transport.TransportSession;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.task.TaskState;
import sun.misc.Unsafe;
import it.unimi.dsi.fastutil.longs.LongSets;
import java.util.UUID;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.core.integration.create.elevator.ElevatorFloorTaskRecord;
import org.maiwithu.maicraft.core.pathing.baritone.EmbeddedBaritoneNavigator;
import org.maiwithu.maicraft.core.pathing.calc.PlanningWorkProgress;
import org.maiwithu.maicraft.core.pathing.baritone.landing.LandingAssistPolicy;
import org.maiwithu.maicraft.intent.Goal;
import org.maiwithu.maicraft.intent.IntentRuntime;
import org.maiwithu.maicraft.intent.IntentTaskRecord;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskResult;

/** 使用惰性身体测试真实 MoveTo、导航和运行时的调用顺序；不启动游戏、窗口、路径搜索或数据包。 */
public final class MoveToTransportCompletionTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        Unsafe memory = (Unsafe) field(Unsafe.class, "theUnsafe").get(null);
        for (String phase : List.of("elevator_ride", "elevator_walk_exit", "jetpack_restore")) {
            nativeCompletion(memory, phase);
        }
        transportFailure(memory, false, .5);
        transportFailure(memory, true, .5);
        transportFailure(memory, false, 8.5);
        planningStallFailsAfterCeiling(memory);
        planningWorkFuseTripsWithoutApproach(memory);
        planningBurnoutSettlesWithinTolerance(memory);
        planningBurnoutKeepsExactStanceHonest(memory);
        degradedShaftLegBeforeFuseFailure(memory);
        knownCellWaypointLegRunsBeforeConcedeFuse(memory);
        knownCellWaypointLegReachesCellAndRestoresTarget(memory);
        knownCellWaypointLegSkippedWithoutIncrement(memory);
        knownStandableCellHandoffKeepsDataOnly(memory);
        planningWorkFuseSparedWhileApproaching(memory);
        longPlanningRenewsOnlyOnProgress(memory);
        discoveryFailure(memory);
        ordinaryNearArrival(memory);
        automaticLandingResult(memory);
        observationDoesNotCompleteTravel(memory);
        waterShoreClimbDeliversArrival(memory);
        waterClimbTimeoutHandsBackToPlanning(memory);
        lowAirClimbsOutToBreathe(memory);
        System.out.println("MoveToTransportCompletionTest: passed");
    }

    private static void observationDoesNotCompleteTravel(Unsafe memory) throws Exception {
        try(var f=new Fixture(memory,.5)) {
            var goal=new Goal("maicraft:travel","Choose an elevator floor",null,
                    "{\"transport_mode\":\"elevator\"}","{}",List.of(),List.of());
            var record=new IntentTaskRecord(UUID.randomUUID(),null,goal);
            record.setState(TaskState.RUNNING);
            Class<?> type=Class.forName("org.maiwithu.maicraft.intent.IntentTask");
            var ctor=type.getDeclaredConstructor(LocalPlayer.class,record.getClass(),IntentRuntime.class);
            ctor.setAccessible(true); Object parent=ctor.newInstance(f.player,record,null);
            var observed=new ElevatorFloorTaskRecord(
                    "test-floor-sync",100,UUID.randomUUID(),null,true);
            observed.setState(TaskState.SUCCESS);
            Task child=new Task() {
                public TaskState tick(LocalPlayer player) { return TaskState.SUCCESS; }
                public void stop(LocalPlayer player,StopReason reason) {}
                public String name() { return "native floor observation"; }
                public TaskResult result(TaskState state) { return TaskResult.ok("floors synchronized"); }
            };
            field(type,"child").set(parent,child); field(type,"childRecord").set(parent,observed);
            field(type,"reobserveAfterChild").setBoolean(parent,true);
            var finish=type.getDeclaredMethod("finishChild"); finish.setAccessible(true);
            check(finish.invoke(parent)==TaskState.RUNNING && record.stepIndex()==0 && record.getState()==TaskState.RUNNING,
                    "successful floor observation must leave the semantic travel step unfinished for its LLM decision");
            check(field(type,"childRecord").get(parent)==null && !field(type,"reobserveAfterChild").getBoolean(parent),
                    "observation cleanup releases the child without leaking its continuation into the later ride");
        }
    }

    private static void nativeCompletion(Unsafe memory, String phase) throws Exception {
        try (var f = new Fixture(memory, .5)) {
            var record = coordinates(0D, 0D);
            var task = f.task(record, true);
            var session = f.transport(TransportSession.Result.running(phase));
            check(task.onTick() == TaskState.RUNNING && session.ticks == 1,
                    phase + ": satisfied body coordinates must not skip the native drive");
            f.nextTick();
            check(task.onTick() == TaskState.RUNNING && session.ticks == 2 && session.stops == 0,
                    phase + ": still settling must not cancel or complete the task");
            check(record.internalVerifiedPosition() == null, "unfinished transport cannot issue an arrival receipt");
            session.result = TransportSession.Result.success("native exit/restoration complete");
            f.nextTick();
            check(task.onTick() == TaskState.RUNNING && session.ticks == 3 && !TransportRuntime.occupied(),
                    "native completion must be consumed before its next-tick ground handoff");
            f.nextTick();
            check(task.onTick() == TaskState.SUCCESS && record.internalVerifiedPosition() != null,
                    "settled native transport must permit the real supported arrival");
            check(task.result(TaskState.SUCCESS).success() && session.stops == 0 && f.callbacks == 1,
                    "successful task cleanup must not relabel the completed transport as cancelled");
            check(((Map<?, ?>) TransportRuntime.diagnosticState().get("last_transport")).get("state").equals("succeeded"),
                    "last transport must retain its native success");
        }
    }

    private static void transportFailure(Unsafe memory, boolean uncertain, double x) throws Exception {
        try (var f = new Fixture(memory, x)) {
            var record = coordinates(0D, null);
            var task = f.task(record, x < 1);
            var session = f.transport(TransportSession.Result.failed("elevator_needs_attention", "still aboard",
                    !uncertain, uncertain));
            check(task.onTick() == TaskState.FAILED && session.ticks == 1 && f.nav.failType() == FailureType.UNKNOWN,
                    "effectful or uncertain transport failure must remain failed even near the destination");
            check(record.internalVerifiedPosition() == null && !field(MoveToCompanionTask.class, "nearRetried").getBoolean(task),
                    "transport failure must neither create an arrival receipt nor start a near retry");
            check(!task.result(TaskState.FAILED).success(), "cleanup must preserve the failed task result");
        }
    }

    @SuppressWarnings("unchecked")
    private static void discoveryFailure(Unsafe memory) throws Exception {
        try (var f = new Fixture(memory, .5)) {
            var record = new MoveToTaskRecord("find", 600, null, null, null, "minecraft:stone", false);
            var task = f.task(record, false);
            var finder = new NearestBlockFinder(f.player, Blocks.STONE);
            var candidates = (List<BlockPos>) field(NearestBlockFinder.class, "candidates").get(finder);
            candidates.addAll(List.of(new BlockPos(2, 0, 0), new BlockPos(4, 0, 0)));
            field(MoveToCompanionTask.class, "finder").set(task, finder);
            f.transport(TransportSession.Result.failed("transport_uncertain", "inspect first", true, true));
            check(task.onTick() == TaskState.FAILED && candidates.size() == 2,
                    "uncertain transport must not rotate FIND candidates into another attempt");
            task.result(TaskState.FAILED);
        }
    }

    /** 连续算路没有新进展时按规划停滞收场；不把无返回结论冒充地形无路。 */
    private static void planningStallFailsAfterCeiling(Unsafe memory) throws Exception {
        try (var f = new Fixture(memory, .5)) {
            var record = new MoveToTaskRecord("planning-stall", 2000, 0D, 0D, 0D, null, false);
            var task = f.task(record, true); task.onStart();
            var session = new Session(TransportSession.Result.running("planning"), false);
            field(TransportNavigator.class, "session").set(f.navigator, session);
            field(TransportNavigator.class, "activeDestination").set(f.navigator, BlockPos.ZERO);
            field(TransportNavigator.class, "targetFingerprint").set(f.navigator, f.compiled.semanticFingerprint());
            TaskState last = TaskState.RUNNING;
            int ticks = 0;
            while (last != TaskState.FAILED && ticks++ < 900) {
                f.nextTick();
                last = task.onTick();
            }
            check(last == TaskState.FAILED, "连续算路零进展超过上限必须按无路失败，不能无限 still working");
            var result = task.result(TaskState.FAILED);
            check(String.valueOf(result.data().get("failure_type")).equalsIgnoreCase("planning_stall"),
                    "没有搜索结论的规划停滞不能伪装成地形无路");
            check(result.message().contains("route planning made no verified progress"),
                    "失败说明要指出无进展预算耗尽，而不是任务总耗时过长");
        }
    }

    /** 规划持续产出工作单位却永不接近目标（地下挖洞寻路的病态形态）时，按收敛熔断收场。 */
    private static void planningWorkFuseTripsWithoutApproach(Unsafe memory) throws Exception {
        // 角色站在容差外（4 格）：贴身容差内的烧尽场景由 planningBurnoutSettlesWithinTolerance 单独覆盖。
        try (var f = new Fixture(memory, 4.5)) {
            var record = new MoveToTaskRecord("planning-fuse", 6000, 0D, 0D, 0D, null, false);
            var task = f.task(record, true); task.onStart();
            // 目标在容差外时 onStart 走过 startWalkingNav，任务用的是重建的导航，桩要打在它身上。
            f.navigator = (TransportNavigator) field(PlayerNav.class, "navigator")
                    .get(field(AbstractCompanionTask.class, "nav").get(task));
            var session = planningSession(f);
            var probe = bareGround(memory, f);
            long highWater = 0;
            TaskState state = TaskState.RUNNING;
            for (int tick = 0; tick < 1300 && state == TaskState.RUNNING; tick++) {
                f.nextTick();
                if (tick > 0 && tick % 100 == 0) {
                    // 搜索持续有产出：verified progress 按期刷新（无进展预算永不满足），工作单位单调累积。
                    session.verifiedProgressTick = f.player.level().getGameTime();
                    highWater += 6000;
                    probe.observe(highWater);
                }
                state = task.onTick();
            }
            check(state == TaskState.FAILED,
                    "搜索工作单位越熔断线且最近距离长时间无改善必须按无收敛失败，不能无限 planning");
            var result = task.result(TaskState.FAILED);
            check(String.valueOf(result.data().get("failure_type")).equalsIgnoreCase("planning_stall"),
                    "收敛熔断同样是撞预算而非证明死路，失败类型沿用 planning_stall");
            check(String.valueOf(result.message()).contains("planning did not converge"),
                    "失败说明要携带工作量、最近距离与无改善时长证据");
            check(((Number) result.data().get("planning_work_units")).longValue() > 60_000,
                    "回执要暴露搜索预算的实际消耗");
            check(result.data().get("best_distance_blocks") instanceof Number,
                    "回执要暴露收敛趋势（曾达到的最近距离）");
        }
    }

    /**
     * 贴身目标（容差内）规划烧尽时就近收尾：身体立定在本任务单容差内、目标区域已加载，
     * 收敛熔断不再以 planning_stall 失败，而是按到达结算并声明结算来源，不再烧穿预算后交付误导性失败。
     */
    private static void planningBurnoutSettlesWithinTolerance(Unsafe memory) throws Exception {
        try (var f = new Fixture(memory, 2.5)) {
            // 只给 x/z 的柱列目标，零半径：导航容差收不下身体，但失败路径的教学性成功按三格算。
            var record = new MoveToTaskRecord("burnout-settle", 600_000, 0D, null, 0D, null, false);
            var task = f.task(record, true); task.onStart();
            f.navigator = (TransportNavigator) field(PlayerNav.class, "navigator")
                    .get(field(AbstractCompanionTask.class, "nav").get(task));
            var session = planningSession(f);
            var probe = bareGround(memory, f);
            long highWater = 0;
            TaskState state = TaskState.RUNNING;
            for (int tick = 0; tick < 1500 && state == TaskState.RUNNING; tick++) {
                f.nextTick();
                if (tick > 0 && tick % 100 == 0) {
                    session.verifiedProgressTick = f.player.level().getGameTime();
                    highWater += 6000;
                    probe.observe(highWater);
                }
                state = task.onTick();
            }
            check(state == TaskState.SUCCESS,
                    "贴身容差内目标规划烧尽应就近收尾而不是 planning_stall，实际 " + state);
            var result = task.result(TaskState.SUCCESS);
            check(Boolean.TRUE.equals(result.data().get("settled_nearby_after_planning_burnout")),
                    "就近收尾回执要声明结算来源");
            check(result.data().get("arrival_grade") != null,
                    "就近收尾要携带到达分级与剩余距离，供调用方自行复检");
            check(result.message().contains("closest stance within the arrival tolerance"),
                    "就近收尾话术要说明规划未能把身体带得更近");
        }
    }

    /** 精确站位目标没有容差短路：贴身烧尽仍诚实失败，但失败说明要点名身体已站在近旁并给出容差重发出路。 */
    private static void planningBurnoutKeepsExactStanceHonest(Unsafe memory) throws Exception {
        try (var f = new Fixture(memory, 2.5)) {
            var record = new MoveToTaskRecord("burnout-exact", 600_000, 0D, 0D, 0D, null,
                    false, false, TransportMode.GROUND, false, true, 0, 0);
            var task = f.task(record, true); task.onStart();
            f.navigator = (TransportNavigator) field(PlayerNav.class, "navigator")
                    .get(field(AbstractCompanionTask.class, "nav").get(task));
            var session = planningSession(f);
            var probe = bareGround(memory, f);
            long highWater = 0;
            TaskState state = TaskState.RUNNING;
            for (int tick = 0; tick < 1500 && state == TaskState.RUNNING; tick++) {
                f.nextTick();
                if (tick > 0 && tick % 100 == 0) {
                    session.verifiedProgressTick = f.player.level().getGameTime();
                    highWater += 6000;
                    probe.observe(highWater);
                }
                state = task.onTick();
            }
            check(state == TaskState.FAILED, "精确站位贴身烧尽必须诚实失败，不能冒充到达");
            var result = task.result(TaskState.FAILED);
            check(String.valueOf(result.data().get("failure_type")).equalsIgnoreCase("planning_stall"),
                    "精确站位烧尽仍按规划不收敛失败");
            check(String.valueOf(result.message()).contains("already standing about 2.0 blocks from the exact cell"),
                    "失败说明要点名身体已站在精确格近旁");
            check(String.valueOf(result.message()).contains("resubmit travel without exact"),
                    "失败说明要给出容差重发的出路");
        }
    }

    /**
     * 收敛熔断触发后先走一次「井口旁楼梯头」降级行程：目标在脚下且授权动土时，
     * 熔断不再直接收场，而是先站到目标柱旁一格再用全新搜索恢复原目标；降级行程之后
     * 再次不收敛才诚实失败，回执声明降级行程已尝试。
     */
    private static void degradedShaftLegBeforeFuseFailure(Unsafe memory) throws Exception {
        try (var f = new Fixture(memory, .5)) {
            var record = new MoveToTaskRecord("fuse-degraded-leg", 20000, 0D, -5D, 0D, null, true);
            var task = f.task(record, true); task.onStart();
            // 目标不在脚下：onStart 走过 startWalkingNav 用真实导航替换了夹具导航，桩要打在它身上。
            f.navigator = (TransportNavigator) field(PlayerNav.class, "navigator")
                    .get(field(AbstractCompanionTask.class, "nav").get(task));
            var session = planningSession(f);
            var probe = bareGround(memory, f);
            long highWater = 0;
            boolean legStarted = false;
            TaskState state = TaskState.RUNNING;
            for (int tick = 0; tick < 3600 && state == TaskState.RUNNING; tick++) {
                f.nextTick();
                if (tick > 0 && tick % 100 == 0) {
                    session.verifiedProgressTick = f.player.level().getGameTime();
                    highWater += 6000;
                    probe.observe(highWater);
                }
                state = task.onTick();
                if (!legStarted && field(MoveToCompanionTask.class, "degradedShaftLegTried").getBoolean(task)) {
                    legStarted = true;
                    // 降级行程的导航在 onTick 里新建；给这一段打上同一套 planning 桩再继续驱动。
                    f.navigator = (TransportNavigator) field(PlayerNav.class, "navigator")
                            .get(field(AbstractCompanionTask.class, "nav").get(task));
                    session = planningSession(f);
                    probe = bareGround(memory, f);
                    highWater = 0;
                }
            }
            check(legStarted, "收敛熔断必须先尝试一次井口旁降级行程，而不是直接收场");
            check(state == TaskState.FAILED, "降级行程之后再次不收敛必须诚实收场");
            var result = task.result(TaskState.FAILED);
            check(String.valueOf(result.message()).contains("planning did not converge"),
                    "降级行程后的失败仍要携带不收敛证据与工作量");
            check(Boolean.TRUE.equals(result.data().get("degraded_shaft_leg_tried")),
                    "回执要声明降级行程已尝试过");
        }
    }

    /** 距离在持续缩短的规划无论消耗多少工作单位都不熔断：收敛本身就是进展。 */    private static void planningWorkFuseSparedWhileApproaching(Unsafe memory) throws Exception {
        try (var f = new Fixture(memory, 30.5)) {
            var record = new MoveToTaskRecord("planning-converging", 6000, 0D, 0D, 0D, null, false,
                    false, TransportMode.ELEVATOR);
            var task = f.task(record, true); task.onStart();
            // onStart 走过 startWalkingNav 后任务用的是重建的导航，桩要打在它身上而不是夹具预建的实例。
            f.navigator = (TransportNavigator) field(PlayerNav.class, "navigator")
                    .get(field(AbstractCompanionTask.class, "nav").get(task));
            var session = planningSession(f);
            var probe = bareGround(memory, f);
            // 任务单留在 ELEVATOR 以跳过地面起飞准备，导航层留在 GROUND 以走普通目标通道。
            field(TransportNavigator.class, "mode").set(f.navigator, TransportMode.GROUND);
            long highWater = 0;
            // 七百刻内走完六十格且工作单位在第 550 刻就已越过熔断线：全程距离单调缩短，不得熔断。
            for (int tick = 0; tick < 700; tick++) {
                f.nextTick();
                if (tick > 0 && tick % 50 == 0) {
                    session.verifiedProgressTick = f.player.level().getGameTime();
                    highWater += 6000;
                    probe.observe(highWater);
                }
                // 身体每刻向目标挪 0.05 格：60 格全程单调改善，工作单位越过熔断线时距离刚刚还在缩短。
                field(LocalPlayer.class, "position").set(f.player, new Vec3(30.5 - tick * 0.05, 0, .5));
                TaskState state = task.onTick();
                if (state != TaskState.RUNNING) {
                    var ended = task.result(state);
                    throw new AssertionError("converging planning ended early at tick " + tick
                            + " (" + ended.message() + ")");
                }
            }
        }
    }

    /** 惰性地面导航：只提供工作单位计数与进度通道，不启动真实 Baritone 搜索。 */
    private static PlanningWorkProgress bareGround(Unsafe memory, Fixture f) throws Exception {
        var ground = (EmbeddedBaritoneNavigator) memory.allocateInstance(EmbeddedBaritoneNavigator.class);
        field(EmbeddedBaritoneNavigator.class, "player").set(ground, f.player);
        field(EmbeddedBaritoneNavigator.class, "playerWorld").set(ground, f.world);
        field(EmbeddedBaritoneNavigator.class, "progress").set(ground, memory.allocateInstance(
                Class.forName("org.maiwithu.maicraft.core.pathing.baritone.NavigationProgress")));
        field(EmbeddedBaritoneNavigator.class, "events").set(ground,
                new EnumMap<>(baritone.api.event.events.PathEvent.class));
        field(EmbeddedBaritoneNavigator.class, "ledger").set(ground, new TerrainBill());
        // Unsafe 裸实例绕过字段初始化器；诊断链路默认这些 Map 非空，逐一补上。
        for (String diagnostics : List.of("dispatchEvidence", "healthEvidence", "dispatchRecovery",
                "probeRecovery", "failureEvidence")) {
            field(EmbeddedBaritoneNavigator.class, diagnostics).set(ground, Map.of());
        }
        var probe = new PlanningWorkProgress();
        // 卡点记忆同样要真实构造：诊断链路会读取它是否为空。
        var stallMemory = Class.forName("org.maiwithu.maicraft.core.pathing.baritone.NavigationStallMemory").getDeclaredConstructor();
        stallMemory.setAccessible(true);
        field(EmbeddedBaritoneNavigator.class, "stalls").set(ground, stallMemory.newInstance());
        field(EmbeddedBaritoneNavigator.class, "probeProgress").set(ground, probe);
        field(TransportNavigator.class, "ground").set(f.navigator, ground);
        return probe;
    }

    /** 注入常驻 planning 阶段的交通会话：导航每刻停在会话等待，不驱动真实地面寻路。 */
    private static Session planningSession(Fixture f) throws Exception {
        var session = new Session(TransportSession.Result.running("planning"), false);
        field(TransportNavigator.class, "session").set(f.navigator, session);
        field(TransportNavigator.class, "activeDestination").set(f.navigator, BlockPos.ZERO);
        field(TransportNavigator.class, "targetFingerprint").set(f.navigator, f.compiled.semanticFingerprint());
        return session;
    }

    private static void longPlanningRenewsOnlyOnProgress(Unsafe memory) throws Exception {
        try (var f = new Fixture(memory, .5)) {
            var record = new MoveToTaskRecord("productive-long-planning", 600, 0D, 0D, 0D, null, false);
            var task = f.task(record, true); task.onStart();
            var session = new Session(TransportSession.Result.running("planning"), false);
            field(TransportNavigator.class, "session").set(f.navigator, session);
            field(TransportNavigator.class, "activeDestination").set(f.navigator, BlockPos.ZERO);
            field(TransportNavigator.class, "targetFingerprint").set(f.navigator, f.compiled.semanticFingerprint());
            // 角色尚未移动，但每十秒确认一批新规划事实；连续九十秒仍应留在同一旅行任务里。
            for (int tick = 0; tick < 1800; tick++) {
                f.nextTick();
                if (tick % 200 == 0) session.verifiedProgressTick = f.player.level().getGameTime();
                check(task.onTick() == TaskState.RUNNING, "new planning progress must refill the shared budget");
            }
            check(record.getDeadlineGameTime() > f.player.level().getGameTime(), "long planning renews the task deadline");
            TaskState state = TaskState.RUNNING;
            for (int tick = 0; tick < 601 && state == TaskState.RUNNING; tick++) { f.nextTick(); state = task.onTick(); }
            check(state == TaskState.FAILED, "once real progress stops the same budget must still expire");
            task.result(state);
        }
    }

    private static void ordinaryNearArrival(Unsafe memory) throws Exception {
        try (var f = new Fixture(memory, 2.5)) {
            var record = coordinates(0D, null);
            var task = f.task(record, false);
            Object ground = field(TransportNavigator.class, "ground").get(f.navigator);
            field(ground.getClass(), "terminalFailure").setBoolean(ground, true);
            field(ground.getClass(), "failureType").set(ground, FailureType.NO_PATH);
            field(ground.getClass(), "failureReason").set(ground, "ordinary terrain ended here");
            check(task.onTick() == TaskState.SUCCESS && record.internalVerifiedPosition() != null,
                    "ordinary NO_PATH within the existing three-block tolerance must retain near success");
            task.result(TaskState.SUCCESS);
        }
    }

    private static MoveToTaskRecord coordinates(Double x, Double y) {
        return new MoveToTaskRecord("move", 600, x, y, 0D, null, false);
    }

    /**
     * 深水贴岸 travel（176 实机失败场景）：身体在水里、目标就是贴岸一格沿的沿顶站立格时，
     * 任务必须直接进入登岸段并停掉导航，爬上沿顶后按到达交付，不再等规划空转 30 秒后报
     * planning_stall。成功只认真的离水：身体站上干燥沿顶才算到达。
     * 几何对齐实机受控场景：三格深水（脚位从池底出发）、沿顶高出水面格一格。
     */
    private static void waterShoreClimbDeliversArrival(Unsafe memory) throws Exception {
        try (var f = new Fixture(memory, .5)) {
            // 水柱 y0..2（顶层水面节点 y2），岸沿地板 (1,3,0) 实心，目标沿顶站立格 (1,4,0)。
            for (int y = 0; y <= 2; y++) {
                f.world.blocks.put(new BlockPos(0, y, 0).asLong(), Blocks.WATER.defaultBlockState());
            }
            f.world.blocks.put(new BlockPos(1, 3, 0).asLong(), Blocks.STONE.defaultBlockState());
            f.player.wet = true;
            field(LocalPlayer.class, "onGround").setBoolean(f.player, true);
            var record = new MoveToTaskRecord("water-shore", 600_000, 1D, 4D, 0D, null, false);
            var task = f.task(record, false);
            task.onStart();
            f.nextTick();
            check(task.onTick() == TaskState.RUNNING, "贴岸目标在水中应进入登岸段而不是立刻失败");
            check(field(MoveToCompanionTask.class, "waterLegKind").get(task) != null,
                    "目标就是贴岸一格沿时必须直接进入登岸段");
            check(field(AbstractCompanionTask.class, "nav").get(task) == null,
                    "登岸段接管身体时必须停掉原导航，不能两套驾驶并存");
            // 驾驶输入要真的发出：贴岸游前进、跳跃按住（实机首版的卡点就是驾驶没生效）。
            f.nextTick();
            check(task.onTick() == TaskState.RUNNING, "登岸段驾驶中应保持运行");
            var applied = (BodyControlPort.Movement) field(DefaultBodyControlPort.class, "movement").get(f.body);
            check(applied.forward() > 0 && applied.jumping(),
                    "登岸段必须每刻发出前进与跳跃输入，实际 " + applied);
            // 模拟原版水中抬头游的助推：身体被抬上沿顶并站稳。
            f.player.wet = false;
            field(LocalPlayer.class, "position").set(f.player, new Vec3(1.5, 4, .5));
            field(LocalPlayer.class, "blockPosition").set(f.player, new BlockPos(1, 4, 0));
            field(LocalPlayer.class, "onGround").setBoolean(f.player, true);
            f.nextTick();
            check(task.onTick() == TaskState.SUCCESS, "爬上沿顶站进目标格必须按到达交付");
            var result = task.result(TaskState.SUCCESS);
            check(Boolean.TRUE.equals(result.data().get("water_shore_climb_out")),
                    "回执要声明到达经由水中登岸段完成");
            check(String.valueOf(result.message()).contains("climbing out of the water"),
                    "成功话术要说明这份到达来自贴岸攀爬");
        }
    }

    /**
     * 登岸段爬不上沿顶时（实机第二轮的形态：贴沿弹跳够不到顶），任务先把身体交还规划
     * 从当前贴沿身位重算一次路线（实机证据：贴沿身位重发后规划数秒内自己登顶），
     * 而不是立刻以仍在水中如实失败；重试只给一次。
     */
    private static void waterClimbTimeoutHandsBackToPlanning(Unsafe memory) throws Exception {
        try (var f = new Fixture(memory, .5)) {
            for (int y = 0; y <= 2; y++) {
                f.world.blocks.put(new BlockPos(0, y, 0).asLong(), Blocks.WATER.defaultBlockState());
            }
            f.world.blocks.put(new BlockPos(1, 3, 0).asLong(), Blocks.STONE.defaultBlockState());
            f.player.wet = true;
            field(LocalPlayer.class, "onGround").setBoolean(f.player, false);
            var record = new MoveToTaskRecord("water-shore-retry", 600_000, 1D, 4D, 0D, null, false);
            var task = f.task(record, false);
            task.onStart();
            f.nextTick();
            check(task.onTick() == TaskState.RUNNING
                    && field(MoveToCompanionTask.class, "waterLegKind").get(task) != null,
                    "贴岸目标应先进入登岸段");
            // 身体贴沿弹跳但始终没能离水：满 10 秒窗口后必须交还规划，而不是直接失败。
            TaskState state = TaskState.RUNNING;
            boolean handedOff = false;
            for (int tick = 0; tick < 260 && state == TaskState.RUNNING; tick++) {
                f.nextTick();
                state = task.onTick();
                if (!handedOff && Boolean.TRUE.equals(
                        field(MoveToCompanionTask.class, "waterClimbReplanTried").get(task))) {
                    handedOff = true;
                    Object nav = field(AbstractCompanionTask.class, "nav").get(task);
                    check(nav != null, "交还规划必须重建导航");
                    f.navigator = (TransportNavigator) field(PlayerNav.class, "navigator").get(nav);
                    planningSession(f);
                    bareGround(memory, f);
                }
            }
            check(handedOff && state == TaskState.RUNNING,
                    "登岸段超时后应交还规划继续任务，实际 handedOff=" + handedOff + " state=" + state);
            check(Boolean.TRUE.equals(field(MoveToCompanionTask.class, "waterClimbReplanWindow").get(task)),
                    "接棒窗口期间贴岸直达触发要让路给规划");
            // 接棒窗口内规划停驻不触发贴岸直达抢占；任务保持运行直到规划自己给出终态。
            for (int tick = 0; tick < 60; tick++) {
                f.nextTick();
                check(task.onTick() == TaskState.RUNNING, "接棒窗口内应让规划运行而不是抢回身体");
            }
            check(field(MoveToCompanionTask.class, "waterLegKind").get(task) == null,
                    "接棒窗口内不得重新进入登岸段");
        }
    }

    /**
     * 憋气兜底：水中空气跌破储备线且身体原地不动满观察窗口时，放弃当前目标就近登岸换气；
     * 离水后目标未到必须如实失败，回执声明行程已为换气中断、身体已安全离水。
     */
    private static void lowAirClimbsOutToBreathe(Unsafe memory) throws Exception {
        try (var f = new Fixture(memory, .5)) {
            // 三格深水柱 y0..2（水面节点 y2）；可攀干岸：支撑 (2,3,0) 实心，站立格 (2,4,0)。
            for (int y = 0; y <= 2; y++) {
                f.world.blocks.put(new BlockPos(0, y, 0).asLong(), Blocks.WATER.defaultBlockState());
            }
            f.world.blocks.put(new BlockPos(2, 3, 0).asLong(), Blocks.STONE.defaultBlockState());
            f.player.wet = true;
            f.player.airSupply = 60;
            field(LocalPlayer.class, "onGround").setBoolean(f.player, false);
            var record = new MoveToTaskRecord("breathe-pause", 600_000, 60D, 0D, 60D, null, false);
            var task = f.task(record, false);
            task.onStart();
            f.navigator = (TransportNavigator) field(PlayerNav.class, "navigator")
                    .get(field(AbstractCompanionTask.class, "nav").get(task));
            planningSession(f);
            bareGround(memory, f);
            // 空气已低于储备线：原地不动满 10 秒观察窗口后必须放弃目标进入换气登岸段。
            TaskState state = TaskState.RUNNING;
            boolean legStarted = false;
            for (int tick = 0; tick < 400 && state == TaskState.RUNNING; tick++) {
                f.nextTick();
                state = task.onTick();
                if (field(MoveToCompanionTask.class, "waterLegKind").get(task) != null) {
                    legStarted = true;
                    check(tick >= 190, "观察窗口未满不得提前抢方向盘");
                    break;
                }
            }
            check(legStarted, "空气走低且原地不动满窗口必须进入换气登岸段");
            check(field(AbstractCompanionTask.class, "nav").get(task) == null,
                    "换气兜底必须停掉原导航");
            // 身体游到岸边爬上沿顶：目标未到，任务如实失败并声明已安全离水。
            f.player.wet = false;
            field(LocalPlayer.class, "position").set(f.player, new Vec3(2.5, 4, .5));
            field(LocalPlayer.class, "blockPosition").set(f.player, new BlockPos(2, 4, 0));
            field(LocalPlayer.class, "onGround").setBoolean(f.player, true);
            f.nextTick();
            check(task.onTick() == TaskState.FAILED, "换气兜底离水后目标未到必须如实失败");
            var result = task.result(TaskState.FAILED);
            check(Boolean.TRUE.equals(result.data().get("interrupted_to_breathe")),
                    "回执要声明行程已为换气中断");
            check(String.valueOf(result.message()).contains("climb out of the water and breathe"),
                    "失败说明要交代身体已离水、可从岸上重发");
        }
    }

    private static final class Session implements TransportSession {
        Result result;
        int ticks, stops;
        final boolean live;
        long verifiedProgressTick = Long.MIN_VALUE;
        Session(Result result) { this(result, true); }
        /** live=false 模拟没有交通进展的会话：liveness 不再充当物理进展证据。 */
        Session(Result result, boolean live) { this.result = result; this.live = live; }
        public Result tick(LocalPlayerContext context) { ticks++; return result; }
        public void requestStop() { stops++; }
        public void abandon() { }
        public boolean safeToInterrupt() { return result.terminal(); }
        public boolean livenessActive() { return live; }
        public long lastVerifiedProgressTick() { return verifiedProgressTick; }
        public String phase() { return result.code(); }
        public Map<String, Object> diagnostics() { return Map.of("ticks", ticks); }
    }

    private static void automaticLandingResult(Unsafe memory) throws Exception {
        var failed = Map.<String,Object>of("strategy","WATER","complete",true,"failed",true,"native_water_contact",false);
        LandingAssistPolicy.report(failed);
        try (var f = new Fixture(memory,.5)) {
            var task = f.task(coordinates(0D,0D),true); task.onStart();
            check(task.onTick()==TaskState.SUCCESS,"an old landing failure must not contaminate a new move");
            check(!task.result(TaskState.SUCCESS).data().containsKey("landing_assist"),"old diagnostics must not be attributed to this task");
        }
        try (var f = new Fixture(memory,.5)) {
            var record=coordinates(0D,0D); var task=f.task(record,true); task.onStart();
            LandingAssistPolicy.report(failed);
            // 到达事实成立时，失败的自动落地保护只作注记降级：终态仍按到达交付，
            // 现场证据随回执保留，由调用方决定是否复检脚下支撑。
            check(task.onTick()==TaskState.SUCCESS && record.internalVerifiedPosition()!=null,
                    "arrival must stand even when this move's automatic protection failed");
            var result = task.result(TaskState.SUCCESS).data();
            check(Boolean.TRUE.equals(result.get("landing_protection_unverified")),
                    "the receipt must annotate the unverified landing protection");
            check(result.get("landing_assist").equals(failed),"receipt must retain actual rescue evidence");
        }
    }

    private static final class Fixture implements AutoCloseable {
        final ClientActorBoundary actor = ClientRuntime.actor();
        final Map<Field, Object> saved = new LinkedHashMap<>();
        final Minecraft minecraft;
        final TestPlayer player;
        final FlatLevel world;
        final DefaultBodyControlPort body = new DefaultBodyControlPort();
        LocalPlayerContext context;
        PlayerNav nav;
        TransportNavigator navigator;
        GoalCompiler.Compiled compiled = GoalCompiler.standOn(BlockPos.ZERO);
        long tick;
        int callbacks;

        Fixture(Unsafe memory, double x) throws Exception {
            minecraft = (Minecraft) memory.allocateInstance(Minecraft.class);
            player = (TestPlayer) memory.allocateInstance(TestPlayer.class);
            // 导航夹具保持真实世界入口的常驻菜单和空游标基线，不能把未初始化玩家当成挡路界面。
            var inventoryMenu = (InventoryMenu) memory.allocateInstance(InventoryMenu.class);
            inventoryMenu.setCarried(ItemStack.EMPTY);
            field(LocalPlayer.class, "inventoryMenu").set(player, inventoryMenu); player.containerMenu = inventoryMenu;
            world = (FlatLevel) memory.allocateInstance(FlatLevel.class);
            // Unsafe 裸实例不跑字段初始化器；逐格覆盖表由构造器补上。
            world.blocks = new HashMap<>();
            // 水域用例要读流体状态，流体查询沿高度链先取维度类型：补上真实维度注册值。
            var dimensionType = new DimensionType(OptionalLong.empty(), true, false, false, true, 1, true, false,
                    0, 16, 16, BlockTags.INFINIBURN_OVERWORLD,
                    ResourceLocation.withDefaultNamespace("overworld"), 0,
                    new DimensionType.MonsterSettings(false, false, ConstantInt.of(0), 0));
            field(Level.class, "dimensionTypeRegistration").set(world, Holder.direct(dimensionType));
            minecraft.player = player; minecraft.level = world;
            field(Minecraft.class, "gameThread").set(minecraft, Thread.currentThread());
            field(Level.class, "dimension").set(world, Level.OVERWORLD);
            field(LocalPlayer.class, "clientLevel").set(player, world);
            field(LocalPlayer.class, "level").set(player, world);
            field(LocalPlayer.class, "position").set(player, new Vec3(x, 0, .5));
            field(LocalPlayer.class, "blockPosition").set(player, BlockPos.containing(x, 0, .5));
            field(LocalPlayer.class, "onGround").setBoolean(player, true);
            player.input = new Input();
            replace(field(Minecraft.class, "instance"), null, minecraft);
            replace(field(ClientActorBoundary.class, "minecraft"), actor, minecraft);
            replace(field(ClientActorBoundary.class, "body"), actor, body);
            replace(field(ClientActorBoundary.class, "observedPlayer"), actor, player);
            remember(field(ClientActorBoundary.class, "activeContext"), actor);
            remember(field(ClientActorBoundary.class, "tickRevision"), actor);
            invoke(body, "requestAutomation", LocalPlayer.class, player);
            invoke(body, "fulfillAutomationRequest", LocalPlayer.class, player);
            nextTick();
        }

        void nextTick() throws Exception {
            world.time = ++tick;
            field(ClientActorBoundary.class, "tickRevision").setLong(actor, tick);
            Constructor<?> ctor = DefaultLocalPlayerContext.class.getDeclaredConstructors()[0]; ctor.setAccessible(true);
            context = (LocalPlayerContext) ctor.newInstance(actor, minecraft, player, world, null, null,
                    field(ClientActorBoundary.class, "bodyEpoch").getLong(actor),
                    field(ClientActorBoundary.class, "controlRevision").getLong(actor), tick, true);
            field(ClientActorBoundary.class, "activeContext").set(actor, context);
            invoke(body, "beginTick", long.class, tick);
        }

        MoveToCompanionTask task(MoveToTaskRecord record, boolean reached) throws Exception {
            if (record.kind != MoveToTaskRecord.Kind.FIND) compiled = new GoalCompiler.Compiled(record.coordinateGoal(),
                    LongSets.emptySet());
            var task = new MoveToCompanionTask(player, record);
            nav = PlayerNav.to(player, () -> compiled, 1, () -> reached).withTransportMode(TransportMode.GROUND);
            navigator = (TransportNavigator) field(PlayerNav.class, "navigator").get(nav);
            field(AbstractCompanionTask.class, "nav").set(task, nav);
            return task;
        }

        Session transport(TransportSession.Result result) throws Exception {
            var session = new Session(result);
            field(TransportNavigator.class, "session").set(navigator, session);
            field(TransportNavigator.class, "activeDestination").set(navigator, BlockPos.ZERO);
            field(TransportNavigator.class, "targetFingerprint").set(navigator, compiled.semanticFingerprint());
            field(TransportNavigator.class, "legOrigin").set(navigator, new Vec3(-5, 0, .5));
            Constructor<?> ctor = Class.forName(TransportNavigator.class.getPackageName() + ".TransportPlan$Offer").getDeclaredConstructors()[0];
            ctor.setAccessible(true);
            Object offer = ctor.newInstance("elevator", BlockPos.ZERO, 20D, (Supplier<TransportSession>) () -> session);
            field(TransportNavigator.class, "offers").set(navigator, List.of(offer));
            field(TransportNavigator.class, "offerIndex").setInt(navigator, 1);
            check(TransportRuntime.acquire(navigator, "elevator", session, context, completed -> {
                callbacks++;
                try { field(TransportNavigator.class, "transportResult").set(navigator, completed); }
                catch (Exception failure) { throw new AssertionError(failure); }
            }), "fixture transport must acquire the real owner lease");
            return session;
        }

        private void remember(Field field, Object owner) throws Exception { saved.put(field, field.get(owner)); }
        private void replace(Field field, Object owner, Object value) throws Exception {
            remember(field, owner); field.set(owner, value);
        }
        public void close() throws Exception {
            TransportRuntime.abandon();
            for (var entry : saved.entrySet()) entry.getKey().set(
                    entry.getKey().getDeclaringClass() == Minecraft.class ? null : actor, entry.getValue());
        }
    }

    private static final class FlatLevel extends ClientLevel {
        long time;
        /** 测试自放的方块与水体：按格覆盖默认平地（STONE/AIR），用于构造水池与岸沿。 */
        Map<Long, BlockState> blocks;
        private FlatLevel() { super(null, null, null, null, 0, 0, null, null, false, 0); }
        @Override public long getGameTime() { return time; }
        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override public BlockState getBlockState(BlockPos pos) {
            var custom = blocks.get(pos.asLong());
            if (custom != null) return custom;
            return (pos.getY() < 0 ? Blocks.STONE : Blocks.AIR).defaultBlockState();
        }
    }

    private static final class TestPlayer extends LocalPlayer {
        /** 水域与空气的假状态：默认干地满气，水域用例按需打开。 */
        boolean wet;
        int airSupply = 300;
        @Override public ItemStack getItemBySlot(EquipmentSlot slot) { return ItemStack.EMPTY; }
        private TestPlayer() { super(null, null, null, null, null, false, false); }
        @Override public boolean isAlive() { return true; }
        // 此夹具只验证清醒时的交通完成；未初始化的实体同步数据不能被当成实际睡眠状态。
        @Override public boolean isSleeping() { return false; }
        @Override public float getHealth() { return 20; }
        @Override public float getAbsorptionAmount() { return 0; }
        @Override public void setSprinting(boolean sprinting) { }
        @Override public boolean isInWater() { return wet; }
        @Override public int getAirSupply() { return airSupply; }
        @Override public int getMaxAirSupply() { return 300; }
    }

    private static void invoke(Object owner, String name, Class<?> parameter, Object value) throws Exception {
        Method method = owner.getClass().getDeclaredMethod(name, parameter); method.setAccessible(true); method.invoke(owner, value);
    }
    private static Field field(Class<?> owner, String name) throws Exception {
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field; }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    /** 自掘竖井场景（地表目标在邻近井底）：收敛熔断先派一次已知格中转段，该段也熔断才诚实失败并在回执声明。 */
    private static void knownCellWaypointLegRunsBeforeConcedeFuse(Unsafe memory) throws Exception {
        try (var f = new Fixture(memory, .5)) {
            BlockPos waypoint = new BlockPos(58, 0, 60);
            var record = new MoveToTaskRecord("fuse-waypoint", 600_000, 60D, 0D, 60D, null,
                    true, false, TransportMode.GROUND, false, true, 0, 0)
                    .withKnownStandableCells(List.of(new BlockPos(3, 0, 3), waypoint));
            var task = f.task(record, true); task.onStart();
            // 目标不在原地时 onStart 走过 startWalkingNav，任务用的是重建的导航，桩要打在它身上。
            f.navigator = (TransportNavigator) field(PlayerNav.class, "navigator")
                    .get(field(AbstractCompanionTask.class, "nav").get(task));
            var session = planningSession(f);
            var probe = bareGround(memory, f);
            long highWater = 0;
            boolean legStarted = false;
            TaskState state = TaskState.RUNNING;
            for (int tick = 0; tick < 2600 && state == TaskState.RUNNING; tick++) {
                f.nextTick();
                if (tick > 0 && tick % 100 == 0) {
                    // 搜索持续有产出：无进展预算永不满足，工作单位单调累积越熔断线。
                    session.verifiedProgressTick = f.player.level().getGameTime();
                    highWater += 6000;
                    probe.observe(highWater);
                }
                state = task.onTick();
                if (!legStarted && state == TaskState.RUNNING
                        && field(MoveToCompanionTask.class, "knownCellLegTarget").get(task) != null) {
                    legStarted = true;
                    check(highWater > 60_000, "中转段必须在收敛熔断之后才启用，不能替代正常规划");
                    // 中转段是全新的导航实例，桩要打在它身上再继续喂工作单位。
                    f.navigator = (TransportNavigator) field(PlayerNav.class, "navigator")
                            .get(field(AbstractCompanionTask.class, "nav").get(task));
                    session = planningSession(f);
                    field(TransportNavigator.class, "activeDestination").set(f.navigator, waypoint);
                    field(TransportNavigator.class, "targetFingerprint").set(f.navigator,
                            GoalCompiler.block(f.world, waypoint).semanticFingerprint());
                    probe = bareGround(memory, f);
                }
            }
            check(legStarted, "规划收敛熔断必须先走一次已知格中转段，而不是直接失败 state=" + state
                    + " cells=" + record.knownStandableCells().size());
            check(state == TaskState.FAILED, "中转段与恢复搜索都熔断后必须诚实失败");
            var result = task.result(TaskState.FAILED);
            check(Boolean.TRUE.equals(result.data().get("known_cell_waypoint_tried")),
                    "回执必须声明已知格中转段已启用");
            check(String.valueOf(result.data().get("known_cell_waypoint")).startsWith("58"),
                    "回执要交付实际选中的中转格（两格候选中增量最大的那格）");
            check(String.valueOf(result.message()).contains("known standable cell waypoint leg"),
                    "失败说明要交代中转段的结果与后续出路");
        }
    }

    /** 中转段走到已知格站稳后不判到达：停掉该段导航，用全新搜索恢复原目标；恢复段仍熔断时如实收场。 */
    private static void knownCellWaypointLegReachesCellAndRestoresTarget(Unsafe memory) throws Exception {
        try (var f = new Fixture(memory, .5)) {
            BlockPos waypoint = new BlockPos(58, 0, 60);
            var record = new MoveToTaskRecord("fuse-waypoint-arrive", 600_000, 60D, 0D, 60D, null,
                    true, false, TransportMode.GROUND, false, true, 0, 0)
                    .withKnownStandableCells(List.of(waypoint));
            var task = f.task(record, true); task.onStart();
            f.navigator = (TransportNavigator) field(PlayerNav.class, "navigator")
                    .get(field(AbstractCompanionTask.class, "nav").get(task));
            var session = planningSession(f);
            var probe = bareGround(memory, f);
            long highWater = 0;
            boolean legStarted = false;
            boolean legArrived = false;
            TaskState state = TaskState.RUNNING;
            for (int tick = 0; tick < 4000 && state == TaskState.RUNNING; tick++) {
                f.nextTick();
                if (tick > 0 && tick % 100 == 0) {
                    session.verifiedProgressTick = f.player.level().getGameTime();
                    highWater += 6000;
                    probe.observe(highWater);
                }
                state = task.onTick();
                if (state != TaskState.RUNNING) break;
                if (!legStarted && field(MoveToCompanionTask.class, "knownCellLegTarget").get(task) != null) {
                    legStarted = true;
                    // 中转段导航不给规划会话：身体到达中转格后由到达判定直接收段。
                    f.navigator = (TransportNavigator) field(PlayerNav.class, "navigator")
                            .get(field(AbstractCompanionTask.class, "nav").get(task));
                    probe = bareGround(memory, f);
                    // 裸地面实例绕过了构造器，没有到达谓词；补上真值让身体就位后立刻宣布到格。
                    field(EmbeddedBaritoneNavigator.class, "reached").set(
                            field(TransportNavigator.class, "ground").get(f.navigator),
                            (java.util.function.BooleanSupplier) () -> true);
                    field(LocalPlayer.class, "position").set(f.player, new Vec3(58.5, 0, 60.5));
                    field(LocalPlayer.class, "blockPosition").set(f.player, waypoint);
                    continue;
                }
                if (legStarted && !legArrived
                        && field(MoveToCompanionTask.class, "knownCellLegTarget").get(task) == null) {
                    legArrived = true;
                    // 该段已被消费、原目标导航已重建：恢复搜索是另一个导航实例，桩打在它身上继续熔断。
                    f.navigator = (TransportNavigator) field(PlayerNav.class, "navigator")
                            .get(field(AbstractCompanionTask.class, "nav").get(task));
                    session = planningSession(f);
                    probe = bareGround(memory, f);
                }
            }
            check(legStarted && legArrived, "中转段必须到达已知格并用全新搜索恢复原目标");
            check(state == TaskState.FAILED, "恢复搜索仍不收敛时必须诚实失败");
            var result = task.result(TaskState.FAILED);
            check(Boolean.TRUE.equals(result.data().get("known_cell_waypoint_tried"))
                            && result.data().get("known_cell_waypoint") == null,
                    "该段已走完时回执不再携带进行中的中转格");
            check(String.valueOf(result.message()).contains("reached its cell, but the restored search still failed"),
                    "失败说明要区分「中转段没走通」与「到格后恢复搜索仍不收敛」");
        }
    }

    /** 已知格都没有准入增量（都比当前站位距目标更远）时不派段：熔断直接失败，回执不伪造中转格。 */
    private static void knownCellWaypointLegSkippedWithoutIncrement(Unsafe memory) throws Exception {
        try (var f = new Fixture(memory, .5)) {
            var record = new MoveToTaskRecord("fuse-no-increment", 600_000, 2D, 0D, 2D, null,
                    true, false, TransportMode.GROUND, false, true, 0, 0)
                    .withKnownStandableCells(List.of(new BlockPos(40, 0, 40), new BlockPos(-40, 0, 40)));
            var task = f.task(record, true); task.onStart();
            f.navigator = (TransportNavigator) field(PlayerNav.class, "navigator")
                    .get(field(AbstractCompanionTask.class, "nav").get(task));
            var session = planningSession(f);
            var probe = bareGround(memory, f);
            long highWater = 0;
            TaskState state = TaskState.RUNNING;
            for (int tick = 0; tick < 2600 && state == TaskState.RUNNING; tick++) {
                f.nextTick();
                if (tick > 0 && tick % 100 == 0) {
                    session.verifiedProgressTick = f.player.level().getGameTime();
                    highWater += 6000;
                    probe.observe(highWater);
                }
                state = task.onTick();
            }
            check(state == TaskState.FAILED, "没有增量已知格时收敛熔断仍要按原口径失败");
            var result = task.result(TaskState.FAILED);
            check(!result.data().containsKey("known_cell_waypoint_tried")
                            && !result.data().containsKey("known_cell_waypoint"),
                    "没有真正派出中转段时回执不得声明该段已启用");
            check(!String.valueOf(result.message()).contains("known standable cell waypoint leg"),
                    "没派段的失败说明不掺中转段叙述");
        }
    }

    /** 语义层只传输入数据：已知可站立格按记录顺序去重、只留当前维度、异维度地标与空运行时不越界；任务单侧保存副本。 */
    @SuppressWarnings("unchecked")
    private static void knownStandableCellHandoffKeepsDataOnly(Unsafe memory) throws Exception {
        try (var f = new Fixture(memory, .5)) {
            var goal = new Goal("maicraft:travel", "handoff", null, "{}", "{}", List.of(), List.of());
            var record = new IntentTaskRecord(UUID.randomUUID(), null, goal);
            var posCtor = Goal.WorldPosition.class.getDeclaredConstructor(
                    int.class, int.class, int.class, String.class);
            posCtor.setAccessible(true);
            var map = (java.util.LinkedHashMap<Integer, Goal.WorldPosition>) field(
                    IntentTaskRecord.class, "internalStepPositions").get(record);
            map.put(0, new Goal.WorldPosition(1, 2, 3, "minecraft:overworld"));
            map.put(1, (Goal.WorldPosition) posCtor.newInstance(4, 5, 6, "minecraft:the_nether"));
            map.put(2, new Goal.WorldPosition(1, 2, 3, "minecraft:overworld"));
            Class<?> intentTaskType = Class.forName("org.maiwithu.maicraft.intent.IntentTask");
            var ctor = intentTaskType.getDeclaredConstructor(
                    LocalPlayer.class, IntentTaskRecord.class, IntentRuntime.class);
            ctor.setAccessible(true);
            Object intentTask = ctor.newInstance(f.player, record, null);
            var method = intentTaskType.getDeclaredMethod("knownStandableCells");
            method.setAccessible(true);
            List<BlockPos> cells = (List<BlockPos>) method.invoke(intentTask);
            check(cells.equals(List.of(new BlockPos(1, 2, 3))),
                    "已知格交接要按回执去重并滤掉异维度位置");
            var source = new java.util.ArrayList<>(List.of(new BlockPos(7, 0, 7)));
            var moveRecord = new MoveToTaskRecord("handoff", 600, 0D, 0D, 0D, null, false)
                    .withKnownStandableCells(source);
            source.clear();
            check(moveRecord.knownStandableCells().equals(List.of(new BlockPos(7, 0, 7))),
                    "任务单要保存已知格副本，交付后源列表变化不得渗入");
            check(new MoveToTaskRecord("handoff-null", 600, 0D, 0D, 0D, null, false)
                    .withKnownStandableCells(null).knownStandableCells().isEmpty(),
                    "空交付必须是空清单，不能是 null");
        }
    }


}
