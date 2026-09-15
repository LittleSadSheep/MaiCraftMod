// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import java.lang.reflect.Proxy;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.core.task.FirstPersonActionGate;

/**
 * 复现扩展快捷栏模组（如 HotBaaaar）把 Inventory.selected 抬到 9 以上的自卫选武场景：
 * 背包武器上快捷栏的交换目标必须折回原版 0~8，交换、确认、关闭、选中整个流程照常走完。
 * 未装该类模组时 selected 不会越界，行为与从前一致。
 */
public final class FirstPersonGateExtendedHotbarTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        swapTargetFoldsIntoVanillaRange();
        extendedSelectionStillStagesThroughAVanillaSlot();
        System.out.println("FirstPersonGateExtendedHotbarTest: extended hotbar selection staging passed");
    }

    private static void swapTargetFoldsIntoVanillaRange() {
        check(VanillaHotbar.swapTarget(0) == 0 && VanillaHotbar.swapTarget(8) == 8,
                "vanilla hotbar slots must pass through unchanged");
        check(VanillaHotbar.swapTarget(15) == 8 && VanillaHotbar.swapTarget(35) == 8,
                "extended selections fold onto the highest vanilla slot");
        check(VanillaHotbar.swapTarget(-1) == 0, "negative selections fold onto the first vanilla slot");
    }

    private static void extendedSelectionStillStagesThroughAVanillaSlot() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            Harness h = new Harness(world);
            world.inventory.selected = 15;   // 扩展快捷栏模组滚轮之后的真实状态
            world.inventory.setItem(15, new ItemStack(Items.DIRT, 3));
            world.inventory.setItem(20, new ItemStack(Items.IRON_SWORD));
            var gate = new FirstPersonActionGate();
            check(h.step(gate) == FirstPersonActionGate.Status.RUNNING && h.opens == 1 && h.swaps == 0,
                    "open a rendered inventory before any swap");
            h.rendered = true;
            check(h.step(gate) == FirstPersonActionGate.Status.RUNNING && h.swaps == 1,
                    "the sword is staged through one swap submission");
            check(h.swapSource == 20 && h.swapHotbar >= 0 && h.swapHotbar <= 8,
                    "an extended selected slot must be folded back into the vanilla swap range");
            check(h.step(gate) == FirstPersonActionGate.Status.RUNNING && h.swaps == 1,
                    "the pending swap receipt is polled, never replayed");
            h.allowSwap = true;
            check(h.step(gate) == FirstPersonActionGate.Status.RUNNING,
                    "a confirmed swap settles before the menu closes");
            check(h.step(gate) == FirstPersonActionGate.Status.RUNNING && h.closes == 1,
                    "the staging inventory closes once the swap is confirmed");
            h.allowClose = true;
            check(h.step(gate) == FirstPersonActionGate.Status.RUNNING && h.selections == 1 && h.selectedSlot == h.swapHotbar,
                    "the folded vanilla slot becomes the hotbar selection");
            check(h.step(gate) == FirstPersonActionGate.Status.RUNNING,
                    "the hotbar selection receipt is polled to completion");
            check(h.step(gate) == FirstPersonActionGate.Status.READY && world.inventory.selected == h.swapHotbar,
                    "the staged sword reaches the hand and the gate reports ready");
            gate.reset();
        }
    }

    /** 菜单与动作端口用代理记账，物品与选择落在真实背包上；每次 step 前进一个测试刻。 */
    private static final class Harness {
        final InteractionWorldTestHarness world;
        final LocalPlayerContext context;
        int opens, swaps, closes, selections, swapSource, swapHotbar, selectedSlot;
        long tick, mutationTick = -1;
        boolean visible, rendered, allowSwap, allowClose;
        MenuReceipt swap, close;
        NativeActionReceipt selecting;

        Harness(InteractionWorldTestHarness world) throws Exception {
            this.world = world;
            world.nextTick();
            MenuPort menus = (MenuPort) Proxy.newProxyInstance(MenuPort.class.getClassLoader(),
                    new Class<?>[]{MenuPort.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "ensureVisible" -> {
                            if (!visible) {
                                opens++; visible = true; mutationTick = tick;
                                yield false;   // 第一刻只宣布打开，画面尚未渲染。
                            }
                            yield rendered && mutationTick != tick;
                        }
                        case "swapInventoryToHotbar" -> {
                            check(visible && rendered && mutationTick != tick,
                                    "swap must use a rendered GUI and a free native mutation tick");
                            mutationTick = tick; swaps++; swapSource = (int) args[1]; swapHotbar = (int) args[2];
                            swap = new MenuReceipt(MenuReceipt.Kind.SWAP_TO_HOTBAR,
                                    (LocalPlayerContext) args[0], 0, 0, 20, false, null);
                            yield swap;
                        }
                        case "close" -> {
                            check(mutationTick != tick, "close cannot share the swap tick");
                            mutationTick = tick; closes++;
                            close = new MenuReceipt(MenuReceipt.Kind.CLOSE,
                                    (LocalPlayerContext) args[0], 0, 0, 20, true, null);
                            yield close;
                        }
                        case "poll" -> {
                            if (args[1] == swap && allowSwap && tick > swap.submittedTick() && !swap.terminal()) {
                                // 模拟服务器确认：剑与被挪开的物品在两个槽位间真实对调。
                                ItemStack staged = world.inventory.getItem(swapSource);
                                world.inventory.setItem(swapSource, world.inventory.getItem(swapHotbar));
                                world.inventory.setItem(swapHotbar, staged);
                                swap.finish(MenuReceipt.Status.CONFIRMED_APPLIED, "extended staging fixture confirmed");
                            }
                            if (args[1] == close && allowClose && tick > close.submittedTick() && !close.terminal()) {
                                visible = false; world.h.minecraft.screen = null;
                                close.finish(MenuReceipt.Status.CONFIRMED_APPLIED, "extended staging close confirmed");
                            }
                            yield args[1];
                        }
                        case "closeForTaskBoundary" -> null;
                        default -> throw new AssertionError("unexpected menu action " + method.getName());
                    });
            NativeActionPort actions = (NativeActionPort) Proxy.newProxyInstance(NativeActionPort.class.getClassLoader(),
                    new Class<?>[]{NativeActionPort.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "selectHotbar" -> {
                            check(mutationTick != tick, "selection cannot share the swap or close tick");
                            mutationTick = tick; selections++; selectedSlot = (int) args[1];
                            world.inventory.selected = selectedSlot;   // 与真实端口一致：本地立即切换。
                            yield selecting = new NativeActionReceipt(NativeActionReceipt.Kind.SELECT_HOTBAR,
                                    (LocalPlayerContext) args[0], (int) args[2], 1,
                                    NativeConfirmation.hotbarSelected(selectedSlot), null, null);
                        }
                        case "poll" -> {
                            if (args[1] == selecting && tick > selecting.submittedTick() && !selecting.terminal()) {
                                selecting.finish(NativeActionReceipt.Status.CONFIRMED_APPLIED,
                                        "extended staging hotbar selection confirmed");
                            }
                            yield args[1];
                        }
                        default -> throw new AssertionError("unexpected native action " + method.getName());
                    });
            context = (LocalPlayerContext) Proxy.newProxyInstance(
                    LocalPlayerContext.class.getClassLoader(), new Class<?>[]{LocalPlayerContext.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "player" -> world.player;
                        case "minecraft" -> world.h.minecraft;
                        case "menus" -> menus;
                        case "actions" -> actions;
                        case "mutationAvailable" -> mutationTick != tick;
                        case "isCurrent" -> true;
                        case "requireCurrent" -> null;
                        case "bodyEpoch", "controlRevision" -> 1L;
                        case "tickRevision" -> tick;
                        default -> throw new AssertionError("unexpected context access " + method.getName());
                    });
        }

        FirstPersonActionGate.Status step(FirstPersonActionGate gate) {
            tick++;
            return gate.select(context, world.player, 20);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
