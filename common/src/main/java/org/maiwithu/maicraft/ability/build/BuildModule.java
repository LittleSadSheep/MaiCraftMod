// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.build;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;
import org.maiwithu.maicraft.ability.design.api.BuildingDesign;
import org.maiwithu.maicraft.ability.design.api.DesignCompiler;
import org.maiwithu.maicraft.ability.design.api.DesignStore;
import org.maiwithu.maicraft.behavior.construction.AnchorResolver;
import org.maiwithu.maicraft.behavior.construction.Blueprint;
import org.maiwithu.maicraft.behavior.construction.ConstructionInput;
import org.maiwithu.maicraft.behavior.construction.ConstructionServices;
import org.maiwithu.maicraft.behavior.construction.ConstructionTask;
import org.maiwithu.maicraft.behavior.construction.PlannedCell;
import org.maiwithu.maicraft.game.ModIdentity;
import org.maiwithu.maicraft.kernel.ability.AbilityDoc;
import org.maiwithu.maicraft.kernel.ability.AbilityModule;
import org.maiwithu.maicraft.kernel.ability.AbilitySpec;
import org.maiwithu.maicraft.kernel.ability.ExecutionMode;
import org.maiwithu.maicraft.kernel.ability.Listing;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.goal.TargetKind;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.param.ParamSpec;
import org.maiwithu.maicraft.kernel.param.ParamSpecs;
import org.maiwithu.maicraft.kernel.param.ParamType;
import org.maiwithu.maicraft.kernel.param.ParamValues;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TaskFactories;

/**
 * 施工能力：按图纸在 target 处施工。design_id / cells / block 三选一（结构文件 file 还没接上），
 * target 是锚点，rotation 绕锚点转。自己只做参数核对、锚点解析和把蓝图落到锚点；
 * 核对、备料、清障、砌筑、倒桶、验收、收回临时方块都是施工引擎的事，结果也是它的结果。
 */
public final class BuildModule implements AbilityModule {

    private final DesignStore designs;
    private final AnchorResolver anchors;
    private final Path schematics;
    private final Function<Permissions, ConstructionServices> services;
    private final AbilitySpec spec = new AbilitySpec(
            ModIdentity.MOD_ID + ":build",
            "按图纸、逐格清单、结构文件或单格在 target 处施工：缺料自己拿、挡着的挖掉、从底往上砌、够不着就垫临时方块再收走",
            AbilityDoc.forAbility("build"),
            ParamSpecs.of(
                    ParamSpec.of("design_id", ParamType.TEXT).doc("按 design 存的图纸施工（三选一）").build(),
                    ParamSpec.of("cells", ParamType.JSON_ARRAY)
                            .doc("逐格蓝图（三选一）：[{\"offset\":[x,y,z],\"block\":\"minecraft:stone\",\"properties\":{...}}]，block 为 minecraft:air 表示清空，水源、岩浆源表示倒桶").build(),
                    ParamSpec.of("block", ParamType.BLOCK_OR_TAG).doc("只放一格（三选一）：放在 target 那一格").build(),
                    ParamSpec.of("properties", ParamType.JSON_OBJECT).doc("配 block：要求的方块状态属性，例如 {\"facing\":\"north\"}").build(),
                    ParamSpec.of("file", ParamType.TEXT).doc("schematics 目录下的结构文件名（四选一）：.nbt / .snbt / .litematic / .schem，或 design 导出的 .json；摆设实体不装").build(),
                    ParamSpec.of("rotation", ParamType.INTEGER).range(0, 270).defaultValue(0).doc("绕锚点转 0 / 90 / 180 / 270 度；只配 design_id 或 file").build()),
            Set.of(TargetKind.HERE, TargetKind.SEEN, TargetKind.LANDMARK, TargetKind.POSITION),
            ExecutionMode.CONTROLS_PLAYER,
            Set.of(),
            List.of(),
            Listing.LISTED);

    /**
     * @param designs  设计存储，按 design_id 取图纸
     * @param anchors  锚点解析
     * @param schematics 结构文件所在的目录
     * @param services   按这次任务的许可拼一份施工服务
     */
    public BuildModule(DesignStore designs, AnchorResolver anchors, Path schematics, Function<Permissions, ConstructionServices> services) {
        this.designs = Objects.requireNonNull(designs, "designs");
        this.anchors = Objects.requireNonNull(anchors, "anchors");
        this.schematics = Objects.requireNonNull(schematics, "schematics");
        this.services = Objects.requireNonNull(services, "services");
    }

    @Override public AbilitySpec spec() {
        return spec;
    }

    // 看参数定蓝图：四选一、锚点、旋转在这里一次核清；落到锚点后交给施工任务。
    @Override public StepDecision decide(StepContext step) {
        ParamValues params = step.goal().params();
        int given = (params.has("design_id") ? 1 : 0) + (params.has("cells") ? 1 : 0) + (params.has("block") ? 1 : 0) + (params.has("file") ? 1 : 0);
        if (given != 1) return fail("design_id、cells、block、file 四选一，现在给了 " + given + " 个", Problem.Kind.INVALID_PARAMETER, null);
        if (params.has("rotation") && params.integer("rotation") != 0 && !params.has("design_id") && !params.has("file")) {
            return fail("rotation 只配 design_id 或 file", Problem.Kind.INVALID_PARAMETER, null);
        }
        Rotation turn = Blueprint.turnOf(params.has("rotation") ? params.integer("rotation") : 0);
        if (turn == null) return fail("rotation 只能是 0、90、180、270", Problem.Kind.INVALID_PARAMETER, null);
        AnchorResolver.Resolution anchor = anchors.resolve(step.goal().target());
        if (anchor instanceof AnchorResolver.Resolution.Failed failed) {
            return fail(failed.problem().message(), failed.problem().kind(), failed.problem().suggestion());
        }
        WorldPosition at = ((AnchorResolver.Resolution.Ready) anchor).anchor();
        try {
            Source source = source(params);
            Blueprint blueprint = Blueprint.at(at.dimension(), new BlockPos(at.x(), at.y(), at.z()), turn, source.cells());
            return new StepDecision.Run(new BuildInput(
                    new ConstructionInput(blueprint, step.goal().permissions(), "施工", source.fixturesSkipped()), source.what()));
        } catch (NoSuchFileException missing) {
            return fail(missing.getReason(), Problem.Kind.NOT_FOUND, "把文件放进 schematics 目录，或改用 design 画图");
        } catch (IOException failure) {
            return fail("结构文件读不出来：" + failure.getMessage(), Problem.Kind.INTERNAL_ERROR, null);
        } catch (IllegalArgumentException invalid) {
            return fail(invalid.getMessage(), Problem.Kind.INVALID_PARAMETER, "按错误里的定位改参数再试");
        }
    }

    /** 蓝图从哪来：相对锚点的计划格、一句说明、结构文件里没装的摆设实体数（别的来源为空）。 */
    private record Source(List<PlannedCell> cells, String what, Integer fixturesSkipped) {}

    // 四个来源各自变成相对计划格：图纸编译、逐格清单、结构文件导入、单格。
    private Source source(ParamValues params) throws IOException {
        if (params.has("design_id")) {
            BuildingDesign design = designs.load(params.text("design_id"));
            return new Source(DesignCompiler.compile(design.drawing()).cells(), "图纸「" + design.name() + "」", null);
        }
        if (params.has("cells")) return new Source(BuildCells.fromArray(params.json("cells")), "逐格清单", null);
        if (params.has("file")) {
            StructureImport.Imported imported = StructureImport.read(schematics, params.text("file"));
            String what = "结构文件 " + params.text("file")
                    + (imported.fixturesSkipped() > 0 ? "，" + imported.fixturesSkipped() + " 个摆设实体不装" : "")
                    + (imported.cellsDropped() > 0 ? "，" + imported.cellsDropped() + " 格建不了跳过" : "");
            return new Source(imported.cells(), what, imported.fixturesSkipped());
        }
        PlannedCell single = BuildCells.single(params.text("block"), params.has("properties") ? params.json("properties") : null);
        return new Source(List.of(single), "一格 " + params.text("block"), null);
    }

    @Override public void registerTasks(TaskFactories factories) {
        // 每次运行按这次任务的许可拼一份施工服务；施工引擎在玩家行为层，能力只递输入。
        factories.register(BuildInput.class, input -> new ConstructionTask(input.construction(), services.apply(input.construction().permissions())));
    }

    private static StepDecision fail(String message, Problem.Kind kind, String suggestion) {
        return new StepDecision.Finish(TaskResult.failed(message, Problem.of(kind, message, suggestion)));
    }
}
