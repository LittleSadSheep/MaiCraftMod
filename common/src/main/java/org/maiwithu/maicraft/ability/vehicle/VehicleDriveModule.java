// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.vehicle;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.ability.travel.api.TravelSteps;
import org.maiwithu.maicraft.ability.vehicle.api.VehicleControls;
import org.maiwithu.maicraft.ability.vehicle.spi.VehicleDriver;
import org.maiwithu.maicraft.ability.vehicle.spi.VehicleReader;
import org.maiwithu.maicraft.behavior.permission.ReadsRememberedPlaces;
import org.maiwithu.maicraft.behavior.travel.ReadsSeenTargets;
import org.maiwithu.maicraft.kernel.ability.AbilityDoc;
import org.maiwithu.maicraft.kernel.ability.AbilityModule;
import org.maiwithu.maicraft.kernel.ability.AbilitySpec;
import org.maiwithu.maicraft.kernel.ability.ExecutionMode;
import org.maiwithu.maicraft.kernel.ability.Listing;
import org.maiwithu.maicraft.kernel.ability.RequiredMod;
import org.maiwithu.maicraft.kernel.goal.Goal;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.goal.TargetKind;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.param.ParamSpec;
import org.maiwithu.maicraft.kernel.param.ParamSpecs;
import org.maiwithu.maicraft.kernel.param.ParamType;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TaskFactories;

/**
 * vehicle_drive：坐到驾驶座上开载具。test 逐条按住控制件看载具怎么动，再松开交还；drive 开到一个地方停稳。
 * 能力只落实"开哪辆、按什么、去哪"：drive 先查操纵表齐不齐（缺必需角色就开不了，如实说缺哪个）、车头定了没有；
 * 角色不在车上时先用出行把人送上去（上船那一段出行方式接手），跑完回来再决定。
 * 怎么坐、怎么接管、怎么按、飞控怎么飞由联动模组的驾驶办法做。
 */
public final class VehicleDriveModule implements AbilityModule {

    /** 需要的模组：无线红石打字机（Simulated，它本身要机械动力）与遥控器都要机械动力。 */
    static final String REQUIRED_MOD = "create";
    /** 一条输入最多按住多少刻（10 秒）。 */
    static final int MAX_PRESS_TICKS = 200;
    /** 一次最多试几条输入。 */
    static final int MAX_INPUTS = 16;

    private final List<VehicleDriver> drivers;
    private final List<VehicleReader> readers;
    private final VehicleControls controls;
    private final ReadsSeenTargets seen;
    private final Function<String, Optional<String>> structures;
    private final ReadsRememberedPlaces places;
    private final Supplier<BlockPos> feet;

    /**
     * @param readers    读载具的办法：试按后认角色、开之前查操纵表要知道种类与回路
     * @param controls   操纵表：试按认出的角色记进去，开的时候按它按键
     * @param structures 观察编号 s# 换成结构身份
     * @param places     记过的地点：drive 的 target 给地点名时用
     * @param feet       角色此刻脚下：坐标没给 y、按方向开多远时用
     */
    public VehicleDriveModule(List<VehicleDriver> drivers, List<VehicleReader> readers, VehicleControls controls,
            ReadsSeenTargets seen, Function<String, Optional<String>> structures, ReadsRememberedPlaces places,
            Supplier<BlockPos> feet) {
        this.drivers = List.copyOf(drivers);
        this.readers = List.copyOf(readers);
        this.controls = Objects.requireNonNull(controls, "controls");
        this.seen = Objects.requireNonNull(seen, "seen");
        this.structures = Objects.requireNonNull(structures, "structures");
        this.places = Objects.requireNonNull(places, "places");
        this.feet = Objects.requireNonNull(feet, "feet");
    }

    @Override public AbilitySpec spec() {
        return new AbilitySpec("maicraft:vehicle_drive",
                "坐上驾驶座开载具：drive 开到一个地方停稳，test 逐条按住控制件看它怎么动再松开",
                AbilityDoc.forAbility("vehicle_drive"),
                ParamSpecs.of(
                        ParamSpec.of("vehicle", ParamType.TEXT)
                                .doc("开哪辆：物理结构的观察编号 s#（observe 的 mod_things.structure）；只用遥控器试按时可以不给").build(),
                        ParamSpec.of("operation", ParamType.CHOICE).choices("drive", "test").defaultValue("drive")
                                .doc("drive 开到 target（默认）/ test 逐条按 inputs 看反应").build(),
                        ParamSpec.of("inputs", ParamType.JSON_ARRAY)
                                .doc("test 逐条按的输入：[{\"control\": \"打字机:W\", \"ticks\": 20}]；control 写控制件和键，"
                                        + "ticks 按住几刻（1～" + MAX_PRESS_TICKS + "），最多 " + MAX_INPUTS + " 条").build(),
                        ParamSpec.of("cruise_altitude", ParamType.INTEGER).range(-64, 320)
                                .doc("只对飞艇：巡航高度（船底的世界 y）；不给按当前与目的地高的那个 + 32").build()),
                Set.of(TargetKind.POSITION, TargetKind.LANDMARK, TargetKind.DIRECTION),
                ExecutionMode.CONTROLS_PLAYER,
                Set.of(RequiredMod.of(REQUIRED_MOD)),
                List.of(),
                Listing.LISTED);
    }

    // 落实开哪辆 → test 读 inputs、drive 查操纵表与目的地 → 不在车上先去上车（出行跑完回来再决定）→ 交给驾驶办法。
    @Override public StepDecision decide(StepContext step) {
        Goal goal = step.goal();
        if (drivers.isEmpty()) {
            return new StepDecision.Finish(TaskResult.failed("开不了", Problem.of(Problem.Kind.UNSUPPORTED,
                    "没有接上驾驶的联动（Simulated 装了，但它的联动没登记成功）")));
        }
        // 只用遥控器试按（开门、开闸也行）：不用指哪辆，也不用上车，按完就是。
        if (!goal.params().has("vehicle") && "test".equals(goal.params().text("operation"))) {
            List<VehicleDriver.Press> inputs = new ArrayList<>();
            String wrong = inputs(goal, inputs);
            if (wrong != null) return invalid(wrong);
            if (!inputs.stream().allMatch(press -> press.control().startsWith("遥控器"))) {
                return invalid("不给 vehicle 时只能按遥控器（遥控器:上 这样）；按无线红石打字机要给结构的 s#");
            }
            return new StepDecision.Run(new VehicleDriveInput(new VehicleDriver.TestRequest("", feet.get(), inputs,
                    goal.permissions()), null));
        }
        if (!goal.params().has("vehicle")) return invalid("要给 vehicle：物理结构的观察编号 s#");
        String vehicle = goal.params().text("vehicle").trim();
        if (!vehicle.matches("s[0-9]+")) {
            return invalid("vehicle 给物理结构的观察编号 s#（observe 的 mod_things.structure 里看），收到的是「" + vehicle + "」");
        }
        Optional<WorldPosition> at = seen.positionOf(vehicle);
        Optional<String> structure = structures.apply(vehicle);
        if (at.isEmpty() || structure.isEmpty()) {
            return new StepDecision.Finish(TaskResult.failed("指的载具不在了", Problem.of(Problem.Kind.TARGET_GONE,
                    "观察编号 " + vehicle + " 指的结构已经不在了", "重新 observe 再指")));
        }
        VehicleDriveInput input;
        if ("test".equals(goal.params().text("operation"))) {
            List<VehicleDriver.Press> inputs = new ArrayList<>();
            String wrong = inputs(goal, inputs);
            if (wrong != null) return invalid(wrong);
            BlockPos near = new BlockPos(at.get().x(), at.get().y(), at.get().z());
            input = new VehicleDriveInput(new VehicleDriver.TestRequest(structure.get(), near, inputs, goal.permissions()),
                    null);
        } else {
            DrivePlan.Destination destination = DrivePlan.destination(goal.target(), places, feet.get());
            if (destination.problem() != null) return new StepDecision.Finish(TaskResult.failed("去哪说不清", destination.problem()));
            Optional<VehicleReader.View> view = readers.stream().map(reader -> reader.read(structure.get()))
                    .flatMap(Optional::stream).findFirst();
            if (view.isEmpty()) {
                return new StepDecision.Finish(TaskResult.failed("读不了这辆", Problem.of(Problem.Kind.NOT_FOUND,
                        "读不了 " + vehicle + "：离得太远，或没有能读它的联动", "离近一点重新 observe 再指")));
            }
            VehicleControls.ControlMap map = VehicleControls.merge(view.get().kind(), view.get().controls(),
                    controls.stored(structure.get()));
            Problem notReady = DrivePlan.notReady(map);
            if (notReady != null) return new StepDecision.Finish(TaskResult.failed("开不了这辆", notReady));
            Integer cruise = goal.params().has("cruise_altitude") ? (int) goal.params().integer("cruise_altitude") : null;
            input = new VehicleDriveInput(null, new VehicleDriver.DriveRequest(structure.get(), destination.at(),
                    destination.heightKnown(), cruise, view.get().kind(), map.forward(), DrivePlan.roles(map),
                    goal.permissions()));
        }
        // 人必须在车上：不在就先把人送到驾驶座旁（目的地在船上，上船那一段由出行方式接手），跑完回来再决定。
        for (VehicleDriver driver : drivers) {
            Optional<WorldPosition> board = driver.boardAt(structure.get());
            if (board.isEmpty()) continue;
            if (!step.taskResults().isEmpty()) {
                TaskResult walked = step.taskResults().getLast();
                return new StepDecision.Finish(TaskResult.failed("没上到车上", Problem.of(Problem.Kind.UNREACHABLE,
                        "走过去了还不在这辆上：" + walked.summary(),
                        walked.problem() == null || walked.problem().suggestion() == null
                                ? "看看船边能不能走上去，或者先自己上船再试" : walked.problem().suggestion())));
            }
            return new StepDecision.Run(TravelSteps.journeyTo(board.get(), 1.0, goal.permissions()), true);
        }
        return new StepDecision.Run(input);
    }

    // 逐条读 inputs：每条要有 control 文字和 1～200 的 ticks；有错返回一句为什么，全对返回 null。
    private static String inputs(Goal goal, List<VehicleDriver.Press> into) {
        if (!goal.params().has("inputs")) return "test 要给 inputs：[{\"control\": \"打字机:W\", \"ticks\": 20}]";
        JsonElement raw = goal.params().json("inputs");
        if (!raw.isJsonArray() || raw.getAsJsonArray().isEmpty()) return "inputs 至少要有一条";
        if (raw.getAsJsonArray().size() > MAX_INPUTS) return "inputs 一次最多 " + MAX_INPUTS + " 条";
        for (JsonElement entry : raw.getAsJsonArray()) {
            if (!entry.isJsonObject()) return "inputs 的每一条是 {\"control\": …, \"ticks\": …}";
            JsonObject press = entry.getAsJsonObject();
            if (!press.has("control") || !press.get("control").isJsonPrimitive()
                    || press.get("control").getAsString().isBlank()) {
                return "inputs 的每一条要有 control（例如 打字机:W）";
            }
            if (!press.has("ticks") || !press.get("ticks").isJsonPrimitive() || !press.get("ticks").getAsJsonPrimitive().isNumber()) {
                return "inputs 的每一条要有整数 ticks（按住几刻）";
            }
            double ticks = press.get("ticks").getAsDouble();
            if (ticks != Math.rint(ticks) || ticks < 1 || ticks > MAX_PRESS_TICKS) {
                return "ticks 是 1～" + MAX_PRESS_TICKS + " 的整数，收到 " + press.get("ticks");
            }
            into.add(new VehicleDriver.Press(press.get("control").getAsString().trim(), (int) ticks));
        }
        return null;
    }

    @Override public void registerTasks(TaskFactories factories) {
        factories.register(VehicleDriveInput.class, input -> new VehicleDriveTask(input, drivers, readers, controls));
    }

    private static StepDecision invalid(String why) {
        return new StepDecision.Finish(TaskResult.failed("参数不对：" + why, Problem.of(Problem.Kind.INVALID_PARAMETER, why,
                null)));
    }
}
