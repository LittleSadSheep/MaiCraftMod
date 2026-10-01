package org.maiwithu.maicraft.core.task.craft;

import java.util.List;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.MenuConfirmation;
import org.maiwithu.maicraft.client.actor.MenuReceipt;
import org.maiwithu.maicraft.client.actor.MenuSynchronization;

/** 取起材料堆 -> 右键放一件 -> 放回余料；逐次确认原生点击后再推进一批合成。 */
public final class CraftingGridPlacement {
    public enum Outcome { RUNNING, READY, FAILED }
    private enum Step { PICK_UP, PLACE_ONE, RETURN_REMAINDER }
    private final AbstractContainerMenu menu;
    private final List<CraftingPlacementPlan.Entry> entries;
    private final ItemStack output;
    private final int resultSlot;
    private int index, confirmedMoves;
    private Step step = Step.PICK_UP;
    private MenuReceipt receipt;
    private ItemStack sourceBefore = ItemStack.EMPTY;
    private long resultDeadline = Long.MIN_VALUE, resultStableSince = Long.MIN_VALUE;
    private String issue;

    public CraftingGridPlacement(AbstractContainerMenu menu, List<CraftingPlacementPlan.Entry> entries,
            int resultSlot, ItemStack output) {
        this.menu = menu; this.entries = List.copyOf(entries); this.resultSlot = resultSlot; this.output = output.copy();
    }

    public int confirmedMoves() { return confirmedMoves; }
    public String issue() { return issue; }

    public Outcome tick(LocalPlayerContext context) {
        context.requireCurrent();
        if (issue != null) return Outcome.FAILED;
        if (context.player().containerMenu != menu) return fail("the active crafting menu changed during native placement");
        if (receipt != null) {
            receipt = context.menus().poll(context, receipt);
            if (!receipt.terminal()) return Outcome.RUNNING;
            if (receipt.status() != MenuReceipt.Status.CONFIRMED_APPLIED)
                return fail("native ingredient move was not confirmed: " + receipt.status() + ": " + receipt.detail());
            receipt = null; confirmedMoves++;
            // 每一步成功都已核对材料原槽、鼠标和目标格；一件材料不用再点空的原槽，整叠余料才放回。
            switch (step) {
                case PICK_UP -> step = Step.PLACE_ONE;
                case PLACE_ONE -> { if (sourceBefore.getCount() == 1) nextIngredient(); else step = Step.RETURN_REMAINDER; }
                case RETURN_REMAINDER -> nextIngredient();
            }
            return Outcome.RUNNING;
        }
        if (index == entries.size()) {
            // 原料已摆好仍不冒称产物形成；等待服务器结果槽的准确物品、组件、数量稳定后才允许取成品。
            if (resultDeadline == Long.MIN_VALUE) resultDeadline = context.tickRevision() + 30;
            if (same(menu.getSlot(resultSlot).getItem(), output)) {
                if (resultStableSince == Long.MIN_VALUE) resultStableSince = context.tickRevision();
                if (context.tickRevision() - resultStableSince >= MenuSynchronization.windowTicks(context)) return Outcome.READY;
            } else resultStableSince = Long.MIN_VALUE;
            return context.tickRevision() >= resultDeadline
                    ? fail("native ingredients were placed but the exact crafting result was not confirmed") : Outcome.RUNNING;
        }
        if (!context.menus().ensureVisible(context)) return Outcome.RUNNING;
        var entry = entries.get(index);
        ItemStack source = menu.getSlot(entry.sourceSlot()).getItem();
        ItemStack target = menu.getSlot(entry.targetSlot()).getItem();
        ItemStack cursor = menu.getCarried();
        switch (step) {
            case PICK_UP -> {
                if (!cursor.isEmpty() || !target.isEmpty() || source.isEmpty()
                        || !ItemStack.isSameItemSameComponents(source, entry.sample()) || !entry.ingredient().test(source))
                    return fail("the selected native ingredient or empty destination changed before pickup");
                sourceBefore = source.copy();
                receipt = context.menus().click(context, entry.sourceSlot(), 0, ClickType.PICKUP, this::observeMove, 40);
            }
            case PLACE_ONE -> {
                if (!source.isEmpty() || !target.isEmpty() || !same(cursor, sourceBefore))
                    return fail("the picked-up ingredient changed before placing one item");
                receipt = context.menus().click(context, entry.targetSlot(), 1, ClickType.PICKUP, this::observeMove, 40);
            }
            case RETURN_REMAINDER -> {
                if (!source.isEmpty() || !same(target, entry.sample()) || !same(cursor, remainder()))
                    return fail("the ingredient remainder changed before returning it to the inventory");
                receipt = context.menus().click(context, entry.sourceSlot(), 0, ClickType.PICKUP, this::observeMove, 40);
            }
        }
        return Outcome.RUNNING;
    }

    private MenuConfirmation.Verdict observeMove(LocalPlayerContext context, MenuReceipt pending) {
        if (context.player().containerMenu != menu) return MenuConfirmation.Verdict.DIVERGED;
        var entry = entries.get(index);
        ItemStack source = menu.getSlot(entry.sourceSlot()).getItem();
        ItemStack target = menu.getSlot(entry.targetSlot()).getItem();
        ItemStack cursor = menu.getCarried();
        boolean after = switch (step) {
            case PICK_UP -> source.isEmpty() && target.isEmpty() && same(cursor, sourceBefore);
            case PLACE_ONE -> source.isEmpty() && same(target, entry.sample()) && same(cursor, remainder());
            case RETURN_REMAINDER -> same(source, remainder()) && same(target, entry.sample()) && cursor.isEmpty();
        };
        if (after) return MenuConfirmation.Verdict.APPLIED;
        boolean before = switch (step) {
            case PICK_UP -> same(source, sourceBefore) && target.isEmpty() && cursor.isEmpty();
            case PLACE_ONE -> source.isEmpty() && target.isEmpty() && same(cursor, sourceBefore);
            case RETURN_REMAINDER -> source.isEmpty() && same(target, entry.sample()) && same(cursor, remainder());
        };
        // 服务器拒绝或槽位发生第三种变化时如实停下，不重复点击已经可能执行的原生搬运。
        return before ? MenuConfirmation.Verdict.NOT_APPLIED : MenuConfirmation.Verdict.DIVERGED;
    }

    private ItemStack remainder() { return sourceBefore.copyWithCount(sourceBefore.getCount() - 1); }
    private void nextIngredient() { index++; step = Step.PICK_UP; sourceBefore = ItemStack.EMPTY; }
    private Outcome fail(String reason) { issue = reason; return Outcome.FAILED; }
    private static boolean same(ItemStack actual, ItemStack expected) {
        if (actual.isEmpty() || expected.isEmpty()) return actual.isEmpty() && expected.isEmpty();
        return actual.getCount() == expected.getCount() && ItemStack.isSameItemSameComponents(actual, expected);
    }
}
