// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.sable;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 载具上认得的部件，和从部件推出来的事实（纯函数）：哪些是控制件、哪些是收端、收端旁边接着的执行件担任什么角色、
 * 整辆是什么种类。只按方块 ID 推：推不出、一个输入接了几样就说未定，留给试按去确认，不猜。
 * 方块 ID 取自 Simulated、Create Aeronautics、Offroad（同属 Simulated-Project 1.3.0）与机械动力。
 */
final class VehicleParts {

    /** 控制件：无线红石打字机、舵轮、油门拉杆。 */
    static final Set<String> CONTROLS = Set.of(StructureGeometry.TYPEWRITER,
            "simulated:steering_wheel", "simulated:throttle_lever");
    /** 收端：机械动力的无线红石链路（切到接收），Simulated 的定向、调制无线红石接收器。 */
    static final Set<String> RECEIVERS = Set.of("create:redstone_link",
            "simulated:directional_linked_receiver", "simulated:modulating_linked_receiver");
    /** 收端旁边接着这些就是动力：螺旋桨轴承出推力，模拟传动器调车轮的转速。 */
    static final Set<String> POWER = Set.of("aeronautics:propeller_bearing",
            "aeronautics:gyroscopic_propeller_bearing", "simulated:analog_transmission");
    /** 升力：可调燃烧器、蒸汽喷口给气球供气。 */
    static final Set<String> LIFT = Set.of("aeronautics:adjustable_burner", "aeronautics:steam_vent");
    /** 转向：扭力弹簧、旋转轴承、定向齿轮换向器拨风帆或舵，车轮悬架打方向。 */
    static final Set<String> STEER = Set.of("simulated:torsion_spring", "simulated:swivel_bearing",
            "simulated:directional_gearshift", "offroad:wheel_mount");
    /** 收端旁边接着这些说不清：离合器、换向器后面还连着别的，要试按才知道。 */
    static final Set<String> RELAYS = Set.of("create:clutch", "create:gearshift", "create:sequenced_gearshift");
    /** 不靠供气就有的浮力：浮空水晶。 */
    static final Set<String> FLOAT = Set.of("aeronautics:levitite", "aeronautics:pearlescent_levitite");
    /** 车轮。 */
    static final String WHEEL = "offroad:wheel_mount";

    /** 角色的说法：给 LLM 的字段值。 */
    static final String ROLE_POWER = "power";
    static final String ROLE_LIFT = "lift";
    static final String ROLE_STEER = "steer";
    static final String ROLE_UNKNOWN = "unknown";

    private static final Map<String, String> NAMES = Map.ofEntries(
            Map.entry("aeronautics:propeller_bearing", "螺旋桨轴承"),
            Map.entry("aeronautics:gyroscopic_propeller_bearing", "陀螺螺旋桨轴承"),
            Map.entry("simulated:analog_transmission", "模拟传动器"),
            Map.entry("aeronautics:adjustable_burner", "可调燃烧器"),
            Map.entry("aeronautics:steam_vent", "蒸汽喷口"),
            Map.entry("simulated:torsion_spring", "扭力弹簧"),
            Map.entry("simulated:swivel_bearing", "旋转轴承"),
            Map.entry("simulated:directional_gearshift", "定向齿轮换向器"),
            Map.entry("offroad:wheel_mount", "车轮悬架"),
            Map.entry("create:clutch", "离合器"),
            Map.entry("create:gearshift", "换向器"),
            Map.entry("create:sequenced_gearshift", "可编程齿轮箱"));

    private VehicleParts() {}

    /** 逐格读时要记下位置的部件：控制件、收端、执行件、浮力源、物理组装器。 */
    static boolean known(String id) {
        return CONTROLS.contains(id) || RECEIVERS.contains(id) || POWER.contains(id) || LIFT.contains(id)
                || STEER.contains(id) || RELAYS.contains(id) || FLOAT.contains(id) || envelope(id);
    }

    /** 气球蒙皮（各色的，含包着轴的）。 */
    static boolean envelope(String id) {
        return id.startsWith("aeronautics:") && (id.endsWith("_envelope") || id.endsWith("_envelope_encased_shaft"));
    }

    /** 收端旁边接着的执行件。 */
    static boolean actuator(String id) {
        return POWER.contains(id) || LIFT.contains(id) || STEER.contains(id) || RELAYS.contains(id);
    }

    /** 一个输入推出来的角色与依据。 */
    record Role(String role, String basis) {}

    /**
     * 一个输入的角色：收端旁边接着的执行件都是同一类就是那一类，接了几类、只接了离合器换向器、什么都没接都说未定。
     *
     * @param receivers 结构上收这个频率的收端有几个
     * @param drives    这些收端旁边的执行件（可重复）
     */
    static Role role(int receivers, Collection<String> drives) {
        if (receivers == 0) return new Role(ROLE_UNKNOWN, "结构上没有收这个频率的收端（也许遥控的是别处）");
        Set<String> roles = new LinkedHashSet<>();
        for (String drive : drives) {
            if (POWER.contains(drive)) roles.add(ROLE_POWER);
            else if (LIFT.contains(drive)) roles.add(ROLE_LIFT);
            else if (STEER.contains(drive)) roles.add(ROLE_STEER);
        }
        String named = names(drives);
        if (roles.size() == 1) {
            String role = roles.iterator().next();
            return new Role(role, "收端旁边接着" + named + (role.equals(ROLE_STEER) ? "；左右要试按才知道" : ""));
        }
        if (roles.size() > 1) return new Role(ROLE_UNKNOWN, "一个输入接了几样：" + named);
        if (!drives.isEmpty()) return new Role(ROLE_UNKNOWN, "收端旁边接着" + named + "，后面还连着什么要试按才知道");
        return new Role(ROLE_UNKNOWN, "收端旁边没有认得的执行件（可能隔着红石线或齿轮），要试按才知道");
    }

    /**
     * 整辆的种类：没读全说不清；没有座位或控制件就只是结构；有车轮是车，有浮力（燃烧器、蒸汽喷口、气球、浮空水晶）是飞艇，
     * 浮在水上是船，只有螺旋桨是固定翼；都不是说不清。
     */
    static String kind(boolean complete, int seats, Collection<String> parts, boolean onWater) {
        if (!complete) return "unknown";
        boolean controls = parts.stream().anyMatch(CONTROLS::contains);
        if (seats == 0 || !controls) return "structure";
        if (parts.contains(WHEEL)) return "car";
        if (parts.stream().anyMatch(id -> LIFT.contains(id) || FLOAT.contains(id) || envelope(id))) return "airship";
        if (onWater) return "boat";
        if (parts.stream().anyMatch(POWER::contains)) return "plane";
        return "unknown";
    }

    /** 执行件的中文名，按出现次数归并：螺旋桨轴承×2、扭力弹簧。 */
    static String names(Collection<String> drives) {
        Map<String, Long> counted = new LinkedHashMap<>();
        for (String drive : drives) counted.merge(drive, 1L, Long::sum);
        return String.join("、", counted.entrySet().stream()
                .map(entry -> NAMES.getOrDefault(entry.getKey(), entry.getKey())
                        + (entry.getValue() > 1 ? "×" + entry.getValue() : ""))
                .toList());
    }

    /** 执行件按出现次数归并的 ID 列表：给结果用，ID×次数。 */
    static List<String> grouped(Collection<String> drives) {
        Map<String, Long> counted = new LinkedHashMap<>();
        for (String drive : drives) counted.merge(drive, 1L, Long::sum);
        return counted.entrySet().stream()
                .map(entry -> entry.getKey() + (entry.getValue() > 1 ? "×" + entry.getValue() : "")).toList();
    }
}
