// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.menu;

import java.util.Objects;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.maiwithu.maicraft.game.interaction.InteractionSender;
import org.maiwithu.maicraft.game.interaction.PendingInteraction;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.PlayerInput;

/** 实际操作玩家的容器界面：一次点一个槽位、报价按钮或配方，等结果明确后再接受下一次。 */
public final class DefaultMenuActions implements MenuActions {
    private static final Logger LOG = LoggerFactory.getLogger(DefaultMenuActions.class);

    private final PlayerInput playerInput;
    /** 挂起的菜单协议要与世界动作互斥；交互提交方由启动时在两端都建好后接进来。 */
    private InteractionSender sender;
    private PendingMenuAction active;
    private final MenuVisibility visibility;
    private AbstractContainerMenu closingMenu;
    private GuiPreparation worldPreparation;

    /** @param screens 界面真正画出来的记录：渲染从 Mixin 进来，点击前要等改变后的界面画过一帧 */
    public DefaultMenuActions(PlayerInput playerInput, RenderedScreens screens) {
        this.playerInput = playerInput;
        this.visibility = new MenuVisibility(screens);
    }

    /** 启动时把同一个交互提交实例接进来；界面准备与它共享对挂起菜单协议的判断。 */
    public void attachSender(InteractionSender sender) {
        this.sender = sender;
        this.worldPreparation = new GuiPreparation(this, sender, playerInput);
    }

    String diagnosticState() {
        return active == null ? "none" : active.kind() + ":" + active.status()
                + " deadline=" + active.deadlineTick() + " closing=" + (closingMenu != null);
    }

    /** 已提交的槽位交换与关闭界面必须先结束，聊天和休息才可接手角色。 */
    @Override public boolean hasPendingTransaction() {
        return (active != null && !active.terminal()) || closingMenu != null;
    }

    @Override public boolean ensureWorldVisible(PlayerContext context) {
        // 移动、施工与换工具都续做同一动作；真实关闭失败后留给新任务一份独立退出请求。
        requireSubmission(context);
        try { return worldPreparation.ready(context, true); }
        catch (RuntimeException failure) {
            worldPreparation = new GuiPreparation(this, sender, playerInput);
            throw failure;
        }
    }

    @Override
    public boolean ensureVisible(PlayerContext context) {
        // 先停输入。需要背包界面时打开它，并等它真的绘制过；不会直接在隐藏的物品栏对象上点击。
        LocalPlayer player = context.localPlayer();
        playerInput.releaseAll(player);
        if (closingMenu != null || active != null && !active.terminal()) return false;
        Minecraft minecraft = Minecraft.getInstance();
        // 背包操作不应被暂停或模组页面永久挡住；工作站菜单仍保持原身份，不在这里换成别的容器。
        if (player.containerMenu == player.inventoryMenu
                && !MenuVisibility.inventoryVisible(minecraft, player)
                && !ensureWorldVisible(context)) return false;
        // 自动移动允许保留聊天框，但整理物品前必须先切换到真实背包界面。
        // 保留玩家打开的其他对话框；新打开的背包也须实际渲染一帧后才能点击。
        if (MenuVisibility.worldInputAllowed(minecraft.screen)
                && player.containerMenu == player.inventoryMenu) {
            if (!context.canInteractThisTick()) return false;
            context.tryClaimInteraction();
            minecraft.setScreen(new MenuVisibility.PlayerInventoryScreen(player));
        }
        return context.canInteractThisTick() && visibility.ready(minecraft, context);
    }

    @Override
    public void interactionSubmitted(PlayerContext context) {
        visibility.changed(context);
    }

    private void requireVisible(PlayerContext context) {
        if (!visibility.ready(Minecraft.getInstance(), context))
            throw new IllegalStateException("the matching menu GUI must be rendered before operating it");
    }

    @Override
    public PendingMenuAction click(PlayerContext context, int slot, int button, ClickType clickType,
                                   MenuConfirmation confirmation, int timeoutTicks) {
        // 检查旧点击已结束、当前菜单可见、槽位合法，再发这一次点击并记住之前的菜单版本。
        requireSubmission(context);
        requireIdle();
        requireVisible(context);
        AbstractContainerMenu menu = context.localPlayer().containerMenu;
        // 满背包分出余量后允许原生界面外左键整份投掷；其他负槽号和操作仍是无效菜单请求。
        boolean outsideDrop = slot == -999 && clickType == ClickType.PICKUP && (button == 0 || button == 1);
        if (!outsideDrop && (slot < 0 || slot >= menu.slots.size())) throw new IllegalArgumentException("menu slot is out of range");
        PendingMenuAction pending = create(PendingMenuAction.Kind.CLICK, context, menu, slot, timeoutTicks, false, confirmation);
        try {
            // 菜单丢弃也按服务端朝向出手；先同步已经转到的真实视角，避免打开背包后仍按旧的低头方向丢在脚边。
            if (clickType == ClickType.THROW || outsideDrop) {
                var player = context.localPlayer();
                connection(context).send(new ServerboundMovePlayerPacket.Rot(
                        player.getYRot(), player.getXRot(), player.onGround()));
            }
            gameMode(context).handleInventoryMouseClick(menu.containerId, slot, button, clickType, context.localPlayer());
            interactionSubmitted(context);
        } catch (RuntimeException failure) {
            pending.finish(PendingMenuAction.Status.UNCERTAIN,
                    "menu click threw after entering the client transaction path");
        }
        return pending;
    }

    @Override
    public PendingMenuAction swapInventoryToHotbar(PlayerContext context, int sourceInventorySlot,
                                                   int hotbarSlot, int timeoutTicks) {
        // 只把普通背包第九格以后的物品换到快捷栏；交换前复制两边物品，之后必须证明它们真的对调。
        requireSubmission(context);
        if (sourceInventorySlot < 9 || sourceInventorySlot > 35) {
            throw new IllegalArgumentException("sourceInventorySlot must be a non-hotbar main inventory slot");
        }
        if (hotbarSlot < 0 || hotbarSlot > 8) throw new IllegalArgumentException("hotbarSlot is out of range");
        if (context.localPlayer().containerMenu != context.localPlayer().inventoryMenu) {
            throw new IllegalStateException("the player inventory menu must be active for hotbar staging");
        }
        requireIdle();
        requireVisible(context);
        ItemStack sourceBefore = context.localPlayer().getInventory().getItem(sourceInventorySlot).copy();
        ItemStack hotbarBefore = context.localPlayer().getInventory().getItem(hotbarSlot).copy();
        // 电量等动态组件不改变物品身份；数量、绑定及其余组件仍逐项核对。
        MenuConfirmation confirmation = MenuConfirmation.inventorySwap(
                sourceInventorySlot, hotbarSlot, sourceBefore, hotbarBefore);
        AbstractContainerMenu menu = context.localPlayer().inventoryMenu;
        PendingMenuAction pending = create(
                PendingMenuAction.Kind.SWAP_TO_HOTBAR, context, menu, sourceInventorySlot, timeoutTicks, false, confirmation);
        try {
            // 原版背包主物品栏沿用槽位 9..35；交换操作的按钮参数表示目标快捷栏槽位。
            gameMode(context).handleInventoryMouseClick(
                    menu.containerId, sourceInventorySlot, hotbarSlot, ClickType.SWAP, context.localPlayer());
            interactionSubmitted(context);
        } catch (RuntimeException failure) {
            pending.finish(PendingMenuAction.Status.UNCERTAIN,
                    "hotbar staging threw after entering the client transaction path");
        }
        return pending;
    }

    @Override public PendingMenuAction swapInventoryToOffhand(PlayerContext context, int inventorySlot, int timeoutTicks) {
        // 把整叠火把通过原生 SWAP 放进副手，旧副手物品回到原格；不切主手，也不反复开关背包。
        requireSubmission(context);
        LocalPlayer player = context.localPlayer();
        if (inventorySlot < 0 || inventorySlot >= 36 || Minecraft.getInstance().screen != null
                || player.containerMenu != player.inventoryMenu
                || !player.inventoryMenu.getCarried().isEmpty() || player.isUsingItem())
            throw new IllegalStateException("offhand staging requires an idle player inventory and world view");
        requireIdle();
        var inventory = player.getInventory();
        var confirmation = MenuConfirmation.inventorySwap(inventorySlot, 40,
                inventory.getItem(inventorySlot).copy(), inventory.getItem(40).copy());
        var menu = player.inventoryMenu;
        int menuSlot = inventorySlot < 9 ? inventorySlot + 36 : inventorySlot;
        var pending = create(PendingMenuAction.Kind.CLICK, context, menu, menuSlot, timeoutTicks, false, confirmation);
        try {
            gameMode(context).handleInventoryMouseClick(menu.containerId, menuSlot, 40, ClickType.SWAP, player);
            interactionSubmitted(context);
        } catch (RuntimeException failure) {
            pending.finish(PendingMenuAction.Status.UNCERTAIN, "offhand swap entered native submission; outcome unknown");
        }
        return pending;
    }

    @Override
    public PendingMenuAction placeRecipe(PlayerContext context, RecipeHolder<?> recipe, boolean shift,
                                         MenuConfirmation confirmation, int timeoutTicks) {
        // 通过游戏的配方簿功能摆配方，不自己往合成格填假物品；是否摆成功由调用者的结果条件判断。
        requireSubmission(context);
        requireIdle();
        requireVisible(context);
        AbstractContainerMenu menu = context.localPlayer().containerMenu;
        PendingMenuAction pending = create(
                PendingMenuAction.Kind.PLACE_RECIPE, context, menu, -1, timeoutTicks, false, confirmation);
        try {
            gameMode(context).handlePlaceRecipe(menu.containerId, recipe, shift);
            interactionSubmitted(context);
        } catch (RuntimeException failure) {
            pending.finish(PendingMenuAction.Status.UNCERTAIN,
                    "recipe placement threw after entering the client transaction path");
        }
        return pending;
    }

    @Override
    public PendingMenuAction close(PlayerContext context, int timeoutTicks) {
        // 已经没有界面、光标和背包合成格都为空时直接完成；否则记下正在关闭的菜单，逐刻处理。
        requireSubmission(context);
        requireIdle();
        LocalPlayer player = context.localPlayer();
        AbstractContainerMenu menu = player.containerMenu;
        if (menu == player.inventoryMenu && Minecraft.getInstance().screen == null
                && menu.getCarried().isEmpty() && !GuiPreparation.inventoryGridOccupied(player)) {
            PendingMenuAction pending = create(PendingMenuAction.Kind.CLOSE, context, menu, -1, timeoutTicks, true,
                    MenuConfirmation.closedToInventory());
            pending.finish(PendingMenuAction.Status.CONFIRMED_APPLIED, "the inventory menu was already active");
            return pending;
        }
        PendingMenuAction pending = create(PendingMenuAction.Kind.CLOSE, context, menu, -1, timeoutTicks, true,
                MenuConfirmation.closedToInventory());
        closingMenu = menu;
        advanceClose(context, pending);
        return pending;
    }

    @Override
    public PendingMenuAction pressButton(PlayerContext context, int button,
                                         MenuConfirmation confirmation, int timeoutTicks) {
        // 附魔前先确认旧事务和已绘制菜单；本刻只占用一次原生交互机会，不能轮询时重复花费材料。
        requireSubmission(context);
        requireIdle();
        requireVisible(context);
        if (button < 0) throw new IllegalArgumentException("menu button must be nonnegative");
        if (timeoutTicks < 1) throw new IllegalArgumentException("timeoutTicks must be positive");
        Objects.requireNonNull(confirmation, "menu button requires an exact postcondition");
        AbstractContainerMenu menu = context.localPlayer().containerMenu;
        PendingMenuAction pending = create(PendingMenuAction.Kind.BUTTON, context, menu, button, timeoutTicks, false, confirmation);
        try {
            // 按1.21.1附魔界面的顺序，先让当前菜单校验报价；客户端拒绝时没有按钮包，不进入等待扣费阶段。
            if (!menu.clickMenuButton(context.localPlayer(), button)) {
                pending.finish(PendingMenuAction.Status.CONFIRMED_NOT_APPLIED, "the native menu rejected the button before submission");
                return pending;
            }
            pending.awaitButtonSynchronization(menu.getStateId());
            gameMode(context).handleInventoryButtonClick(menu.containerId, button);
            interactionSubmitted(context);
        } catch (RuntimeException failure) {
            // 原生校验或发包途中异常时无法证明是否执行，保留不确定结果供上层收尾，不能自动再点一次。
            pending.finish(PendingMenuAction.Status.UNCERTAIN, "menu button threw after entering the client transaction path");
        }
        return pending;
    }

    private void advanceClose(PlayerContext context, PendingMenuAction pending) {
        // 只关当时那一个菜单；等最后操作被显示过并且本刻还有交互机会，再调用原版关闭流程。
        if (context.localPlayer().containerMenu != closingMenu) {
            closingMenu = null;
            pending.finish(PendingMenuAction.Status.UNCERTAIN, "the menu changed before its GUI could be closed");
            return;
        }
        boolean visible = MenuVisibility.matches(Minecraft.getInstance(), closingMenu);
        playerInput.releaseAll(context.localPlayer());
        if (!context.canInteractThisTick()) return;
        if (visible && !visibility.ready(Minecraft.getInstance(), context)) {
            // 原任务可能已经结束；关闭菜单尚未确认时，仍需阻止后继任务接管菜单。
            context.tryClaimInteraction();
            return;
        }
        context.tryClaimInteraction();
        try {
            context.localPlayer().closeContainer();
            closingMenu = null;
            visibility.reset();
        } catch (RuntimeException failure) {
            closingMenu = null;
            pending.finish(PendingMenuAction.Status.UNCERTAIN,
                    "menu close threw after entering the client transaction path");
        }
    }

    @Override
    public PendingMenuAction closeForTaskBoundary(
            PlayerContext context, int timeoutTicks, String boundaryReason) {
        // 任务结束时可以先把旧的未确认点击标为不确定，再创建关闭请求，避免旧点击一直占位而关不了菜单。
        requireSubmission(context);
        if (active != null && !active.terminal() && active.kind() == PendingMenuAction.Kind.CLOSE) return active;
        if (active != null && !active.terminal()) {
            active.finish(PendingMenuAction.Status.UNCERTAIN,
                    boundaryReason == null || boundaryReason.isBlank()
                            ? "the owning task ended before menu confirmation"
                            : boundaryReason);
        }
        // close() 会登记新的关闭确认；即使原任务不再持有它，边界收尾也会在后续游戏刻继续确认。
        return close(context, timeoutTicks);
    }

    @Override
    public PendingMenuAction poll(PlayerContext context, PendingMenuAction pending) {
        // 先核对角色和菜单没被替换，再看槽位的期望变化；一次菜单版本变化本身不说明具体哪项操作产生了它。
        // 反击或补食可在旧关闭已确认后创建新交易；恢复的任务仍须读回自己的冻结结果，不能因端口已换交易而报内部错误。
        if (pending != null && pending.terminal()) return pending;
        requireActive(pending);
        if (!pending.fromSamePlayer(context)) {
            pending.finish(PendingMenuAction.Status.UNCERTAIN,
                    "body or control authority changed before menu confirmation");
            return pending;
        }
        if (closingMenu != null) {
            advanceClose(context, pending);
            if (pending.terminal()) return pending;
            if (closingMenu != null) {
                if (context.clientTick() >= pending.deadlineTick()) {
                    pending.finish(PendingMenuAction.Status.UNCERTAIN, "the GUI close presentation window expired");
                    closingMenu = null;
                }
                return pending;
            }
        }
        AbstractContainerMenu menu = context.localPlayer().containerMenu;
        boolean containerChanged = !pending.matchesSubmittedMenu(menu);
        // 按钮排除本地报价校验改变的版本；其他菜单事务仍从提交前版本开始等服务端同步。
        boolean stateChanged = !containerChanged && menu.getStateId() != pending.synchronizationStateId();
        if (containerChanged && !pending.allowContainerChange()) {
            pending.finish(PendingMenuAction.Status.UNCERTAIN,
                    "the active container changed before the submitted click was confirmed");
            return pending;
        }
        MenuConfirmation.Verdict verdict;
        try {
            // 先读取具体物品和成本变化；按钮还必须有新同步，普通槽位沿用原确认规则。
            // 网络延迟中仍是旧状态时先等待，不能提前认定附魔被拒绝或物品已经丢失。
            verdict = pending.confirmation().observe(context, pending);
        } catch (RuntimeException observationFailure) {
            verdict = MenuConfirmation.Verdict.PENDING;
        }
        boolean synchronizationObserved = stateChanged
                || (containerChanged && pending.allowContainerChange());
        switch (verdict) {
            case APPLIED -> {
                // 附魔按钮会真实花费经验和青金石，必须同时收到新菜单版本；不能借用普通槽位的稳定等待退路。
                if (synchronizationObserved) {
                    pending.finish(PendingMenuAction.Status.CONFIRMED_APPLIED,
                            "the exact synchronized menu postcondition confirmed the transaction");
                } else if (pending.kind() != PendingMenuAction.Kind.BUTTON && pending.appliedStableWithoutRevision(
                        context.clientTick(), MenuSynchronization.windowTicks(context))) {
                    // 没有菜单版本更新时，目前允许本地期望状态稳定一段时间后当作成功，不是收到了专门的服务器确认。
                    // 原版客户端先预测菜单点击结果，再接受服务端修正；不能把短暂的预测画面当成成功。
                    // 若点击没有带来 stateId 更新，需等待已观测往返延迟加少量游戏刻余量，
                    // 期间始终没有修正，才可将稳定的后置状态作为当前可取得的确认依据。
                    pending.finish(PendingMenuAction.Status.CONFIRMED_APPLIED,
                            "the exact menu postcondition remained stable beyond the server round-trip window");
                }
            }
            case NOT_APPLIED -> {
                pending.clearUnacknowledgedApplied();
                if (synchronizationObserved) {
                    pending.finish(PendingMenuAction.Status.CONFIRMED_NOT_APPLIED,
                            "server-synchronized menu facts confirmed that the transaction was rejected");
                }
            }
            case DIVERGED -> {
                pending.clearUnacknowledgedApplied();
                if (synchronizationObserved) {
                    pending.finish(PendingMenuAction.Status.DIVERGED,
                            "menu slots diverged from both the exact before and expected after state");
                }
            }
            case PENDING -> pending.clearUnacknowledgedApplied();
        }
        if (!pending.terminal() && context.clientTick() >= pending.deadlineTick()) {
            pending.finish(PendingMenuAction.Status.UNCERTAIN,
                    "the bounded menu confirmation window expired");
        }
        if (pending.terminal() && pending.kind() != PendingMenuAction.Kind.CLOSE) visibility.changed(context);
        return pending;
    }

    /** 换角色或交还控制权时结束旧等待，并走原版关闭交还菜单。 */
    public void revokeForBoundary(String reason) {
        if (active != null && !active.terminal()) active.finish(PendingMenuAction.Status.UNCERTAIN, reason);
        closingMenu = null;
        visibility.reset();
    }

    /**
     * 向玩家交还菜单前，先通过原版关闭流程处理鼠标上携带的物品。
     * 服务端会将它归还背包或执行原版溢出处理，再由自动化释放菜单控制权。
     */
    public void revokeForHumanHandoff(LocalPlayer player, String reason) {
        // 人工接管时结束旧等待并走原版关闭；光标上的物品由游戏正常返还或按溢出规则处理。
        if (active != null && !active.terminal()) {
            active.finish(PendingMenuAction.Status.UNCERTAIN, reason);
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
            // 等待记录已标记结果不确定，保留真实菜单供玩家检查当前状态。
        }
    }

    private PendingMenuAction create(PendingMenuAction.Kind kind, PlayerContext context,
                                     AbstractContainerMenu menu, int slot, int timeoutTicks,
                                     boolean allowContainerChange, MenuConfirmation confirmation) {
        if (timeoutTicks < 1) throw new IllegalArgumentException("timeoutTicks must be positive");
        // 普通一秒超时不能比已知往返还短；留出首次观察和稳定检查的时间，关闭本地界面则沿用调用方预算。
        int budget = kind == PendingMenuAction.Kind.CLOSE ? timeoutTicks
                : Math.max(timeoutTicks, MenuSynchronization.windowTicks(context) + 3);
        PendingMenuAction pending = PendingMenuAction.forMenu(kind, context, menu, slot, budget,
                allowContainerChange, confirmation);
        active = pending;
        // 提交行与确认终态行构成一次菜单事务的完整时间线；超时预算是否过短，两行对照即得。
        LOG.debug("menu {} slot={} containerId={} stateId={} budget={}t submitted",
                kind, slot, menu.containerId, menu.getStateId(), budget);
        return pending;
    }

    private static void requireSubmission(PlayerContext context) {
        if (context == null || !context.isCurrent()) {
            throw new IllegalArgumentException("the player context must belong to the current tick");
        }
    }

    private void requireIdle() {
        // 还有上一项菜单操作没确认时不能再点，避免后续结果无法对应到哪次点击。
        if (active != null && !active.terminal()) {
            throw new IllegalStateException("a menu transaction is already awaiting confirmation");
        }
    }

    private void requireActive(PendingMenuAction pending) {
        if (pending == null || pending != active) {
            throw new IllegalArgumentException("the pending menu action is not the active transaction");
        }
    }

    /** 每刻推进一次：即使原任务对象已经结束，动作入口仍继续完成挂着的关闭。 */
    void advance(PlayerContext context) {
        PendingMenuAction pending = active;
        if (pending != null && !pending.terminal()) {
            poll(context, pending);
            if (!pending.terminal() && pending.kind() == PendingMenuAction.Kind.CLOSE) {
                playerInput.releaseAll(context.localPlayer());
                context.tryClaimInteraction();
            }
        }
    }

    private static MultiPlayerGameMode gameMode(PlayerContext context) {
        MultiPlayerGameMode gameMode = Minecraft.getInstance().gameMode;
        if (gameMode == null) throw new IllegalStateException("no active client game mode");
        return gameMode;
    }

    private static ClientPacketListener connection(PlayerContext context) {
        ClientPacketListener connection = context.localPlayer().connection;
        if (connection == null) throw new IllegalStateException("no active server connection");
        return connection;
    }
}
