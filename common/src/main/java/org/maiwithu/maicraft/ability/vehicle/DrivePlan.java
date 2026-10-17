// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.vehicle;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.ability.vehicle.api.VehicleControls;
import org.maiwithu.maicraft.behavior.permission.ReadsRememberedPlaces;
import org.maiwithu.maicraft.behavior.travel.LandmarkResolver;
import org.maiwithu.maicraft.behavior.travel.ReadsSeenTargets;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;

/**
 * 开到某处之前要落实的两件事（纯函数）：去哪——坐标、记过的地点、往东南西北哪边开多远；
 * 能不能开——操纵表缺必需角色就开不了（如实说缺哪个，这是事实，不是猜设计好不好），车头没定也开不了；
 * 能开就把操纵表收成"角色 → 输入"，同一角色有几个输入时按 LLM 定的、试过的、推出来的先后挑一个。
 */
final class DrivePlan {

    /** 开车用得上的角色。 */
    static final Set<String> DRIVING = Set.of("power", "reverse", "brake", "lift", "descend",
            "yaw_left", "yaw_right", "steer_left", "steer_right");

    /**
     * 去哪。
     *
     * @param at          目的地（世界坐标）；说不清为 null
     * @param heightKnown 高度核实过
     * @param problem     说不清时为什么；说清了为 null
     */
    record Destination(Vec3 at, boolean heightKnown, Problem problem) {}

    private DrivePlan() {}

    /**
     * @param seen 此刻看得见的告示牌从这里读：地点名没记过时按牌子认地方
     * @param feet 角色此刻脚下：坐标没给 y、按方向开多远都从这里算
     */
    static Destination destination(Target target, ReadsRememberedPlaces places, ReadsSeenTargets seen, BlockPos feet) {
        if (target instanceof Target.Position position) {
            boolean known = position.y() != null;
            return new Destination(new Vec3(position.x() + 0.5, known ? position.y() : feet.getY(), position.z() + 0.5),
                    known, null);
        }
        if (target instanceof Target.Landmark landmark) {
            // 地点名：记过的地点优先，没记过认附近唯一写着它的告示牌，开到牌子那一格；都落不了就说清为什么。
            var located = LandmarkResolver.resolve(landmark.name(), places, seen::signs);
            if (located instanceof LandmarkResolver.Resolution.Missing missing) {
                return new Destination(null, false, Problem.of(Problem.Kind.NOT_FOUND,
                        missing.reason(), missing.suggestionOr("改用坐标")));
            }
            WorldPosition place = ((LandmarkResolver.Resolution.Found) located).position();
            return new Destination(new Vec3(place.x() + 0.5, place.y(), place.z() + 0.5), true, null);
        }
        if (target instanceof Target.Direction direction) {
            int[] step = switch (direction.toward()) {
                case NORTH -> new int[]{0, -1};
                case SOUTH -> new int[]{0, 1};
                case WEST -> new int[]{-1, 0};
                case EAST -> new int[]{1, 0};
                default -> null;
            };
            if (step == null) {
                return new Destination(null, false, Problem.of(Problem.Kind.INVALID_PARAMETER,
                        "开载具时方向写东南西北（north / south / east / west），前后左右说不清是按人还是按车"));
            }
            return new Destination(new Vec3(feet.getX() + 0.5 + step[0] * direction.distance(), feet.getY(),
                    feet.getZ() + 0.5 + step[1] * direction.distance()), false, null);
        }
        return new Destination(null, false, Problem.of(Problem.Kind.INVALID_PARAMETER,
                "drive 要给 target：坐标、记过的地点，或往东南西北哪边开多远"));
    }

    /** 开不了的原因：缺必需角色、车头没定；能开为 null。 */
    static Problem notReady(VehicleControls.ControlMap map) {
        if (!map.missing().isEmpty()) {
            return Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, "开这辆还缺：" + String.join("、", map.missing()),
                    "先 vehicle_drive(operation=test) 试按认角色，或 machine_configure 对这个 s# 定角色");
        }
        if (map.forward().isEmpty()) {
            return Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, "车头朝哪还不知道",
                    "试按一下动力键（平着挪的那个方向就当车头），或 machine_configure 写 forward");
        }
        return null;
    }

    /** 操纵表收成"角色 → 输入"：同一角色按 LLM 定的、试过的、推出来的先后挑第一个。 */
    static Map<String, String> roles(VehicleControls.ControlMap map) {
        Map<String, String> roles = new LinkedHashMap<>();
        for (String source : List.of(VehicleControls.ASSIGNED, VehicleControls.TESTED, VehicleControls.INFERRED)) {
            for (VehicleControls.Entry entry : map.entries()) {
                if (entry.source().equals(source) && DRIVING.contains(entry.role())) {
                    roles.putIfAbsent(entry.role(), entry.control());
                }
            }
        }
        return roles;
    }
}
