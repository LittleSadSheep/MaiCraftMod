package org.maiwithu.maicraft.core.pathing.goal;

import net.minecraft.world.phys.Vec3;

/** A direction anchored when intent starts; camera motion never changes the requested region. */
public record RegionalGoal(Vec3 origin, Vec3 direction, double radius) {
    public RegionalGoal {
        if (origin == null || direction == null || !Double.isFinite(radius) || radius < 8 || radius > 128)
            throw new IllegalArgumentException("regional travel radius must be 8..128 blocks");
    }
    public static Vec3 direction(String name, float yaw) {
        double angle = Math.toRadians(yaw);
        Vec3 forward = new Vec3(-Math.sin(angle),0,Math.cos(angle));
        return switch (name) {
            case "down" -> new Vec3(0,-1,0);
            case "up" -> new Vec3(0,1,0);
            case "forward" -> forward;
            case "backward" -> forward.scale(-1);
            case "left" -> new Vec3(forward.z,0,-forward.x);
            case "right" -> new Vec3(-forward.z,0,forward.x);
            case "north" -> new Vec3(0,0,-1);
            case "south" -> new Vec3(0,0,1);
            case "west" -> new Vec3(-1,0,0);
            case "east" -> new Vec3(1,0,0);
            default -> throw new IllegalArgumentException("unknown regional direction: " + name);
        };
    }
    public boolean contains(Vec3 point) { return point.distanceToSqr(origin) <= radius*radius; }
    public double progress(Vec3 point) { return point.subtract(origin).dot(direction); }
    public boolean matches(Vec3 surface) { return contains(surface) && progress(surface) >= 3; }
}
