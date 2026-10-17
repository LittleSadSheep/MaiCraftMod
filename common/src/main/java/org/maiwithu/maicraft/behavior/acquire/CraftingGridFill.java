// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;

import org.maiwithu.maicraft.game.menu.MenuConfirmation;
import org.maiwithu.maicraft.game.menu.PendingMenuAction;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.ActionStatus;

/**
 * 配方簿点不了时把料逐格点进合成格：左键拿起一堆料，右键往要它的格子各放几件，剩下的放回原格；
 * 全部摆完等产出格出这条配方的成品。工作台的 3×3 和背包的 2×2 合成格都用它。
 *
 * <p>每一下都等游戏确认再点下一下，不改背包数据凑配方。这一下没得到确认、摆完产出格不出货（这条配方的摆法对不上）
 * 都如实失败；合成格和鼠标上的料关界面时原版会还回背包。哪格放什么、从哪堆拿见 {@link CraftingGridPlan}。
 */
final class CraftingGridFill {

    /** 一下点击等游戏确认的期限（刻）。 */
    private static final int CLICK_TIMEOUT_TICKS = 100;
    /** 摆完等产出格出货的期限（刻）：成品是服务器算好同步过来的，差一两个包。 */
    private static final int RESULT_WAIT_TICKS = 40;

    /** 一下点击：点哪一格、哪个键，点完要看到什么。 */
    private record Click(int slot, int button, Expect expect, String what) {}

    /** 点完要看到的：鼠标上拿起了东西、这一格多了一件、鼠标上空了。 */
    private enum Expect { CURSOR_FILLED, SLOT_GREW, CURSOR_EMPTIED }

    private final List<Click> clicks;
    private final int resultSlot;
    private final String resultItem;
    private final String resultName;
    private final int crafts;
    private int next;
    private int resultWaited;
    private PendingMenuAction pending;

    private CraftingGridFill(List<Click> clicks, int resultSlot, String resultItem, String resultName, int crafts) {
        this.clicks = List.copyOf(clicks);
        this.resultSlot = resultSlot;
        this.resultItem = resultItem;
        this.resultName = resultName;
        this.crafts = crafts;
    }

    /**
     * 照身上的料排一轮逐格摆料；配方形状摆不进这个合成格、或身上的料连一份都摆不满时给空。
     *
     * @param holder      游戏自己的配方对象：按它的形状与原料定每格放什么
     * @param gridWidth   合成格的宽（工作台 3，背包 2）
     * @param gridSlots   合成格各格的界面格号，按行排
     * @param resultSlot  产出格的界面格号
     * @param sourceSlots 身上能拿料的各格的界面格号
     * @param sources     这些格里此刻放着的东西，和 sourceSlots 一一对应
     * @param wanted      这一轮想做几份
     * @param result      要做出来的东西（物品 ID 与给人看的名字）
     */
    static Optional<CraftingGridFill> plan(RecipeHolder<?> holder, int gridWidth, List<Integer> gridSlots,
            int resultSlot, List<Integer> sourceSlots, List<ItemStack> sources, int wanted, WantedItem result) {
        List<Ingredient> ingredients = holder.value().getIngredients();
        List<Boolean> empty = ingredients.stream().map(Ingredient::isEmpty).toList();
        int gridHeight = gridSlots.size() / gridWidth;
        Optional<int[]> layout = holder.value() instanceof ShapedRecipe shaped
                ? CraftingGridPlan.layout(true, shaped.getWidth(), shaped.getHeight(), empty, gridWidth, gridHeight)
                : CraftingGridPlan.layout(false, 0, 0, empty, gridWidth, gridHeight);
        if (layout.isEmpty()) return Optional.empty();
        // 每个要放东西的格子能从哪几堆拿：按原生配料判断，标签、带附魔或改过名的料都按游戏自己的规矩认。
        Map<Integer, List<Integer>> options = new LinkedHashMap<>();
        for (int i = 0; i < ingredients.size(); i++) {
            int cell = layout.get()[i];
            if (cell < 0) continue;
            List<Integer> fits = new ArrayList<>();
            for (int s = 0; s < sources.size(); s++) {
                ItemStack stack = sources.get(s);
                if (!stack.isEmpty() && ingredients.get(i).test(stack)) fits.add(sourceSlots.get(s));
            }
            options.put(gridSlots.get(cell), fits);
        }
        List<CraftingGridPlan.Stack> stacks = new ArrayList<>();
        for (int s = 0; s < sources.size(); s++) {
            ItemStack stack = sources.get(s);
            if (!stack.isEmpty()) stacks.add(new CraftingGridPlan.Stack(sourceSlots.get(s), stack.getCount(),
                    stack.getMaxStackSize()));
        }
        return CraftingGridPlan.plan(options, stacks, wanted).map(pickups -> {
            List<Click> clicks = new ArrayList<>();
            for (CraftingGridPlan.Pickup pickup : pickups) {
                // 左键拿起整堆 → 右键往要它的各格各放几件 → 剩下的左键放回原格。
                clicks.add(new Click(pickup.sourceSlot(), 0, Expect.CURSOR_FILLED, "拿起原料"));
                for (int cell : pickup.cells()) {
                    for (int n = 0; n < pickup.perCell(); n++) {
                        clicks.add(new Click(cell, 1, Expect.SLOT_GREW, "往合成格放原料"));
                    }
                }
                if (pickup.leftover() > 0) {
                    clicks.add(new Click(pickup.sourceSlot(), 0, Expect.CURSOR_EMPTIED, "把剩下的原料放回背包"));
                }
            }
            int crafts = pickups.getFirst().perCell();
            return new CraftingGridFill(clicks, resultSlot, result.specifier(), result.describe(), crafts);
        });
    }

    /** 这一轮摆够几份：摆完点一下产出格（快速移动）就做这么多份。 */
    int crafts() {
        return crafts;
    }

    /** 这一轮一共要点几下：调用方按它多给些时间。 */
    int clickCount() {
        return clicks.size();
    }

    /** 有一下点击还在等游戏确认：这时停下，鼠标上可能还拿着料。 */
    boolean clicking() {
        return pending != null;
    }

    /**
     * 推进一刻：一刻只点一下，等确认了再点下一下；全部摆完、产出格出了这条配方的成品给完成。
     * 别的界面操作没结清或界面还没真正显示时不点，下一刻再看。
     */
    ActionStatus tick(PlayerContext player) {
        if (pending != null) {
            pending = player.menuActions().poll(player, pending);
            if (!pending.terminal()) return ActionStatus.running();
            PendingMenuAction.Status status = pending.status();
            pending = null;
            if (status != PendingMenuAction.Status.CONFIRMED_APPLIED) {
                return ActionStatus.failed(Problem.of(Problem.Kind.REFUSED_BY_GAME,
                        "逐格摆" + resultName + "的料时，第 " + next + " 下（" + clicks.get(next - 1).what()
                                + "）没得到游戏确认"));
            }
            return ActionStatus.progressed();
        }
        if (next < clicks.size()) {
            if (player.menuActions().hasPendingTransaction() || !player.menuActions().ensureVisible(player)) {
                return ActionStatus.running();
            }
            Click click = clicks.get(next++);
            pending = player.menuActions().click(player, click.slot(), click.button(), ClickType.PICKUP,
                    confirmation(player, click), CLICK_TIMEOUT_TICKS);
            return ActionStatus.progressed();
        }
        // 全部摆完：产出格出这条配方的成品才算摆对；出了别的东西或一直不出，说明摆法对不上。
        ItemStack output = player.localPlayer().containerMenu.getSlot(resultSlot).getItem();
        if (!output.isEmpty() && BuiltInRegistries.ITEM.getKey(output.getItem()).toString().equals(resultItem)) {
            return ActionStatus.done();
        }
        if (++resultWaited > RESULT_WAIT_TICKS) {
            return ActionStatus.failed(Problem.of(Problem.Kind.REFUSED_BY_GAME,
                    "原料逐格摆好了，产出格没出" + resultName + "（这条配方的摆法对不上），做不了"));
        }
        return ActionStatus.running();
    }

    // 每一下点完的确认：拿起时鼠标上有了东西，放料时这一格比点之前多，放回时鼠标上空了。
    private static MenuConfirmation confirmation(PlayerContext player, Click click) {
        return switch (click.expect()) {
            case CURSOR_FILLED -> (who, sent) -> who.localPlayer().containerMenu.getCarried().isEmpty()
                    ? MenuConfirmation.Verdict.PENDING : MenuConfirmation.Verdict.APPLIED;
            case CURSOR_EMPTIED -> (who, sent) -> who.localPlayer().containerMenu.getCarried().isEmpty()
                    ? MenuConfirmation.Verdict.APPLIED : MenuConfirmation.Verdict.PENDING;
            case SLOT_GREW -> {
                int before = player.localPlayer().containerMenu.getSlot(click.slot()).getItem().getCount();
                yield (who, sent) -> who.localPlayer().containerMenu.getSlot(click.slot()).getItem().getCount() > before
                        ? MenuConfirmation.Verdict.APPLIED : MenuConfirmation.Verdict.PENDING;
            }
        };
    }
}
