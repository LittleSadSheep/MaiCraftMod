// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.BodyControlPort;

/** One placement approach and at most one observed jump; reports physical evidence on failure. */
final class CreateMechanicalPlacementAttempt {
    private boolean jumpRequested, airborne;
    private int jumpTicks, jumpCommands, centerTicks, exactHits;
    private double firstEyeY = Double.NaN, maximumEyeY = -Double.MAX_VALUE;
    private Map<String, Object> latest = Map.of();

    void observe(Vec3 feet, Vec3 eye, Vec3 velocity, boolean onGround, boolean inputJump, boolean sneak,
            String pose, boolean screenOpen, BlockPos stand, BlockPos support, String supportFace,
            String actualHit, String actualFace, boolean exactHit, double pitchError) {
        if (Double.isNaN(firstEyeY)) firstEyeY = eye.y;
        maximumEyeY = Math.max(maximumEyeY, eye.y);
        if (jumpRequested) { jumpTicks++; airborne |= !onGround; }
        if (exactHit) exactHits++;
        var state = new LinkedHashMap<String, Object>();
        state.put("feet", vector(feet)); state.put("eye", vector(eye)); state.put("velocity", vector(velocity));
        state.put("on_ground", onGround); state.put("input_jump", inputJump); state.put("sneaking", sneak);
        state.put("pose", pose); state.put("screen_open", screenOpen); state.put("planned_stand", stand.toShortString());
        state.put("support", support.toShortString()); state.put("support_face", supportFace);
        state.put("actual_hit", actualHit); state.put("actual_face", actualFace); state.put("pitch_error", pitchError);
        latest = Map.copyOf(state);
    }
    void requestJump() { jumpRequested = true; jumpTicks = 0; }
    boolean jumpRequested() { return jumpRequested; }
    boolean airborne() { return airborne; }
    boolean holdJump() { return jumpRequested && !airborne && jumpTicks < 12; }
    void jumpCommand() { jumpCommands++; }
    boolean expired() { return jumpRequested && jumpTicks > 30; }
    boolean centerExpired() { return ++centerTicks > 100; }
    static boolean centered(Vec3 feet, Vec3 velocity, BlockPos stand) {
        Vec3 target = Vec3.atBottomCenterOf(stand);
        return Math.abs(feet.y - target.y) < .1 && Math.hypot(feet.x - target.x, feet.z - target.z) <= .12
                && Math.hypot(velocity.x, velocity.z) <= .025;
    }
    static BodyControlPort.Movement centerMovement(Vec3 feet, BlockPos stand, float yaw) {
        Vec3 delta = Vec3.atBottomCenterOf(stand).subtract(feet); double distance = Math.hypot(delta.x, delta.z);
        if (distance <= .12) return BodyControlPort.Movement.STOPPED;
        double scale = Math.min(.3, distance * 2) / distance, angle = Math.toRadians(yaw);
        double x = delta.x * scale, z = delta.z * scale;
        return new BodyControlPort.Movement((float) (-x * Math.sin(angle) + z * Math.cos(angle)),
                (float) (x * Math.cos(angle) + z * Math.sin(angle)), false, false, false);
    }
    Map<String, Object> report() {
        var result = new LinkedHashMap<>(latest);
        result.put("jump_requested", jumpRequested); result.put("airborne_observed", airborne);
        result.put("jump_ticks", jumpTicks); result.put("jump_command_ticks", jumpCommands); result.put("centering_ticks", centerTicks);
        result.put("exact_support_hit_ticks", exactHits);
        if (!Double.isNaN(firstEyeY)) { result.put("first_eye_y", firstEyeY); result.put("maximum_eye_y", maximumEyeY); }
        return result;
    }
    private static List<Double> vector(Vec3 value) { return List.of(value.x, value.y, value.z); }
}
