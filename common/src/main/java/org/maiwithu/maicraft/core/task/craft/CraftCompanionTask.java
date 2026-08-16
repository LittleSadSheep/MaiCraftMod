package org.maiwithu.maicraft.core.task.craft;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
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
import org.maiwithu.maicraft.core.task.build.BuildTaskRecord;
import org.maiwithu.maicraft.core.task.move.MoveToTaskRecord;
import org.maiwithu.maicraft.core.act.Interaction;
import org.maiwithu.maicraft.entity.InputDriver;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/** Receipt-driven recipe placement, result take, and menu close. */
public final class CraftCompanionTask extends AbstractCompanionTask<CraftTaskRecord> {
    private static final double AIM_CONVERGENCE_DOT = Math.cos(Math.toRadians(1.0D));
    /** Renewed only after concrete state progress; this is a no-progress lease, not a total cap. */
    private static final long PROGRESS_LEASE_TICKS = 60L * 20L;

    private enum Stage { CLOSE_WRONG_MENU, PREPARE_SURFACE, OPEN, PLACE, TAKE, CLOSE }
    private Stage stage;
    private RecipeHolder<?> recipe;
    private NativeActionReceipt openReceipt;
    private MenuReceipt menuReceipt;
    private int resultSlot = -1;
    private Item output;
    private int beforeCount;
    private int crafted;
    private long stationAimRequestedRevision = Long.MIN_VALUE;
    private boolean requiresTable;
    private final CraftingWorkstationCoordinator workstation =
            new CraftingWorkstationCoordinator();
    private BlockPos station;
    private Task surfaceChild;
    private TaskRecord surfaceRecord;
    private CraftingWorkstationCoordinator.Directive surfaceDirective;
    private int surfaceChildSerial;
    private boolean stationPlaced;

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
        station = r.station;
        if (hasCraftGrid(requiresTable ? 3 : 2, requiresTable ? 3 : 2)) {
            stage = Stage.PLACE;
        } else if (player.containerMenu != player.inventoryMenu) {
            stage = Stage.CLOSE_WRONG_MENU;
        } else {
            stage = requiresTable ? Stage.PREPARE_SURFACE : Stage.PLACE;
        }
    }

    @Override protected TaskState onTick() {
        if (surfaceChild != null) return tickSurfaceChild();
        return switch (stage) {
            case CLOSE_WRONG_MENU -> closeWrongMenu();
            case PREPARE_SURFACE -> prepareSurface();
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
        renewProgressLease();
        if (requiresTable) {
            stage = Stage.PREPARE_SURFACE;
        } else stage = Stage.PLACE;
        return TaskState.RUNNING;
    }

    private TaskState prepareSurface() {
        CraftingWorkstationCoordinator.Directive directive = workstation.next(player, station);
        return switch (directive.action()) {
            case READY -> {
                station = directive.position();
                stationAimRequestedRevision = Long.MIN_VALUE;
                stage = Stage.OPEN;
                yield TaskState.RUNNING;
            }
            case MOVE_TO_EXISTING -> {
                if (directive.workstationBlock() == null) {
                    workstation.reject(directive);
                    yield TaskState.RUNNING;
                }
                String blockId = BuiltInRegistries.BLOCK.getKey(
                        directive.workstationBlock()).toString();
                yield startSurfaceChild(new MoveToTaskRecord(
                        childId("move"), childDeadline(),
                        null, null, null, blockId, false), directive);
            }
            case PLACE_CARRIED -> {
                BlockPos site = directive.position();
                var table = directive.workstationBlock();
                if (table == null) {
                    workstation.reject(directive);
                    yield TaskState.RUNNING;
                }
                BuildTaskRecord.Target target = new BuildTaskRecord.Target(
                        table, table.asItem(), site,
                        BuiltInRegistries.BLOCK.getKey(table).toString(),
                        null, null, null).asItemPlace();
                yield startSurfaceChild(new BuildTaskRecord(
                        childId("place"), childDeadline(),
                        List.of(target), false, true, false), directive);
            }
            case UNAVAILABLE -> {
                fail("recipe " + r.recipeId + " needs a 3x3 crafting surface, but "
                        + directive.detail(), FailureType.NO_SUPPORT);
                yield TaskState.FAILED;
            }
        };
    }

    private TaskState startSurfaceChild(
            TaskRecord record, CraftingWorkstationCoordinator.Directive directive) {
        surfaceDirective = directive;
        surfaceRecord = record;
        surfaceChild = TaskFactory.create(player, record);
        return TaskState.RUNNING;
    }

    private TaskState tickSurfaceChild() {
        Task active = surfaceChild;
        TaskRecord activeRecord = surfaceRecord;
        TaskState terminal = runChild(active);
        if (activeRecord != null) {
            r.extendDeadlineTo(activeRecord.getDeadlineGameTime());
        }
        if (terminal == null) return TaskState.RUNNING;
        TaskResult result = active.result(terminal);
        CraftingWorkstationCoordinator.Directive completed = surfaceDirective;
        surfaceChild = null;
        surfaceRecord = null;
        surfaceDirective = null;

        if (terminal != TaskState.SUCCESS || result == null || !result.success()) {
            workstation.reject(completed);
            station = null;
            stage = Stage.PREPARE_SURFACE;
            return TaskState.RUNNING;
        }

        if (completed.action() == CraftingWorkstationCoordinator.Action.PLACE_CARRIED) {
            if (!CraftingWorkstationCoordinator.usableTable(player, completed.position())) {
                workstation.reject(completed);
                station = null;
                stage = Stage.PREPARE_SURFACE;
                return TaskState.RUNNING;
            }
            stationPlaced = true;
            station = completed.position();
            renewProgressLease();
            stage = Stage.OPEN;
            return TaskState.RUNNING;
        }

        CraftingWorkstationCoordinator.Directive afterMove =
                workstation.next(player, completed.position());
        if (afterMove.action() == CraftingWorkstationCoordinator.Action.READY) {
            station = afterMove.position();
            stationAimRequestedRevision = Long.MIN_VALUE;
            renewProgressLease();
            stage = Stage.OPEN;
        } else {
            workstation.reject(completed);
            station = null;
            stage = Stage.PREPARE_SURFACE;
        }
        return TaskState.RUNNING;
    }

    private TaskState openStation() {
        var context = ClientRuntime.requireContext(player);
        if (!CraftingWorkstationCoordinator.usableTable(player, station)) {
            station = null;
            stage = Stage.PREPARE_SURFACE;
            return TaskState.RUNNING;
        }
        if (openReceipt == null) {
            if (!CraftingWorkstationCoordinator.withinReach(player, station)) {
                stage = Stage.PREPARE_SURFACE;
                return TaskState.RUNNING;
            }
            Vec3 aimPoint = Vec3.atCenterOf(station);
            InputDriver.lookAt(player, aimPoint);
            long revision = context.tickRevision();
            if (stationAimRequestedRevision == Long.MIN_VALUE) {
                stationAimRequestedRevision = revision;
                return TaskState.RUNNING;
            }
            Vec3 desired = aimPoint.subtract(player.getEyePosition());
            if (revision <= stationAimRequestedRevision
                    || (desired.lengthSqr() > 1.0e-8D
                            && player.getLookAngle().normalize().dot(desired.normalize())
                                    < AIM_CONVERGENCE_DOT)) {
                return TaskState.RUNNING;
            }
            HitResult aimed = Interaction.nativeRaytrace(player, 4.5);
            if (!(aimed instanceof BlockHitResult hit) || !hit.getBlockPos().equals(station)) {
                workstation.reject(new CraftingWorkstationCoordinator.Directive(
                        CraftingWorkstationCoordinator.Action.READY, station,
                        "the converged crosshair proves this crafting table is occluded from the stance"));
                station = null;
                stationAimRequestedRevision = Long.MIN_VALUE;
                stage = Stage.PREPARE_SURFACE;
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
            workstation.reject(new CraftingWorkstationCoordinator.Directive(
                    CraftingWorkstationCoordinator.Action.READY, station,
                    "the selected crafting table did not open"));
            openReceipt = null;
            station = null;
            stage = Stage.PREPARE_SURFACE;
            return TaskState.RUNNING;
        }
        openReceipt = null;
        renewProgressLease();
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
        renewProgressLease();
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
        renewProgressLease();
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

    @Override protected void cleanup() {
        if (surfaceChild != null) {
            surfaceChild.stop(player, Task.StopReason.REPLACED);
            surfaceChild = null;
        }
        surfaceRecord = null;
        surfaceDirective = null;
        openReceipt = null;
        menuReceipt = null;
        workstation.close();
        super.cleanup();
    }
    @Override protected Map<String, Object> resultData() {
        Map<String, Object> data = new HashMap<>();
        data.put("recipe", r.recipeId.toString());
        data.put("crafted", crafted);
        data.put("crafting_table_placed", stationPlaced);
        if (station != null) {
            data.put("crafting_station", Map.of(
                    "x", station.getX(), "y", station.getY(), "z", station.getZ()));
        }
        return data;
    }
    @Override protected String successMessage() { return "crafted " + crafted + " item(s) via " + r.recipeId; }
    @Override protected String cancelledMessage() { return "craft interrupted"; }

    private String childId(String label) {
        return r.getToolCallId() + "-craft-surface-" + label + "-" + (++surfaceChildSerial);
    }

    private long childDeadline() {
        return r.getDeadlineGameTime();
    }

    private void renewProgressLease() {
        r.extendDeadlineTo(player.level().getGameTime() + PROGRESS_LEASE_TICKS);
    }
}
