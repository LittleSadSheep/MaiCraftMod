// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.ModIdentity;
import org.maiwithu.maicraft.kernel.ability.AbilityModule;
import org.maiwithu.maicraft.kernel.ability.AbilityDoc;
import org.maiwithu.maicraft.kernel.ability.AbilitySpec;
import org.maiwithu.maicraft.kernel.ability.ExecutionMode;
import org.maiwithu.maicraft.kernel.ability.Listing;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.goal.TargetKind;
import org.maiwithu.maicraft.kernel.param.ParamSpec;
import org.maiwithu.maicraft.kernel.param.ParamSpecs;
import org.maiwithu.maicraft.kernel.param.ParamType;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TaskFactories;

/**
 * 机器查看能力：看一台机器或一片机器区——由哪些机器组成、各自的状态、挂在哪些网络上。只读分析。
 *
 * <p>不打开界面看机器内部（那归 use 与 deposit/obtain），不预测机器能不能转；
 * 转不转以每台的运行状态为准，一起给在结果里。范围没给就是脚下这一片。
 *
 * <p>operation=read_site 读现场：把 target 那一台机器抄成能直接喂回 machine_build 的机器蓝图，并建一份档案
 * （指着档案读时不另建，给整机比对）。只读，不动角色、不改世界。
 */
final class MachineInspectModule implements AbilityModule {

    private final MachineServices services;
    private final MachineArchives archives;
    private final WorldMemory memory;
    private final MachineVehicles vehicles;
    private final AbilitySpec spec = new AbilitySpec(
            ModIdentity.MOD_ID + ":machine_inspect",
            "看一台机器或一片机器区：组成、运行状态、进出口、挂在哪些网络上；指档案名时再和档案整机比对；"
                    + "operation=read_site 把 target 那一台读成机器蓝图并建档（只读）",
            AbilityDoc.forAbility("machine_inspect"),
            ParamSpecs.of(
                    ParamSpec.of("radius", ParamType.INTEGER).range(1, 32).defaultValue(16)
                            .doc("看多大范围，单位格；给了目标就是以它为中心，没给以角色为中心").build(),
                    ParamSpec.of("item", ParamType.ITEM_OR_TAG)
                            .doc("看身上物品自己的设置（过滤器的规则）时写物品 ID：每一件分别列出在哪一格、现在是什么规则").build(),
                    ParamSpec.of("balance", ParamType.CHOICE).choices("forces", "ballast")
                            .doc("看载具（target 给 s#）时要不要受力：forces 开着与关着动力两种情况往哪边倒、升力比重量；"
                                    + "ballast 再给几处放配重的位置").build(),
                    ParamSpec.of("operation", ParamType.CHOICE).choices("inspect", "read_site").defaultValue("inspect")
                            .doc("inspect 看（默认）；read_site 把 target 指的那一台机器读成能直接喂回 machine_build 的"
                                    + "机器蓝图（结果 site.blueprint），并按 name 建一份档案；指档案名时不另建档，给整机比对").build(),
                    ParamSpec.of("name", ParamType.TEXT)
                            .doc("read_site 时新档案的名字；不给按锚点起\"机器@x,y,z\"").build()),
            Set.of(TargetKind.HERE, TargetKind.SEEN, TargetKind.LANDMARK, TargetKind.POSITION),
            ExecutionMode.READ_ONLY,
            Set.of(),
            List.of(),
            Listing.LISTED);

    MachineInspectModule(MachineServices services, MachineArchives archives, WorldMemory memory,
            MachineVehicles vehicles) {
        this.services = services;
        this.archives = archives;
        this.memory = memory;
        this.vehicles = vehicles;
    }

    @Override public AbilitySpec spec() {
        return spec;
    }

    // 范围在参数规格里已经校过；目标是档案名就带上档案，看它的范围并做整机比对；read_site 再抄成机器蓝图并建档。
    @Override public StepDecision decide(StepContext step) {
        boolean readSite = step.goal().params().has("operation")
                && step.goal().params().text("operation").equals("read_site");
        Optional<StepDecision> wrong = readSiteMisuse(step, readSite);
        if (wrong.isPresent()) return wrong.get();
        // 给了 item：看身上那种物品每一件自己的设置，不看机器。
        if (step.goal().params().has("item")) {
            return new StepDecision.Run(new ItemSettingsTask.Input(step.goal().params().text("item").toLowerCase(Locale.ROOT),
                    null, Map.of(), step.goal().permissions()));
        }
        // 指结构的观察编号 s#：载具也是机器，看它的 vehicle 一节（座位、回路、姿态），不扫世界里的方块。
        if (step.goal().target() instanceof Target.Seen seen && seen.id().startsWith("s")) {
            Optional<String> structure = vehicles.structureOf().apply(seen.id());
            if (structure.isEmpty()) {
                return new StepDecision.Finish(TaskResult.failed("指的结构不在了", Problem.of(Problem.Kind.TARGET_GONE,
                        "观察编号 " + seen.id() + " 指的结构已经不在了", "重新 observe 再指")));
            }
            String balance = step.goal().params().has("balance") ? step.goal().params().text("balance") : null;
            return new StepDecision.Run(new VehicleInspectTask.Input(structure.get(), seen.id(), balance));
        }
        int radius = (int) (step.goal().params().has("radius") ? step.goal().params().integer("radius") : 16);
        MachineArchive archive = step.goal().target() instanceof Target.Landmark landmark
                ? archives.find(landmark.name()).filter(found -> !found.removed()).orElse(null)
                : null;
        if (!readSite) {
            return new StepDecision.Run(new MachineInspectInput(step.goal().target(), radius, archive));
        }
        // 读现场的新档案名：给了 name 用它，target 是记过的地点就用地点名，都没有由任务按锚点起"机器@x,y,z"。
        String name = step.goal().params().has("name") ? step.goal().params().text("name")
                : step.goal().target() instanceof Target.Landmark landmark ? landmark.name() : null;
        return new StepDecision.Run(new MachineInspectInput(step.goal().target(), radius, archive, true, name));
    }

    /**
     * 读现场的写法在计划阶段一次核清：name 只在 read_site 时用；read_site 读的是现场的机器，不和 item、载具的 s# 一起用；
     * 指着档案读不另建档，不用给 name；新档案的名字已经有一台机器在用就不覆盖。
     */
    private Optional<StepDecision> readSiteMisuse(StepContext step, boolean readSite) {
        boolean named = step.goal().params().has("name");
        if (!readSite) {
            return named ? Optional.of(fail("name 只在 operation=read_site 时用", null)) : Optional.empty();
        }
        if (step.goal().params().has("item")) {
            return Optional.of(fail("read_site 读的是现场的机器，不和 item 一起用", "看物品设置去掉 operation"));
        }
        if (step.goal().target() instanceof Target.Seen seen && seen.id().startsWith("s")) {
            return Optional.of(fail("载具（s#）不读成机器蓝图", "target 指机器的一格（b# 或坐标）"));
        }
        if (!named) return Optional.empty();
        String name = step.goal().params().text("name");
        boolean pointsAtArchive = step.goal().target() instanceof Target.Landmark landmark
                && archives.find(landmark.name()).filter(found -> !found.removed()).isPresent();
        if (pointsAtArchive) {
            return Optional.of(fail("指着机器档案读现场不另建档案，不用给 name", "去掉 name；要另建一份就让 target 指那台机器的一格"));
        }
        if (archives.find(name).isPresent()) {
            return Optional.of(fail("档案名「" + name + "」已经有一台机器在用",
                    "换一个 name，或让 target 指这个档案名来读它的现场"));
        }
        return Optional.empty();
    }

    private static StepDecision fail(String message, String suggestion) {
        Problem problem = Problem.of(Problem.Kind.INVALID_PARAMETER, message, suggestion);
        return new StepDecision.Finish(TaskResult.failed(problem.headline(), problem));
    }

    @Override public void registerTasks(TaskFactories factories) {
        factories.register(MachineInspectInput.class, input -> new MachineInspectTask(input, services, archives, memory));
        factories.register(VehicleInspectTask.Input.class, input -> new VehicleInspectTask(input, vehicles));
    }
}
