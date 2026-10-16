// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.build;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;
import org.maiwithu.maicraft.ability.design.api.BuildingArchive;
import org.maiwithu.maicraft.ability.design.api.BuildingDesign;
import org.maiwithu.maicraft.ability.design.api.BuildingRecords;
import org.maiwithu.maicraft.ability.design.api.DesignCompiler;
import org.maiwithu.maicraft.ability.design.api.DesignStore;
import org.maiwithu.maicraft.behavior.construction.AnchorResolver;
import org.maiwithu.maicraft.behavior.construction.Blueprint;
import org.maiwithu.maicraft.behavior.construction.BlueprintPatch;
import org.maiwithu.maicraft.behavior.construction.ConstructionInput;
import org.maiwithu.maicraft.behavior.construction.ConstructionServices;
import org.maiwithu.maicraft.behavior.construction.ConstructionTask;
import org.maiwithu.maicraft.behavior.construction.PlannedCell;
import org.maiwithu.maicraft.behavior.construction.RecordsConstruction;
import org.maiwithu.maicraft.game.ModIdentity;
import org.maiwithu.maicraft.kernel.ability.AbilityDoc;
import org.maiwithu.maicraft.kernel.ability.AbilityModule;
import org.maiwithu.maicraft.kernel.ability.AbilitySpec;
import org.maiwithu.maicraft.kernel.ability.ExecutionMode;
import org.maiwithu.maicraft.kernel.ability.Listing;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.Question;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.goal.Target;
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
 * 施工能力：按图纸在 target 处施工。design_id / cells / block / file（结构文件，经 StructureImport 读进来）四选一，
 * target 是锚点，rotation 绕锚点转。自己只做参数核对、锚点解析和把蓝图落到锚点；
 * 核对、备料、清障、砌筑、倒桶、验收、收回临时方块都是施工引擎的事，结果也是它的结果。
 *
 * <p>盖完记一份建筑档案（名字、图纸版本、锚点、朝向、整栋蓝图）。target 写一栋的档案名就是改建：锚点和朝向照档案，
 * 新一版图纸整份换掉档案的蓝图，逐格清单或单格是补丁、并进档案；收场时整栋比一遍，没对上的每一格都列。
 * 单格放在某一栋的范围里也并进那一栋；范围外的单格不建档。
 */
public final class BuildModule implements AbilityModule {

    private final DesignStore designs;
    private final AnchorResolver anchors;
    private final Path schematics;
    private final Function<Permissions, ConstructionServices> services;
    private final BuildingRecords records;
    private final AbilitySpec spec = new AbilitySpec(
            ModIdentity.MOD_ID + ":build",
            "按图纸、逐格清单、结构文件或单格在 target 处施工：缺料自己拿、挡着的挖掉、从底往上砌、够不着就垫临时方块再收走；"
                    + "盖完记建筑档案，target 写档案名就是改那一栋",
            AbilityDoc.forAbility("build"),
            ParamSpecs.of(
                    ParamSpec.of("design_id", ParamType.TEXT).doc("按 design 存的图纸施工（四选一）；target 是档案名时整份换掉那一栋的图").build(),
                    ParamSpec.of("cells", ParamType.JSON_ARRAY)
                            .doc("逐格蓝图（四选一）：[{\"offset\":[x,y,z],\"block\":\"minecraft:stone\",\"properties\":{...}}]，block 为 minecraft:air 表示清空，水源、岩浆源表示倒桶；"
                                    + "target 是档案名时 offset 相对档案锚点，是并进那一栋的补丁").build(),
                    ParamSpec.of("block", ParamType.BLOCK_OR_TAG).doc("只放一格（四选一）：放在 target 那一格；落在某一栋的范围里就并进那一栋").build(),
                    ParamSpec.of("properties", ParamType.JSON_OBJECT).doc("配 block：要求的方块状态属性，例如 {\"facing\":\"north\"}").build(),
                    ParamSpec.of("file", ParamType.TEXT).doc("schematics 目录下的结构文件名（四选一）：.nbt / .snbt / .litematic / .schem，或 design 导出的 .json；摆设实体不装").build(),
                    ParamSpec.of("rotation", ParamType.INTEGER).range(0, 270).defaultValue(0).doc("绕锚点转 0 / 90 / 180 / 270 度；只配 design_id 或 file；改建照档案的朝向，不用给").build(),
                    ParamSpec.of("name", ParamType.TEXT).doc("新建筑的档案名；不给就用图纸名，撞名加 @x,y,z；target 已经是档案时不用给").build()),
            Set.of(TargetKind.HERE, TargetKind.SEEN, TargetKind.LANDMARK, TargetKind.POSITION),
            ExecutionMode.CONTROLS_PLAYER,
            Set.of(),
            List.of(),
            Listing.LISTED);

    /**
     * @param designs    设计存储，按 design_id 取图纸
     * @param anchors    锚点解析
     * @param schematics 结构文件所在的目录
     * @param services   按这次任务的许可拼一份施工服务
     * @param records    建筑档案、记过的地点、世界记忆、设施认法
     */
    public BuildModule(DesignStore designs, AnchorResolver anchors, Path schematics, Function<Permissions, ConstructionServices> services,
            BuildingRecords records) {
        this.designs = Objects.requireNonNull(designs, "designs");
        this.anchors = Objects.requireNonNull(anchors, "anchors");
        this.schematics = Objects.requireNonNull(schematics, "schematics");
        this.services = Objects.requireNonNull(services, "services");
        this.records = Objects.requireNonNull(records, "records");
    }

    @Override public AbilitySpec spec() {
        return spec;
    }

    // 看参数定蓝图：四选一、锚点、旋转在这里一次核清；target 是档案名就是改那一栋；落到锚点后交给施工任务。
    @Override public StepDecision decide(StepContext step) {
        ParamValues params = step.goal().params();
        int given = (params.has("design_id") ? 1 : 0) + (params.has("cells") ? 1 : 0) + (params.has("block") ? 1 : 0) + (params.has("file") ? 1 : 0);
        if (given != 1) return fail("design_id、cells、block、file 四选一，现在给了 " + given + " 个", Problem.Kind.INVALID_PARAMETER, null);
        int degrees = params.has("rotation") ? (int) params.integer("rotation") : 0;
        if (degrees != 0 && !params.has("design_id") && !params.has("file")) {
            return fail("rotation 只配 design_id 或 file", Problem.Kind.INVALID_PARAMETER, null);
        }
        Rotation turn = Blueprint.turnOf(degrees);
        if (turn == null) return fail("rotation 只能是 0、90、180、270", Problem.Kind.INVALID_PARAMETER, null);
        if (params.has("name") && (params.text("name").isBlank() || params.text("name").length() > 64)) {
            return fail("name 不能为空，也不能超过 64 个字符", Problem.Kind.INVALID_PARAMETER, null);
        }
        Optional<StepDecision> renovation = renovationOf(step, params, degrees);
        if (renovation.isPresent()) return renovation.get();
        AnchorResolver.Resolution anchor = anchors.resolve(step.goal().target());
        if (anchor instanceof AnchorResolver.Resolution.Failed failed) {
            return fail(failed.problem().message(), failed.problem().kind(), failed.problem().suggestion());
        }
        WorldPosition at = ((AnchorResolver.Resolution.Ready) anchor).anchor();
        try {
            Source source = source(params);
            BlockPos origin = new BlockPos(at.x(), at.y(), at.z());
            Blueprint blueprint = Blueprint.at(at.dimension(), origin, turn, source.cells());
            return new StepDecision.Run(new BuildInput(
                    new ConstructionInput(blueprint, step.goal().permissions(), "施工", source.fixturesSkipped(),
                            recorderFor(params, source, blueprint, degrees)), source.what()));
        } catch (NoSuchFileException missing) {
            return fail(missing.getReason(), Problem.Kind.NOT_FOUND, "把文件放进 schematics 目录，或改用 design 画图");
        } catch (IOException failure) {
            return fail("结构文件读不出来：" + failure.getMessage(), Problem.Kind.INTERNAL_ERROR, null);
        } catch (IllegalArgumentException invalid) {
            return fail(invalid.getMessage(), Problem.Kind.INVALID_PARAMETER, "按错误里的定位改参数再试");
        }
    }

    // 新盖的一栋记档；单格落在某一栋的范围里并进那一栋；范围外的单格不建档。
    private RecordsConstruction recorderFor(ParamValues params, Source source, Blueprint blueprint, int degrees) {
        if (params.has("block")) {
            BlockPos cell = blueprint.cells().getFirst().pos();
            return records.archives().containing(blueprint.dimension(), cell)
                    .<RecordsConstruction>map(home -> new BuildingRecorder(records, patched(home, blueprint), true))
                    .orElse(RecordsConstruction.NONE);
        }
        String wanted = params.has("name") ? params.text("name").trim() : source.name();
        String name = records.archives().unusedName(wanted, blueprint.dimension(), blueprint.anchor());
        BuildingArchive planned = new BuildingArchive(name, blueprint.dimension(), blueprint.anchor(), degrees, source.designId(),
                BuildingRecorder.relative(blueprint), null, null);
        return new BuildingRecorder(records, planned, false);
    }

    // 一格补丁并进一栋：偏移换成相对那一栋的锚点，同一格以补丁为准。
    private static BuildingArchive patched(BuildingArchive archive, Blueprint patch) {
        Blueprint relativeToArchive = new Blueprint(patch.dimension(), archive.anchor(), patch.cells());
        return archive.with(BlueprintPatch.merge(archive.cells(), BuildingRecorder.relative(relativeToArchive)), archive.designId(),
                archive.lastCheckAt(), archive.lastCheckNote());
    }

    /**
     * target 是一栋建筑档案的名字时，这次就是改这一栋；不是档案名给空，照普通施工走。
     * 和一个记过的地点同名而不在一处：问一声指的是哪个。
     */
    private Optional<StepDecision> renovationOf(StepContext step, ParamValues params, int degrees) {
        if (!(step.goal().target() instanceof Target.Landmark landmark)) return Optional.empty();
        BuildingRecords.Lookup lookup = records.lookup(landmark.name());
        if (lookup instanceof BuildingRecords.Lookup.Found found) return Optional.of(renovate(step, params, found.archive(), degrees));
        if (!(lookup instanceof BuildingRecords.Lookup.Ambiguous ambiguous)) return Optional.empty();
        // 问过了：改那一栋、在记过的地点另盖（照普通施工走）、或者不盖了。
        List<String> answers = step.answers();
        if (answers.contains("cancel")) return Optional.of(new StepDecision.Finish(TaskResult.cancelled("施工取消了")));
        if (answers.contains("place")) return Optional.empty();
        if (answers.contains("building")) return Optional.of(renovate(step, params, ambiguous.archive(), degrees));
        WorldPosition place = ambiguous.place();
        BlockPos anchor = ambiguous.archive().anchor();
        return Optional.of(new StepDecision.Ask(new Question(Question.Reason.UNCLEAR_TARGET,
                "「" + landmark.name() + "」既是记过的地点 (" + place.x() + ", " + place.y() + ", " + place.z()
                        + ")，又是锚点在 (" + anchor.getX() + ", " + anchor.getY() + ", " + anchor.getZ() + ") 的一栋建筑档案，两处不在一起",
                List.of(new Question.Option("building", "改那一栋建筑（照档案的锚点与朝向）"),
                        new Question.Option("place", "在记过的那个地点另盖"),
                        new Question.Option("cancel", "不盖了")))));
    }

    // 改一栋：锚点、朝向照档案；新一版图纸或结构文件整份换掉档案的蓝图，逐格清单与单格是补丁，并进档案。
    private StepDecision renovate(StepContext step, ParamValues params, BuildingArchive archive, int degrees) {
        if (degrees != 0 && degrees != archive.rotation()) {
            return fail("改建沿用「" + archive.name() + "」的朝向（rotation=" + archive.rotation() + "）；换朝向就是另盖一栋，target 写坐标、给新的 name",
                    Problem.Kind.INVALID_PARAMETER, null);
        }
        AnchorResolver.Resolution here = anchors.resolve(new Target.Here());
        if (here instanceof AnchorResolver.Resolution.Ready ready && ready.anchor().dimension() != null
                && !ready.anchor().dimension().equals(archive.dimension())) {
            return fail("「" + archive.name() + "」在 " + archive.dimension() + "，角色现在在 " + ready.anchor().dimension(),
                    Problem.Kind.UNREACHABLE, "先 travel 回那个维度");
        }
        try {
            Source source = source(params);
            boolean patch = params.has("cells") || params.has("block");
            Blueprint construction = Blueprint.at(archive.dimension(), archive.anchor(),
                    patch ? Rotation.NONE : Blueprint.turnOf(archive.rotation()), source.cells());
            List<PlannedCell> drawn = BuildingRecorder.relative(construction);
            BuildingArchive planned = patch
                    ? archive.with(BlueprintPatch.merge(archive.cells(), drawn), archive.designId(), archive.lastCheckAt(), archive.lastCheckNote())
                    : archive.with(drawn, source.designId(), archive.lastCheckAt(), archive.lastCheckNote());
            return new StepDecision.Run(new BuildInput(
                    new ConstructionInput(construction, step.goal().permissions(), "施工", source.fixturesSkipped(),
                            new BuildingRecorder(records, planned, true)),
                    "改建「" + archive.name() + "」：" + source.what()));
        } catch (NoSuchFileException missing) {
            return fail(missing.getReason(), Problem.Kind.NOT_FOUND, "把文件放进 schematics 目录，或改用 design 画图");
        } catch (IOException failure) {
            return fail("结构文件读不出来：" + failure.getMessage(), Problem.Kind.INTERNAL_ERROR, null);
        } catch (IllegalArgumentException invalid) {
            return fail(invalid.getMessage(), Problem.Kind.INVALID_PARAMETER, "按错误里的定位改参数再试");
        }
    }

    /**
     * 蓝图从哪来：相对锚点的计划格、一句说明、结构文件里没装的摆设实体数（别的来源为空）、
     * 图纸版本（只有 design_id 有）、没给 name 时用的档案名。
     */
    private record Source(List<PlannedCell> cells, String what, Integer fixturesSkipped, String designId, String name) {}

    // 四个来源各自变成相对计划格：图纸编译、逐格清单、结构文件导入、单格。
    private Source source(ParamValues params) throws IOException {
        if (params.has("design_id")) {
            BuildingDesign design = designs.load(params.text("design_id"));
            return new Source(DesignCompiler.compile(design.drawing()).cells(), "图纸「" + design.name() + "」", null,
                    design.id(), design.name());
        }
        if (params.has("cells")) return new Source(BuildCells.fromArray(params.json("cells")), "逐格清单", null, null, "建筑");
        if (params.has("file")) {
            StructureImport.Imported imported = StructureImport.read(schematics, params.text("file"));
            String what = "结构文件 " + params.text("file")
                    + (imported.fixturesSkipped() > 0 ? "，" + imported.fixturesSkipped() + " 个摆设实体不装" : "")
                    + (imported.cellsDropped() > 0 ? "，" + imported.cellsDropped() + " 格建不了跳过" : "");
            String stem = Path.of(params.text("file")).getFileName().toString().replaceFirst("\\.[^.]+$", "");
            return new Source(imported.cells(), what, imported.fixturesSkipped(), null, stem);
        }
        PlannedCell single = BuildCells.single(params.text("block"), params.has("properties") ? params.json("properties") : null);
        return new Source(List.of(single), "一格 " + params.text("block"), null, null, "建筑");
    }

    @Override public void registerTasks(TaskFactories factories) {
        // 每次运行按这次任务的许可拼一份施工服务；施工引擎在玩家行为层，能力只递输入。
        factories.register(BuildInput.class, input -> new ConstructionTask(input.construction(), services.apply(input.construction().permissions())));
    }

    // 没开工就收场：一句话结论只带结论句，展开的原因（比如转述下一层的失败）只在问题里留一份。
    private static StepDecision fail(String message, Problem.Kind kind, String suggestion) {
        Problem problem = Problem.of(kind, message, suggestion);
        return new StepDecision.Finish(TaskResult.failed(problem.headline(), problem));
    }
}
