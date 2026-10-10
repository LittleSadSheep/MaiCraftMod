// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.design;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.google.gson.JsonObject;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.ability.design.api.DesignStore;
import org.maiwithu.maicraft.behavior.construction.AnchorResolver;
import org.maiwithu.maicraft.behavior.construction.Blueprint;
import org.maiwithu.maicraft.kernel.goal.Goal;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.param.ParamValues;
import org.maiwithu.maicraft.kernel.param.ParseResult;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TickContext;

/** 画图能力：画、改、看、投影、导出都当场做完，统计随结果给；图纸写错按参数问题带定位失败。 */
class DesignModuleTest {

    private static final String DIM = "minecraft:overworld";

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private final List<Blueprint> shown = new ArrayList<>();
    private final Path directory;
    private final DesignModule module;

    DesignModuleTest() throws Exception {
        directory = Files.createTempDirectory("design-module-");
        AnchorResolver anchors = new AnchorResolver(id -> Optional.empty(), name -> Optional.empty(),
                () -> new WorldPosition(5, 64, 5, DIM), (x, z) -> Optional.of(70));
        module = new DesignModule(new DesignStore(directory.resolve("designs.sqlite")), anchors, shown::add, directory.resolve("schematics"));
    }

    private TaskResult run(Target target, JsonObject raw) {
        ParseResult parsed = module.spec().paramSpecs().parse(raw);
        assertTrue(parsed.ok(), () -> parsed.errors().toString());
        ParamValues params = parsed.params();
        Goal goal = Goal.of("maicraft:design", target, params);
        StepContext step = new StepContext() {
            @Override public Goal goal() { return goal; }
            @Override public int stepIndex() { return 0; }
            @Override public TickContext tick() { return null; }
        };
        return assertInstanceOf(StepDecision.Finish.class, module.decide(step)).result();
    }

    private static JsonObject operation(String name) {
        JsonObject raw = new JsonObject();
        raw.addProperty("operation", name);
        return raw;
    }

    private DesignDetails create(JsonObject drawing) {
        JsonObject raw = operation("create");
        raw.add("drawing", drawing);
        TaskResult result = run(null, raw);
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        return assertInstanceOf(DesignDetails.class, result.details());
    }

    @Test
    void 画完再改再看再导出() {
        JsonObject drawing = DesignSamples.drawing(DesignSamples.mesh("Box", "cube", new double[] {1, 1, 1}, new int[] {2, 2, 2}, "Body"));
        drawing.addProperty("name", "小盒");
        DesignDetails created = create(drawing);
        assertNotNull(created.designId());
        assertNull(created.parentDesignId());
        assertEquals(8, created.cellCount());
        assertEquals(8, created.materials().get("minecraft:stone"));
        assertEquals(List.of(0, 0, 0), created.bounds().min());

        JsonObject raw = operation("update");
        raw.addProperty("design_id", created.designId());
        raw.add("edits", DesignSamples.json("{\"objects\":[{\"name\":\"Box\",\"dimensions\":[4,2,2]}]}"));
        TaskResult updated = run(null, raw);
        DesignDetails revised = assertInstanceOf(DesignDetails.class, updated.details());
        assertEquals(created.designId(), revised.parentDesignId(), "改出新的一版，父版本留着");
        assertEquals(16, revised.cellCount(), "只改尺寸，材质还是原来的");

        raw = operation("inspect");
        raw.addProperty("design_id", revised.designId());
        DesignDetails looked = assertInstanceOf(DesignDetails.class, run(null, raw).details());
        assertNotNull(looked.inspection());
        assertNull(looked.cellCount(), "看图不重复报统计");

        raw = operation("export");
        raw.addProperty("design_id", revised.designId());
        DesignDetails exported = assertInstanceOf(DesignDetails.class, run(null, raw).details());
        assertTrue(Files.exists(Path.of(exported.file())), exported.file());
        assertTrue(exported.file().endsWith(".json"));
    }

    @Test
    void 投影落到锚点交给叠加() {
        DesignDetails created = create(DesignSamples.drawing(DesignSamples.mesh("Box", "cube", new double[] {0.5, 0.5, 0.5}, new int[] {1, 1, 1}, "Body")));
        JsonObject raw = operation("preview");
        raw.addProperty("design_id", created.designId());
        raw.addProperty("rotation", 90);
        TaskResult result = run(new Target.Position(20, null, 3, null), raw);
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertEquals(1, shown.size());
        assertEquals(new BlockPos(20, 70, 3), shown.getFirst().anchor(), "省略 y 取地表");
        assertEquals(DIM, shown.getFirst().dimension());

        raw.addProperty("rotation", 45);
        assertEquals(Problem.Kind.INVALID_PARAMETER, run(new Target.Here(), raw).problem().kind());
        raw.addProperty("rotation", 0);
        assertEquals(Problem.Kind.NOT_FOUND, run(new Target.Landmark("家"), raw).problem().kind());
    }

    @Test
    void 写错按参数问题带定位() {
        JsonObject raw = operation("create");
        raw.add("drawing", DesignSamples.drawing(DesignSamples.mesh("Box", "cube", new double[] {1, 1, 1}, new int[] {2, 2, 2}, "没有的材质")));
        TaskResult result = run(null, raw);
        assertEquals(Problem.Kind.INVALID_PARAMETER, result.problem().kind());
        assertTrue(result.summary().contains("Box"), "定位到对象：" + result.summary());

        assertEquals(Problem.Kind.INVALID_PARAMETER, run(null, operation("create")).problem().kind(), "create 没给 drawing");
        raw = operation("inspect");
        raw.addProperty("design_id", "00000000-0000-0000-0000-000000000000");
        assertEquals(Problem.Kind.INVALID_PARAMETER, run(null, raw).problem().kind(), "编号不存在");
    }
}
