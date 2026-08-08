package org.maiwithu.maicraft.core.task.craft;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.MenuConfirmation;
import org.maiwithu.maicraft.client.actor.MenuReceipt;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.client.actor.NativeConfirmation;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.PlayerInv;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.act.Interaction;
import org.maiwithu.maicraft.entity.InputDriver;
import org.maiwithu.maicraft.task.TaskState;

/** Receipt-driven recipe placement, result take, and menu close. */
public final class CraftCompanionTask extends AbstractCompanionTask<CraftTaskRecord> {
    private enum Stage { CLOSE_WRONG_MENU, OPEN, PLACE, TAKE, CLOSE }
    private Stage stage;
    private RecipeHolder<?> recipe;
    private NativeActionReceipt openReceipt;
    private MenuReceipt menuReceipt;
    private int resultSlot = -1;
    private Item output;
    private int beforeCount;
    private int crafted;
    private int stationAimTicks;
    private boolean requiresTable;

    public CraftCompanionTask(LocalPlayer player, CraftTaskRecord record) { super(player, record); }

    @Override protected void onStart() {
        var manager = ClientRuntime.requireContext(player).connection().getRecipeManager();
        for (RecipeHolder<?> candidate : manager.getRecipes()) {
            if (candidate.id().toString().equals(r.recipeId.toString())) { recipe = candidate; break; }
        }
        if (recipe == null) {
            fail("recipe is not known to this client: " + r.recipeId, FailureType.NO_MATERIAL);
            return;
        }
        requiresTable = requiresThreeByThree(recipe);
        if (hasCraftGrid(requiresTable ? 3 : 2, requiresTable ? 3 : 2)) {
            stage = Stage.PLACE;
        } else if (player.containerMenu != player.inventoryMenu) {
            stage = Stage.CLOSE_WRONG_MENU;
        } else if (requiresTable && r.station == null) {
            fail("recipe " + r.recipeId
                    + " needs an open compatible 3x3 crafting surface or an explicit station",
                    FailureType.NO_SUPPORT);
        } else {
            stage = requiresTable ? Stage.OPEN : Stage.PLACE;
        }
    }

    @Override protected TaskState onTick() {
        return switch (stage) {
            case CLOSE_WRONG_MENU -> closeWrongMenu();
            case OPEN -> openStation();
            case PLACE -> placeRecipe();
            case TAKE -> takeResult();
            case CLOSE -> closeMenu();
        };
    }

    private TaskState closeWrongMenu() {
        var context = ClientRuntime.requireContext(player);
        if (menuReceipt == null) {
            menuReceipt = context.menus().close(context, 20);
            return TaskState.RUNNING;
        }
        menuReceipt = context.menus().poll(context, menuReceipt);
        if (!menuReceipt.terminal()) return TaskState.RUNNING;
        if (menuReceipt.status() != MenuReceipt.Status.CONFIRMED_APPLIED) {
            fail("could not close the non-crafting menu: " + menuReceipt.detail(), FailureType.UNKNOWN);
            return TaskState.FAILED;
        }
        menuReceipt = null;
        if (requiresTable) {
            if (r.station == null) {
                fail("recipe " + r.recipeId + " needs an explicit 3x3 crafting station",
                        FailureType.NO_SUPPORT);
                return TaskState.FAILED;
            }
            stage = Stage.OPEN;
        } else stage = Stage.PLACE;
        return TaskState.RUNNING;
    }

    private TaskState openStation() {
        var context = ClientRuntime.requireContext(player);
        if (!context.level().isLoaded(r.station)) {
            fail("crafting station is outside loaded client terrain", FailureType.TARGET_LOST);
            return TaskState.FAILED;
        }
        if (openReceipt == null) {
            if (player.getEyePosition().distanceTo(Vec3.atCenterOf(r.station)) > 4.5) {
                fail("crafting station is outside interaction reach", FailureType.OUT_OF_REACH);
                return TaskState.FAILED;
            }
            InputDriver.lookAt(player, Vec3.atCenterOf(r.station));
            HitResult aimed = Interaction.nativeRaytrace(player, 4.5);
            if (!(aimed instanceof BlockHitResult hit) || !hit.getBlockPos().equals(r.station)) {
                if (++stationAimTicks >= 5) {
                    fail("crafting station is occluded from the current stance", FailureType.OCCLUDED);
                    return TaskState.FAILED;
                }
                return TaskState.RUNNING;
            }
            int beforeMenu = player.containerMenu.containerId;
            openReceipt = context.actions().useBlock(context, InteractionHand.MAIN_HAND, hit,
                    NativeConfirmation.menuChanged(beforeMenu), 30);
            return TaskState.RUNNING;
        }
        openReceipt = context.actions().poll(context, openReceipt);
        if (!openReceipt.terminal()) return TaskState.RUNNING;
        if (openReceipt.status() != NativeActionReceipt.Status.CONFIRMED_APPLIED) {
            fail("crafting station did not open: " + openReceipt.detail(), FailureType.UNKNOWN);
            return TaskState.FAILED;
        }
        openReceipt = null;
        stage = Stage.PLACE;
        return TaskState.RUNNING;
    }

    private TaskState placeRecipe() {
        var context = ClientRuntime.requireContext(player);
        if (menuReceipt == null) {
            menuReceipt = context.menus().placeRecipe(
                    context, recipe, false, MenuConfirmation.stateChanged(), 30);
            return TaskState.RUNNING;
        }
        menuReceipt = context.menus().poll(context, menuReceipt);
        if (!menuReceipt.terminal()) return TaskState.RUNNING;
        if (menuReceipt.status() != MenuReceipt.Status.CONFIRMED_APPLIED) {
            fail("recipe placement was not confirmed: " + menuReceipt.detail(), FailureType.NO_MATERIAL);
            return TaskState.FAILED;
        }
        menuReceipt = null;
        resultSlot = findResultSlot();
        if (resultSlot < 0 || player.containerMenu.getSlot(resultSlot).getItem().isEmpty()) {
            fail("the current menu cannot form recipe " + r.recipeId
                    + "; open the required crafting surface and ensure its materials are present",
                    FailureType.NO_MATERIAL);
            return TaskState.FAILED;
        }
        output = player.containerMenu.getSlot(resultSlot).getItem().getItem();
        beforeCount = PlayerInv.count(player.getInventory(), output);
        stage = Stage.TAKE;
        return TaskState.RUNNING;
    }

    private TaskState takeResult() {
        var context = ClientRuntime.requireContext(player);
        if (menuReceipt == null) {
            menuReceipt = context.menus().click(context, resultSlot, 0, ClickType.QUICK_MOVE,
                    (c, ignored) -> PlayerInv.count(c.player().getInventory(), output) > beforeCount
                            ? MenuConfirmation.Verdict.APPLIED : MenuConfirmation.Verdict.PENDING,
                    40);
            return TaskState.RUNNING;
        }
        menuReceipt = context.menus().poll(context, menuReceipt);
        if (!menuReceipt.terminal()) return TaskState.RUNNING;
        if (menuReceipt.status() != MenuReceipt.Status.CONFIRMED_APPLIED) {
            fail("crafted result was not received: " + menuReceipt.detail(), FailureType.NO_SPACE);
            return TaskState.FAILED;
        }
        menuReceipt = null;
        crafted += PlayerInv.count(player.getInventory(), output) - beforeCount;
        if (crafted >= r.count) {
            stage = Stage.CLOSE;
        } else {
            stage = Stage.PLACE;
        }
        return TaskState.RUNNING;
    }

    private TaskState closeMenu() {
        var context = ClientRuntime.requireContext(player);
        if (menuReceipt == null) {
            menuReceipt = context.menus().close(context, 20);
            return TaskState.RUNNING;
        }
        menuReceipt = context.menus().poll(context, menuReceipt);
        if (!menuReceipt.terminal()) return TaskState.RUNNING;
        if (menuReceipt.status() == MenuReceipt.Status.CONFIRMED_APPLIED) return TaskState.SUCCESS;
        fail("craft completed but menu close was not confirmed: " + menuReceipt.detail(),
                FailureType.UNKNOWN);
        return TaskState.FAILED;
    }

    private int findResultSlot() {
        for (int i = 0; i < player.containerMenu.slots.size(); i++) {
            if (player.containerMenu.getSlot(i) instanceof ResultSlot) return i;
        }
        return -1;
    }

    private boolean hasCraftGrid(int width, int height) {
        for (Slot slot : player.containerMenu.slots) {
            if (slot.container instanceof CraftingContainer grid
                    && grid.getWidth() >= width && grid.getHeight() >= height) return true;
        }
        return false;
    }

    private static boolean requiresThreeByThree(RecipeHolder<?> holder) {
        if (!(holder.value() instanceof CraftingRecipe crafting)) return true;
        if (crafting instanceof ShapedRecipe shaped) {
            return shaped.getWidth() > 2 || shaped.getHeight() > 2;
        }
        return crafting.getIngredients().stream().filter(ingredient -> !ingredient.isEmpty()).count() > 4;
    }

    @Override protected void cleanup() { openReceipt = null; menuReceipt = null; }
    @Override protected Map<String, Object> resultData() {
        Map<String, Object> data = new HashMap<>();
        data.put("recipe", r.recipeId.toString()); data.put("crafted", crafted); return data;
    }
    @Override protected String successMessage() { return "crafted " + crafted + " item(s) via " + r.recipeId; }
    @Override protected String cancelledMessage() { return "craft interrupted"; }
}
