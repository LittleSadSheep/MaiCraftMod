package org.maiwithu.maicraft.core.pathing.baritone;

import baritone.api.pathing.movement.IMovement;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.movement.movements.MovementDiagonal;
import baritone.pathing.movement.movements.MovementTraverse;

/**
 * 一次已核对的平地跑跳期间，暂时让指定的走路步骤仍按起跳地面高度判断进度，避免刚离地就误认为偏离路线。
 */
public final class GroundJumpContinuation {
    private Integer floor;
    private double takeoffY;
    private boolean airborne;
    private int ticks;
    private java.util.Set<IMovement> verified = java.util.Set.of();

    public void launch(int floor, double takeoffY, java.util.List<IMovement> runway) {
        this.floor = floor; this.takeoffY = takeoffY; airborne = false; ticks = 0;
        verified = java.util.Set.copyOf(runway);
    }

    public void observe(boolean grounded, double y) {
        if (floor == null) return;
        if (!grounded) airborne = true;
        // 落回地面、掉到起跳高度以下，或等待超时，就结束这次临时高度；没有成功起跳最多等三次更新。
        if (grounded && airborne || y < takeoffY - 0.1 || ++ticks > (airborne ? 40 : 3)) floor = null;
    }

    // 只对当时核对过、起终点都在同一地面层的直走／斜走步骤生效；其他动作仍使用实际脚的位置。
    public BetterBlockPos feet(IMovement movement, BetterBlockPos actual) {
        if (floor == null || movement == null || !verified.contains(movement)
                || !(movement instanceof MovementTraverse || movement instanceof MovementDiagonal)
                || movement.getSrc().getY() != floor || movement.getDest().getY() != floor) return actual;
        return new BetterBlockPos(actual.x, floor, actual.z);
    }
}
