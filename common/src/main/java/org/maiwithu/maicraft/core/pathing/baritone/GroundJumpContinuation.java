package org.maiwithu.maicraft.core.pathing.baritone;

import baritone.api.pathing.movement.IMovement;
import baritone.api.utils.BetterBlockPos;
import java.util.List;
import java.util.Set;

/**
 * 一次已核对的平地跑跳期间，暂时让指定的走路步骤仍按起跳地面高度判断进度，避免刚离地就误认为偏离路线。
 */
public final class GroundJumpContinuation {
    private Integer floor;
    private double takeoffY;
    private boolean airborne;
    private int ticks;
    private Set<IMovement> verified = Set.of();

    public void launch(int floor, double takeoffY, List<IMovement> runway) {
        this.floor = floor; this.takeoffY = takeoffY; airborne = false; ticks = 0;
        verified = Set.copyOf(runway);
    }

    public void observe(boolean grounded, double y) {
        if (floor == null) return;
        if (!grounded) airborne = true;
        // 落回地面、掉到起跳高度以下，或等待超时，就结束这次临时高度；没有成功起跳最多等三次更新。
        if (grounded && airborne || y < takeoffY - 0.1 || ++ticks > (airborne ? 40 : 3)) floor = null;
    }

    public boolean controls(IMovement movement) {
        return floor != null && movement != null && verified.contains(movement)
                && TravelRunway.accepts(movement)
                && movement.getSrc().getY() == floor && movement.getDest().getY() == floor;
    }

    // 只有完整飞行走廊已通过检查的移动，才能继承此临时地面支撑。
    public BetterBlockPos feet(IMovement movement, BetterBlockPos actual) {
        if (!controls(movement)) return actual;
        return new BetterBlockPos(actual.x, floor, actual.z);
    }
}
