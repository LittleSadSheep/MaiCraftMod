// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.game.menu.MenuActions;
import org.maiwithu.maicraft.game.interaction.PendingInteraction;
import org.maiwithu.maicraft.game.menu.PendingMenuAction;
import org.maiwithu.maicraft.game.menu.VanillaHotbar;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 换到主手执行接缝的读端：把身上指定的一种物品换到主手，像真人一样分步做——
 * 主背包里的经背包界面换到快捷栏再关上界面，副手的经原生的副手交换换到当前选中的快捷栏，
 * 最后选中那一格。每一步都等游戏确认了才走下一步，不冒进。
 */
public final class ClientMovesToMainhand implements MovesToMainhand {

    /** 等一步确认的期限；到点按没能确认收场，不盲目重做。 */
    private static final int STEP_TIMEOUT_TICKS = 60;

    private final Supplier<PlayerContext> contexts;

    public ClientMovesToMainhand(Supplier<PlayerContext> contexts) {
        this.contexts = Objects.requireNonNull(contexts);
    }

    @Override
    public Optional<Action> moveToMainhand(String itemId) {
        return actionToMainhand(itemId);
    }

    /** 这件东西在不在身上（任意格，含副手与盔甲）。 */
    public boolean carried(String itemId) {
        PlayerContext context = contexts.get();
        return context != null && findItem(context.localPlayer(), itemId) != NOT_CARRIED;
    }

    /**
     * 把这件东西弄到主手的动作；已经在主手时给空。
     * 身上没有、或它穿在盔甲格里（那是穿卸装备的事）时也如实给空。
     */
    public Optional<Action> actionToMainhand(String itemId) {
        PlayerContext context = contexts.get();
        if (context == null) return Optional.empty();
        int source = findItem(context.localPlayer(), itemId);
        if (source == ALREADY_SELECTED) return Optional.empty();
        // 身上没有这件东西：接缝如实给空，缺的自己去拿是拿到物品模型的事。
        if (source == NOT_CARRIED) return Optional.empty();
        // 穿在身上的（盔甲格）不是这里换的：那是穿卸装备的事，如实给空不冒充换得了。
        if (source >= 36 && source <= 39) return Optional.empty();
        return Optional.of(new MoveAction("把 " + itemId + " 换到主手", source));
    }

    /**
     * 把主手（选中的快捷栏格）上的东西收进主背包的一个空格，腾出空手：开背包界面，用原生交换把它换进那个空格，
     * 再关上界面。主背包也没有空格时给空，腾地方是另一件事。
     */
    public Optional<Action> stowMainhand() {
        PlayerContext context = contexts.get();
        if (context == null || context.localPlayer() == null) return Optional.empty();
        var inventory = context.localPlayer().getInventory();
        for (int slot = FIRST_MAIN_SLOT; slot <= LAST_MAIN_SLOT; slot++) {
            if (inventory.getItem(slot).isEmpty()) {
                // 和空格交换：主手那件进背包，选中的那一格变空，选中不用改。
                return Optional.of(new MoveAction("把主手的东西收进背包", slot));
            }
        }
        return Optional.empty();
    }

    /** 主背包的格号范围（快捷栏之外、盔甲格之前）。 */
    private static final int FIRST_MAIN_SLOT = 9;
    private static final int LAST_MAIN_SLOT = 35;

    /** 找东西在身上的哪个格：主背包 9..35、副手、或已经在选中的快捷栏格。 */
    static final int NOT_CARRIED = -1;
    static final int ALREADY_SELECTED = -2;

    static int findItem(LocalPlayer player, String itemId) {
        ResourceLocation wanted = ResourceLocation.tryParse(itemId);
        var inventory = player.getInventory();
        int selected = inventory.selected;
        if (isItem(inventory.getItem(selected), wanted)) return ALREADY_SELECTED;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (slot == selected) continue;
            if (isItem(inventory.getItem(slot), wanted)) return slot;
        }
        return NOT_CARRIED;
    }

    private static boolean isItem(ItemStack stack, ResourceLocation wanted) {
        return !stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(wanted);
    }

    /** 换手动作：界面搬运 → 关界面 → 副手交换 → 选中，逐刻推进；收进背包也走前两步加一次选中核对。 */
    private final class MoveAction implements Action {
        /** 这次搬的是什么，给日志与描述的一句话。 */
        private final String what;
        /** 身上那件东西现在的格号；副手是 40，随搬运推进更新。 */
        private int slot;
        private Stage stage;
        private PendingMenuAction menuPending;
        private PendingInteraction selectPending;
        private Problem failure;

        MoveAction(String what, int sourceSlot) {
            this.what = what;
            this.slot = sourceSlot;
            // 已经在快捷栏的选中那一格就行；副手的先做原生交换（要关着界面），主背包的先开界面搬运。
            this.stage = sourceSlot == 40 ? Stage.OFFHAND_SWAP
                    : sourceSlot >= 0 && sourceSlot <= 8 ? Stage.SELECTING
                    : Stage.MENU_STAGE;
        }

        @Override
        public ActionStatus tick(TickContext context) {
            if (failure != null) return ActionStatus.failed(failure);
            PlayerContext player = context.player();
            return switch (stage) {
                case MENU_STAGE -> menuStage(player);
                case CLOSING -> closing(player);
                case OFFHAND_SWAP -> offhandSwap(player);
                case SELECTING -> selecting(player);
            };
        }

        // 开背包界面，把东西换到当前选中的快捷栏格；每刻先看界面能不能操作。
        private ActionStatus menuStage(PlayerContext player) {
            MenuActions actions = player.menuActions();
            if (menuPending == null) {
                if (!actions.ensureVisible(player)) return ActionStatus.running();
                // 换之前先把旧快捷栏物品的格冻结进确认条件：交换必须两端都对上调换才算数。
                int hotbar = VanillaHotbar.swapTarget(player.localPlayer().getInventory().selected);
                menuPending = actions.swapInventoryToHotbar(player, slot, hotbar, STEP_TIMEOUT_TICKS);
                return ActionStatus.running();
            }
            if (!menuPending.terminal()) {
                menuPending = actions.poll(player, menuPending);
                return ActionStatus.running();
            }
            if (menuPending.status() != PendingMenuAction.Status.CONFIRMED_APPLIED) {
                return fail("换到快捷栏没能确认：" + menuPending.detail());
            }
            // 换完东西就在选中的快捷栏格了；把界面关上再回到世界操作。
            slot = VanillaHotbar.swapTarget(player.localPlayer().getInventory().selected);
            menuPending = actions.close(player, STEP_TIMEOUT_TICKS);
            stage = Stage.CLOSING;
            return ActionStatus.running();
        }

        // 等界面关上；关不上不进世界操作，菜单同步没结清时选中也不可靠。
        private ActionStatus closing(PlayerContext player) {
            if (!menuPending.terminal()) {
                menuPending = player.menuActions().poll(player, menuPending);
                return ActionStatus.running();
            }
            if (menuPending.status() != PendingMenuAction.Status.CONFIRMED_APPLIED) {
                return fail("背包界面没能关上：" + menuPending.detail());
            }
            menuPending = null;
            stage = Stage.SELECTING;
            return ActionStatus.running();
        }

        // 副手交换：把选中的快捷栏格与副手对调，东西就到了选中的格；要世界画面空闲才能做。
        private ActionStatus offhandSwap(PlayerContext player) {
            MenuActions actions = player.menuActions();
            if (menuPending == null) {
                try {
                    menuPending = actions.swapInventoryToOffhand(player, slot, STEP_TIMEOUT_TICKS);
                } catch (IllegalStateException busy) {
                    // 画面被占或角色正在用东西：下一刻再试，不硬抢。
                    return ActionStatus.running();
                }
                return ActionStatus.running();
            }
            if (!menuPending.terminal()) {
                menuPending = actions.poll(player, menuPending);
                return ActionStatus.running();
            }
            if (menuPending.status() != PendingMenuAction.Status.CONFIRMED_APPLIED) {
                return fail("副手交换没能确认：" + menuPending.detail());
            }
            slot = player.localPlayer().getInventory().selected;
            menuPending = null;
            stage = Stage.SELECTING;
            return ActionStatus.running();
        }

        // 选中那一格：东西已经在快捷栏，选中就是主手。
        private ActionStatus selecting(PlayerContext player) {
            var sender = player.interactionSender();
            if (sender == null || !player.canInteractThisTick()) return ActionStatus.running();
            // 选中在等确认时只推进，不重复提交新的选中。
            if (selectPending == null) {
                selectPending = sender.selectHotbar(player, slot, STEP_TIMEOUT_TICKS);
            } else if (!selectPending.terminal()) {
                selectPending = sender.poll(player, selectPending);
                return ActionStatus.running();
            }
            if (!selectPending.terminal()) return ActionStatus.running();
            if (selectPending.status() != PendingInteraction.Status.CONFIRMED_APPLIED) {
                return fail("选中快捷栏没能确认");
            }
            return ActionStatus.done();
        }

        private ActionStatus fail(String message) {
            failure = Problem.of(Problem.Kind.STUCK, message, null);
            return ActionStatus.failed(failure);
        }

        @Override
        public String describe() {
            return what;
        }
    }

    /** 换手的先后顺序。 */
    private enum Stage { MENU_STAGE, CLOSING, OFFHAND_SWAP, SELECTING }
}
