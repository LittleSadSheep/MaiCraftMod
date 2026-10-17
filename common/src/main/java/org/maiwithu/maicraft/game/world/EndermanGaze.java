// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

import java.util.List;

import net.minecraft.world.phys.Vec3;

/**
 * 末影人对视：原版末影人被玩家盯着眼睛看就发怒。纯函数，全仓只有这一份。
 *
 * <p>原版的判定：64 格内（末影人的跟随距离），玩家视线方向与"玩家眼睛到末影人眼睛"方向的夹角余弦大于
 * {@code 1 − 0.025 ÷ 距离}，中间没有方块挡着，玩家头上没戴雕刻南瓜。离得越远容差越小，二十格外差两三度就不算对视；
 * 所以平常走路镜头往下压一点就碰不到，危险的是平视、抬头瞄准时正好有一只站在那条线上。
 */
public final class EndermanGaze {

    /** 末影人在这么远内（格）才会因为被盯着而发怒。 */
    public static final double RANGE = 64.0;
    /** 原版对视容差：夹角余弦要大于 1 − 它 ÷ 距离。 */
    static final double TOLERANCE = 0.025;
    /** 镜头往下压时依次试的俯角（度，往下为正）：先看前方地面，不行再看近处，再不行看脚下。 */
    static final float[] LOOK_DOWN_PITCHES = {8f, 35f, 70f};

    private EndermanGaze() {}

    /** 一只末影人：眼睛在哪、我看不看得见它的眼睛（中间有没有方块挡着）。 */
    public record Eyes(double x, double y, double z, boolean inSight) {}

    /**
     * 这一眼会不会惹毛这只末影人。
     *
     * @param eye  我的眼睛
     * @param look 我的视线方向（不必是单位向量）
     */
    public static boolean provokes(Vec3 eye, Vec3 look, Eyes enderman, boolean wearingPumpkin) {
        if (wearingPumpkin || !enderman.inSight()) return false;
        Vec3 toEyes = new Vec3(enderman.x() - eye.x, enderman.y() - eye.y, enderman.z() - eye.z);
        double distance = toEyes.length();
        if (distance > RANGE || distance < 1.0e-6 || look.lengthSqr() < 1.0e-12) return false;
        double cosine = look.normalize().dot(toEyes.scale(1.0 / distance));
        return cosine > 1.0 - TOLERANCE / distance;
    }

    /** 这一眼会不会惹毛其中任何一只。 */
    public static boolean provokesAny(Vec3 eye, Vec3 look, List<Eyes> endermen, boolean wearingPumpkin) {
        for (Eyes enderman : endermen) {
            if (provokes(eye, look, enderman, wearingPumpkin)) return true;
        }
        return false;
    }

    /** 原版的视线方向：偏航角与俯仰角（度，俯仰往下为正）换成方向。 */
    public static Vec3 lookVector(float yaw, float pitch) {
        double yawRadians = Math.toRadians(yaw);
        double pitchRadians = Math.toRadians(pitch);
        double flat = Math.cos(pitchRadians);
        return new Vec3(-Math.sin(yawRadians) * flat, -Math.sin(pitchRadians), Math.cos(yawRadians) * flat);
    }

    /**
     * 不惹毛末影人的俯仰角：想看的方向不对视就照看；对视了就把镜头往下压，依次试看前方地面、近处、脚下，
     * 挑第一个谁都不对视的（都对视时给最低的那个）。不动偏航角，走的方向不变。
     *
     * @param wantedPitch 本来想看的俯仰角（度，往下为正）
     */
    public static float avertedPitch(Vec3 eye, float yaw, float wantedPitch, List<Eyes> endermen, boolean wearingPumpkin) {
        if (!provokesAny(eye, lookVector(yaw, wantedPitch), endermen, wearingPumpkin)) return wantedPitch;
        float lowest = wantedPitch;
        for (float pitch : LOOK_DOWN_PITCHES) {
            if (pitch <= wantedPitch) continue;
            lowest = pitch;
            if (!provokesAny(eye, lookVector(yaw, pitch), endermen, wearingPumpkin)) return pitch;
        }
        return lowest;
    }
}
