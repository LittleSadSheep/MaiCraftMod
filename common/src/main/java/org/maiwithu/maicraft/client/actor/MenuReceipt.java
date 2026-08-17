// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import java.util.UUID;

/** Receipt for exactly one menu click, recipe placement, or close request. */
public final class MenuReceipt {
    public enum Kind { CLICK, SWAP_TO_HOTBAR, PLACE_RECIPE, CLOSE }
    public enum Status { PENDING, CONFIRMED_APPLIED, CONFIRMED_NOT_APPLIED, DIVERGED, UNCERTAIN }

    private final UUID id = UUID.randomUUID();
    private final Kind kind;
    private final long bodyEpoch;
    private final long controlRevision;
    private final long submittedTick;
    private final long deadlineTick;
    private final int containerId;
    private final int beforeStateId;
    private final boolean allowContainerChange;
    private final MenuConfirmation confirmation;
    private Status status = Status.PENDING;
    private String detail = "awaiting the server-synchronized menu state";
    /** First tick of an exact positive postcondition not accompanied by menu revision evidence. */
    private long unacknowledgedAppliedSince = Long.MIN_VALUE;

    MenuReceipt(Kind kind, LocalPlayerContext context, int containerId, int beforeStateId,
                int timeoutTicks, boolean allowContainerChange, MenuConfirmation confirmation) {
        if (timeoutTicks < 1) throw new IllegalArgumentException("timeoutTicks must be positive");
        this.kind = kind;
        this.bodyEpoch = context.bodyEpoch();
        this.controlRevision = context.controlRevision();
        this.submittedTick = context.tickRevision();
        this.deadlineTick = Math.addExact(submittedTick, timeoutTicks);
        this.containerId = containerId;
        this.beforeStateId = beforeStateId;
        this.allowContainerChange = allowContainerChange;
        this.confirmation = confirmation;
    }

    public UUID id() { return id; }
    public Kind kind() { return kind; }
    public Status status() { return status; }
    public long bodyEpoch() { return bodyEpoch; }
    public long controlRevision() { return controlRevision; }
    public long submittedTick() { return submittedTick; }
    public long deadlineTick() { return deadlineTick; }
    public int containerId() { return containerId; }
    public int beforeStateId() { return beforeStateId; }
    public String detail() { return detail; }
    public boolean terminal() { return status != Status.PENDING; }

    boolean allowContainerChange() { return allowContainerChange; }
    MenuConfirmation confirmation() { return confirmation; }
    boolean appliedStableWithoutRevision(long tickRevision, int requiredStableTicks) {
        if (unacknowledgedAppliedSince == Long.MIN_VALUE) {
            unacknowledgedAppliedSince = tickRevision;
            return false;
        }
        return tickRevision - unacknowledgedAppliedSince >= requiredStableTicks;
    }
    void clearUnacknowledgedApplied() {
        unacknowledgedAppliedSince = Long.MIN_VALUE;
    }
    void finish(Status status, String detail) {
        if (terminal()) return;
        this.status = status;
        this.detail = detail;
    }
}
