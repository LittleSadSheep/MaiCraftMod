// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.vehicle;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.ability.travel.api.TravelSteps;
import org.maiwithu.maicraft.ability.vehicle.spi.VehicleDriver;
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
 * vehicle_drive：坐到驾驶座上开载具。test 逐条按住控制件看载具怎么动，再松开交还；drive（开到一个地方停稳）还没做。
 * 能力只落实"开哪辆、按什么"，角色不在车上时先用出行把人送上去（上船那一段出行方式接手），
 * 怎么坐、怎么接管、怎么按由联动模组的驾驶办法做。
 */
public final class VehicleDriveModule implements AbilityModule {

    /** 需要的模组：现在能开的只有 Simulated 无线红石打字机控制的载具。 */
    static final String REQUIRED_MOD = "simulated";
    /** 一条输入最多按住多少刻（10 秒）。 */
    static final int MAX_PRESS_TICKS = 200;
    /** 一次最多试几条输入。 */
    static final int MAX_INPUTS = 16;

    private final List<VehicleDriver> drivers;
    private final ReadsSeenTargets seen;
    private final Function<String, Optional<String>> structures;

    /** @param structures 观察编号 s# 换成结构身份 */
    public VehicleDriveModule(List<VehicleDriver> drivers, ReadsSeenTargets seen,
            Function<String, Optional<String>> structures) {
        this.drivers = List.copyOf(drivers);
        this.seen = Objects.requireNonNull(seen, "seen");
        this.structures = Objects.requireNonNull(structures, "structures");
    }

    @Override public AbilitySpec spec() {
        return new AbilitySpec("maicraft:vehicle_drive",
                "坐上驾驶座开载具：test 逐条按住控制件看它怎么动再松开（开到某处的 drive 还没做）",
                AbilityDoc.forAbility("vehicle_drive"),
                ParamSpecs.of(
                        ParamSpec.of("vehicle", ParamType.TEXT).required()
                                .doc("开哪辆：物理结构的观察编号 s#（observe 的 mod_things.structure）").build(),
                        ParamSpec.of("operation", ParamType.CHOICE).choices("drive", "test").defaultValue("drive")
                                .doc("drive 开到一个地方（默认，还没做）/ test 逐条按 inputs 看反应").build(),
                        ParamSpec.of("inputs", ParamType.JSON_ARRAY)
                                .doc("test 逐条按的输入：[{\"control\": \"打字机:W\", \"ticks\": 20}]；control 写控制件和键，"
                                        + "ticks 按住几刻（1～" + MAX_PRESS_TICKS + "），最多 " + MAX_INPUTS + " 条").build()),
                Set.of(TargetKind.POSITION, TargetKind.LANDMARK, TargetKind.DIRECTION),
                ExecutionMode.CONTROLS_PLAYER,
                Set.of(RequiredMod.of(REQUIRED_MOD)),
                List.of(),
                Listing.LISTED);
    }

    // 落实开哪辆、按什么 → 不在车上先去上车（出行跑完回来再决定）→ 交给驾驶办法试按。
    @Override public StepDecision decide(StepContext step) {
        Goal goal = step.goal();
        if (drivers.isEmpty()) {
            return new StepDecision.Finish(TaskResult.failed("开不了", Problem.of(Problem.Kind.UNSUPPORTED,
                    "没有接上驾驶的联动（Simulated 装了，但它的联动没登记成功）")));
        }
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
        if (!"test".equals(goal.params().text("operation"))) {
            return new StepDecision.Finish(TaskResult.failed("开到某处还没做", Problem.of(Problem.Kind.UNSUPPORTED,
                    "vehicle_drive 的 drive（开到一个地方停稳）还没做好", "先用 operation=test 逐条试按，看每个键让它怎么动")));
        }
        List<VehicleDriver.Press> inputs = new ArrayList<>();
        String wrong = inputs(goal, inputs);
        if (wrong != null) return invalid(wrong);
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
        BlockPos near = new BlockPos(at.get().x(), at.get().y(), at.get().z());
        return new StepDecision.Run(new VehicleDriveInput(new VehicleDriver.TestRequest(structure.get(), near, inputs,
                goal.permissions())));
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
        factories.register(VehicleDriveInput.class, input -> new VehicleDriveTask(input.request(), drivers));
    }

    private static StepDecision invalid(String why) {
        return new StepDecision.Finish(TaskResult.failed("参数不对：" + why, Problem.of(Problem.Kind.INVALID_PARAMETER, why,
                null)));
    }
}
