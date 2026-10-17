// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.explore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.behavior.navigation.WalkReport;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryKind;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryOrigin;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryRecord;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.kernel.event.TaskEvent;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;
import org.maiwithu.maicraft.kernel.task.TickResult;

/** 探索任务：观察推进、发现落账、半径收尾、换向升级与回露天的竖直上掘，全部按现场如实结算。 */
class ExploreTaskTest {

    @BeforeAll
    static void bootMinecraft() {
        // 结构线索的证据表按真实注册表解析：注册表引导过才能碰。
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @TempDir
    Path temp;

    private final ExploreFakes.FakeWorld world = new ExploreFakes.FakeWorld();
    private final ExploreFakes.FakeWalks walks = new ExploreFakes.FakeWalks();
    private final ExploreFakes.FakeEvents events = new ExploreFakes.FakeEvents();
    private final ExploreFakes.TestTick tick = new ExploreFakes.TestTick();

    private WorldMemory memory() {
        WorldMemory memory = new WorldMemory(new DocumentStore(temp.resolve("state.sqlite")), "key");
        world.walks = walks;
        return memory;
    }

    private ExploreTask task(WorldMemory memory, ExploreObjective objective,
            ExploreObjective.Interests interests, Target.Toward direction,
            int angle, int minDistance, int maxDistance, TerrainPermit permit) {
        return new ExploreTask(objective, interests, direction, angle, minDistance, maxDistance,
                0, permit, world, walks, memory, events);
    }

    private static final TerrainPermit NATURAL = TerrainPermit.NATURAL;

    /** 推进到出结果为止（观察一圈约五十多刻，留足余量）。 */
    private TaskResult runToFinish(ExploreTask task) {
        task.start(tick);
        for (int i = 0; i < 400; i++) {
            TickResult result = task.tick(tick);
            if (result instanceof TickResult.Finished finished) {
                return finished.result();
            }
            tick.nextTick();
        }
        throw new AssertionError("探索任务没有按脚本收场");
    }

    /** 只推进给定的刻数，不等到收场。 */
    private void runTicks(ExploreTask task, int ticks) {
        task.start(tick);
        for (int i = 0; i < ticks; i++) {
            task.tick(tick);
            tick.nextTick();
        }
    }

    @Test
    void 身体所在群系免视线闸记到访_看不见的远望样本只计列不落账() {
        WorldMemory memory = memory();
        // 东边 40 格有一片丛林，但被山挡住：过不了视线闸，就不能当"看见了"记。
        world.putBiome(40, 0, "minecraft:jungle");
        world.blocked.add("40,0");
        ExploreTask task = task(memory, ExploreObjective.survey(), ExploreObjective.Interests.NONE,
                null, 360, 0, 256, NATURAL);
        runTicks(task, 60);

        Optional<MemoryRecord> plains =
                memory.allRecords().stream()
                        .filter(record -> record.contents() != null
                                && record.contents().contains("minecraft:plains"))
                        .findFirst();
        assertTrue(plains.isPresent(), "脚下的平原群系要记进世界记忆");
        assertEquals(MemoryOrigin.VISITED, plains.get().origin(), "身体所在群系是到访");
        assertTrue(memory.allRecords().stream()
                        .noneMatch(record -> record.contents() != null
                                && record.contents().contains("minecraft:jungle")),
                "过不了视线闸的远望样本不落账");
    }

    @Test
    void 留意清单的方块看见了记入结果与世界记忆并发一条事件() {
        WorldMemory memory = memory();
        world.putColumn(24, 0, new ColumnSample(true, "minecraft:plains", 63, false, true,
                "minecraft:chest"));
        ExploreObjective.Interests interests = new ExploreObjective.Interests(
                List.of("minecraft:chest"), List.of(), List.of(), List.of());
        walks.enqueue(new ExploreFakes.ScriptedWalk(
                WalkReport.planning(),
                WalkReport.onTheWay(new BlockPos(0, 64, 250), false)));
        ExploreTask task = task(memory, ExploreObjective.survey(), interests,
                null, 360, 0, 256, NATURAL);
        TaskResult result = runToFinish(task);

        long chestEvents = events.messages.stream()
                .filter(message -> message.contains("minecraft:chest")).count();
        assertEquals(1, chestEvents, "同一发现只发一条事件");
        assertTrue(memory.allRecords().stream().anyMatch(record ->
                        record.kind() == MemoryKind.SITE
                                && record.contents().contains("minecraft:chest")),
                "留意命中落进世界记忆");
        ExploreDetails details = assertInstanceOf(ExploreDetails.class, result.details());
        assertTrue(details.findings().stream().anyMatch(finding ->
                finding.kind() == ExploreFinding.Kind.BLOCK));
    }

    @Test
    void 定向探索走到候选群系核实到访并交付位置() {
        WorldMemory memory = memory();
        world.putBiome(0, -40, "minecraft:jungle");
        walks.enqueue(new ExploreFakes.ScriptedWalk(
                WalkReport.planning(),
                WalkReport.onTheWay(new BlockPos(0, 64, -20), false),
                WalkReport.arrived(new BlockPos(0, 64, -40), false)));
        ExploreTask task = task(memory,
                new ExploreObjective(ExploreObjective.Kind.BIOME, List.of("minecraft:jungle")),
                ExploreObjective.Interests.NONE, Target.Toward.NORTH, 90, 16, 256, NATURAL);
        TaskResult result = runToFinish(task);

        assertEquals(TaskResult.Status.DONE, result.status(), () -> "problem: " + result.problem());
        ExploreDetails details = assertInstanceOf(ExploreDetails.class, result.details());
        assertNotNull(details.reached());
        assertEquals(new WorldPosition(0, 64, -40, "minecraft:overworld"), details.reached());
        assertTrue(details.findings().stream().anyMatch(finding ->
                        finding.kind() == ExploreFinding.Kind.BIOME
                                && finding.targetId().equals("minecraft:jungle")
                                && finding.origin() == MemoryOrigin.VISITED),
                "核实过的群系按到访落账");
        assertEquals(1, details.legs().total());
        assertEquals(0, details.legs().failures());
    }

    @Test
    void 走失败的候选记进拒绝集_回观察不再选它() {
        WorldMemory memory = memory();
        // 一片丛林（抽样网格换了圆心也会命中旁边几列）。
        for (int x = -10; x <= 10; x++) {
            for (int z = -50; z <= -36; z++) {
                world.putBiome(x, z, "minecraft:jungle");
            }
        }
        // 朝候选走的几段都走不过去，身体留在出发点附近。
        for (int i = 0; i < 6; i++) {
            walks.enqueue(new ExploreFakes.ScriptedWalk(
                    WalkReport.planning(),
                    WalkReport.failed(Problem.of(Problem.Kind.UNREACHABLE, "隔着一道崖"),
                            new BlockPos(0, 64, -2), false)));
        }
        ExploreTask task = task(memory,
                new ExploreObjective(ExploreObjective.Kind.BIOME, List.of("minecraft:jungle")),
                ExploreObjective.Interests.NONE, Target.Toward.NORTH, 90, 16, 256, NATURAL);
        runTicks(task, 300);
        // 走失败的那一处周围 16 格内不再当候选：朝这片丛林只出发过一次。
        long toJungle = walks.started.stream()
                .filter(destination -> Math.abs(destination.getX()) <= 10
                        && destination.getZ() <= -36 && destination.getZ() >= -50).count();
        assertEquals(1, toJungle, "走失败的候选不再反复去撞：" + walks.started);
    }

    @Test
    void 行进中达成目标立即取消在途段收尾() {
        WorldMemory memory = memory();
        world.putBiome(0, -40, "minecraft:jungle");
        walks.enqueue(new ExploreFakes.ScriptedWalk(
                WalkReport.planning(),
                WalkReport.onTheWay(new BlockPos(0, 64, -40), false),
                WalkReport.onTheWay(new BlockPos(0, 64, -60), false),
                WalkReport.arrived(new BlockPos(0, 64, -80), false))
                .stopAnswers(false, true));
        ExploreTask task = task(memory,
                new ExploreObjective(ExploreObjective.Kind.BIOME, List.of("minecraft:jungle")),
                ExploreObjective.Interests.NONE, Target.Toward.NORTH, 90, 16, 256, NATURAL);
        TaskResult result = runToFinish(task);

        assertEquals(TaskResult.Status.DONE, result.status(), () -> "problem: " + result.problem());
        ExploreDetails details = assertInstanceOf(ExploreDetails.class, result.details());
        assertEquals(new WorldPosition(0, 64, -40, "minecraft:overworld"), details.reached(),
                "进过 jungle 那一刻就核实收尾，不走过头");
        assertTrue(walks.currentStopRequests() >= 1, "在途段要请求停下");
    }

    @Test
    void 到达半径成功收尾并取消在途段() {
        WorldMemory memory = memory();
        walks.enqueue(new ExploreFakes.ScriptedWalk(
                WalkReport.planning(),
                WalkReport.onTheWay(new BlockPos(0, 64, 30), false),
                WalkReport.onTheWay(new BlockPos(0, 64, 58), false))
                .stopAnswers(true));
        ExploreTask task = task(memory, ExploreObjective.survey(), ExploreObjective.Interests.NONE,
                null, 360, 0, 64, NATURAL);
        TaskResult result = runToFinish(task);

        assertEquals(TaskResult.Status.DONE, result.status(), () -> "problem: " + result.problem());
        ExploreDetails details = assertInstanceOf(ExploreDetails.class, result.details());
        assertEquals(ExploreDetails.StopReason.RADIUS_REACHED, details.stopReason());
        assertTrue(walks.currentStopRequests() >= 1, "进入判定带要取消在途段");
    }

    @Test
    void 进入判定带那一刻恰好走到_照样收工不干站着() {
        // 实机（全量测试 10-12）：在判定带上恰好走到航点，收段只认"停下"不认"到了"，人在山顶站了五分钟不收工。
        WorldMemory memory = memory();
        walks.enqueue(new ExploreFakes.ScriptedWalk(
                WalkReport.planning(),
                WalkReport.onTheWay(new BlockPos(0, 64, 58), false),
                WalkReport.arrived(new BlockPos(0, 64, 59), false))
                .stopAnswers(Collections.nCopies(10_000, false).toArray(Boolean[]::new)));
        ExploreTask task = task(memory, ExploreObjective.survey(), ExploreObjective.Interests.NONE,
                null, 360, 0, 64, NATURAL);
        TaskResult result = runToFinish(task);

        assertEquals(TaskResult.Status.DONE, result.status(), () -> "problem: " + result.problem());
        assertEquals(ExploreDetails.StopReason.RADIUS_REACHED,
                assertInstanceOf(ExploreDetails.class, result.details()).stopReason());
    }

    @Test
    void 越过硬边界截停独立如实报告() {
        WorldMemory memory = memory();
        walks.enqueue(new ExploreFakes.ScriptedWalk(
                WalkReport.planning(),
                WalkReport.onTheWay(new BlockPos(0, 64, 80), false)));
        ExploreTask task = task(memory, ExploreObjective.survey(), ExploreObjective.Interests.NONE,
                null, 360, 0, 64, NATURAL);
        TaskResult result = runToFinish(task);

        assertEquals(TaskResult.Status.FAILED, result.status(), () -> "problem: " + result.problem());
        Problem problem = result.problem();
        assertEquals(Problem.Kind.UNREACHABLE, problem.kind());
        assertTrue(problem.message().contains("越出"), "如实写明越界，不冒充无路也不冒充达成");
        ExploreDetails details = assertInstanceOf(ExploreDetails.class, result.details());
        assertEquals(ExploreDetails.StopReason.LEFT_BOUND, details.stopReason());
    }

    @Test
    void 扇形外或看不见的候选只记不追() {
        WorldMemory memory = memory();
        // 东边看得见的丛林：记一条远望，但不在朝北的扇形里，不出发。
        world.putBiome(40, 0, "minecraft:jungle");
        // 北边看不见的丛林：在扇形里，但过不了视线闸，同样不出发也不落账。
        world.putBiome(0, -40, "minecraft:jungle");
        world.blocked.add("0,-40");
        ExploreTask task = task(memory,
                new ExploreObjective(ExploreObjective.Kind.BIOME, List.of("minecraft:jungle")),
                ExploreObjective.Interests.NONE, Target.Toward.NORTH, 90, 16, 256, NATURAL);
        runTicks(task, 60);

        // 不出发，是指不朝这两处候选去核实：东边的在扇形外，北边的看不见。
        assertTrue(walks.started.stream().noneMatch(cell ->
                        cell.getX() == 40 && cell.getZ() == 0 || cell.getX() == 0 && cell.getZ() == -40),
                "扇形外与看不见的候选都不出发去核实");
        // 没走到就不冒充到访：记下的只是远望。
        assertTrue(memory.allRecords().stream().anyMatch(record ->
                        record.contents() != null && record.contents().contains("minecraft:jungle")
                                && record.origin() == MemoryOrigin.SEEN),
                "扇形外看得见的远望证据照记");
        assertTrue(memory.allRecords().stream().noneMatch(record ->
                        record.contents() != null && record.contents().contains("minecraft:jungle")
                                && record.origin() == MemoryOrigin.VISITED),
                "没走到就没有到访");
    }

    @Test
    void 最近五段失败满四段触发换出发点() {
        WorldMemory memory = memory();
        for (int i = 0; i < 5; i++) {
            walks.enqueue(new ExploreFakes.ScriptedWalk(
                    WalkReport.onTheWay(new BlockPos(0, 64, -8 - i), false),
                    WalkReport.failed(Problem.of(Problem.Kind.UNREACHABLE, "崖壁挡路"),
                            new BlockPos(0, 64, -8 - i), false)));
        }
        ExploreTask task = task(memory,
                new ExploreObjective(ExploreObjective.Kind.BIOME, List.of("minecraft:jungle")),
                ExploreObjective.Interests.NONE, Target.Toward.NORTH, 90, 16, 256, NATURAL);
        runToFinish(task);

        assertTrue(walks.started.size() >= 5, "失败四段之后要升级，不能原地反复试同一条路");
        BlockPos fifth = walks.started.get(4);
        assertEquals(32, fifth.getX(), "第五段是换出发点：垂直于当前方位横移约 32 格");
    }

    @Test
    void 换出发点与轮换都试过才受阻_受阻说明带全部历史() {
        WorldMemory memory = memory();
        // 稀疏世界：只有出发点与东西两侧 32 格已加载，北面全是未加载的迷雾。
        world.onlyRegisteredColumns = true;
        world.putBiome(0, 0, "minecraft:plains");
        world.putBiome(32, 0, "minecraft:plains");
        world.putBiome(-32, 0, "minecraft:plains");
        walks.enqueue(new ExploreFakes.ScriptedWalk(
                WalkReport.onTheWay(new BlockPos(32, 64, 0), false),
                WalkReport.failed(Problem.of(Problem.Kind.UNREACHABLE, "无路"), new BlockPos(32, 64, 0), false)));
        walks.enqueue(new ExploreFakes.ScriptedWalk(
                WalkReport.onTheWay(new BlockPos(-32, 64, 0), false),
                WalkReport.failed(Problem.of(Problem.Kind.UNREACHABLE, "无路"), new BlockPos(-32, 64, 0), false)));
        ExploreTask task = task(memory,
                new ExploreObjective(ExploreObjective.Kind.BIOME, List.of("minecraft:jungle")),
                ExploreObjective.Interests.NONE, Target.Toward.NORTH, 90, 16, 256, NATURAL);
        TaskResult result = runToFinish(task);

        assertEquals(TaskResult.Status.FAILED, result.status(), () -> "problem: " + result.problem());
        Problem problem = result.problem();
        assertEquals(Problem.Kind.UNREACHABLE, problem.kind());
        assertTrue(problem.message().contains("换出发点"));
        assertTrue(problem.message().contains("轮换"));
        assertTrue(problem.message().contains("受阻不是目标不存在的证据"));
        ExploreDetails details = assertInstanceOf(ExploreDetails.class, result.details());
        assertEquals(ExploreDetails.StopReason.BLOCKED, details.stopReason());
        assertTrue(details.sectorHistory().stream().anyMatch(note -> note.contains("横移")));
        assertTrue(details.sectorHistory().stream().anyMatch(note -> note.contains("轮换")));
        assertEquals(2, details.legs().failures(), "两次换出发点都没走出去");
    }

    @Test
    void 全向跑图走不出去时没到过航点按卡住收场() {
        WorldMemory memory = memory();
        world.onlyRegisteredColumns = true;
        world.putBiome(0, 0, "minecraft:plains");
        ExploreTask task = task(memory, ExploreObjective.survey(), ExploreObjective.Interests.NONE,
                null, 360, 0, 256, NATURAL);
        TaskResult result = runToFinish(task);

        assertEquals(TaskResult.Status.FAILED, result.status(), () -> "problem: " + result.problem());
        assertEquals(Problem.Kind.STUCK, result.problem().kind());
        assertTrue(result.problem().message().contains("没到过任何航点"));
    }

    @Test
    void 回露天_被埋且有动土许可先沿本列竖直上掘破出() {
        WorldMemory memory = memory();
        // 矿道里：脚下 20，头顶这一列要到 90 才是地表。
        world.spot = new WorldPosition(0, 20, 0, "minecraft:overworld");
        world.putColumn(0, 0, new ColumnSample(true, "minecraft:dripstone_caves", 90, false, true,
                "minecraft:stone"));
        walks.enqueue(new ExploreFakes.ScriptedWalk(
                WalkReport.planning(),
                WalkReport.onTheWay(new BlockPos(0, 45, 0), false),
                WalkReport.arrived(new BlockPos(0, 91, 0), false)));
        ExploreTask task = task(memory,
                new ExploreObjective(ExploreObjective.Kind.SURFACE, List.of()),
                ExploreObjective.Interests.NONE, null, 360, 0, 256, NATURAL);
        TaskResult result = runToFinish(task);

        assertEquals(TaskResult.Status.DONE, result.status(), () -> "problem: " + result.problem());
        ExploreDetails details = assertInstanceOf(ExploreDetails.class, result.details());
        assertEquals(new WorldPosition(0, 91, 0, "minecraft:overworld"), details.reached(),
                "破出露天的位置如实交付");
        assertEquals(0, walks.started.get(0).getX());
        assertEquals(0, walks.started.get(0).getZ(), "上掘沿脚下的这一列");
    }

    @Test
    void 回露天_顶盖是液体跳过上掘照走地面流程() {
        WorldMemory memory = memory();
        world.spot = new WorldPosition(0, 20, 0, "minecraft:overworld");
        world.putColumn(0, 0, new ColumnSample(true, "minecraft:dripstone_caves", 62, true, false,
                "minecraft:water"));
        walks.enqueue(new ExploreFakes.ScriptedWalk(
                WalkReport.planning(),
                WalkReport.onTheWay(new BlockPos(0, 20, 30), false),
                WalkReport.arrived(new BlockPos(0, 20, 58), false)));
        ExploreTask task = task(memory,
                new ExploreObjective(ExploreObjective.Kind.SURFACE, List.of()),
                ExploreObjective.Interests.NONE, null, 360, 0, 64, NATURAL);
        TaskResult result = runToFinish(task);

        assertTrue(walks.started.stream().noneMatch(cell -> cell.getX() == 0 && cell.getZ() == 0),
                "顶盖是液体不上掘：硬钻会灌进来，照走地面流程");
        assertEquals(TaskResult.Status.FAILED, result.status(), "走完范围也没回到露天，如实说没找到");
        assertEquals(Problem.Kind.NOT_FOUND, result.problem().kind());
    }

    @Test
    void 回露天_没有动土许可时如实结束并写明出路() {
        WorldMemory memory = memory();
        world.spot = new WorldPosition(0, 20, 0, "minecraft:overworld");
        world.putColumn(0, 0, new ColumnSample(true, "minecraft:dripstone_caves", 90, false, true,
                "minecraft:stone"));
        ExploreTask task = task(memory,
                new ExploreObjective(ExploreObjective.Kind.SURFACE, List.of()),
                ExploreObjective.Interests.NONE, null, 360, 0, 256, TerrainPermit.WALK_ONLY);
        TaskResult result = runToFinish(task);

        assertEquals(TaskResult.Status.FAILED, result.status(), () -> "problem: " + result.problem());
        assertEquals(Problem.Kind.NEED_APPROVAL, result.problem().kind());
        assertTrue(result.problem().suggestion().contains("许可动土"));
    }

    @Test
    void 找平台站上四邻三格同高的地方才交货() {
        WorldMemory memory = memory();
        // 四邻是相邻的四格：脚下 (0,-48) 加东南西北里至少三格同高踩得住。
        for (int[] around : new int[][]{{0, -48}, {0, -49}, {1, -48}, {0, -47}, {-1, -48}}) {
            world.support(around[0], 64, around[1]);
        }
        walks.enqueue(new ExploreFakes.ScriptedWalk(
                WalkReport.planning(),
                WalkReport.onTheWay(new BlockPos(0, 64, -24), false),
                WalkReport.arrived(new BlockPos(0, 64, -48), false)));
        ExploreTask task = task(memory,
                new ExploreObjective(ExploreObjective.Kind.FLAT_GROUND, List.of()),
                ExploreObjective.Interests.NONE, Target.Toward.NORTH, 90, 16, 256, NATURAL);
        TaskResult result = runToFinish(task);

        assertEquals(TaskResult.Status.DONE, result.status(), () -> "problem: " + result.problem());
        ExploreDetails details = assertInstanceOf(ExploreDetails.class, result.details());
        assertEquals(new WorldPosition(0, 64, -48, "minecraft:overworld"), details.reached());
    }

    @Test
    void 站上的地方复核不过就不冒充到达_接着走下一处() {
        WorldMemory memory = memory();
        // 到了那一柱，但脚下事实不是平台（四邻不同高）：不算找到，接着走下一处。
        walks.enqueue(new ExploreFakes.ScriptedWalk(
                WalkReport.planning(),
                WalkReport.onTheWay(new BlockPos(0, 64, -24), false),
                WalkReport.arrived(new BlockPos(0, 64, -48), false)));
        walks.enqueue(new ExploreFakes.ScriptedWalk(
                WalkReport.onTheWay(new BlockPos(0, 64, 58), false)));
        ExploreTask task = task(memory,
                new ExploreObjective(ExploreObjective.Kind.FLAT_GROUND, List.of()),
                ExploreObjective.Interests.NONE, Target.Toward.NORTH, 90, 16, 64, NATURAL);
        TaskResult result = runToFinish(task);

        assertEquals(TaskResult.Status.FAILED, result.status(), () -> "problem: " + result.problem());
        assertEquals(Problem.Kind.NOT_FOUND, result.problem().kind(),
                "到达半径也没核实到平台，如实说查过没有");
        ExploreDetails details = assertInstanceOf(ExploreDetails.class, result.details());
        assertNull(details.reached(), "复核不过的位置不冒充核实位置");
        assertTrue(details.legs().total() >= 1, "走过航点，没停在不合格的地方");
    }

    @Test
    void 跑图途中发现结构线索来源远望_走到跟前升级到访() {
        WorldMemory memory = memory();
        ExploreObjective.Interests interests = new ExploreObjective.Interests(List.of(), List.of(),
                List.of("minecraft:village_plains"), List.of());
        world.structureClues = List.of(new ExplorationWorld.Spotted("minecraft:village_plains",
                new WorldPosition(40, 63, 0, "minecraft:overworld")));
        walks.enqueue(new ExploreFakes.ScriptedWalk(
                WalkReport.planning(),
                WalkReport.onTheWay(new BlockPos(16, 64, 8), false),
                WalkReport.arrived(new BlockPos(32, 64, 8), false)));
        ExploreTask task = task(memory, ExploreObjective.survey(), interests,
                null, 360, 0, 256, NATURAL);
        runTicks(task, 260);

        var village = memory.allRecords().stream()
                .filter(record -> record.contents() != null
                        && record.contents().contains("minecraft:village_plains"))
                .toList();
        assertEquals(1, village.size(), "同一条线索合并成一条");
        assertEquals(MemoryOrigin.VISITED, village.get(0).origin(),
                "走到结构跟前，远望的线索升级成到访");
        assertTrue(events.kinds.contains(TaskEvent.Kind.DISCOVERED));
    }

    @Test
    void 起点群系按身体所在格实测记_不采顶盖采样() {
        WorldMemory memory = memory();
        // 起点这一列的顶盖采样读到地下的深暗之域（区块刚同步时的错读），身体实测站在森林：
        // 起点到访记忆认实测值，不把采样值写成脏记忆。世界只有起点一列加载，身体不动。
        world.onlyRegisteredColumns = true;
        world.putColumn(0, 0, new ColumnSample(true, "minecraft:deep_dark", 63, false, true,
                "minecraft:grass_block"));
        world.bodyBiome = "minecraft:forest";
        ExploreTask task = task(memory, ExploreObjective.survey(), ExploreObjective.Interests.NONE,
                null, 360, 0, 256, NATURAL);
        runTicks(task, 60);

        assertTrue(memory.allRecords().stream().anyMatch(record -> record.contents() != null
                        && record.contents().contains("minecraft:forest")),
                "起点群系按实测的森林记");
        assertTrue(memory.allRecords().stream().noneMatch(record -> record.contents() != null
                        && record.contents().contains("minecraft:deep_dark")),
                "顶盖采样的深暗之域不进记忆");
    }

    @Test
    void 空转满时限收场写明停工原因与现场() {
        WorldMemory memory = memory();
        // 观察圈看完后朝航点走，走到对着到不了的落点每刻空转：收场要有 stop_reason 与观察、走行的现场。
        walks.enqueue(new ExploreFakes.ScriptedWalk(
                WalkReport.planning(),
                WalkReport.onTheWay(new BlockPos(8, 64, 0), false)).idleWithoutProgress());
        ExploreTask task = task(memory, ExploreObjective.survey(), ExploreObjective.Interests.NONE,
                null, 360, 0, 64, NATURAL);
        task.start(tick);
        TaskResult result = null;
        for (int i = 0; i < 2400 && result == null; i++) {
            TickResult tickResult = task.tick(tick);
            if (tickResult instanceof TickResult.Finished finished) {
                result = finished.result();
            }
            tick.nextTick();
        }
        assertNotNull(result, "空转满时限要按卡住收场");
        TaskResult stalled = result;

        assertEquals(TaskResult.Status.FAILED, stalled.status());
        assertEquals(Problem.Kind.STUCK, stalled.problem().kind());
        assertTrue(stalled.problem().message().contains("空转收场"), () -> "说明：" + stalled.problem().message());
        assertTrue(stalled.problem().message().contains("已观察"), () -> "说明：" + stalled.problem().message());
        assertTrue(stalled.problem().message().contains("最远走到"), () -> "说明：" + stalled.problem().message());
        ExploreDetails details = assertInstanceOf(ExploreDetails.class, stalled.details());
        assertEquals(ExploreDetails.StopReason.FRONTIERS_EXHAUSTED, details.stopReason(),
                "没失败过段的空转按前沿用尽收场");
    }

    @Test
    void 探索中切换维度如实结束旧范围作废() {
        WorldMemory memory = memory();
        ExploreTask task = task(memory, ExploreObjective.survey(), ExploreObjective.Interests.NONE,
                null, 360, 0, 256, NATURAL);
        task.start(tick);
        world.dimension = "minecraft:the_nether";
        TickResult result = task.tick(tick);

        TaskResult finished = assertInstanceOf(TickResult.Finished.class, result).result();
        assertEquals(TaskResult.Status.CANCELLED, finished.status());
        assertTrue(finished.summary().contains("维度"));
    }

}