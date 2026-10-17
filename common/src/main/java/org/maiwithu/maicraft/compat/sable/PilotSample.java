// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.sable;

import net.minecraft.world.phys.Vec3;

/**
 * 驾驶时每刻看一次载具：飞控与开车只按这些事实调键，不改速度、不施力。
 *
 * @param tick     这一刻（游戏刻）
 * @param position 船底中心在世界里的位置（高度按船底算，离地间隙就是它减地面）
 * @param velocity 每秒挪几格（世界坐标）
 * @param heading  车头此刻朝哪，度：和玩家朝向一样算（0 南、90 西、180 北、270 东）
 * @param tilt     斜了多少度（俯仰、横滚里大的那个）
 * @param turnRate 车头每秒转几度（正的是往右转）
 * @param tiltRate 斜角每秒变几度
 * @param contact  接地：grounded / airborne / unknown
 */
record PilotSample(long tick, Vec3 position, Vec3 velocity, double heading, double tilt, double turnRate,
                   double tiltRate, String contact) {

    /** 水平速度（每秒几格）。 */
    double flatSpeed() {
        return Math.hypot(velocity.x, velocity.z);
    }

    boolean grounded() {
        return VehicleAttitude.GROUNDED.equals(contact);
    }

    /** 从这里朝某一点的朝向，度（玩家朝向的算法）。 */
    double headingTo(Vec3 point) {
        return Math.toDegrees(Math.atan2(-(point.x - position.x), point.z - position.z));
    }

    /** 到某一点的水平距离。 */
    double flatDistance(Vec3 point) {
        return Math.hypot(point.x - position.x, point.z - position.z);
    }

    /** 两个朝向之差，落在 -180～180：正的是要往右转。 */
    static double turn(double from, double to) {
        double difference = (to - from) % 360;
        if (difference > 180) difference -= 360;
        if (difference < -180) difference += 360;
        return difference;
    }
}
