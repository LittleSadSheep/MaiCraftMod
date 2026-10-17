// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.menu;

import java.util.UUID;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.maiwithu.maicraft.game.player.PlayerContext;

/** 一次菜单操作的等待记录：保存原菜单编号和版本、期望结果，以及何时算等待超时。 */
public final class PendingMenuAction {
    public enum Kind { CLICK, SWAP_TO_HOTBAR, PLACE_RECIPE, CLOSE, BUTTON, MOD_ACTION }
    public enum Status { PENDING, CONFIRMED_APPLIED, CONFIRMED_NOT_APPLIED, DIVERGED, UNCERTAIN }

    private static final Logger LOG = LoggerFactory.getLogger(PendingMenuAction.class);

    private final UUID id = UUID.randomUUID();
    private final Kind kind;
    /** 提交时的角色对象；换了玩家对象（重生、换世界）后旧提交的结果不再可信。 */
    private final LocalPlayer submittedPlayer;
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

    /** 只留角色对象本身做身份标记；重生、换世界后旧提交的结果不再可信。 */
    PendingMenuAction(Kind kind, PlayerContext context, int containerId, int beforeStateId,
                      int slot, int timeoutTicks, boolean allowContainerChange, MenuConfirmation confirmation) {
        if (timeoutTicks < 1) throw new IllegalArgumentException("timeoutTicks must be positive");
        this.kind = kind;
        this.submittedPlayer = context.localPlayer();
        this.submittedTick = context.clientTick();
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
    public long submittedTick() { return submittedTick; }
    public long deadlineTick() { return deadlineTick; }
    public int containerId() { return containerId; }
    public int beforeStateId() { return beforeStateId; }
    public String detail() { return detail; }
    public boolean terminal() { return status != Status.PENDING; }

    boolean fromSamePlayer(PlayerContext context) {
        return submittedPlayer == context.localPlayer();
    }
    boolean allowContainerChange() { return allowContainerChange; }
    static PendingMenuAction forMenu(Kind kind, PlayerContext context, AbstractContainerMenu menu,
                                     int slot, int timeoutTicks, boolean allowContainerChange,
                                     MenuConfirmation confirmation) {
        // 保存真实菜单对象，编号再次使用时也不能把新菜单的变化当成旧点击的确认。
        PendingMenuAction pending = new PendingMenuAction(kind, context, menu.containerId, menu.getStateId(),
                slot, timeoutTicks, allowContainerChange, confirmation);
        pending.submittedMenu = menu;
        return pending;
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
        // 没收到新版本但画面已像成功时，从首次匹配开始计时；是否允许靠这种等待推断成功，由菜单入口决定。
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
        // finish 是所有菜单操作终态的唯一收口：审计日志挂在这里才能覆盖每一条提交路径。
        if (status == Status.UNCERTAIN || status == Status.DIVERGED) {
            LOG.warn("menu {} slot={} -> {} (budget {}t, submitted @t{}, containerId={}): {}",
                    kind, slot, status, deadlineTick - submittedTick, submittedTick, containerId, detail);
        } else {
            LOG.debug("menu {} slot={} -> {} (budget {}t): {}",
                    kind, slot, status, deadlineTick - submittedTick, detail);
        }
    }
}
