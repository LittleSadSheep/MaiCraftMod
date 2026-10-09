// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.InteractionHand;

import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.interaction.PendingInteraction;
import org.maiwithu.maicraft.game.menu.MenuActions;
import org.maiwithu.maicraft.game.menu.MenuConfirmation;
import org.maiwithu.maicraft.game.menu.PendingMenuAction;
import org.maiwithu.maicraft.game.player.GearSlotName;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 穿卸装备执行接缝的读端：穿上与卸下都是原生交互的编排——护甲先换到主手再对自己使用一次，
 * 副手是原生的副手交换，主手是选中；取下护甲经背包界面整堆搬出，取下副手是用一个空格交换。
 * 每步等游戏确认了才走下一步；现场做不了（身上没有、背包腾不出空格）时如实给空或以问题失败，
 * 不装作穿过或卸过。
 */
public final class ClientGearChanges implements GearChanges {

    /** 等一步确认的期限；到点按没能确认收场，不盲目重做。 */
    private static final int STEP_TIMEOUT_TICKS = 60;

    /** 护甲四格在角色物品格里的编号：靴 36、护腿 37、胸甲 38、头盔 39。 */
    private static int armorInventorySlot(GearSlotName slot) {
        return switch (slot) {
            case FEET -> 36;
            case LEGS -> 37;
            case CHEST -> 38;
            case HEAD -> 39;
            default -> -1;
        };
    }

    /** 护甲四格在背包界面里的槽位号：头盔 5、胸甲 6、护腿 7、靴子 8。 */
    private static int armorMenuSlot(GearSlotName slot) {
        return switch (slot) {
            case HEAD -> 5;
            case CHEST -> 6;
            case LEGS -> 7;
            case FEET -> 8;
            default -> -1;
        };
    }

    private final Supplier<PlayerContext> contexts;
    private final ClientMovesToMainhand movesToMainhand;

    public ClientGearChanges(Supplier<PlayerContext> contexts, ClientMovesToMainhand movesToMainhand) {
        this.contexts = Objects.requireNonNull(contexts);
        this.movesToMainhand = Objects.requireNonNull(movesToMainhand);
    }

    @Override
    public Optional<Action> wear(String itemId, GearSlotName slot) {
        PlayerContext context = contexts.get();
        if (context == null) return Optional.empty();
        if (slot == GearSlotName.MAINHAND || slot == GearSlotName.OFFHAND
                || armorInventorySlot(slot) > 0) {
            return Optional.of(new WearAction(itemId, slot));
        }
        return Optional.empty();
    }

    @Override
    public Optional<Action> takeOff(GearSlotName slot) {
        PlayerContext context = contexts.get();
        if (context == null) return Optional.empty();
        // 护甲经背包界面整堆搬出；副手与空背包格交换。主手取下（腾到背包）没有单一原生手势，做不了。
        if (armorInventorySlot(slot) > 0 || slot == GearSlotName.OFFHAND) {
            return Optional.of(new TakeOffAction(slot));
        }
        return Optional.empty();
    }

    /** 穿上：护甲 = 换到主手 + 对自己使用；副手 = 原生交换；主手 = 换到主手。 */
    private final class WearAction implements Action {
        private final String itemId;
        private final GearSlotName slot;
        private Action stage;
        private PendingInteraction usePending;
        private Problem failure;

        WearAction(String itemId, GearSlotName slot) {
            this.itemId = itemId;
            this.slot = slot;
            // 主手与护甲都从"换到主手"起手；副手一步交换就到，直接进副手阶段。
            this.stage = slot == GearSlotName.OFFHAND ? null : handStage();
        }

        // 换到主手起手：东西已在主手时给空（不用换），身上没有时这单就做不成。
        private Action handStage() {
            if (!movesToMainhand.carried(itemId)) {
                failure = Problem.of(Problem.Kind.NEED_ITEM, "身上没有 " + itemId + "，穿不上", null);
                return null;
            }
            return movesToMainhand.actionToMainhand(itemId).orElse(null);
        }

        @Override
        public ActionStatus tick(TickContext context) {
            if (failure != null) return ActionStatus.failed(failure);
            PlayerContext player = context.player();
            if (stage != null) {
                ActionStatus status = stage.tick(context);
                // 换手告一段落（无论成没成）都进下一步：成了继续穿，没成由 use 阶段的确认如实报。
                if (status instanceof ActionStatus.Running) return ActionStatus.running();
                if (status instanceof ActionStatus.Failed failed) {
                    failure = failed.problem();
                    return ActionStatus.failed(failure);
                }
                stage = null;
            }
            return switch (slot) {
                // 副手：一次原生交换把东西放进副手；主手：换到手就算穿好。
                case OFFHAND -> offhandSwap(player);
                case MAINHAND -> ActionStatus.done();
                // 护甲：对自己使用一次，确认护甲格换上了这件。
                default -> useOnSelf(player);
            };
        }

        // 副手交换：只认主背包与快捷栏里的东西；交换要求世界画面空闲，占着就下一刻再试。
        private ActionStatus offhandSwap(PlayerContext player) {
            LocalPlayer body = player.localPlayer();
            int source = findFirstInventorySlot(body, itemId);
            if (source < 0) {
                failure = Problem.of(Problem.Kind.NEED_ITEM, "身上没有 " + itemId + "，穿不上副手", null);
                return ActionStatus.failed(failure);
            }
            try {
                PendingMenuAction pending = player.menuActions()
                        .swapInventoryToOffhand(player, source, STEP_TIMEOUT_TICKS);
                if (!pending.terminal()) return ActionStatus.running();
                return pending.status() == PendingMenuAction.Status.CONFIRMED_APPLIED
                        ? ActionStatus.done()
                        : fail("副手交换没能确认：" + pending.detail());
            } catch (IllegalStateException busy) {
                // 画面被占或角色正在用东西：下一刻再试，不硬抢。
                return ActionStatus.running();
            }
        }

        // 对自己使用主手的护甲：生效的标志是护甲格换上了这件（或主手换持了别的）。
        private ActionStatus useOnSelf(PlayerContext player) {
            var sender = player.interactionSender();
            if (sender == null || !player.canInteractThisTick()) return ActionStatus.running();
            if (usePending == null) {
                // 生效的两个标志任取其一：主手换持了别的，或护甲格里出现了手里这件。
                var handBefore = player.localPlayer().getMainHandItem().copy();
                int armorSlot = armorInventorySlot(slot);
                usePending = sender.useItem(player, InteractionHand.MAIN_HAND,
                        InteractionConfirmation.anyOf(
                                InteractionConfirmation.heldItemChanged(InteractionHand.MAIN_HAND, handBefore),
                                InteractionConfirmation.inventorySlot(armorSlot, handBefore)),
                        STEP_TIMEOUT_TICKS);
            } else if (!usePending.terminal()) {
                usePending = sender.poll(player, usePending);
                if (!usePending.terminal()) return ActionStatus.running();
            }
            if (!usePending.terminal()) return ActionStatus.running();
            return usePending.status() == PendingInteraction.Status.CONFIRMED_APPLIED
                    ? ActionStatus.done()
                    : fail("对自己使用护甲没能生效");
        }

        private ActionStatus fail(String message) {
            failure = Problem.of(Problem.Kind.STUCK, message, null);
            return ActionStatus.failed(failure);
        }

        @Override
        public String describe() {
            return "把 " + itemId + " 穿上 " + slot.paramName();
        }
    }

    /** 取下：护甲经背包界面整堆搬出；副手与一个空背包格交换。 */
    private final class TakeOffAction implements Action {
        private final GearSlotName slot;
        private PendingMenuAction menuPending;
        private Problem failure;

        TakeOffAction(GearSlotName slot) {
            this.slot = slot;
        }

        @Override
        public ActionStatus tick(TickContext context) {
            if (failure != null) return ActionStatus.failed(failure);
            PlayerContext player = context.player();
            return slot == GearSlotName.OFFHAND ? offhandTakeOff(player) : armorTakeOff(player);
        }

        // 副手取下：找一个空的背包格与副手交换，东西就进了背包。
        private ActionStatus offhandTakeOff(PlayerContext player) {
            LocalPlayer body = player.localPlayer();
            int empty = firstEmptyInventorySlot(body);
            if (empty < 0) {
                failure = Problem.of(Problem.Kind.INVENTORY_FULL, "背包没有空格，副手的东西取不下来", null);
                return ActionStatus.failed(failure);
            }
            try {
                PendingMenuAction pending = player.menuActions()
                        .swapInventoryToOffhand(player, empty, STEP_TIMEOUT_TICKS);
                if (!pending.terminal()) return ActionStatus.running();
                return pending.status() == PendingMenuAction.Status.CONFIRMED_APPLIED
                        ? ActionStatus.done()
                        : fail("副手交换没能确认：" + pending.detail());
            } catch (IllegalStateException busy) {
                return ActionStatus.running();
            }
        }

        // 护甲取下：打开背包界面，对护甲格做一次整堆搬出；栏位空了才算取下。
        private ActionStatus armorTakeOff(PlayerContext player) {
            MenuActions actions = player.menuActions();
            int menuSlot = armorMenuSlot(slot);
            if (menuPending == null) {
                if (!actions.ensureVisible(player)) return ActionStatus.running();
                menuPending = actions.click(player, menuSlot, 0,
                        ClickType.QUICK_MOVE,
                        slotEmptied(menuSlot), STEP_TIMEOUT_TICKS);
                return ActionStatus.running();
            }
            if (!menuPending.terminal()) {
                menuPending = actions.poll(player, menuPending);
                return ActionStatus.running();
            }
            return menuPending.status() == PendingMenuAction.Status.CONFIRMED_APPLIED
                    ? ActionStatus.done()
                    : fail("护甲取下没能确认：" + menuPending.detail());
        }

        // 确认条件：护甲格空了才算这一下搬出去了。
        private MenuConfirmation slotEmptied(int menuSlot) {
            return (context, pending) -> {
                var menu = context.localPlayer().containerMenu;
                if (menu.containerId != pending.containerId() || menuSlot >= menu.slots.size()) {
                    return MenuConfirmation.Verdict.PENDING;
                }
                return menu.getSlot(menuSlot).getItem().isEmpty()
                        ? MenuConfirmation.Verdict.APPLIED
                        : MenuConfirmation.Verdict.PENDING;
            };
        }

        private ActionStatus fail(String message) {
            failure = Problem.of(Problem.Kind.STUCK, message, null);
            return ActionStatus.failed(failure);
        }

        @Override
        public String describe() {
            return "取下 " + slot.paramName() + " 的装备";
        }
    }

    /** 在主背包与快捷栏里找指定物品的格；找不到给 -1。 */
    private static int findFirstInventorySlot(LocalPlayer body, String itemId) {
        var wanted = ResourceLocation.tryParse(itemId);
        var inventory = body.getInventory();
        for (int slot = 0; slot <= 35; slot++) {
            var stack = inventory.getItem(slot);
            if (!stack.isEmpty() && BuiltInRegistries.ITEM
                    .getKey(stack.getItem()).equals(wanted)) return slot;
        }
        return -1;
    }

    /** 找第一个空的主背包格（9..35）；没有给 -1。 */
    private static int firstEmptyInventorySlot(LocalPlayer body) {
        var inventory = body.getInventory();
        for (int slot = 9; slot <= 35; slot++) {
            if (inventory.getItem(slot).isEmpty()) return slot;
        }
        return -1;
    }
}
