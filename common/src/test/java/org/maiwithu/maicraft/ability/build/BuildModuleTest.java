// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.ability.design.api.BuildingArchive;
import org.maiwithu.maicraft.ability.design.api.BuildingArchives;
import org.maiwithu.maicraft.ability.design.api.BuildingRecords;
import org.maiwithu.maicraft.ability.design.api.DesignStore;
import org.maiwithu.maicraft.behavior.construction.AnchorResolver;
import org.maiwithu.maicraft.behavior.construction.CellKind;
import org.maiwithu.maicraft.behavior.construction.ConstructionDetails;
import org.maiwithu.maicraft.behavior.construction.PlannedCell;
import org.maiwithu.maicraft.behavior.construction.ReadsBlocks;
import org.maiwithu.maicraft.behavior.construction.RecordsConstruction;
import org.maiwithu.maicraft.kernel.goal.Question;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;
import org.maiwithu.maicraft.kernel.goal.Goal;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.param.ParamValues;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.TickContext;

/** 施工能力：逐格清单与单格变成计划格，四选一与锚点在计划阶段核清，落到锚点后交给施工任务。 */
class BuildModuleTest {

    private static final String DIM = "minecraft:overworld";

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    /** 一套替身：施工能力、建筑档案库、记过的地点、记进世界记忆的那几栋。 */
    private record Fixture(BuildModule module, BuildingArchives archives, Map<String, WorldPosition> places,
                           List<BuildingArchive> remembered) {}

    private static Fixture fixture() throws Exception {
        Path root = Files.createTempDirectory("build-module-");
        DesignStore store = new DesignStore(root.resolve("designs.sqlite"));
        Path schematics = Files.createDirectories(root.resolve("schematics"));
        NbtIo.writeCompressed(StructureImportTest.vanilla(), schematics.resolve("house.nbt"));
        AnchorResolver anchors = new AnchorResolver(id -> Optional.empty(), name -> Optional.empty(),
                () -> new WorldPosition(5, 64, 5, DIM), (x, z) -> Optional.of(70));
        BuildingArchives archives = new BuildingArchives(new DocumentStore(root.resolve("world.sqlite")), "w".repeat(64));
        Map<String, WorldPosition> places = new HashMap<>();
        List<BuildingArchive> remembered = new ArrayList<>();
        BuildingRecords records = new BuildingRecords(archives, name -> Optional.ofNullable(places.get(name)), remembered::add,
                id -> id.equals("minecraft:chest"));
        BuildModule module = new BuildModule(store, anchors, schematics, permissions -> {
            throw new UnsupportedOperationException("计划阶段不拼服务");
        }, records);
        return new Fixture(module, archives, places, remembered);
    }

    private static BuildModule module() throws Exception {
        return fixture().module();
    }

    private static StepContext step(BuildModule module, Target target, JsonObject raw, String... answers) {
        ParamValues params = module.spec().paramSpecs().parse(raw).params();
        Goal goal = Goal.of("maicraft:build", target, params);
        return new StepContext() {
            @Override public Goal goal() { return goal; }
            @Override public int stepIndex() { return 0; }
            @Override public TickContext tick() { return null; }
            @Override public List<String> answers() { return List.of(answers); }
        };
    }

    private static BuildInput run(BuildModule module, Target target, JsonObject raw, String... answers) {
        return assertInstanceOf(BuildInput.class, assertInstanceOf(StepDecision.Run.class,
                module.decide(step(module, target, raw, answers))).input());
    }

    /** 替身现场：摆了的格给状态，其余是空气；全都算加载了。 */
    private static ReadsBlocks world(Map<BlockPos, BlockState> blocks) {
        return new ReadsBlocks() {
            @Override public boolean loaded(BlockPos pos) { return true; }
            @Override public BlockState state(BlockPos pos) { return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState()); }
        };
    }

    // 施工收场时引擎自己的那份细节：这里只要个空壳，测的是档案与整栋比对。
    private static BuildDetails settle(BuildInput input, Map<BlockPos, BlockState> blocks) {
        BlockPos anchor = input.construction().blueprint().anchor();
        ConstructionDetails engine = new ConstructionDetails(new WorldPosition(anchor.getX(), anchor.getY(), anchor.getZ(), DIM),
                anchor, anchor, Map.of(), List.of(), Map.of(), List.of(), null);
        return assertInstanceOf(BuildDetails.class, input.construction().recorder().record(engine, world(blocks)));
    }

    private static PlannedCell stone(int x) {
        return PlannedCell.block(new BlockPos(x, 0, 0), Blocks.STONE.defaultBlockState(), Set.of());
    }

    // 一栋存好的小屋：锚点 (10,64,10)，东西向三格石头。
    private static BuildingArchive cottage(Fixture fixture, int rotation) {
        BuildingArchive archive = new BuildingArchive("小屋", DIM, new BlockPos(10, 64, 10), rotation, null,
                List.of(stone(0), stone(1), stone(2)), null, null);
        assertTrue(fixture.archives().save(archive));
        return archive;
    }

    @Test
    void 盖完记一份档案_名字照图纸_撞名加坐标() throws Exception {
        Fixture fixture = fixture();
        JsonObject cells = json("{\"cells\":[{\"offset\":[0,0,0],\"block\":\"minecraft:stone\"},{\"offset\":[1,0,0],\"block\":\"minecraft:stone\"}]}");
        BuildInput first = run(fixture.module(), new Target.Here(), cells);
        BuildDetails details = settle(first, Map.of(new BlockPos(5, 64, 5), Blocks.STONE.defaultBlockState(),
                new BlockPos(6, 64, 5), Blocks.STONE.defaultBlockState()));
        assertEquals("建筑", details.archive().name());
        assertTrue(details.check().allMatch(), details.check().toString());
        assertEquals(null, details.layout(), "新盖的不带布局，改建才带");
        BuildingArchive stored = fixture.archives().find("建筑").orElseThrow();
        assertEquals(List.of(new BlockPos(0, 0, 0), new BlockPos(1, 0, 0)), stored.cells().stream().map(PlannedCell::pos).toList(), "档案存相对锚点的偏移");
        assertEquals("整栋都对上了", stored.lastCheckNote());
        assertEquals(List.of(stored), fixture.remembered(), "存好就记进世界记忆");
        BuildInput second = run(fixture.module(), new Target.Position(20, 64, 20, null), cells);
        assertEquals("建筑@20,64,20", settle(second, Map.of()).archive().name(), "同名的一栋在别处，加上坐标");
        assertEquals("整栋都对上了", fixture.archives().find("建筑").orElseThrow().lastCheckNote(), "原来那一栋的档案不被覆盖");
    }

    @Test
    void 档案名当目标就是改建_逐格是补丁_整栋比对列出别处没对上的格() throws Exception {
        Fixture fixture = fixture();
        cottage(fixture, 0);
        BuildInput input = run(fixture.module(), new Target.Landmark("小屋"),
                json("{\"cells\":[{\"offset\":[3,0,0],\"block\":\"minecraft:oak_planks\"}]}"));
        assertEquals(List.of(new BlockPos(13, 64, 10)), input.construction().blueprint().cells().stream().map(PlannedCell::pos).toList(),
                "补丁的偏移相对档案锚点，施工只做补丁这几格");
        assertTrue(input.what().startsWith("改建「小屋」"), input.what());
        // 补上的那格对了；旁边原有的一格被换成了泥土：整栋比对把它列出来，施工照样按施工的结果算。
        BuildDetails details = settle(input, Map.of(new BlockPos(10, 64, 10), Blocks.STONE.defaultBlockState(),
                new BlockPos(11, 64, 10), Blocks.DIRT.defaultBlockState(), new BlockPos(12, 64, 10), Blocks.STONE.defaultBlockState(),
                new BlockPos(13, 64, 10), Blocks.OAK_PLANKS.defaultBlockState()));
        assertEquals(1, details.check().differences().size(), details.check().toString());
        assertEquals(new BlockPos(11, 64, 10), details.check().differences().getFirst().at());
        assertTrue(details.layout() != null, "改建带上这一栋的布局");
        BuildingArchive merged = fixture.archives().find("小屋").orElseThrow();
        assertEquals(4, merged.cells().size(), "补丁并进档案，原来的格留着");
        assertEquals("1 格没对上", merged.lastCheckNote());
    }

    @Test
    void 改建沿用档案的朝向() throws Exception {
        Fixture fixture = fixture();
        cottage(fixture, 90);
        assertProblem(fixture.module().decide(step(fixture.module(), new Target.Landmark("小屋"), json("{\"file\":\"house\",\"rotation\":180}"))),
                Problem.Kind.INVALID_PARAMETER);
        BuildInput input = run(fixture.module(), new Target.Landmark("小屋"), json("{\"file\":\"house\"}"));
        // 文件里的格 (10,0,0) 照档案的 90 度绕锚点 (10,64,10) 转到 (10,64,20)。
        assertTrue(input.construction().blueprint().cellAt(new BlockPos(10, 64, 20)).isPresent(), "没给 rotation 就照档案的朝向");
    }

    @Test
    void 单格落在一栋的范围里并进那一栋_范围外不建档() throws Exception {
        Fixture fixture = fixture();
        cottage(fixture, 0);
        BuildInput inside = run(fixture.module(), new Target.Position(11, 64, 10, null), json("{\"block\":\"minecraft:spruce_planks\"}"));
        settle(inside, Map.of());
        BuildingArchive merged = fixture.archives().find("小屋").orElseThrow();
        assertEquals(Blocks.SPRUCE_PLANKS, merged.cells().stream().filter(cell -> cell.pos().equals(new BlockPos(1, 0, 0)))
                .findFirst().orElseThrow().state().getBlock(), "门那一格换成了新放的方块");
        BuildInput outside = run(fixture.module(), new Target.Position(30, 64, 30, null), json("{\"block\":\"minecraft:torch\"}"));
        assertEquals(RecordsConstruction.NONE, outside.construction().recorder());
    }

    @Test
    void 档案和记过的地点同名不同处_先问指哪个() throws Exception {
        Fixture fixture = fixture();
        cottage(fixture, 0);
        fixture.places().put("小屋", new WorldPosition(100, 64, 100, DIM));
        JsonObject patch = json("{\"cells\":[{\"offset\":[0,1,0],\"block\":\"minecraft:stone\"}]}");
        var ask = assertInstanceOf(StepDecision.Ask.class, fixture.module().decide(step(fixture.module(), new Target.Landmark("小屋"), patch)));
        assertEquals(Question.Reason.UNCLEAR_TARGET, ask.question().reason());
        BuildInput building = run(fixture.module(), new Target.Landmark("小屋"), patch, "building");
        assertEquals(new BlockPos(10, 65, 10), building.construction().blueprint().cells().getFirst().pos(), "答了改那一栋就照档案的锚点");
        // 同名的地点就在这一栋里（屋里记的"小屋"）：是同一栋，不问。
        fixture.places().put("小屋", new WorldPosition(11, 64, 10, DIM));
        run(fixture.module(), new Target.Landmark("小屋"), patch);
    }

    @Test
    void 逐格清单变成计划格() {
        List<PlannedCell> cells = BuildCells.fromArray(JsonParser.parseString(
                "[{\"offset\":[0,0,0],\"block\":\"minecraft:oak_stairs\",\"properties\":{\"facing\":\"east\"}},"
                        + "{\"offset\":[1,0,0],\"block\":\"minecraft:air\"},{\"offset\":[2,0,0],\"block\":\"minecraft:water\"}]"));
        assertEquals(3, cells.size());
        assertEquals(Direction.EAST, cells.get(0).state().getValue(StairBlock.FACING));
        assertEquals(Set.of("facing"), cells.get(0).required(), "点名的属性是验收标准");
        assertEquals(CellKind.AIR, cells.get(1).kind());
        assertEquals(CellKind.FLUID_SOURCE, cells.get(2).kind());
        for (String bad : List.of("[]", "[{\"offset\":[0,0],\"block\":\"minecraft:stone\"}]", "[{\"offset\":[0,0,0],\"block\":\"minecraft:nope\"}]",
                "[{\"offset\":[0,0,0],\"block\":\"minecraft:stone\",\"properties\":{\"facing\":\"up\"}}]",
                "[{\"offset\":[0,0,0],\"block\":\"#minecraft:logs\"}]", "[{\"offset\":[0,0,0],\"block\":\"minecraft:stone\",\"extra\":1}]")) {
            assertThrows(IllegalArgumentException.class, () -> BuildCells.fromArray(JsonParser.parseString(bad)), bad);
        }
        assertEquals(Blocks.TORCH, BuildCells.single("minecraft:torch", null).state().getBlock());
    }

    @Test
    void 四选一与锚点在计划阶段核清() throws Exception {
        BuildModule module = module();
        BuildInput input = run(module, new Target.Here(), json("{\"cells\":[{\"offset\":[0,1,0],\"block\":\"minecraft:stone\"}]}"));
        assertEquals(new BlockPos(5, 65, 5), input.construction().blueprint().cells().getFirst().pos(), "offset 加在脚下的锚点上");
        assertEquals(DIM, input.construction().blueprint().dimension());
        assertEquals(null, input.construction().fixturesSkipped(), "不是结构文件来的没有摆设实体这回事");
        BuildInput single = run(module, new Target.Position(0, null, 0, null), json("{\"block\":\"minecraft:stone\"}"));
        assertEquals(new BlockPos(0, 70, 0), single.construction().blueprint().anchor(), "省略 y 取地表");
        assertProblem(module.decide(step(module, new Target.Here(), json("{}"))), Problem.Kind.INVALID_PARAMETER);
        assertProblem(module.decide(step(module, new Target.Here(), json("{\"block\":\"minecraft:stone\",\"cells\":[]}"))), Problem.Kind.INVALID_PARAMETER);
        assertProblem(module.decide(step(module, new Target.Here(), json("{\"block\":\"minecraft:stone\",\"rotation\":90}"))), Problem.Kind.INVALID_PARAMETER);
        assertProblem(module.decide(step(module, null, json("{\"block\":\"minecraft:stone\"}"))), Problem.Kind.INVALID_PARAMETER);
        assertProblem(module.decide(step(module, new Target.Landmark("家"), json("{\"block\":\"minecraft:stone\"}"))), Problem.Kind.NOT_FOUND);
        assertProblem(module.decide(step(module, new Target.Here(), json("{\"design_id\":\"00000000-0000-0000-0000-000000000000\"}"))), Problem.Kind.INVALID_PARAMETER);
    }

    @Test
    void 结构文件落到锚点并带上没装的摆设数() throws Exception {
        BuildModule module = module();
        BuildInput input = run(module, new Target.Here(), json("{\"file\":\"house\",\"rotation\":90}"));
        assertEquals(1, input.construction().fixturesSkipped());
        assertTrue(input.what().contains("1 个摆设实体不装") && input.what().contains("1 格建不了跳过"), input.what());
        // 文件里的格 (10,0,0) 绕锚点顺时针转 90 度落到 (5-0, 64, 5+10)。
        assertTrue(input.construction().blueprint().cellAt(new BlockPos(5, 64, 15)).isPresent(), "rotation 配 file 有效");
        assertProblem(module.decide(step(module, new Target.Here(), json("{\"file\":\"nothing\"}"))), Problem.Kind.NOT_FOUND);
        assertProblem(module.decide(step(module, new Target.Here(), json("{\"file\":\"../house\"}"))), Problem.Kind.INVALID_PARAMETER);
    }

    private static void assertProblem(StepDecision decision, Problem.Kind kind) {
        var finish = assertInstanceOf(StepDecision.Finish.class, decision);
        assertTrue(finish.result().problem() != null, finish.result().summary());
        assertEquals(kind, finish.result().problem().kind(), finish.result().summary());
        // 一句话结论只带问题的结论句，展开的原因只在问题里一份。
        assertEquals(finish.result().problem().headline(), finish.result().summary());
    }
}
