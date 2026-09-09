package org.maiwithu.maicraft.core.pathing.baritone;

import org.maiwithu.maicraft.core.pathing.util.SwimAirBudget;

/**
 * 记住当前在潜水、正常游泳、上浮还是等气补满，并给出向上、向下、疾跑和抬头的意图；本类不读取地图。
 */
public final class SwimTravelControl {
    enum Phase { OFF, DIVING, CRUISING, SURFACING, REFILL }

    private static final double DEPTH_DEADBAND = 0.12;
    final SwimAirBudget airBudget = new SwimAirBudget();
    private Phase phase = Phase.OFF;
    private double targetY;
    private double feetY;

    void update(boolean inWater, boolean onGround, boolean swimming, boolean eyesWet, double feetY,
                double eyeHeight, double waterSurface, double routeY, boolean deepRoute,
                boolean approachingShore, int air, int maxAir, int ascentReserve) {
        this.feetY = feetY;
        if (!inWater && onGround) {
            phase = Phase.OFF;
            return;
        }
        if (!inWater) {
            // 游泳时短暂跃出水面不算上岸；还没补满气就继续等，不能立刻再潜下去。
            if (phase != Phase.OFF) phase = air < maxAir ? Phase.REFILL : Phase.OFF;
            return;
        }
        if (phase == Phase.SURFACING) {
            if (!eyesWet) phase = air < maxAir ? Phase.REFILL : Phase.OFF;
        } else if (phase == Phase.REFILL) {
            if (air >= maxAir) phase = Phase.OFF;
            else if (eyesWet) phase = Phase.SURFACING;
        } else if (phase != Phase.OFF
                && (air <= ascentReserve || !deepRoute || approachingShore)) {
            phase = Phase.SURFACING;
        } else if (phase == Phase.DIVING && swimming) {
            // 眼睛入水还不够，原版真正切成游泳姿势后才结束主动下潜。
            phase = Phase.CRUISING;
        } else if (phase == Phase.CRUISING && !swimming) {
            phase = Phase.DIVING;
        }
        if (phase == Phase.OFF && deepRoute && !approachingShore && air > ascentReserve) {
            phase = swimming ? Phase.CRUISING : Phase.DIVING;
        }
        // 路线本来要求在深水处到达时，不能为了贴近水面而把游泳目标抬高。
        targetY = Math.min(routeY + 0.1,
                waterSurface - eyeHeight - (phase == Phase.DIVING ? 0.15 : 0.60));
    }

    int verticalIntent() {
        if (phase == Phase.OFF) return 0;
        if (phase == Phase.SURFACING || phase == Phase.REFILL) return 1;
        if (feetY > targetY + DEPTH_DEADBAND) return -1;
        if (feetY < targetY - DEPTH_DEADBAND) return 1;
        return 0;
    }

    float cameraPitch() {
        if (phase == Phase.DIVING) return 28.0F;
        if (phase == Phase.SURFACING || phase == Phase.REFILL) return -24.0F;
        return phase == Phase.CRUISING ? verticalIntent() * -8.0F : 0.0F;
    }

    boolean active() { return phase != Phase.OFF; }
    boolean recovering() { return phase == Phase.SURFACING || phase == Phase.REFILL; }
    boolean sprinting() { return phase == Phase.DIVING || phase == Phase.CRUISING; }
    Phase phase() { return phase; }
    /**
     * 路线换成台阶等非平游动作时停止跟踪深度；已经开始且尚未完成的换气继续保留。
     */
    void releaseRoute(boolean inWater, boolean onGround, int air, int maxAir) {
        if ((!inWater && onGround) || air >= maxAir || !recovering()) phase = Phase.OFF;
    }

    /**
     * 同一玩家、同一世界的路线段共用换气进度；重生或换世界时重新开始，避免继承旧身体的状态。
     */
    public static final class BodyState {
        private Object body;
        private Object world;
        private SwimTravelControl control;

        public SwimTravelControl bind(Object body, Object world) {
            if (control == null || this.body != body || this.world != world) {
                this.body = body;
                this.world = world;
                control = new SwimTravelControl();
            }
            return control;
        }

        public void clear() {
            body = null;
            world = null;
            control = null;
        }
    }
}
