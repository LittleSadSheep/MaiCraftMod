package org.maiwithu.maicraft.client.actor;

import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapedRecipePattern;
import org.maiwithu.maicraft.core.task.craft.CraftingGridPlacement;
import org.maiwithu.maicraft.core.task.craft.CraftingPlacementPlan;

/** 使用真实原生菜单点击搬运材料，回放余料返还、结果延迟和服务器拒绝，禁止重复提交或伪造产物。 */
public final class CraftingGridPlacementTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        placement(2, true); placement(1, true); placement(2, false); rejectedPickup();
        System.out.println("CraftingGridPlacementTest: passed");
    }

    private static ShapedRecipe recipe() {
        return new ShapedRecipe("", CraftingBookCategory.MISC, ShapedRecipePattern.of(
                Map.of('A', Ingredient.of(Items.COAL), 'B', Ingredient.of(Items.STICK)), List.of("A", "B")),
                new ItemStack(Items.TORCH, 4));
    }

    private static void placement(int count, boolean deliver) throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            h.enableCraftingTransactions();
            h.inventory.setItem(0, new ItemStack(Items.COAL, count));
            var sticks = new ItemStack(Items.STICK, count); sticks.set(DataComponents.CUSTOM_NAME, Component.literal("施工木棍"));
            h.inventory.setItem(1, sticks);
            var menu = h.player.containerMenu; var grid = (CraftingContainer) menu.getSlot(1).container;
            var plan = CraftingPlacementPlan.create(h.player, recipe(), grid);
            var placement = new CraftingGridPlacement(menu, plan, 0, new ItemStack(Items.TORCH, 4));
            CraftingGridPlacement.Outcome outcome = CraftingGridPlacement.Outcome.RUNNING;
            for (int tick = 0; tick < 160 && outcome == CraftingGridPlacement.Outcome.RUNNING; tick++) {
                MenuVisibility.rendered(h.h.minecraft.screen);
                outcome = placement.tick(h.h.context);
                // 输入形成后先收到错误数量，再同步准确结果；摆料器不能用网格变化代替产物确认。
                if (recipe().matches(grid.asCraftInput(), h.level)) {
                    if (deliver && tick >= 35) menu.getSlot(0).set(new ItemStack(Items.TORCH, 4));
                    else menu.getSlot(0).set(new ItemStack(Items.TORCH, 3));
                }
                h.nextTick();
            }
            check(outcome == (deliver ? CraftingGridPlacement.Outcome.READY : CraftingGridPlacement.Outcome.FAILED),
                    "only the exact delayed output can complete native placement");
            check(h.mode.menuClicks == (count == 1 ? 4 : 6) && h.mode.recipePlacements == 0,
                    "each material is picked once, placed once and its real remainder returned once");
            check(h.inventory.countItem(Items.STICK) == count - 1 && h.inventory.countItem(Items.COAL) == count - 1
                    && menu.getCarried().isEmpty(), "a batch commits one item of each kind and leaves no cursor residue");
            check(menu.getSlot(3).getItem().has(DataComponents.CUSTOM_NAME) && h.inventory.countItem(Items.TORCH) == 0,
                    "native clicks preserve ingredient components and do not invent carried output");
        }
    }

    private static void rejectedPickup() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            h.enableCraftingTransactions();
            h.inventory.setItem(0, new ItemStack(Items.COAL, 2)); h.inventory.setItem(1, new ItemStack(Items.STICK, 2));
            var menu = h.player.containerMenu; var grid = (CraftingContainer) menu.getSlot(1).container;
            var placement = new CraftingGridPlacement(menu, CraftingPlacementPlan.create(h.player, recipe(), grid),
                    0, new ItemStack(Items.TORCH, 4));
            for (int tick = 0; tick < 15 && h.mode.menuClicks == 0; tick++) {
                MenuVisibility.rendered(h.h.minecraft.screen); placement.tick(h.h.context); h.nextTick();
            }
            check(h.mode.menuClicks == 1, "one actual native pickup was submitted");
            // 服务端撤回本地预测时，材料和鼠标恢复提交前状态，并以菜单版本更新确认拒绝。
            h.inventory.setItem(0, new ItemStack(Items.COAL, 2)); menu.setCarried(ItemStack.EMPTY); menu.incrementStateId();
            check(placement.tick(h.h.context) == CraftingGridPlacement.Outcome.FAILED && h.mode.menuClicks == 1,
                    "a rejected pickup ends without resubmitting or placing phantom ingredients");
        }
    }

    private static void check(boolean condition, String reason) { if (!condition) throw new AssertionError(reason); }
}
