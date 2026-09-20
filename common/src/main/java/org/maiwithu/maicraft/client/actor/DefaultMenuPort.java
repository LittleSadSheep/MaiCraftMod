// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/** 实际操作玩家菜单：一次点一个槽位、报价按钮或配方，等结果明确后再接受下一次；也负责任务结束时关好界面。 */
public final class DefaultMenuPort implements MenuPort {
    String diagnosticState() {
        return active == null ? "none" : active.kind() + ":" + active.status()
                + " deadline=" + active.deadlineTick() + " closing=" + (closingMenu != null);
    }

    private MenuReceipt active;
    private final MenuVisibility visibility = new MenuVisibility();
    private AbstractContainerMenu closingMenu;

    @Override
    public boolean ensureVisible(LocalPlayerContext context) {
        // 先停移动。需要背包界面时打开它，并等它真的绘制过；不会直接在隐藏的物品栏对象上点击。
        DefaultLocalPlayerContext current = requireSubmission(context);
        current.body().releaseAll();
        if (closingMenu != null || active != null && !active.terminal()) return false;
        // 自动移动允许保留聊天框，但整理物品前必须先切换到真实背包界面。
        // 保留玩家打开的其他对话框；新打开的背包也须实际渲染一帧后才能点击。
        if (DefaultBodyControlPort.permitsWorldMovement(current.minecraft().screen)
                && current.player().containerMenu == current.player().inventoryMenu) {
            if (!current.mutationAvailable()) return false;
            current.claimMutation();
            current.minecraft().setScreen(new MenuVisibility.PlayerInventoryScreen(current.player()));
        }
        return current.mutationAvailable() && visibility.ready(current);
    }

    @Override
    public void interactionSubmitted(LocalPlayerContext context) {
        context.requireCurrent();
        visibility.changed(context);
    }

    private void requireVisible(LocalPlayerContext context) {
        if (!visibility.ready(context)) throw new IllegalStateException("the matching menu GUI must be rendered before operating it");
    }

    @Override
    public MenuReceipt click(LocalPlayerContext context, int slot, int button, ClickType clickType,
                              MenuConfirmation confirmation, int timeoutTicks) {
        // 检查旧点击已结束、当前菜单可见、槽位合法，再发这一次点击并记住之前的菜单版本。
        DefaultLocalPlayerContext current = requireSubmission(context);
        requireIdle();
        requireVisible(current);
        AbstractContainerMenu menu = current.player().containerMenu;
        if (slot < 0 || slot >= menu.slots.size()) throw new IllegalArgumentException("menu slot is out of range");
        current.claimMutation();
        MenuReceipt receipt = create(MenuReceipt.Kind.CLICK, current, menu, timeoutTicks, false, confirmation);
        try {
            current.gameMode().handleInventoryMouseClick(menu.containerId, slot, button, clickType, current.player());
            interactionSubmitted(current);
        } catch (RuntimeException failure) {
            receipt.finish(MenuReceipt.Status.UNCERTAIN,
                    "menu click threw after entering the client transaction path");
        }
        return receipt;
    }

    @Override
    public MenuReceipt swapInventoryToHotbar(LocalPlayerContext context, int sourceInventorySlot,
                                              int hotbarSlot, int timeoutTicks) {
        // 只把普通背包第九格以后的物品换到快捷栏；交换前复制两边物品，之后必须证明它们真的对调。
        DefaultLocalPlayerContext current = requireSubmission(context);
        if (sourceInventorySlot < 9 || sourceInventorySlot > 35) {
            throw new IllegalArgumentException("sourceInventorySlot must be a non-hotbar main inventory slot");
        }
        if (hotbarSlot < 0 || hotbarSlot > 8) throw new IllegalArgumentException("hotbarSlot is out of range");
        if (current.player().containerMenu != current.player().inventoryMenu) {
            throw new IllegalStateException("the player inventory menu must be active for hotbar staging");
        }
        requireIdle();
        requireVisible(current);
        ItemStack sourceBefore = current.player().getInventory().getItem(sourceInventorySlot).copy();
        ItemStack hotbarBefore = current.player().getInventory().getItem(hotbarSlot).copy();
        MenuConfirmation confirmation = MenuConfirmation.inventorySwap(
                sourceInventorySlot, hotbarSlot, sourceBefore, hotbarBefore);
        AbstractContainerMenu menu = current.player().inventoryMenu;
        current.claimMutation();
        MenuReceipt receipt = create(
                MenuReceipt.Kind.SWAP_TO_HOTBAR, current, menu, timeoutTicks, false, confirmation);
        try {
            // 原版背包主物品栏沿用槽位 9..35；交换操作的按钮参数表示目标快捷栏槽位。
            current.gameMode().handleInventoryMouseClick(
                    menu.containerId, sourceInventorySlot, hotbarSlot, ClickType.SWAP, current.player());
            interactionSubmitted(current);
        } catch (RuntimeException failure) {
            receipt.finish(MenuReceipt.Status.UNCERTAIN,
                    "hotbar staging threw after entering the client transaction path");
        }
        return receipt;
    }

    @Override
    public MenuReceipt placeRecipe(LocalPlayerContext context, RecipeHolder<?> recipe, boolean shift,
                                    MenuConfirmation confirmation, int timeoutTicks) {
        // 通过游戏的配方簿功能摆配方，不自己往合成格填假物品；是否摆成功由调用者的结果条件判断。
        DefaultLocalPlayerContext current = requireSubmission(context);
        requireIdle();
        requireVisible(current);
        AbstractContainerMenu menu = current.player().containerMenu;
        current.claimMutation();
        MenuReceipt receipt = create(
                MenuReceipt.Kind.PLACE_RECIPE, current, menu, timeoutTicks, false, confirmation);
        try {
            current.gameMode().handlePlaceRecipe(menu.containerId, recipe, shift);
            interactionSubmitted(current);
        } catch (RuntimeException failure) {
            receipt.finish(MenuReceipt.Status.UNCERTAIN,
                    "recipe placement threw after entering the client transaction path");
        }
        return receipt;
    }

    @Override
    public MenuReceipt close(LocalPlayerContext context, int timeoutTicks) {
        // 已经没有界面、光标和背包合成格都为空时直接完成；否则记下正在关闭的菜单，逐刻处理。
        DefaultLocalPlayerContext current = requireSubmission(context);
        requireIdle();
        AbstractContainerMenu menu = current.player().containerMenu;
        if (menu == current.player().inventoryMenu && current.minecraft().screen == null
                && menu.getCarried().isEmpty() && !inventoryGridOccupied(current)) {
            MenuReceipt receipt = create(MenuReceipt.Kind.CLOSE, current, menu, timeoutTicks, true,
                    MenuConfirmation.closedToInventory());
            receipt.finish(MenuReceipt.Status.CONFIRMED_APPLIED, "the inventory menu was already active");
            return receipt;
        }
        MenuReceipt receipt = create(MenuReceipt.Kind.CLOSE, current, menu, timeoutTicks, true,
                MenuConfirmation.closedToInventory());
        closingMenu = menu;
        advanceClose(current, receipt);
        return receipt;
    }

    @Override
    public MenuReceipt pressButton(LocalPlayerContext context, int button,
                                    MenuConfirmation confirmation, int timeoutTicks) {
        // 附魔前先确认控制权、旧事务和已绘制菜单；本刻只占用一次原生操作机会，不能轮询时重复花费材料。
        DefaultLocalPlayerContext current = requireSubmission(context);
        requireIdle();
        requireVisible(current);
        if (button < 0) throw new IllegalArgumentException("menu button must be nonnegative");
        if (timeoutTicks < 1) throw new IllegalArgumentException("timeoutTicks must be positive");
        Objects.requireNonNull(confirmation, "menu button requires an exact postcondition");
        AbstractContainerMenu menu = current.player().containerMenu;
        current.claimMutation();
        MenuReceipt receipt = create(MenuReceipt.Kind.BUTTON, current, menu, timeoutTicks, false, confirmation);
        try {
            // 按1.21.1附魔界面的顺序，先让当前菜单校验报价；客户端拒绝时没有按钮包，不进入等待扣费阶段。
            if (!menu.clickMenuButton(current.player(), button)) {
                receipt.finish(MenuReceipt.Status.CONFIRMED_NOT_APPLIED, "the native menu rejected the button before submission");
                return receipt;
            }
            receipt.awaitButtonSynchronization(menu.getStateId());
            current.gameMode().handleInventoryButtonClick(menu.containerId, button);
            interactionSubmitted(current);
        } catch (RuntimeException failure) {
            // 原生校验或发包途中异常时无法证明是否执行，保留不确定结果供上层收尾，不能自动再点一次。
            receipt.finish(MenuReceipt.Status.UNCERTAIN, "menu button threw after entering the client transaction path");
        }
        return receipt;
    }

    private void advanceClose(DefaultLocalPlayerContext current, MenuReceipt receipt) {
        // 只关当时那一个菜单；等最后操作被显示过并且本刻还有操作机会，再调用原版关闭流程。
        if (current.player().containerMenu != closingMenu) {
            closingMenu = null;
            receipt.finish(MenuReceipt.Status.UNCERTAIN, "the menu changed before its GUI could be closed");
            return;
        }
        boolean visible = MenuVisibility.matches(current.minecraft(), closingMenu);
        current.body().releaseAll();
        if (!current.mutationAvailable()) return;
        if (visible && !visibility.ready(current)) {
            // 原任务可能已经结束；关闭菜单尚未确认时，仍需阻止后继任务接管菜单。
            current.claimMutation();
            return;
        }
        current.claimMutation();
        try {
            current.player().closeContainer();
            closingMenu = null;
            visibility.reset();
        } catch (RuntimeException failure) {
            closingMenu = null;
            receipt.finish(MenuReceipt.Status.UNCERTAIN,
                    "menu close threw after entering the client transaction path");
        }
    }

    private static boolean inventoryGridOccupied(LocalPlayerContext context) {
        for (int slot = 1; slot <= 4; slot++) {
            if (!context.player().inventoryMenu.getSlot(slot).getItem().isEmpty()) return true;
        }
        return false;
    }

    @Override
    public MenuReceipt closeForTaskBoundary(
            LocalPlayerContext context, int timeoutTicks, String boundaryReason) {
        // 任务结束时可以先把旧的未确认点击标为不确定，再创建关闭请求，避免旧点击一直占位而关不了菜单。
        requireSubmission(context);
        if (active != null && !active.terminal() && active.kind() == MenuReceipt.Kind.CLOSE) return active;
        if (active != null && !active.terminal()) {
            active.finish(MenuReceipt.Status.UNCERTAIN,
                    boundaryReason == null || boundaryReason.isBlank()
                            ? "the owning task ended before menu confirmation"
                            : boundaryReason);
        }
        // close() 会登记新的关闭回执；即使原任务不再持有它，身体边界也会在后续游戏刻继续确认。
        return close(context, timeoutTicks);
    }

    @Override
    public MenuReceipt poll(LocalPlayerContext context, MenuReceipt receipt) {
        // 先核对玩家和菜单没被替换，再看槽位的期望变化；一次菜单版本变化本身不说明具体哪项操作产生了它。
        context.requireCurrent();
        requireActive(receipt);
        if (receipt.terminal()) return receipt;
        if (receipt.bodyEpoch() != context.bodyEpoch() ||
                receipt.controlRevision() != context.controlRevision() ||
                !context.permitsNativeActions()) {
            receipt.finish(MenuReceipt.Status.UNCERTAIN,
                    "body or control authority changed before menu confirmation");
            return receipt;
        }
        if (closingMenu != null && context instanceof DefaultLocalPlayerContext current) {
            advanceClose(current, receipt);
            if (receipt.terminal()) return receipt;
            if (closingMenu != null) {
                if (context.tickRevision() >= receipt.deadlineTick()) {
                    receipt.finish(MenuReceipt.Status.UNCERTAIN, "the GUI close presentation window expired");
                    closingMenu = null;
                }
                return receipt;
            }
        }
        AbstractContainerMenu menu = context.player().containerMenu;
        boolean containerChanged = !receipt.matchesSubmittedMenu(menu);
        // 按钮排除本地报价校验改变的版本；其他菜单事务仍从提交前版本开始等服务端同步。
        boolean stateChanged = !containerChanged && menu.getStateId() != receipt.synchronizationStateId();
        if (containerChanged && !receipt.allowContainerChange()) {
            receipt.finish(MenuReceipt.Status.UNCERTAIN,
                    "the active container changed before the submitted click was confirmed");
            return receipt;
        }
        MenuConfirmation.Verdict verdict;
        try {
            // 先读取具体物品和成本变化；按钮还必须有新同步，普通槽位沿用原确认规则。
            // 网络延迟中仍是旧状态时先等待，不能提前认定附魔被拒绝或物品已经丢失。
            verdict = receipt.confirmation().observe(context, receipt);
        } catch (RuntimeException observationFailure) {
            verdict = MenuConfirmation.Verdict.PENDING;
        }
        boolean synchronizationObserved = stateChanged
                || (containerChanged && receipt.allowContainerChange());
        switch (verdict) {
            case APPLIED -> {
                // 附魔按钮会真实花费经验和青金石，必须同时收到新菜单版本；不能借用普通槽位的稳定等待退路。
                if (synchronizationObserved) {
                    receipt.finish(MenuReceipt.Status.CONFIRMED_APPLIED,
                            "the exact synchronized menu postcondition confirmed the transaction");
                } else if (receipt.kind() != MenuReceipt.Kind.BUTTON && receipt.appliedStableWithoutRevision(
                        context.tickRevision(), MenuSynchronization.windowTicks(context))) {
                    // 没有菜单版本更新时，目前允许本地期望状态稳定一段时间后当作成功，不是收到了专门的服务器确认。
                    // 原版客户端先预测菜单点击结果，再接受服务端修正；不能把短暂的预测画面当成成功。
                    // 若点击没有带来 stateId 更新，需等待已观测往返延迟加少量游戏刻余量，
                    // 期间始终没有修正，才可将稳定的后置状态作为当前可取得的确认依据。
                    receipt.finish(MenuReceipt.Status.CONFIRMED_APPLIED,
                            "the exact menu postcondition remained stable beyond the server round-trip window");
                }
            }
            case NOT_APPLIED -> {
                receipt.clearUnacknowledgedApplied();
                if (synchronizationObserved) {
                    receipt.finish(MenuReceipt.Status.CONFIRMED_NOT_APPLIED,
                            "server-synchronized menu facts confirmed that the transaction was rejected");
                }
            }
            case DIVERGED -> {
                receipt.clearUnacknowledgedApplied();
                if (synchronizationObserved) {
                    receipt.finish(MenuReceipt.Status.DIVERGED,
                            "menu slots diverged from both the exact before and expected after state");
                }
            }
            case PENDING -> receipt.clearUnacknowledgedApplied();
        }
        if (!receipt.terminal() && context.tickRevision() >= receipt.deadlineTick()) {
            receipt.finish(MenuReceipt.Status.UNCERTAIN,
                    "the bounded menu confirmation window expired");
        }
        if (receipt.terminal() && receipt.kind() != MenuReceipt.Kind.CLOSE) visibility.changed(context);
        return receipt;
    }

    void revokeForBoundary(String reason) {
        if (active != null && !active.terminal()) active.finish(MenuReceipt.Status.UNCERTAIN, reason);
        closingMenu = null;
        visibility.reset();
    }

    /**
     * 向玩家交还菜单前，先通过原版关闭流程处理鼠标上携带的物品。
     * 服务端会将它归还背包或执行原版溢出处理，再由自动化释放菜单控制权。
     */
    void revokeForHumanHandoff(LocalPlayer player, String reason) {
        // 人工接管时结束旧等待并走原版关闭；光标上的物品由游戏正常返还或按溢出规则处理。
        if (active != null && !active.terminal()) {
            active.finish(MenuReceipt.Status.UNCERTAIN, reason);
        }
        if (player == null) return;
        closingMenu = null;
        visibility.reset();
        boolean automationMenuOpen = player.containerMenu != player.inventoryMenu
                || MenuVisibility.inventoryVisible(Minecraft.getInstance(), player);
        boolean carrying = !player.containerMenu.getCarried().isEmpty();
        if (!automationMenuOpen && !carrying) return;
        try {
            player.closeContainer();
        } catch (RuntimeException ignored) {
            // 回执已标记结果不确定，保留真实菜单供玩家检查当前状态。
        }
    }

    private MenuReceipt create(MenuReceipt.Kind kind, LocalPlayerContext context,
                               AbstractContainerMenu menu, int timeoutTicks,
                               boolean allowContainerChange, MenuConfirmation confirmation) {
        if (timeoutTicks < 1) throw new IllegalArgumentException("timeoutTicks must be positive");
        // 普通一秒超时不能比已知往返还短；留出首次观察和稳定检查的时间，关闭本地界面则沿用调用方预算。
        int budget = kind == MenuReceipt.Kind.CLOSE ? timeoutTicks
                : Math.max(timeoutTicks, MenuSynchronization.windowTicks(context) + 3);
        MenuReceipt receipt = MenuReceipt.forMenu(kind, context, menu, budget, allowContainerChange, confirmation);
        active = receipt;
        return receipt;
    }

    private static DefaultLocalPlayerContext requireSubmission(LocalPlayerContext context) {
        if (!(context instanceof DefaultLocalPlayerContext current)) {
            throw new IllegalArgumentException("unsupported LocalPlayerContext implementation");
        }
        current.requireSubmissionAuthority();
        return current;
    }

    private void requireIdle() {
        // 还有上一项菜单操作没确认时不能再点，避免后续结果无法对应到哪次点击。
        if (active != null && !active.terminal()) {
            throw new IllegalStateException("a menu transaction is already awaiting confirmation");
        }
    }

    private void requireActive(MenuReceipt receipt) {
        if (receipt == null || receipt != active) {
            throw new IllegalArgumentException("the receipt is not the active menu transaction");
        }
    }
    void advance(LocalPlayerContext context) {
        // 即使原任务对象已经结束，动作入口仍继续完成挂着的关闭，并在此期间阻止新任务用同一刻再操作。
        MenuReceipt receipt = active;
        if (receipt != null && !receipt.terminal()) {
            poll(context, receipt);
            if (!receipt.terminal() && receipt.kind() == MenuReceipt.Kind.CLOSE
                    && context.mutationAvailable() && context instanceof DefaultLocalPlayerContext current) {
                current.body().releaseAll();
                current.claimMutation();
            }
        }
    }
}
