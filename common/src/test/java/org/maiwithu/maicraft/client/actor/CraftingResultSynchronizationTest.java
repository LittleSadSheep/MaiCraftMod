package org.maiwithu.maicraft.client.actor;

import net.minecraft.SharedConstants;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import org.maiwithu.maicraft.core.task.craft.CraftCompanionTask;
import org.maiwithu.maicraft.core.task.craft.CraftTaskRecord;

/** 先收到菜单版本、再收到红石粉结果槽；任务等待同一次摆配方完成，不能把分包同步误报为缺料。 */
public final class CraftingResultSynchronizationTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        scenario(true); scenario(false);
        System.out.println("CraftingResultSynchronizationTest: passed");
    }
    private static void scenario(boolean deliver) throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            var menu = new InventoryMenu(h.inventory, true, h.player);
            ActorControlTestHarness.field(h.player.getClass(), "inventoryMenu").set(h.player, menu);
            h.player.containerMenu = menu; h.h.minecraft.screen = h.h.inventoryScreen();
            var id = ResourceLocation.parse("minecraft:redstone");
            var recipe = new RecipeHolder<>(id, new ShapelessRecipe("", CraftingBookCategory.MISC,
                    new ItemStack(Items.REDSTONE, 9), NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.REDSTONE_BLOCK))));
            var task = new CraftCompanionTask(h.player, new CraftTaskRecord("split-redstone", 1000, id, 9, null));
            set(task, "recipe", recipe); set(task, "plannedOutput", new ItemStack(Items.REDSTONE, 9));
            set(task, "outputPerBatch", 9); set(task, "plannedBatches", 1);
            var stage = CraftCompanionTask.class.getDeclaredField("stage"); stage.setAccessible(true);
            for (Object value : stage.getType().getEnumConstants()) if (value.toString().equals("PLACE")) stage.set(task, value);
            var place = CraftCompanionTask.class.getDeclaredMethod("placeRecipe"); place.setAccessible(true);
            // 先真实经过可见界面与渲染等待，再由DefaultMenuPort提交一次原生配方请求。
            for (int tick = 0; tick < 12 && h.mode.recipePlacements == 0; tick++) {
                place.invoke(task); h.nextTick(); MenuVisibility.rendered(h.h.minecraft.screen);
            }
            check(h.mode.recipePlacements == 1, "one native placement was submitted");
            menu.incrementStateId(); place.invoke(task);
            check(stage.get(task).toString().equals("PLACE"), "a menu revision with an empty result must keep waiting");
            menu.getSlot(0).set(new ItemStack(Items.REDSTONE, 8)); h.nextTick(); place.invoke(task);
            check(stage.get(task).toString().equals("PLACE"), "the wrong batch count cannot confirm placement");
            var changed = new ItemStack(Items.REDSTONE, 9); changed.set(DataComponents.CUSTOM_NAME, Component.literal("different"));
            menu.getSlot(0).set(changed); h.nextTick(); place.invoke(task);
            check(stage.get(task).toString().equals("PLACE"), "different output components cannot confirm placement");
            if (deliver) {
                menu.getSlot(0).set(new ItemStack(Items.REDSTONE, 9)); menu.incrementStateId(); h.nextTick(); place.invoke(task);
                check(stage.get(task).toString().equals("TAKE"), "the later exact synchronized result enables one batch pickup");
            } else {
                // 一直收不到准确产物时仍有原生确认期限；退出等待后只收尾网格，不能再次摆放配方。
                for (int tick = 0; tick < 35 && stage.get(task).toString().equals("PLACE"); tick++) {
                    h.nextTick(); place.invoke(task);
                }
                check(stage.get(task).toString().equals("RETURN_GRID"), "missing output ends in bounded grid cleanup");
            }
            check(h.mode.recipePlacements == 1 && h.inventory.countItem(Items.REDSTONE) == 0,
                    "waiting neither resubmits the recipe nor invents carried output");
        }
    }
    private static void set(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
    private static void check(boolean condition, String reason) { if (!condition) throw new AssertionError(reason); }
}
