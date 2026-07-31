// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** Mutable, client-thread-owned receipt for one native action attempt. */
public final class NativeActionReceipt {
    public enum Kind { BREAK_BLOCK, USE_BLOCK, USE_ITEM, RELEASE_ITEM, SELECT_HOTBAR, CREATIVE_SET_SLOT, MOD_PROTOCOL, ATTACK_ENTITY, INTERACT_ENTITY }
    public enum Status { PENDING, CONFIRMED_APPLIED, CONFIRMED_NOT_APPLIED, CANCELLED, DIVERGED, UNCERTAIN }

    private final UUID id = UUID.randomUUID();
    private final Kind kind;
    private final long bodyEpoch;
    private final long controlRevision;
    private final long submittedTick;
    private final long deadlineTick;
    private final int stableTicksRequired;
    private final NativeConfirmation confirmation;
    private final BlockPos breakTarget;
    private final Direction breakFace;
    private Status status = Status.PENDING;
    private int stableTicks;
    private long lastStableTickRevision = Long.MIN_VALUE;
    private long lastNativeTick;
    private String detail = "awaiting authoritative client facts";

    NativeActionReceipt(
            Kind kind,
            LocalPlayerContext context,
            int timeoutTicks,
            int stableTicksRequired,
            NativeConfirmation confirmation,
            BlockPos breakTarget,
            Direction breakFace) {
        if (timeoutTicks < 1) throw new IllegalArgumentException("timeoutTicks must be positive");
        if (stableTicksRequired < 1) throw new IllegalArgumentException("stableTicksRequired must be positive");
        this.kind = kind;
        this.bodyEpoch = context.bodyEpoch();
        this.controlRevision = context.controlRevision();
        this.submittedTick = context.tickRevision();
        this.deadlineTick = Math.addExact(submittedTick, timeoutTicks);
        this.stableTicksRequired = stableTicksRequired;
        this.confirmation = confirmation;
        this.breakTarget = breakTarget == null ? null : breakTarget.immutable();
        this.breakFace = breakFace;
        this.lastNativeTick = submittedTick;
    }

    public UUID id() { return id; }
    public Kind kind() { return kind; }
    public Status status() { return status; }
    public long bodyEpoch() { return bodyEpoch; }
    public long controlRevision() { return controlRevision; }
    public long submittedTick() { return submittedTick; }
    public long deadlineTick() { return deadlineTick; }
    public String detail() { return detail; }
    public boolean terminal() { return status != Status.PENDING; }

    NativeConfirmation confirmation() { return confirmation; }
    BlockPos breakTarget() { return breakTarget; }
    Direction breakFace() { return breakFace; }
    long lastNativeTick() { return lastNativeTick; }
    void nativeAdvanced(long tick) { lastNativeTick = tick; }
    void resetStable(long tickRevision) {
        if (lastStableTickRevision == tickRevision) return;
        lastStableTickRevision = tickRevision;
        stableTicks = 0;
    }
    boolean countStable(long tickRevision) {
        if (lastStableTickRevision != tickRevision) {
            lastStableTickRevision = tickRevision;
            stableTicks++;
        }
        return stableTicks >= stableTicksRequired;
    }
    void finish(Status status, String detail) {
        if (terminal()) return;
        this.status = status;
        this.detail = detail;
    }
}
