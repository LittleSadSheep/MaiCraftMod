// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import org.maiwithu.maicraft.game.player.PlayerContext;
import java.util.AbstractMap;
import java.util.Deque;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.BeforeEach;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.navigation.WalkReport;
import org.maiwithu.maicraft.behavior.navigation.calc.NavGoal;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跨维度段的任务：扫描没完成不收场、门没了换下一候选、20 秒进不去排除、
 * 维度变了才算到、先离门口再结算、没门且不许动地形就按没门结束。
 */
class DimensionTravelTaskTest {

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final BlockPos ENTRANCE = new BlockPos(6, 64, 8);

    private DimensionFakes.FakeWorld world;
    private DimensionFakes.FakeContext context;
    private DimensionFakes.FakeWalks walks;
    private DimensionTravelServices services;

    @BeforeEach
    void setUp() {
        world = new DimensionFakes.FakeWorld();
        context = new DimensionFakes.FakeContext();
        walks = new DimensionFakes.FakeWalks();
        services = new DimensionTravelServices(world, () -> context, walks,
                new DimensionFakes.FakeObtain(), new DimensionFakes.FakeToMainhand(),
                new DimensionFakes.FakeUses(), permissions -> null, null,
                () -> List.of(), progress -> {});
    }

    private static TickContext tick() {
        return new TickContext() {
            @Override public long gameTick() {
                return 0;
            }

            @Override public PlayerContext player() {
                return null;
            }
        };
    }

    /** 逐刻推进到结束；跑不完按失败收场。 */
    private static TaskResult run(DimensionTravelTask task) {
        task.start(tick());
        for (long i = 0; i < 5000; i++) {
            if (task.tick(tick()) instanceof TickResult.Finished finished) {
                return finished.result();
            }
        }
        throw new AssertionError("五千刻都没做完");
    }

    /** 身体沾着门格的脚本：进门前两次都在门里，第三次读时把维度换到下界（传送完成）。 */
    private void crossToNetherOnThirdRead() {
        world.touchScript.add(new AbstractMap.SimpleEntry<>(true, null));
        world.touchScript.add(new AbstractMap.SimpleEntry<>(true, null));
        world.touchScript.add(new AbstractMap.SimpleEntry<>(true,
                () -> world.dimension = DimensionRoute.NETHER));
    }

    private DimensionTravelTask taskToNether(Permissions permissions) {
        return new DimensionTravelTask(new DimensionTravelTask.Journey(
                DimensionRoute.NETHER, null, 128, 0, permissions), services);
    }

    @Test
    void scanIncompleteNeverConcludesNotFound() {
        // 扫描分刻推进：没扫完时任务不结束，也不冒充"没有"。
        world.enqueuePortals(new PortalFinder.Round(List.of(), false));
        DimensionTravelTask task = taskToNether(Permissions.DEFAULT);
        task.start(tick());
        assertFalse(task.tick(tick()) instanceof TickResult.Finished, "没扫完不下没找到的结论");
        assertFalse(task.tick(tick()) instanceof TickResult.Finished, "下一刻继续等扫描");
    }

    @Test
    void walksToDoorEntersAndSettlesAwayFromIt() {
        world.putPortal(Blocks.NETHER_PORTAL, ENTRANCE);
        world.enqueuePortals(new PortalFinder.Round(List.of(
                new PortalFinder.Entrance(ENTRANCE, Set.of(ENTRANCE.asLong(), ENTRANCE.above().asLong()))), true));
        crossToNetherOnThirdRead();
        DimensionTravelTask task = taskToNether(Permissions.DEFAULT);
        TaskResult result = run(task);

        assertEquals(TaskResult.Status.DONE, result.status(), result.summary() + " / " + result.problem());
        // 走向门口的导航目标把门格当要点到的方块并受保护：路线不穿门抄近路、不拆门。
        GoalCompiler.Compiled approach = walks.started.get(0);
        assertTrue(approach.sacred().contains(ENTRANCE.asLong()), "门格受保护");
        assertInstanceOf(NavGoal.GetToBlock.class, approach.goal(), "走到门口是站到门格旁边");
        assertEquals(2, walks.started.size(), "走近门口 + 离门口各走一段");
        DimensionTravelDetails details = (DimensionTravelDetails) result.details();
        assertEquals(DimensionRoute.NETHER, details.arrivedDimension());
        assertEquals(new WorldPosition(ENTRANCE.getX(), ENTRANCE.getY(), ENTRANCE.getZ(),
                DimensionRoute.OVERWORLD), details.portalAt());
    }

    @Test
    void doorGoneBeforeEntryTriesTheNextCandidate() {
        BlockPos second = new BlockPos(-12, 64, 4);
        world.putPortal(Blocks.NETHER_PORTAL, second);
        world.enqueuePortals(new PortalFinder.Round(List.of(
                new PortalFinder.Entrance(ENTRANCE, Set.of(ENTRANCE.asLong()))), true));
        world.enqueuePortals(new PortalFinder.Round(List.of(
                new PortalFinder.Entrance(second, Set.of(second.asLong()))), true));
        crossToNetherOnThirdRead();
        DimensionTravelTask task = taskToNether(Permissions.DEFAULT);
        TaskResult result = run(task);

        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        // 第一扇门进门前复核发现没了：排除并如实交代，最后进的是第二扇。
        assertTrue(result.attempts().stream().anyMatch(attempt -> attempt.result().contains("门方块已经不在了")),
                "进门前复核的交代要留在结果里：" + result.attempts());
        assertEquals(new WorldPosition(second.getX(), second.getY(), second.getZ(), DimensionRoute.OVERWORLD),
                ((DimensionTravelDetails) result.details()).portalAt());
    }

    @Test
    void approachFailureOnlyExcludesTheTriedEntrance() {
        world.putPortal(Blocks.NETHER_PORTAL, ENTRANCE);
        world.enqueuePortals(new PortalFinder.Round(List.of(
                new PortalFinder.Entrance(ENTRANCE, Set.of(ENTRANCE.asLong()))), true));
        world.enqueuePortals(new PortalFinder.Round(List.of(), false));
        world.enqueueScan();

        DimensionTravelTask task = taskToNether(Permissions.DEFAULT);
        TaskResult result = run(task);

        // 接近失败只排除这一格；扫描窗口已试败但没扫完，任务还在（另一侧仍在候选里）。
        assertTrue(result.attempts().stream().anyMatch(attempt -> attempt.tried().contains("6, 64, 8")),
                "试过哪个门口要写清楚：" + result.attempts());
        assertTrue(result.problem().message().contains("平整空地") || result.problem().kind() == Problem.Kind.NOT_FOUND,
                "失败交代现场：" + result.problem());
    }

    @Test
    void builtPortalIsWalkedThrough() {
        // 附近没门：建门子任务点着火、门面出现之后，回去找这扇门走进去，不按"没验出活门"收场。
        NetherPortalFrame frame = new NetherPortalFrame(new BlockPos(10, 65, 10), Direction.Axis.X, 2, 3);
        for (BlockPos cell : frame.frame()) {
            world.blocks.put(cell, Blocks.OBSIDIAN.defaultBlockState());
        }
        for (BlockPos cell : frame.interior()) {
            world.blocks.put(cell, Blocks.AIR.defaultBlockState());
        }
        world.carried = PortalBuilder.FLINT_AND_STEEL;
        world.enqueueScan(new DimensionWorldView.Scan(List.of(frame.cell(0, -1)), true));
        DimensionFakes.FakeUses lights = new DimensionFakes.FakeUses();
        lights.effects.add(() -> {
            for (BlockPos cell : frame.interior()) {
                world.blocks.put(cell, Blocks.NETHER_PORTAL.defaultBlockState());
            }
        });
        BlockPos doorway = frame.interior().get(0);
        world.enqueuePortals(new PortalFinder.Round(List.of(), true));
        world.enqueuePortals(new PortalFinder.Round(List.of(
                new PortalFinder.Entrance(doorway, Set.of(doorway.asLong()))), true));
        crossToNetherOnThirdRead();
        DimensionTravelServices building = new DimensionTravelServices(world, () -> context, walks,
                new DimensionFakes.FakeObtain(), new DimensionFakes.FakeToMainhand(), lights,
                permissions -> null, null, () -> List.of(), progress -> {});
        TaskResult result = run(new DimensionTravelTask(new DimensionTravelTask.Journey(
                DimensionRoute.NETHER, null, 128, 0, Permissions.DEFAULT), building));

        assertEquals(TaskResult.Status.DONE, result.status(), result.summary() + " / " + result.problem());
        assertEquals(DimensionRoute.NETHER, ((DimensionTravelDetails) result.details()).arrivedDimension());
    }

    @Test
    void entryTimeoutExcludesTheDoor() {
        world.putPortal(Blocks.NETHER_PORTAL, ENTRANCE);
        world.enqueuePortals(new PortalFinder.Round(List.of(
                new PortalFinder.Entrance(ENTRANCE, Set.of(ENTRANCE.asLong()))), true));
        world.enqueuePortals(new PortalFinder.Round(List.of(), true));
        // 门一直在，身体一直没进门格：20 秒后排除这扇门，随后按没门走建门收场（附近也没有空地）。
        world.enqueueScan();
        DimensionTravelTask task = taskToNether(Permissions.DEFAULT);
        TaskResult result = run(task);

        assertEquals(TaskResult.Status.FAILED, result.status());
        assertTrue(result.attempts().stream().anyMatch(attempt -> attempt.result().contains("秒没能走进门格")),
                "20 秒进不去要如实交代：" + result.attempts());
    }

    @Test
    void noTerrainPermissionEndsWithoutBuildingOrAsking() {
        // change_blocks=none：没门就按没门结束并建议提许可，不开工建门、不向 LLM 提问。
        world.enqueuePortals(new PortalFinder.Round(List.of(), true));
        Permissions noChange = Permissions.DEFAULT.mergedWith(Permissions.BlockChanges.NONE,
                null, null, null, null, null);
        DimensionTravelTask task = taskToNether(noChange);
        TaskResult result = run(task);

        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(Problem.Kind.NOT_FOUND, result.problem().kind());
        assertTrue(result.problem().suggestion().contains("change_blocks"),
                "建议里写明提许可：" + result.problem().suggestion());
    }

    private DimensionTravelTask taskHomeFromTheEnd() {
        world.dimension = DimensionRoute.END;
        return new DimensionTravelTask(new DimensionTravelTask.Journey(
                DimensionRoute.OVERWORLD, null, 128, 0, Permissions.DEFAULT), services);
    }

    private static Map.Entry<Boolean, Runnable> touch(Runnable effect) {
        return new AbstractMap.SimpleEntry<>(true, effect);
    }

    @Test
    void litExitPortalIsWalkedIntoAndSettledAtTheRespawnPoint() {
        // 龙死了门亮着：不扫描，直接从出口门挑入口走过去跳进去；落在重生点，不再离落点几步。
        world.feet = new BlockPos(30, 61, 4);
        world.lightExitPortal();
        world.touchScript.add(touch(null));
        world.touchScript.add(touch(() -> {
            world.dimension = DimensionRoute.OVERWORLD;
            world.feet = new BlockPos(120, 70, -35);
        }));
        TaskResult result = run(taskHomeFromTheEnd());

        assertEquals(TaskResult.Status.DONE, result.status(), result.summary() + " / " + result.problem());
        assertEquals(1, walks.started.size(), "只走近门口一段，到了重生点不离落点");
        DimensionTravelDetails details = (DimensionTravelDetails) result.details();
        assertEquals(DimensionRoute.OVERWORLD, details.arrivedDimension());
        WorldPosition door = details.portalAt();
        assertEquals(DimensionRoute.END, door.dimension(), "用的门按末地坐标写");
        assertEquals(Blocks.END_PORTAL, world.blocks.get(new BlockPos(door.x(), door.y(), door.z())).getBlock(),
                "入口是出口门的门格：" + door);
        assertNull(details.expectedLanding(), "出口门落在重生点，没有换算预期");
        assertEquals(-1, details.landingOffset());
        assertFalse(details.oneWay(), "走出口门回来不再提回程要打龙");
        assertTrue(result.summary().contains("重生点"), result.summary());
        assertTrue(result.summary().contains("120, 70, -35"), "报实测落点：" + result.summary());
    }

    @Test
    void firstExitPlaysTheEndPoemAndItIsSkipped() {
        // 第一次过出口门原版放终末之诗：像按 Esc 一样关掉一次，原版随即把人送回去，结果里写明跳过了。
        world.lightExitPortal();
        world.touchScript.add(touch(() -> world.poemShowing = true));
        world.onPoemClosed = () -> {
            world.poemShowing = false;
            world.dimension = DimensionRoute.OVERWORLD;
        };
        TaskResult result = run(taskHomeFromTheEnd());

        assertEquals(TaskResult.Status.DONE, result.status(), result.summary() + " / " + result.problem());
        assertEquals(1, world.poemCloses, "关一次就够");
        assertTrue(result.summary().contains("终末之诗"), result.summary());
    }

    @Test
    void poemIsNeverTouchedOnOtherRoutes() {
        // 不是走出口门：屏幕上就算有那张界面也不去关，只认维度。
        world.poemShowing = true;
        world.putPortal(Blocks.NETHER_PORTAL, ENTRANCE);
        world.enqueuePortals(new PortalFinder.Round(List.of(
                new PortalFinder.Entrance(ENTRANCE, Set.of(ENTRANCE.asLong()))), true));
        crossToNetherOnThirdRead();
        TaskResult result = run(taskToNether(Permissions.DEFAULT));

        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertEquals(0, world.poemCloses);
    }

    @Test
    void unlitExitPortalSaysBeatTheDragonFirst() {
        // 门座在、门洞是空的：龙还活着，一步不走，说要先用 fight 打龙，不去找别的门。
        TaskResult result = run(taskHomeFromTheEnd());

        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(Problem.Kind.NOT_POSSIBLE_HERE, result.problem().kind());
        assertTrue(result.problem().message().contains("末影龙"), result.problem().message());
        assertTrue(result.problem().suggestion().contains("fight"), result.problem().suggestion());
        assertTrue(walks.started.isEmpty(), "门没亮不往哪儿走");
    }

    @Test
    void unloadedIslandCenterSaysGoBackToTheIsland() {
        // 主岛中心那一片没加载（人在外岛）：看不见门，不冒充"没亮"，也不自己往虚空那边走。
        world.exitLoaded = false;
        TaskResult result = run(taskHomeFromTheEnd());

        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(Problem.Kind.NOT_FOUND, result.problem().kind());
        assertTrue(result.problem().suggestion().contains("主岛"), result.problem().suggestion());
        assertTrue(walks.started.isEmpty());
    }

    @Test
    void onTheOuterIslandsTheSuggestionPointsAtTheEndGateway() {
        // 人在外岛：回主岛走不过去，建议里直接说用 via=end_gateway 过折跃门回去。
        world.exitLoaded = false;
        world.dimension = DimensionRoute.END;
        world.feet = new BlockPos(1100, 60, 0);
        TaskResult result = run(taskHomeFromTheEnd());

        assertEquals(Problem.Kind.NOT_FOUND, result.problem().kind());
        assertTrue(result.problem().suggestion().contains("via=end_gateway"), result.problem().suggestion());
    }

    @Test
    void respawnAnchorInTheNetherKeepsGoingThroughANetherPortal() {
        // 重生锚设在下界：出口门先送到下界，从那里接着走下界门回主世界，结果里先交代这一段。
        world.lightExitPortal();
        world.touchScript.add(touch(null));
        world.touchScript.add(touch(() -> {
            world.dimension = DimensionRoute.NETHER;
            world.feet = new BlockPos(10, 70, 10);
        }));
        world.putPortal(Blocks.NETHER_PORTAL, ENTRANCE);
        world.enqueuePortals(new PortalFinder.Round(List.of(
                new PortalFinder.Entrance(ENTRANCE, Set.of(ENTRANCE.asLong()))), true));
        world.touchScript.add(touch(null));
        world.touchScript.add(touch(() -> world.dimension = DimensionRoute.OVERWORLD));
        TaskResult result = run(taskHomeFromTheEnd());

        assertEquals(TaskResult.Status.DONE, result.status(), result.summary() + " / " + result.problem());
        assertTrue(result.summary().contains("重生锚"), result.summary());
        DimensionTravelDetails details = (DimensionTravelDetails) result.details();
        assertEquals(DimensionRoute.OVERWORLD, details.arrivedDimension());
        assertEquals(DimensionRoute.NETHER, details.portalAt().dimension(), "后一段用的是下界门");
        assertEquals(3, walks.started.size(), "走近出口门、走近下界门、离下界门落点各一段");
    }

    @Test
    void everyExitCellTriedEndsUnreachableWithEachReason() {
        // 门洞边一格都走不过去：二十格逐个排除，不去找别的门；每格的原因各记一条，条数就是门格数。
        world.lightExitPortal();
        for (int i = 0; i < 20; i++) {
            walks.enqueue(new TravelFakes.ScriptedWalk(WalkReport.failed(
                    Problem.of(Problem.Kind.UNREACHABLE, "门洞边被围死了"), new BlockPos(30, 61, 4), false)));
        }
        TaskResult result = run(taskHomeFromTheEnd());

        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(Problem.Kind.UNREACHABLE, result.problem().kind());
        assertTrue(result.problem().message().contains("20 个门格"), result.problem().message());
        assertEquals(20, result.attempts().size(), "一格一条：" + result.attempts());
        assertEquals(20, walks.started.size());
    }

    @Test
    void dimensionOnlyGoalAlreadyThereFinishesAtOnce() {
        DimensionTravelTask task = new DimensionTravelTask(new DimensionTravelTask.Journey(
                DimensionRoute.OVERWORLD, null, 128, 0, Permissions.DEFAULT), services);
        TaskResult result = run(task);
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertEquals(DimensionRoute.OVERWORLD, ((DimensionTravelDetails) result.details()).arrivedDimension());
    }

    @Test
    void coordinateGoalKeepsWalkingAfterCrossing() {
        // 带坐标的目标：跨完接着走步行段，最后一段到了整趟才算到。
        world.putPortal(Blocks.NETHER_PORTAL, ENTRANCE);
        world.enqueuePortals(new PortalFinder.Round(List.of(
                new PortalFinder.Entrance(ENTRANCE, Set.of(ENTRANCE.asLong()))), true));
        crossToNetherOnThirdRead();
        TravelDestination there = TravelDestination.confirmed(
                new WorldPosition(80, 40, -20, DimensionRoute.NETHER), 2);
        DimensionTravelTask task = new DimensionTravelTask(new DimensionTravelTask.Journey(
                DimensionRoute.NETHER, there, 128, 0, Permissions.DEFAULT), services);
        TaskResult result = run(task);
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary() + " / " + result.problem()
);
        assertTrue(result.summary().contains("the_nether"), "总结里先说到哪个维度：" + result.summary());
        assertEquals(3, walks.started.size(), "走近门口、离门口、最后一段步行各一次");
    }

    @Test
    void settlementCarriesScaleExpectationAndOffset() {
        world.feet = new BlockPos(808, 64, -248);
        world.putPortal(Blocks.NETHER_PORTAL, ENTRANCE);
        world.enqueuePortals(new PortalFinder.Round(List.of(
                new PortalFinder.Entrance(ENTRANCE, Set.of(ENTRANCE.asLong()))), true));
        world.touchScript.add(new AbstractMap.SimpleEntry<>(true, null));
        world.touchScript.add(new AbstractMap.SimpleEntry<>(true, null));
        world.touchScript.add(new AbstractMap.SimpleEntry<>(true, () -> {
            world.dimension = DimensionRoute.NETHER;
            world.feet = new BlockPos(101, 40, -31);
        }));
        DimensionTravelTask task = taskToNether(Permissions.DEFAULT);
        TaskResult result = run(task);
        DimensionTravelDetails details = (DimensionTravelDetails) result.details();
        // 从主世界 (808, *, -248) 过门，预期落在下界 (101, *, -31)：实测就在预期上，水平偏差 0。
        WorldPosition expected = details.expectedLanding();
        assertEquals(DimensionRoute.NETHER, expected.dimension());
        assertEquals(101, expected.x());
        assertEquals(-31, expected.z());
        assertEquals(0, details.landingOffset());
    }
}
