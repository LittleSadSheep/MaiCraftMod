// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.deposit;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.world.entity.player.Inventory;

import org.maiwithu.maicraft.game.menu.PendingMenuAction;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.ClientBackpackView;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 把副手那一堆放回背包的生产实现：挑主背包一个空格，原生 SWAP 把副手的东西换进去（像玩家在背包里把副手的拖回格子）。
 * 整理背包时副手攥着的玫瑰石英这类也一起存掉，不留在手里。
 */
final class LiveOffhandClearing implements DepositSeams.ClearsOffhand {

    /** 等游戏确认换好的期限（刻）。 */
    private static final int SWAP_TICKS = 40;

    private final Supplier<PlayerContext> context;

    LiveOffhandClearing(Supplier<PlayerContext> context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override public Optional<BackpackStack> held() {
        PlayerContext player = context.get();
        if (player == null || player.localPlayer() == null) return Optional.empty();
        var stack = player.localPlayer().getOffhandItem();
        return stack.isEmpty() ? Optional.empty() : Optional.of(ClientBackpackView.snapshot(stack));
    }

    @Override public Optional<Action> intoBackpack() {
        PlayerContext player = context.get();
        if (player == null || player.localPlayer() == null) return Optional.empty();
        int empty = emptySlot(player.localPlayer().getInventory());
        return empty < 0 ? Optional.empty() : Optional.of(new Swap(empty));
    }

    // 主背包的空格：先找背包里的（不占快捷栏），没有再找快捷栏的。
    private static int emptySlot(Inventory inventory) {
        for (int slot = 9; slot < 36; slot++) if (inventory.getItem(slot).isEmpty()) return slot;
        for (int slot = 0; slot < 9; slot++) if (inventory.getItem(slot).isEmpty()) return slot;
        return -1;
    }

    /** 一下原生 SWAP：空格与副手对调；界面没闲下来就等，换好按游戏确认算。 */
    private final class Swap implements Action {
        private final int slot;
        private PendingMenuAction pending;

        Swap(int slot) {
            this.slot = slot;
        }

        @Override public ActionStatus tick(TickContext tick) {
            PlayerContext player = tick.player();
            if (player == null || player.localPlayer() == null) return ActionStatus.running();
            if (pending == null) {
                if (!player.canInteractThisTick()) return ActionStatus.running();
                try {
                    // 界面没关、光标挂着东西：先关上回到世界里再换。
                    if (!player.menuActions().ensureWorldVisible(player)) return ActionStatus.running();
                    pending = player.menuActions().swapInventoryToOffhand(player, slot, SWAP_TICKS);
                } catch (IllegalStateException busy) {
                    return ActionStatus.running();
                }
                return ActionStatus.progressed();
            }
            if (!pending.terminal()) {
                pending = player.menuActions().poll(player, pending);
                return ActionStatus.running();
            }
            return pending.status() == PendingMenuAction.Status.CONFIRMED_APPLIED ? ActionStatus.done()
                    : ActionStatus.failed(Problem.of(Problem.Kind.STUCK, "副手的东西没能放回背包：" + pending.detail()));
        }

        @Override public String describe() {
            return "把副手的东西放回背包";
        }
    }
}
