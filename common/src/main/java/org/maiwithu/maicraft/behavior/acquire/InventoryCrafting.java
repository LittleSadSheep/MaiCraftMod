// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 在背包的 2×2 合成格里做东西：像玩家按 E 打开背包，经配方簿摆一份原料，产出格一出货就整堆收进背包，
 * 做够件数（按背包里实际多出来的算）关上背包。木板、木棍、工作台这类摆得进 2×2 的合成都走这里，
 * 新世界开局不用先有一张工作台。
 *
 * <p>每一下都等游戏确认再走下一步：配方簿摆完要看到产出格出了东西，取货要看到产出格变了。
 * 配方簿点不了（配方还没解锁、原料带附魔或改过名配方簿不认）就逐格摆（见 {@link CraftingGridFill}）；
 * 逐格摆身上的料也摆不满一份才如实失败，引擎换别的路。被打断或收尾时请游戏关上背包，合成格里剩下的原版会还回背包。
 */
final class InventoryCrafting implements Action {

    /** 背包界面里产出格的槽位号。 */
    private static final int RESULT_SLOT = InventoryMenu.RESULT_SLOT;
    /** 一下点击等游戏确认的期限（刻）。 */
    private static final int CLICK_TIMEOUT_TICKS = 100;
    /** 一次背包合成的总期限（刻）：开背包、摆料、取货几下就够，留足余量。 */
    private static final int GIVE_UP_TICKS = 20 * 60;
    /** 背包界面里 2×2 合成格的槽位号（按行排）与身上能拿料的槽位号（主背包与快捷栏）。 */
    private static final List<Integer> GRID_SLOTS = List.of(1, 2, 3, 4);
    private static final int FIRST_CARRIED_SLOT = InventoryMenu.INV_SLOT_START;
    private static final int END_CARRIED_SLOT = InventoryMenu.USE_ROW_SLOT_END;

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
    /** 发起任务的记账口：每取一次货记一笔做出的东西，结果里的变化能和背包净变化对得上；null 不记。 */
    private final TaskRecords records;

    private Stage stage = Stage.OPEN;
    private int waited;
    /** 背包里实际多出来的产出件数：每次取货按取货前后背包里的数量差记。 */
    private int produced;
    private PendingMenuAction pending;
    private Settlement settlement;
    /** 配方簿点不了：这条还没解锁，或配方簿摆过一次没摆上；之后都逐格摆。 */
    private boolean byHand;
    /** 正在逐格摆的这一轮；没在摆时为 null。 */
    private CraftingGridFill filling;
    /** 总期限：逐格摆料时按点击的下数再多给一些。 */
    private int giveUpTicks = GIVE_UP_TICKS;

    /**
     * @param holder 游戏自己的配方对象：配方簿只认它
     * @param recipe 这条配方的只读快照（产出、单次产出件数）
     * @param times  要做几次
     * @param contexts 当刻的角色：被打断、收尾时没有本刻上下文，从这里取来请游戏关背包
     * @param records 发起任务的记账口；null 表示这次执行不接，做了什么都不记
     */
    InventoryCrafting(RecipeHolder<?> holder, WorkstationRecipe recipe, int times, Supplier<PlayerContext> contexts,
            TaskRecords records) {
        this.holder = Objects.requireNonNull(holder, "holder");
        this.recipe = Objects.requireNonNull(recipe, "recipe");
        this.targetItems = times * recipe.resultCount();
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.records = records;
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
        if (++waited > giveUpTicks) {
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

    // 摆料与收货：逐格摆到一半先摆完；做够了去关背包；产出格有货先收；否则摆下一份。
    // 逐格摆到一半时产出格可能先出了别的东西，不去收它。
    private ActionStatus work(PlayerContext player) {
        if (filling != null) {
            return continueFilling(player);
        }
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
                        // 真收进来几件就记几件：自制的东西逐笔记进发起任务的结果，结果里的变化能和背包净变化对得上。
                        int gained = Math.max(0, carriedResults(player) - before);
                        produced += gained;
                        if (gained > 0 && records != null) {
                            records.change(Change.of(Change.Kind.ITEM_GAINED, recipe.result().itemId(), gained));
                        }
                        if (status == PendingMenuAction.Status.CONFIRMED_NOT_APPLIED) {
                            return giveUp(player, Problem.of(Problem.Kind.INVENTORY_FULL,
                                    "产出格的" + recipe.result().describe() + "收不进背包，背包可能满了"));
                        }
                        return ActionStatus.progressed();
                    });
        }
        // 配方簿里这条还没解锁就直接逐格摆；解锁了先走配方簿，没摆上（原料带附魔、改过名配方簿不认）也改逐格摆。
        if (!byHand && !player.localPlayer().getRecipeBook().contains(holder)) {
            byHand = true;
        }
        if (byHand) {
            return placeByHand(player, menu);
        }
        return submit(player.menuActions().placeRecipe(player, holder, false, resultShown(), CLICK_TIMEOUT_TICKS),
                status -> {
                    if (status != PendingMenuAction.Status.CONFIRMED_APPLIED) byHand = true;
                    return ActionStatus.progressed();
                });
    }

    // 逐格摆一轮：合成格里有上一份留下的东西先快速移动回背包，格子空了再按身上的料排一轮；
    // 身上的料一份都摆不满就如实说缺料。
    private ActionStatus placeByHand(PlayerContext player, InventoryMenu menu) {
        for (int slot : GRID_SLOTS) {
            ItemStack left = menu.getSlot(slot).getItem();
            if (left.isEmpty()) continue;
            ItemStack shown = left.copy();
            return submit(player.menuActions().click(player, slot, 0, ClickType.QUICK_MOVE,
                    slotChanged(slot, shown), CLICK_TIMEOUT_TICKS), status ->
                    status == PendingMenuAction.Status.CONFIRMED_APPLIED ? ActionStatus.progressed()
                            : giveUp(player, Problem.of(Problem.Kind.INVENTORY_FULL, "合成格里剩下的"
                                    + shown.getHoverName().getString() + "收不回背包，背包可能满了")));
        }
        List<Integer> carriedSlots = new ArrayList<>();
        List<ItemStack> carried = new ArrayList<>();
        for (int slot = FIRST_CARRIED_SLOT; slot < END_CARRIED_SLOT; slot++) {
            carriedSlots.add(slot);
            carried.add(menu.getSlot(slot).getItem());
        }
        int stillToMake = Math.max(1, (targetItems - produced + recipe.resultCount() - 1) / recipe.resultCount());
        Optional<CraftingGridFill> fill = CraftingGridFill.plan(holder, 2, GRID_SLOTS, RESULT_SLOT, carriedSlots,
                carried, stillToMake, recipe.result());
        if (fill.isEmpty()) {
            return giveUp(player, Problem.of(Problem.Kind.NEED_ITEM, "配方簿点不了，逐格摆时身上的料摆不满一份"
                    + recipe.result().describe()));
        }
        filling = fill.get();
        giveUpTicks += filling.clickCount() * MenuRecipeRuns.TICKS_PER_GRID_CLICK;
        return continueFilling(player);
    }

    // 推进逐格摆的这一轮：摆完、产出格出了成品交回去取货；没得到确认或摆法对不上如实失败并关上背包。
    private ActionStatus continueFilling(PlayerContext player) {
        ActionStatus status = filling.tick(player);
        if (status instanceof ActionStatus.Done) {
            filling = null;
            return ActionStatus.progressed();
        }
        if (status instanceof ActionStatus.Failed failed) {
            filling = null;
            return giveUp(player, failed.problem());
        }
        return status;
    }

    // 点到的格子变了（变空、变少或换了东西）才算这一下生效。
    private static MenuConfirmation slotChanged(int slot, ItemStack before) {
        return (player, click) -> ItemStack.matches(player.localPlayer().containerMenu.getSlot(slot).getItem(), before)
                ? MenuConfirmation.Verdict.PENDING : MenuConfirmation.Verdict.APPLIED;
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
        // 逐格摆到一半被打断：关背包时合成格和鼠标上的料原版还回背包，回来按身上的料重新排一轮。
        filling = null;
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
        // 点到一半、逐格摆到一半时停下不安全：鼠标上可能还拿着料。
        return pending != null && !pending.terminal() || filling != null
                ? Interruptibility.UNSAFE_TO_STOP : Interruptibility.WORKING;
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
