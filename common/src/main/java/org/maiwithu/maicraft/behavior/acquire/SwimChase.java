// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import org.maiwithu.maicraft.behavior.navigation.util.SwimAirBudget;

/**
 * 水里追猎物的判断：人和猎物泡在同一片水里时，这一刻镜头朝哪、按哪些键游过去，氧气快不够了先浮上去换满再接着追。
 * 本类只算，不读游戏也不按键；一次追一只用一个实例，记着这一口气是不是正在上去换。
 *
 * <p>游法照原版：眼睛没进水（水面上漂着、浅水里站着）时前进键只管水平挪，往下沉按潜行、往上按跳；眼睛进了水再按着疾跑，
 * 原版切成游泳姿势，顺着镜头的方向游，所以镜头一直盯着猎物的身体中心，它往下钻就跟着往下扎。站在水底时不按潜行，
 * 免得蹲下来走得更慢。氧气按要上浮多深、最近的耗气速度预留（与水下路线共用一份估算），剩下的不够浮上去就先上去，
 * 头出了水还踩着水等氧气补满，满了再回来追。
 */
public final class SwimChase {

    /** 水平离它这么近、眼睛又没进水时不再按前进：水面上只能水平挪，再按就冲过头，改成只往下沉或往上浮。 */
    private static final double CLOSE_HORIZONTAL = 0.6;
    /** 它比眼睛高出这么多才按跳往上游。 */
    private static final double RISE_ABOVE_EYES = 0.5;
    /** 它比脚底低这么多才按潜行往下沉。 */
    private static final double SINK_BELOW_FEET = 0.2;
    /** 浮上去换气时镜头抬多高：看着水面往上游。 */
    private static final float SURFACING_PITCH = -60.0f;

    /**
     * 这一刻的处境。位移都从眼睛量到猎物身体中心。
     *
     * @param belowFeet         猎物身体中心比脚底低多少格（低为正）
     * @param eyesInWater       眼睛在不在水里
     * @param onGround          是不是踩在水底或岸上
     * @param depthBelowSurface 眼睛离头顶水面还有多深（格）；头顶没找到水面时给一个大数
     */
    public record Scene(long tick, double dx, double dy, double dz, double belowFeet, boolean eyesInWater,
            boolean onGround, int air, int maxAir, double depthBelowSurface) {
    }

    /**
     * 这一刻怎么动：镜头的偏航与俯仰（俯仰向下为正），前进键按多少（0～1，按镜头朝向），跳、潜行、疾跑按不按，
     * 以及这一刻是不是在浮上去换气。
     */
    public record Stroke(float yaw, float pitch, float forward, boolean jump, boolean sneak, boolean sprint,
            boolean surfacing) {
    }

    private final SwimAirBudget airBudget = new SwimAirBudget();
    private boolean surfacing;

    /** 现在是不是在浮上去换气（或头已出水、踩着水等氧气补满）。 */
    public boolean surfacing() {
        return surfacing;
    }

    /**
     * 按这一刻的处境给出动作。氧气剩下的不够从这个深度浮上去（另留三秒反应）就转去换气；换气时头出了水、氧气满了才回来追。
     *
     * @param currentYaw 此刻镜头的偏航；它正好在头顶或脚下、水平方向说不清时沿用
     */
    public Stroke next(Scene scene, float currentYaw) {
        airBudget.observe(scene.tick(), scene.air(), scene.eyesInWater());
        int reserve = SwimAirBudget.requiredAirForAscent(scene.depthBelowSurface(), airBudget.airPerTick());
        if (!surfacing && scene.eyesInWater() && scene.air() <= reserve) {
            surfacing = true;
        } else if (surfacing && !scene.eyesInWater() && scene.air() >= scene.maxAir()) {
            surfacing = false;
        }
        if (surfacing) {
            // 先上去换气：抬头按跳往上游；头出了水还按着跳踩住水面，不沉下去，等氧气补满。
            return new Stroke(currentYaw, SURFACING_PITCH, 0.0f, true, false, false, true);
        }
        double horizontal = Math.hypot(scene.dx(), scene.dz());
        float yaw = horizontal < 1.0e-3 ? currentYaw
                : (float) Math.toDegrees(Math.atan2(scene.dz(), scene.dx())) - 90.0f;
        float pitch = (float) -Math.toDegrees(Math.atan2(scene.dy(), Math.max(horizontal, 1.0e-3)));
        // 眼睛进了水就一直往前游（游泳姿势顺着镜头走，正上正下也游得过去）；水面上离它水平够近就不再往前冲。
        boolean ahead = scene.eyesInWater() || horizontal > CLOSE_HORIZONTAL;
        boolean rise = scene.dy() > RISE_ABOVE_EYES;
        boolean sink = !rise && !scene.onGround() && scene.belowFeet() > SINK_BELOW_FEET;
        return new Stroke(yaw, pitch, ahead ? 1.0f : 0.0f, rise, sink, ahead && scene.eyesInWater(), false);
    }
}
