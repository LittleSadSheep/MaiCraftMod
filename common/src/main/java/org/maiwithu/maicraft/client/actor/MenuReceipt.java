// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import java.util.UUID;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.maiwithu.maicraft.core.Constants;

/** 一次菜单操作的等待记录：保存原菜单编号和版本、期望结果，以及何时算等待超时。 */
public final class MenuReceipt {
    public enum Kind { CLICK, SWAP_TO_HOTBAR, PLACE_RECIPE, CLOSE, BUTTON }
    public enum Status { PENDING, CONFIRMED_APPLIED, CONFIRMED_NOT_APPLIED, DIVERGED, UNCERTAIN }

    /** 最近一次到达 UNCERTAIN 的菜单回执快照，供调试面板与人工核验对账；消费可能已发生，只保留最近一次。 */
    public record UncertainSnapshot(Kind kind, int slot, String menuClass,
                                    long submittedTick, int timeoutTicks, String detail) {}
    private static volatile UncertainSnapshot lastUncertain;

    private final UUID id = UUID.randomUUID();
    private final Kind kind;
    private final long bodyEpoch;
    private final long controlRevision;
    private final long submittedTick;
    private final long deadlineTick;
    private final int containerId;
    private final int beforeStateId;
    private final int slot;
    private final boolean allowContainerChange;
    private final MenuConfirmation confirmation;
    private AbstractContainerMenu submittedMenu;
    private int synchronizationStateId;
    private Status status = Status.PENDING;
    private String detail = "awaiting the server-synchronized menu state";
    /** 记录首次满足精确后置条件的游戏刻，供没有菜单版本更新时等待稳定确认。 */
    private long unacknowledgedAppliedSince = Long.MIN_VALUE;

    MenuReceipt(Kind kind, LocalPlayerContext context, int containerId, int beforeStateId,
                int slot, int timeoutTicks, boolean allowContainerChange, MenuConfirmation confirmation) {
        if (timeoutTicks < 1) throw new IllegalArgumentException("timeoutTicks must be positive");
        this.kind = kind;
        this.bodyEpoch = context.bodyEpoch();
        this.controlRevision = context.controlRevision();
        this.submittedTick = context.tickRevision();
        this.deadlineTick = Math.addExact(submittedTick, timeoutTicks);
        this.containerId = containerId;
        this.beforeStateId = beforeStateId;
        this.slot = slot;
        this.synchronizationStateId = beforeStateId;
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
    static MenuReceipt forMenu(Kind kind, LocalPlayerContext context, AbstractContainerMenu menu,
                               int slot, int timeoutTicks, boolean allowContainerChange,
                               MenuConfirmation confirmation) {
        // 生产端口保存真实菜单对象，编号再次使用时也不能把新菜单的变化当成旧点击的确认。
        MenuReceipt receipt = new MenuReceipt(kind, context, menu.containerId, menu.getStateId(),
                slot, timeoutTicks, allowContainerChange, confirmation);
        receipt.submittedMenu = menu;
        return receipt;
    }
    boolean matchesSubmittedMenu(AbstractContainerMenu menu) {
        return menu.containerId == containerId && (submittedMenu == null || submittedMenu == menu);
    }
    MenuConfirmation confirmation() { return confirmation; }
    int synchronizationStateId() { return synchronizationStateId; }
    void awaitButtonSynchronization(int clientStateId) {
        // 本地附魔报价校验不是服务端执行结果；按钮只接受校验完成后到来的新菜单版本。
        synchronizationStateId = clientStateId;
    }
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
    public static UncertainSnapshot lastUncertain() { return lastUncertain; }

    void finish(Status status, String detail) {
        // 已结束的结果不会在这里被后来的槽位回滚改写，因此不能过早给出确定结论。
        if (terminal()) return;
        this.status = status;
        this.detail = detail;
        // finish 是所有回执的唯一终态收口：审计日志与 UNCERTAIN 快照挂在这里才能覆盖每一条提交路径。
        if (status == Status.UNCERTAIN) {
            lastUncertain = new UncertainSnapshot(kind, slot,
                    submittedMenu == null ? null : submittedMenu.getClass().getSimpleName(),
                    submittedTick, (int) (deadlineTick - submittedTick), detail);
        }
        if (status == Status.UNCERTAIN || status == Status.DIVERGED) {
            Constants.LOG.warn("menu {} slot={} -> {} (budget {}t, submitted @t{}, containerId={}): {}",
                    kind, slot, status, deadlineTick - submittedTick, submittedTick, containerId, detail);
        } else {
            Constants.LOG.debug("menu {} slot={} -> {} (budget {}t): {}",
                    kind, slot, status, deadlineTick - submittedTick, detail);
        }
    }
}
