// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.Objects;
import java.util.function.Supplier;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;

import org.maiwithu.maicraft.game.menu.MenuConfirmation;
import org.maiwithu.maicraft.game.menu.PendingMenuAction;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 在背包的 2×2 合成格里做东西：像玩家按 E 打开背包，经配方簿摆一份原料，产出格一出货就整堆收进背包，
 * 做够件数（按背包里实际多出来的算）关上背包。木板、木棍、工作台这类摆得进 2×2 的合成都走这里，
 * 新世界开局不用先有一张工作台。
 *
 * <p>每一下都等游戏确认再走下一步：配方簿摆完要看到产出格出了东西，取货要看到产出格变了。
 * 配方簿摆不进去（身上缺料、配方还没解锁）如实失败，引擎换别的路。被打断或收尾时请游戏关上背包，
 * 合成格里剩下的原版会还回背包。
 */
final class InventoryCrafting implements Action {

    /** 背包界面里产出格的槽位号。 */
    private static final int RESULT_SLOT = InventoryMenu.RESULT_SLOT;
    /** 一下点击等游戏确认的期限（刻）。 */
    private static final int CLICK_TIMEOUT_TICKS = 100;
    /** 一次背包合成的总期限（刻）：开背包、摆料、取货几下就够，留足余量。 */
    private static final int GIVE_UP_TICKS = 20 * 60;

    /** 打开背包 → 摆料与收货 → 关上 → 结束。 */
    private enum Stage { OPEN, WORK, CLOSE, FINISHED }

    /** 一下点击确认之后要做的结算。 */
    @FunctionalInterface
    private interface Settlement {
        ActionStatus settle(PendingMenuAction.Status status);
    }

    private final RecipeHolder<?> holder;
    private final WorkstationRecipe recipe;
    private final int targetItems;
    private final Supplier<PlayerContext> contexts;

    private Stage stage = Stage.OPEN;
    private int waited;
    /** 背包里实际多出来的产出件数：每次取货按取货前后背包里的数量差记。 */
    private int produced;
    private PendingMenuAction pending;
    private Settlement settlement;

    /**
     * @param holder 游戏自己的配方对象：配方簿只认它
     * @param recipe 这条配方的只读快照（产出、单次产出件数）
     * @param times  要做几次
     * @param contexts 当刻的角色：被打断、收尾时没有本刻上下文，从这里取来请游戏关背包
     */
    InventoryCrafting(RecipeHolder<?> holder, WorkstationRecipe recipe, int times, Supplier<PlayerContext> contexts) {
        this.holder = Objects.requireNonNull(holder, "holder");
        this.recipe = Objects.requireNonNull(recipe, "recipe");
        this.targetItems = times * recipe.resultCount();
        this.contexts = Objects.requireNonNull(contexts, "contexts");
    }

    @Override
    public ActionStatus tick(TickContext tick) {
        PlayerContext player = tick.player();
        if (player == null || player.localPlayer() == null) {
            return ActionStatus.running();
        }
        if (stage == Stage.FINISHED) {
            return ActionStatus.done();
        }
        if (++waited > GIVE_UP_TICKS) {
            return giveUp(player, Problem.of(Problem.Kind.STUCK, "在背包里做" + recipe.result().describe() + "超时，先收手"));
        }
        if (pending != null) {
            return awaitClick(player);
        }
        return switch (stage) {
            case OPEN -> open(player);
            case WORK -> work(player);
            case CLOSE -> closeInventory(player);
            case FINISHED -> ActionStatus.done();
        };
    }

    // 打开背包界面：别的界面开着时不抢（交给关界面的规矩先关掉），背包画出来了才动手。
    private ActionStatus open(PlayerContext player) {
        if (!player.menuActions().ensureVisible(player)) {
            return ActionStatus.running();
        }
        if (player.localPlayer().containerMenu != player.localPlayer().inventoryMenu) {
            return giveUp(player, Problem.of(Problem.Kind.REFUSED_BY_GAME,
                    "开着别的界面，用不了背包的合成格，做不了" + recipe.result().describe()));
        }
        stage = Stage.WORK;
        return ActionStatus.progressed();
    }

    // 摆料与收货：做够了去关背包；产出格有货先收；否则经配方簿摆下一份。
    private ActionStatus work(PlayerContext player) {
        if (produced >= targetItems) {
            stage = Stage.CLOSE;
            return ActionStatus.progressed();
        }
        if (!ready(player)) {
            return ActionStatus.running();
        }
        InventoryMenu menu = player.localPlayer().inventoryMenu;
        ItemStack output = menu.getSlot(RESULT_SLOT).getItem();
        if (!output.isEmpty()) {
            int before = carriedResults(player);
            ItemStack shown = output.copy();
            return submit(player.menuActions().click(player, RESULT_SLOT, 0, ClickType.QUICK_MOVE,
                    resultChanged(shown), CLICK_TIMEOUT_TICKS), status -> {
                        produced += Math.max(0, carriedResults(player) - before);
                        if (status == PendingMenuAction.Status.CONFIRMED_NOT_APPLIED) {
                            return giveUp(player, Problem.of(Problem.Kind.INVENTORY_FULL,
                                    "产出格的" + recipe.result().describe() + "收不进背包，背包可能满了"));
                        }
                        return ActionStatus.progressed();
                    });
        }
        return submit(player.menuActions().placeRecipe(player, holder, false, resultShown(), CLICK_TIMEOUT_TICKS),
                status -> status == PendingMenuAction.Status.CONFIRMED_APPLIED ? ActionStatus.progressed()
                        : giveUp(player, Problem.of(Problem.Kind.NEED_ITEM,
                                "配方簿没有把原料摆进背包的合成格（身上可能缺料，或配方还没解锁），做不了"
                                        + recipe.result().describe())));
    }

    // 关上背包：做出来的已经按背包里的数量算过，关得利不利索不改写这件事。
    private ActionStatus closeInventory(PlayerContext player) {
        if (!ready(player)) {
            return ActionStatus.running();
        }
        return submit(player.menuActions().close(player, CLICK_TIMEOUT_TICKS), status -> {
            stage = Stage.FINISHED;
            return ActionStatus.done();
        });
    }

    private boolean ready(PlayerContext player) {
        return !player.menuActions().hasPendingTransaction() && player.menuActions().ensureVisible(player);
    }

    private ActionStatus submit(PendingMenuAction submitted, Settlement after) {
        pending = submitted;
        settlement = after;
        return ActionStatus.progressed();
    }

    // 等这一下的结果；有了结果交给它的结算决定下一步。
    private ActionStatus awaitClick(PlayerContext player) {
        pending = player.menuActions().poll(player, pending);
        if (!pending.terminal()) {
            return ActionStatus.running();
        }
        PendingMenuAction.Status status = pending.status();
        Settlement after = settlement;
        pending = null;
        settlement = null;
        return after.settle(status);
    }

    // 做不成就收场：请游戏关上背包，合成格里剩下的原版还回背包。
    private ActionStatus giveUp(PlayerContext player, Problem problem) {
        requestClose(player);
        stage = Stage.FINISHED;
        return ActionStatus.failed(problem);
    }

    private void requestClose(PlayerContext player) {
        try {
            player.menuActions().closeForTaskBoundary(player, CLICK_TIMEOUT_TICKS, "背包合成收尾");
        } catch (RuntimeException busy) {
            // 界面入口此刻不接受关闭：留给下一步动作的"先关旧界面"处理，这里不硬抢。
        }
    }

    // 背包里有几件这次要做的东西：取货前后各数一次，差就是这次真收进来的。
    private int carriedResults(PlayerContext player) {
        var item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(recipe.result().itemId()));
        return player.localPlayer().getInventory().countItem(item);
    }

    // 摆料的确认条件：产出格出了东西才算摆上（合成格里的料对上了配方）。
    private static MenuConfirmation resultShown() {
        return (player, click) -> player.localPlayer().inventoryMenu.getSlot(RESULT_SLOT).getItem().isEmpty()
                ? MenuConfirmation.Verdict.PENDING : MenuConfirmation.Verdict.APPLIED;
    }

    // 取货的确认条件：产出格变了（变空、换了东西）才算拿走。
    private static MenuConfirmation resultChanged(ItemStack before) {
        return (player, click) -> ItemStack.matches(player.localPlayer().inventoryMenu.getSlot(RESULT_SLOT).getItem(), before)
                ? MenuConfirmation.Verdict.PENDING : MenuConfirmation.Verdict.APPLIED;
    }

    // 被生存需求打断：关上背包，合成格里的料原版还回背包；恢复后重新打开接着做。
    @Override
    public void pause() {
        if (stage == Stage.FINISHED) return;
        PlayerContext player = currentPlayer();
        pending = null;
        settlement = null;
        if (player != null) requestClose(player);
        stage = Stage.OPEN;
    }

    // 不再做了：关上背包。
    @Override
    public void close() {
        if (stage == Stage.FINISHED) return;
        PlayerContext player = currentPlayer();
        if (player != null) requestClose(player);
        stage = Stage.FINISHED;
    }

    // 收尾时没有本刻的上下文：取当刻的角色，不在世界里就不用关。
    private PlayerContext currentPlayer() {
        PlayerContext player = contexts.get();
        return player == null || player.localPlayer() == null ? null : player;
    }

    @Override
    public Interruptibility interruptibility() {
        return pending != null && !pending.terminal() ? Interruptibility.UNSAFE_TO_STOP : Interruptibility.WORKING;
    }

    @Override
    public String describe() {
        return switch (stage) {
            case OPEN -> "打开背包准备做" + recipe.result().describe();
            case WORK -> "在背包合成格里做" + recipe.result().describe();
            case CLOSE -> "关上背包";
            case FINISHED -> "背包里做完了" + recipe.result().describe();
        };
    }
}
