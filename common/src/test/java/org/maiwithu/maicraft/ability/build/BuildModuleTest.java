// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
import org.maiwithu.maicraft.ability.design.api.DesignStore;
import org.maiwithu.maicraft.behavior.construction.AnchorResolver;
import org.maiwithu.maicraft.behavior.construction.CellKind;
import org.maiwithu.maicraft.behavior.construction.PlannedCell;
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

    private static BuildModule module() throws Exception {
        Path root = Files.createTempDirectory("build-module-");
        DesignStore store = new DesignStore(root.resolve("designs.sqlite"));
        Path schematics = Files.createDirectories(root.resolve("schematics"));
        NbtIo.writeCompressed(StructureImportTest.vanilla(), schematics.resolve("house.nbt"));
        AnchorResolver anchors = new AnchorResolver(id -> Optional.empty(), name -> Optional.empty(),
                () -> new WorldPosition(5, 64, 5, DIM), (x, z) -> Optional.of(70));
        return new BuildModule(store, anchors, schematics, permissions -> {
            throw new UnsupportedOperationException("计划阶段不拼服务");
        });
    }

    private static StepContext step(BuildModule module, Target target, JsonObject raw) {
        ParamValues params = module.spec().paramSpecs().parse(raw).params();
        Goal goal = Goal.of("maicraft:build", target, params);
        return new StepContext() {
            @Override public Goal goal() { return goal; }
            @Override public int stepIndex() { return 0; }
            @Override public TickContext tick() { return null; }
        };
    }

    private static BuildInput run(BuildModule module, Target target, JsonObject raw) {
        return assertInstanceOf(BuildInput.class, assertInstanceOf(StepDecision.Run.class, module.decide(step(module, target, raw))).input());
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
    }
}
