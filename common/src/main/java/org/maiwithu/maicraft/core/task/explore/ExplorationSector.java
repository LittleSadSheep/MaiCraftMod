package org.maiwithu.maicraft.core.task.explore;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 搜索方向在出发时固定；候选地点按扇区筛选，角色实际走的路线仍可绕障碍。 */
public record ExplorationSector(String direction, int angleDegrees, int minDistance) {
    public static final Set<String> DIRECTIONS = Set.of("north", "northeast", "east", "southeast",
            "south", "southwest", "west", "northwest", "forward", "backward", "left", "right");

    public ExplorationSector {
        direction = direction == null ? null : direction.strip().toLowerCase(Locale.ROOT);
        if (direction != null && !DIRECTIONS.contains(direction))
            throw new IllegalArgumentException("exploration direction must be horizontal: " + DIRECTIONS);
        if (angleDegrees < 1 || angleDegrees > 360)
            throw new IllegalArgumentException("angle_degrees must be the full sector width in 1..360");
        if (minDistance < 0) throw new IllegalArgumentException("min_distance must be nonnegative");
        if (direction == null && angleDegrees != 360)
            throw new IllegalArgumentException("angle_degrees needs an exploration direction");
    }

    /** 指定方向时默认先离开脚边十六格，避免尚未出发就用所在群系完成跑图。 */
    public static ExplorationSector of(String direction, Integer angle, Integer minimum, int radius) {
        var result = new ExplorationSector(direction, angle == null ? (direction == null ? 360 : 90) : angle,
                minimum == null ? (direction == null ? 0 : 16) : minimum);
        if (result.minDistance > radius) throw new IllegalArgumentException("min_distance exceeds max_distance");
        return result;
    }

    public Area at(double x, double z, float startYaw) {
        double bearing = switch (direction == null ? "all" : direction) {
            case "north" -> 0; case "northeast" -> 45; case "east" -> 90;
            case "southeast" -> 135; case "south" -> 180; case "southwest" -> 225;
            case "west" -> 270; case "northwest" -> 315;
            case "forward" -> startYaw + 180; case "backward" -> startYaw;
            case "left" -> startYaw + 90; case "right" -> startYaw + 270;
            default -> 0;
        };
        return new Area(x, z, bearing, this);
    }

    public record Area(double originX, double originZ, double bearing, ExplorationSector request) {
        /** 航点只受扇区约束；到达最终候选还必须满足用户要求的最小搜索距离。 */
        public boolean contains(double x, double z) {
            if (request.direction == null || request.angleDegrees == 360) return true;
            double dx = x - originX, dz = z - originZ;
            if (Math.hypot(dx, dz) < 0.000001) return false;
            double actual = Math.toDegrees(Math.atan2(dx, -dz));
            double difference = Math.IEEEremainder(actual - bearing, 360);
            return Math.abs(difference) <= request.angleDegrees / 2.0 + 0.000001;
        }

        public boolean accepts(double x, double z) {
            return Math.hypot(x - originX, z - originZ) >= request.minDistance && contains(x, z);
        }

        public Map<String, Object> describe() {
            return Map.of("direction", request.direction == null ? "all" : request.direction,
                    "angle_degrees", request.angleDegrees, "min_distance", request.minDistance,
                    "bearing_degrees", (bearing % 360 + 360) % 360);
        }
    }
}
