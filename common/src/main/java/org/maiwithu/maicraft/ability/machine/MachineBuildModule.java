// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import org.maiwithu.maicraft.ability.design.api.BuildingDesign;
import org.maiwithu.maicraft.ability.design.api.DesignCompiler;
import org.maiwithu.maicraft.ability.design.api.DesignStore;
import org.maiwithu.maicraft.behavior.construction.AnchorResolver;
import org.maiwithu.maicraft.behavior.construction.ConstructionServices;
import org.maiwithu.maicraft.behavior.construction.PlannedCell;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.ModIdentity;
import org.maiwithu.maicraft.kernel.ability.AbilityDoc;
import org.maiwithu.maicraft.kernel.ability.AbilityModule;
import org.maiwithu.maicraft.kernel.ability.AbilitySpec;
import org.maiwithu.maicraft.kernel.ability.ExecutionMode;
import org.maiwithu.maicraft.kernel.ability.Listing;
import org.maiwithu.maicraft.kernel.goal.Permissions;
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
 * 按机器蓝图施工的能力：把一份机器蓝图落到 target 处——锚点、逐格施工、装安装段与部件、改装后设置，
 * 建成后并入机器档案；目标是已有档案时就是修改（补丁合并进整机），operation=remove 拆档案里的整台。
 *
 * <p>自己只做参数核对、锚点解析与补丁合并；核对、备料、清障、砌筑、倒桶、验收是施工引擎的事，
 * 安装段、部件与设置是机器类型的事，这台机器能不能转不在这里判，留给 machine_run。
 */
final class MachineBuildModule implements AbilityModule {

    private final MachineServices services;
    private final MachineArchives archives;
    private final AnchorResolver anchors;
    private final Optional<DesignStore> designs;
    private final WorldMemory memory;
    /** 按这次任务的许可拼一份施工服务，交给施工引擎。 */
    private final Function<Permissions, ConstructionServices> construction;
    private final AbilitySpec spec = new AbilitySpec(
            ModIdentity.MOD_ID + ":machine_build",
            "按机器蓝图在 target 处建一台机器：逐格施工、装安装段与部件、改装后设置，建成入档；"
                    + "target 指已有档案就是修改，operation=remove 拆档案里的整台",
            AbilityDoc.forAbility("machine_build"),
            ParamSpecs.of(
                    ParamSpec.of("blueprint", ParamType.JSON_OBJECT)
                            .doc("机器蓝图的正文（JSON 对象）：cells 逐格、parts 部件、installations 安装段、"
                                    + "settings 装后设置；和 design_id 二选一，operation=remove 时不给").build(),
                    ParamSpec.of("design_id", ParamType.TEXT)
                            .doc("已保存的设计编号：按它存的方块清单建（没有机器附加条目）").build(),
                    ParamSpec.of("name", ParamType.TEXT)
                            .doc("新机器的档案名；不给就按锚点起名。修改已有档案时不用给").build(),
                    ParamSpec.of("operation", ParamType.TEXT).defaultValue("build")
                            .doc("build 建或改（默认）/ remove 拆档案里的整台（target 指档案名）").build()),
            Set.of(TargetKind.HERE, TargetKind.SEEN, TargetKind.LANDMARK, TargetKind.POSITION),
            ExecutionMode.CONTROLS_PLAYER,
            Set.of(),
            List.of(),
            Listing.LISTED);

    MachineBuildModule(MachineServices services, MachineArchives archives, AnchorResolver anchors,
            Optional<DesignStore> designs, WorldMemory memory,
            Function<Permissions, ConstructionServices> construction) {
        this.services = Objects.requireNonNull(services, "services");
        this.archives = Objects.requireNonNull(archives, "archives");
        this.anchors = Objects.requireNonNull(anchors, "anchors");
        this.designs = Objects.requireNonNull(designs, "designs");
        this.memory = Objects.requireNonNull(memory, "memory");
        this.construction = Objects.requireNonNull(construction, "construction");
    }

    @Override public AbilitySpec spec() {
        return spec;
    }

    // 蓝图正文、锚点、档案名与拆建开关在这里一次核清；落到档案或锚点后交给施工任务。
    @Override public StepDecision decide(StepContext step) {
        ParamValues params = step.goal().params();
        String operation = params.has("operation") ? params.text("operation") : "build";
        if (!operation.equals("build") && !operation.equals("remove")) {
            return fail("operation 只能是 build 或 remove，现在是 " + operation, Problem.Kind.INVALID_PARAMETER, null);
        }
        if (operation.equals("remove")) {
            return decideRemove(step, params);
        }
        int given = (params.has("blueprint") ? 1 : 0) + (params.has("design_id") ? 1 : 0);
        if (given != 1) {
            return fail("blueprint 和 design_id 二选一，现在给了 " + given + " 个", Problem.Kind.INVALID_PARAMETER, null);
        }
        MachineBlueprint parsed;
        String what;
        try {
            if (params.has("design_id")) {
                BuildingDesign design = designs.get().load(params.text("design_id"));
                parsed = cellsOnly(design);
                what = "设计「" + design.name() + "」";
            } else {
                parsed = MachineBlueprints.parse(params.json("blueprint"));
                what = "机器蓝图";
            }
        } catch (IllegalArgumentException invalid) {
            return fail(invalid.getMessage(), Problem.Kind.INVALID_PARAMETER, "按错误里的定位改参数再试");
        }
        return decideBuild(step, params, parsed, what);
    }

    // 建：目标是已有档案（按 landmark 名字找到）就合并补丁照档案锚点改，否则解析新锚点按新机器建。
    private StepDecision decideBuild(StepContext step, ParamValues params, MachineBlueprint parsed, String what) {
        if (step.goal().target() instanceof Target.Landmark landmark) {
            Optional<MachineArchive> byName = archives.find(landmark.name());
            if (byName.isPresent()) {
                return patch(byName.get(), parsed, step.goal().permissions());
            }
        }
        AnchorResolver.Resolution anchor = anchors.resolve(step.goal().target());
        if (anchor instanceof AnchorResolver.Resolution.Failed failed) {
            return fail(failed.problem().message(), failed.problem().kind(), failed.problem().suggestion());
        }
        WorldPosition at = ((AnchorResolver.Resolution.Ready) anchor).anchor();
        Problem offWorld = worldCheck(at);
        if (offWorld != null) {
            return new StepDecision.Finish(TaskResult.failed(offWorld.message(), offWorld));
        }
        String name = archiveName(params, step.goal().target(), at);
        if (archives.find(name).isPresent()) {
            return fail("档案名「" + name + "」已经有一台机器在用", Problem.Kind.INVALID_PARAMETER,
                    "换一个 name，或把 target 指到那台机器上来修改它");
        }
        return new StepDecision.Run(new MachineBuildInput(false, parsed, at, name, null,
                step.goal().permissions(), "按" + what + "在 (" + at.x() + "," + at.y() + "," + at.z() + ") 建机器"));
    }

    // 改：补丁相对档案锚点合并，建成什么以合并结果为准；档案在别的维度照实说去不了。
    private StepDecision patch(MachineArchive archive, MachineBlueprint parsed, Permissions permissions) {
        if (services.world().playerSpot().isEmpty()) {
            return fail("角色现在不在世界里，动不了机器", Problem.Kind.UNREACHABLE, "先回到世界");
        }
        if (!services.world().playerSpot().get().dimension().equals(archive.dimension())) {
            return fail("档案「" + archive.name() + "」在另一个维度（" + archive.dimension() + "），跨维度还没有支持",
                    Problem.Kind.UNREACHABLE, "到 " + archive.dimension() + " 再下达");
        }
        MachineBlueprint merged = MachinePatch.merge(archive.blueprint(), parsed);
        WorldPosition at = new WorldPosition(archive.anchor().getX(), archive.anchor().getY(),
                archive.anchor().getZ(), archive.dimension());
        return new StepDecision.Run(new MachineBuildInput(false, merged, at, archive.name(), archive,
                permissions, "把补丁并进档案「" + archive.name() + "」"));
    }

    // 拆：只认档案名；拆过再拆按没得拆说，蓝图保留着可以随时再建。
    private StepDecision decideRemove(StepContext step, ParamValues params) {
        if (params.has("blueprint") || params.has("design_id")) {
            return fail("拆除按档案来，不用给蓝图", Problem.Kind.INVALID_PARAMETER, "target 指档案名即可");
        }
        if (!(step.goal().target() instanceof Target.Landmark landmark)) {
            return fail("拆除要指档案名（target 的 landmark 写法）", Problem.Kind.INVALID_PARAMETER,
                    "先查世界记忆或 machine_inspect 确认名字");
        }
        Optional<MachineArchive> archive = archives.find(landmark.name());
        if (archive.isEmpty()) {
            return fail("没有叫「" + landmark.name() + "」的机器档案", Problem.Kind.NOT_FOUND,
                    "名字对上再拆；机器建档后才有档案可拆");
        }
        if (archive.get().removed()) {
            return fail("「" + landmark.name() + "」已经是拆除状态", Problem.Kind.NOT_POSSIBLE_HERE,
                    "要重建就照它的蓝图再下一次 machine_build");
        }
        MachineArchive target = archive.get();
        WorldPosition at = new WorldPosition(target.anchor().getX(), target.anchor().getY(),
                target.anchor().getZ(), target.dimension());
        Problem offWorld = worldCheck(at);
        if (offWorld != null) {
            return new StepDecision.Finish(TaskResult.failed(offWorld.message(), offWorld));
        }
        return new StepDecision.Run(new MachineBuildInput(true, null, at, target.name(), target,
                step.goal().permissions(), "拆掉机器「" + target.name() + "」"));
    }

    /** 角色所在维度与目标维度对不上、或角色不在世界里时的问题；同维度给 null。 */
    private Problem worldCheck(WorldPosition at) {
        if (services.world().playerSpot().isEmpty()) {
            return Problem.of(Problem.Kind.UNREACHABLE, "角色现在不在世界里，动不了工地", "先回到世界");
        }
        String here = services.world().playerSpot().get().dimension();
        if (!here.equals(at.dimension())) {
            return Problem.of(Problem.Kind.UNREACHABLE,
                    "目标在另一个维度（" + at.dimension() + "），跨维度施工还没有支持", null);
        }
        return null;
    }

    /** 档案名：给定的优先；目标是地标用它的名字；再不然按锚点起"机器@x,y,z"。 */
    private String archiveName(ParamValues params, Target target, WorldPosition at) {
        if (params.has("name")) {
            return params.text("name");
        }
        if (target instanceof Target.Landmark landmark) {
            return landmark.name();
        }
        return "机器@" + at.x() + "," + at.y() + "," + at.z();
    }

    /** 设计编号里的图只有逐格清单，没有机器附加条目；按它建出来的机器档案也照实记。 */
    private MachineBlueprint cellsOnly(BuildingDesign design) {
        List<PlannedCell> cells = DesignCompiler.compile(design.drawing()).cells();
        return new MachineBlueprint(cells, List.of(), List.of(), List.of(), List.of());
    }

    private static StepDecision fail(String message, Problem.Kind kind, String suggestion) {
        return new StepDecision.Finish(TaskResult.failed(message, Problem.of(kind, message, suggestion)));
    }

    @Override public void registerTasks(TaskFactories factories) {
        // 每次运行按这次任务的许可拼一份施工服务；施工引擎在玩家行为层，能力只递输入。
        factories.register(MachineBuildInput.class,
                input -> new MachineBuildTask(input, services, archives, memory, construction));
    }
}
