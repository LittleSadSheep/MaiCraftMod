// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine.create;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.maiwithu.maicraft.server.inventory.ResourceIdentity;
import org.maiwithu.maicraft.server.machine.NativeApi;
import org.maiwithu.maicraft.server.machine.ServerProductionEvents;

/** Native completion scopes and actual mutation receipts; never infers processing from inventory polling. */
public final class CreateGrindingCapture {
    private static final ThreadLocal<ArrayDeque<Scope>> SCOPES = ThreadLocal.withInitial(ArrayDeque::new);
    public static final class Scope {
        final BlockEntity owner; final ServerLevel level; final BlockPos position; final long tick;
        final boolean crusher; final List<ItemStack> outputs = new ArrayList<>();
        Object inputInventory, outputInventory, recipe;
        ItemStack consumed = ItemStack.EMPTY;
        String recipeId;
        int rolls;
        boolean invalid, closed;
        Scope(BlockEntity owner, ServerLevel level, boolean crusher) {
            this.owner = owner; this.level = level; this.crusher = crusher;
            position = owner.getBlockPos().immutable(); tick = level.getGameTime();
        }
    }
    public record Consumption(Scope scope, ItemStack before, ItemStack stack, int requested) {}
    public record Output(Scope scope, Object inventory, int slot, ItemStack offered) {}
    private CreateGrindingCapture() {}

    public static Scope begin(BlockEntity owner, boolean crusher) {
        if (!(owner.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread()) return null;
        var calls = SCOPES.get();
        if (calls.size() >= 8) { calls.forEach(scope -> scope.invalid = true); return null; }
        Scope scope = new Scope(owner, level, crusher); calls.push(scope);
        try {
            scope.inputInventory = NativeApi.field(owner, crusher ? CreateGrindingRecipeAccess.CRUSH : CreateGrindingRecipeAccess.MILL,
                    crusher ? "inventory" : "inputInv");
            scope.outputInventory = crusher ? scope.inputInventory : NativeApi.field(owner, CreateGrindingRecipeAccess.MILL, "outputInv");
        } catch (RuntimeException | LinkageError unavailable) { scope.invalid = true; }
        return scope;
    }

    public static void finish(Scope scope, boolean completed) {
        if (scope == null || scope.closed) return;
        var calls = SCOPES.get();
        try {
            if (calls.peek() != scope || !completed || scope.invalid || scope.recipeId == null || scope.consumed.isEmpty()
                    || scope.rolls != (scope.crusher ? scope.consumed.getCount() : 1)
                    || scope.owner.isRemoved() || scope.owner.getLevel() != scope.level
                    || !scope.owner.getBlockPos().equals(scope.position) || scope.level.getGameTime() != scope.tick) return;
            JsonArray inputs = new JsonArray(), outputs = new JsonArray(); inputs.add(item(scope, scope.consumed));
            scope.outputs.forEach(stack -> outputs.add(item(scope, stack)));
            ServerProductionEvents.recordProduction(scope.level, scope.position, scope.recipeId, inputs, outputs, scope.rolls,
                    scope.crusher ? "create.crushing_wheel_controller.applyRecipe" : "create.millstone.process");
        } catch (RuntimeException | LinkageError unavailable) { /* Instrumentation cannot interrupt native processing. */ }
        finally {
            scope.closed = true;
            if (calls.peek() == scope) calls.pop(); else { calls.forEach(call -> call.invalid = true); calls.remove(scope); }
            if (calls.isEmpty()) SCOPES.remove();
        }
    }

    public static Consumption beforeShrink(BlockEntity owner, ItemStack stack, int requested) {
        Scope scope = current(owner);
        if (scope == null || scope.crusher) return null;
        try {
            if (requested != 1 || stack != stack(scope.inputInventory, 0) || stack.isEmpty()) { scope.invalid = true; return null; }
            return new Consumption(scope, stack.copy(), stack, requested);
        } catch (RuntimeException | LinkageError unavailable) { scope.invalid = true; return null; }
    }

    public static void afterShrink(Consumption receipt) {
        if (receipt == null) return;
        Scope scope = receipt.scope();
        try {
            ItemStack remaining = receipt.stack();
            if (receipt.before().getCount() - remaining.getCount() != receipt.requested()
                    || !remaining.isEmpty() && !ItemStack.isSameItemSameComponents(receipt.before(), remaining)
                    || !scope.consumed.isEmpty()) { scope.invalid = true; return; }
            scope.consumed = receipt.before().copyWithCount(receipt.requested());
        } catch (RuntimeException | LinkageError unavailable) { scope.invalid = true; }
    }

    public static Consumption beforeClear(BlockEntity owner, Object inventory) {
        Scope scope = current(owner);
        if (scope == null || !scope.crusher) return null;
        try {
            if (inventory != scope.inputInventory) { scope.invalid = true; return null; }
            ItemStack input = stack(inventory, 0).copy();
            if (input.isEmpty() || input.getCount() > 4096) { scope.invalid = true; return null; }
            return new Consumption(scope, input, ItemStack.EMPTY, input.getCount());
        } catch (RuntimeException | LinkageError unavailable) { scope.invalid = true; return null; }
    }

    public static void afterClear(Consumption receipt) {
        if (receipt == null) return;
        Scope scope = receipt.scope();
        try {
            if (!stack(scope.inputInventory, 0).isEmpty() || !scope.consumed.isEmpty()) { scope.invalid = true; return; }
            scope.consumed = receipt.before();
        } catch (RuntimeException | LinkageError unavailable) { scope.invalid = true; }
    }

    public static void rolled(BlockEntity owner, Object nativeRecipe) {
        Scope scope = current(owner);
        if (scope == null) return;
        try {
            if (scope.recipe != null && scope.recipe != nativeRecipe || ++scope.rolls > 4096) { scope.invalid = true; return; }
            if (scope.recipe == null) {
                scope.recipe = nativeRecipe;
                scope.recipeId = CreateGrindingRecipeAccess.id(scope.level, nativeRecipe);
                if (scope.recipeId == null) scope.invalid = true;
            }
        } catch (RuntimeException | LinkageError unavailable) { scope.invalid = true; }
    }

    public static Output beforeInsert(BlockEntity owner, Object inventory, ItemStack offered, boolean simulate) {
        Scope scope = current(owner);
        if (scope == null || scope.crusher || simulate) return null;
        try {
            if (inventory != scope.outputInventory) { scope.invalid = true; return null; }
            return new Output(scope, inventory, -1, offered.copy());
        } catch (RuntimeException | LinkageError unavailable) { scope.invalid = true; return null; }
    }

    public static void afterInsert(Output receipt, ItemStack remainder) {
        if (receipt == null) return;
        try {
            int accepted = receipt.offered().getCount() - remainder.getCount();
            if (accepted < 0 || !remainder.isEmpty() && !ItemStack.isSameItemSameComponents(receipt.offered(), remainder)) {
                receipt.scope().invalid = true; return;
            }
            addOutput(receipt.scope(), receipt.offered().copyWithCount(accepted));
        } catch (RuntimeException | LinkageError unavailable) { receipt.scope().invalid = true; }
    }

    public static Output beforeWrite(BlockEntity owner, Object inventory, int slot, ItemStack offered) {
        Scope scope = current(owner);
        if (scope == null || !scope.crusher) return null;
        try {
            if (inventory != scope.outputInventory || slot < 1 || slot >= 32 || !stack(inventory, slot).isEmpty()) {
                scope.invalid = true; return null;
            }
            return new Output(scope, inventory, slot, offered.copy());
        } catch (RuntimeException | LinkageError unavailable) { scope.invalid = true; return null; }
    }

    public static void afterWrite(Output receipt) {
        if (receipt == null) return;
        try {
            ItemStack written = stack(receipt.inventory(), receipt.slot());
            if (!ItemStack.matches(written, receipt.offered())) { receipt.scope().invalid = true; return; }
            addOutput(receipt.scope(), receipt.offered());
        } catch (RuntimeException | LinkageError unavailable) { receipt.scope().invalid = true; }
    }

    private static Scope current(BlockEntity owner) {
        Scope scope = SCOPES.get().peek();
        return scope == null || scope.owner != owner || scope.invalid || scope.closed ? null : scope;
    }
    private static ItemStack stack(Object inventory, int slot) {
        return (ItemStack) NativeApi.call(inventory, "net.neoforged.neoforge.items.ItemStackHandler", "getStackInSlot", slot);
    }
    private static void addOutput(Scope scope, ItemStack output) {
        if (output.isEmpty()) return;
        for (ItemStack existing : scope.outputs) if (ItemStack.isSameItemSameComponents(existing, output)) {
            existing.setCount(Math.addExact(existing.getCount(), output.getCount())); return;
        }
        if (scope.outputs.size() >= 64) { scope.invalid = true; return; }
        scope.outputs.add(output.copy());
    }
    private static JsonObject item(Scope scope, ItemStack stack) {
        JsonObject row = new JsonObject(), identity = ResourceIdentity.item(stack, scope.level.registryAccess());
        row.add("identity", identity); row.addProperty("resource_id", ResourceIdentity.key(identity));
        row.addProperty("amount", stack.getCount()); return row;
    }
}
