// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import java.util.UUID;

/** 一次菜单操作的等待记录：保存原菜单编号和版本、期望结果，以及何时算等待超时。 */
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
        // 没收到新版本但画面已像成功时，从首次匹配开始计时；是否允许靠这种等待推断成功，由菜单端口决定。
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
        // 已结束的结果不会在这里被后来的槽位回滚改写，因此不能过早给出确定结论。
        if (terminal()) return;
        this.status = status;
        this.detail = detail;
    }
}
