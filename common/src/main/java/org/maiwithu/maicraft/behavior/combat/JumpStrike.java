// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.combat;

import java.util.function.BooleanSupplier;

/**
 * 跳劈：什么时候起跳、手什么时候先按住不砍、什么时候不跳了站着砍。战斗引擎每刻问一次，记着这一仗跳的几件事。
 *
 * <p>够得着、头顶有空、不是苦力怕（跳起来落不回原地躲不了），充能过半就起跳，往下落时才砍，原版算暴击；
 * 跳不了（水里、梯子上、头顶有方块）就站着砍。起跳前、往上升时手先按住，镜头由引擎压在出手的那一点上。
 *
 * <p>跳了砍不出去不硬跳：落地时攻击条满、准星正落在它身上就站着直接砍（斧子这类慢武器一跳的工夫充不满，
 * 往下落时还没满，落地才满）；连着两跳一刀没砍出去就站着砍一阵；按了跳一直没离地（灵魂沙、蜂蜜块上）也站着砍一阵。
 * 实机 10-15 跳劈跳半天不劈：近战没出手 81 次，"镜头还没转到"44 次。
 */
final class JumpStrike {

    /** 充能到这么多就起跳：落到能砍的时候正好充满，跳劈不比站着砍慢。 */
    static final float JUMP_AT = 0.5f;
    /** 连着这么多刻想跳却没离地：这一阵不跳了，站着砍。 */
    static final int MAX_GROUNDED_JUMPS = 5;
    /** 跳不起来、或连着两跳一刀没砍出去之后，站着砍这么久（刻）。 */
    static final int NO_LEAP_TICKS = 100;
    /** 连着这么多跳一刀都没砍出去：这一阵不跳了，站着砍。 */
    static final int MAX_DRY_LEAPS = 2;
    /** 落地时攻击条满、准星在它身上，站着直接砍：这么多刻里不起跳，砍出去就接着跳劈。 */
    static final int GROUND_STRIKE_TICKS = 10;

    /**
     * 这一刻怎么做。
     *
     * @param leaping   这一刻按跳劈来（起跳或在空中）
     * @param jumpNow   这一刻按跳
     * @param holdSwing 手先按住不砍（还在地上等起跳、往上升）：镜头压在出手点上等着
     */
    record Move(boolean leaping, boolean jumpNow, boolean holdSwing) {}

    /** 连着想跳却没离地的刻数，与这之后不再试跳还剩多少刻。 */
    private int groundedJumps;
    private int noLeapTicks;
    /** 按了跳、还没离地；离地后在这一跳的空中；这一跳里砍出去过没有；连着几跳一刀没砍出去。 */
    private boolean jumpPressed;
    private boolean leapAirborne;
    private boolean swungThisLeap;
    private int dryLeaps;
    /** 落地站着直接砍还剩几刻（这几刻不起跳）。 */
    private int groundStrikeTicks;

    /**
     * 这一刻跳不跳、砍不砍。
     *
     * @param inReach      够得着它
     * @param leapable     它能跳着砍（苦力怕不跳：跳起来落不回原地躲不了）
     * @param strikeReady  落地这一刻问：攻击条满了、准星正落在它身上
     */
    Move decide(BattleMoves.Posture posture, boolean inReach, boolean leapable, BooleanSupplier strikeReady) {
        if (noLeapTicks > 0) noLeapTicks--;
        if (groundStrikeTicks > 0) groundStrikeTicks--;
        if (jumpPressed && !posture.onGround()) {
            // 按了跳、离了地：这一跳开始了，看它落地前砍没砍出去。
            jumpPressed = false;
            leapAirborne = true;
            swungThisLeap = false;
        }
        if (leapAirborne && posture.onGround()) landed(inReach && strikeReady.getAsBoolean());
        boolean leaping = inReach && noLeapTicks == 0 && groundStrikeTicks == 0 && leapable
                && (posture.canJump() || !posture.onGround());
        boolean jumpNow = leaping && posture.onGround() && posture.charge() >= JUMP_AT;
        if (jumpNow) jumpPressed = true;
        groundedJumps = jumpNow ? groundedJumps + 1 : posture.onGround() ? groundedJumps : 0;
        if (groundedJumps > MAX_GROUNDED_JUMPS) {
            // 按了跳却一直没离地：这一阵站着砍，别卡在"等跳起来"上一下都不出手。
            groundedJumps = 0;
            noLeapTicks = NO_LEAP_TICKS;
            leaping = false;
            jumpNow = false;
            jumpPressed = false;
        }
        return new Move(leaping, jumpNow, leaping && (posture.onGround() || !posture.falling()));
    }

    /**
     * 这一刻砍出去了（打中或被拒都算）：这一跳不算白跳；落地站着砍的那一下也算这一跳砍出去了，砍完接着跳劈。
     */
    void swung() {
        if (leapAirborne) swungThisLeap = true;
        if (groundStrikeTicks > 0) dryLeaps = 0;
        groundStrikeTicks = 0;
    }

    // 落了地：这一跳一刀没砍出去，连着两跳都这样就站着砍一阵；攻击条满、准星正在它身上就站着直接砍，不再起跳。
    private void landed(boolean strikeReady) {
        leapAirborne = false;
        if (swungThisLeap) {
            dryLeaps = 0;
        } else if (++dryLeaps >= MAX_DRY_LEAPS) {
            dryLeaps = 0;
            noLeapTicks = NO_LEAP_TICKS;
        }
        if (strikeReady) groundStrikeTicks = GROUND_STRIKE_TICKS;
    }
}
