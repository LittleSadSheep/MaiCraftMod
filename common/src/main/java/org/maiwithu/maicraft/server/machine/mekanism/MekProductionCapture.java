// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine.mekanism;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.maiwithu.maicraft.server.inventory.ResourceIdentity;
import org.maiwithu.maicraft.server.machine.NativeApi;
import org.maiwithu.maicraft.server.machine.ServerProductionEvents;

/** Scoped actual resource effects. No predicted output, activity flag or inventory polling can append an event. */
public final class MekProductionCapture {
    private static final int MAX_DEPTH = 8, MAX_TRANSFERS = 64, MAX_EVENT_CHARS = 16_384;
    private static final String CACHE = "mekanism.api.recipes.cache.CachedRecipe";
    private static final ThreadLocal<State> STATE = new ThreadLocal<>();
    private static final class State {
        final ArrayDeque<Process> processes = new ArrayDeque<>();
        final ArrayDeque<Completion> completions = new ArrayDeque<>();
    }
    public static final class Process {
        private final Object cached;
        private BlockEntity owner;
        private ServerLevel level;
        private BlockPos position;
        private long tick;
        private int index;
        private boolean invalid, closed;
        private Completion ready;
        private Process(Object cached) { this.cached = cached; }
    }
    public static final class Completion {
        private final Process process;
        private final int operations, expectedInputs;
        private final JsonArray inputs = new JsonArray(), outputs = new JsonArray();
        private int inputCalls, transfers;
        private boolean invalid, closed;
        private Completion(Process process, int operations, int expectedInputs) {
            this.process = process; this.operations = operations; this.expectedInputs = expectedInputs;
        }
    }
    public record Transfer(Completion completion, Object snapshot, boolean input) {}

    private MekProductionCapture() {}

    public static void registerMonitor(Object monitor, Object handler, int index) {
        try { MekProductionOwners.register(monitor, handler, index); }
        catch (RuntimeException | LinkageError ignored) { /* Native monitor construction must succeed unchanged. */ }
    }

    public static Process beginProcess(Object monitor, Object cached) {
        State state = STATE.get();
        if (state == null) { state = new State(); STATE.set(state); }
        if (state.processes.size() >= MAX_DEPTH) { invalidate(state); return null; }
        Process scope = new Process(cached);
        state.processes.push(scope);
        try {
            MekProductionOwners.Owner association = MekProductionOwners.owner(monitor);
            BlockEntity owner = association == null ? null : association.block().get();
            if (owner == null || owner.isRemoved() || !(owner.getLevel() instanceof ServerLevel level)
                    || !level.getServer().isSameThread()) { scope.invalid = true; return scope; }
            scope.owner = owner; scope.level = level; scope.position = owner.getBlockPos().immutable();
            scope.tick = level.getGameTime(); scope.index = association.index();
        } catch (RuntimeException | LinkageError ignored) { scope.invalid = true; }
        return scope;
    }

    public static void endProcess(Process scope, boolean completed) {
        if (scope == null || scope.closed) return;
        State state = STATE.get();
        try {
            if (state == null || state.processes.peek() != scope || !completed || scope.invalid || scope.ready == null
                    || scope.owner.isRemoved() || scope.owner.getLevel() != scope.level
                    || !scope.owner.getBlockPos().equals(scope.position) || scope.level.getGameTime() != scope.tick) return;
            Object recipe = NativeApi.call(scope.cached, CACHE, "getRecipe");
            String id = MekProductionOwners.recipeId(scope.owner, scope.level, recipe);
            if (id != null) ServerProductionEvents.recordProduction(scope.level, scope.position, id,
                    scope.ready.inputs, scope.ready.outputs, scope.ready.operations,
                    "mekanism.CachedRecipe.finishProcessing.cache_" + scope.index);
        } catch (RuntimeException | LinkageError ignored) {
            // Missing attribution or a failed completion must never become successful production evidence.
        } finally {
            scope.closed = true;
            if (state != null) {
                if (state.processes.peek() == scope) state.processes.pop(); else { invalidate(state); state.processes.clear(); }
                cleanup(state);
            }
        }
    }

    public static Completion beginCompletion(Object cached, int operations) {
        State state = STATE.get();
        if (state == null) return null;
        if (state.completions.size() >= MAX_DEPTH) { invalidate(state); return null; }
        Process process = state.processes.peek();
        Completion scope = new Completion(process, operations, expectedInputs(cached));
        scope.invalid = process == null || process.invalid || process.cached != cached || scope.expectedInputs == 0
                || operations <= 0 || operations > 4096;
        state.completions.push(scope);
        return scope;
    }

    public static void endCompletion(Completion scope, boolean completed) {
        if (scope == null || scope.closed) return;
        State state = STATE.get();
        try {
            if (state == null || state.completions.peek() != scope || !completed || scope.invalid || scope.process.invalid
                    || scope.inputCalls != scope.expectedInputs || scope.inputs.isEmpty() || scope.outputs.isEmpty()) return;
            if (scope.inputs.toString().length() + scope.outputs.toString().length() > MAX_EVENT_CHARS
                    || scope.process.ready != null) { scope.process.invalid = true; return; }
            scope.process.ready = scope;
        } catch (RuntimeException | LinkageError ignored) { if (scope.process != null) scope.process.invalid = true; }
        finally {
            scope.closed = true;
            if (state != null) {
                if (state.completions.peek() == scope) state.completions.pop(); else { invalidate(state); state.completions.clear(); }
                cleanup(state);
            }
        }
    }

    public static Transfer beforeOutput(Object offered, Object action) { return snapshot(offered, action, false); }

    public static Transfer beforeInput(Object storage, String api, String getter, Object action) {
        if (active() == null) return null;
        try { return snapshot(NativeApi.call(storage, api, getter), action, true); }
        catch (RuntimeException | LinkageError ignored) { failActive(); return null; }
    }

    private static Transfer snapshot(Object value, Object action, boolean input) {
        Completion scope = active();
        if (scope == null) return null;
        try {
            if (!(action instanceof Enum<?> mode) || !mode.name().equals("EXECUTE")) return null;
            if (++scope.transfers > MAX_TRANSFERS) { scope.invalid = true; return null; }
            return new Transfer(scope, MekanismResourceStacks.copy(value), input);
        } catch (RuntimeException | LinkageError ignored) { scope.invalid = true; return null; }
    }

    public static void afterOutput(Transfer transfer, Object returned) {
        if (!usable(transfer, false)) return;
        Completion scope = transfer.completion;
        try {
            Object remainder = MekanismResourceStacks.copy(returned);
            long offered = MekanismResourceStacks.amount(transfer.snapshot), remaining = MekanismResourceStacks.amount(remainder);
            if (offered <= 0 || remaining < 0 || remaining > offered || remaining > 0
                    && !MekanismResourceStacks.sameIdentity(transfer.snapshot, remainder, scope.process.level.registryAccess())) {
                scope.invalid = true; return;
            }
            if (offered > remaining) append(scope.outputs, transfer.snapshot, offered - remaining, scope);
        } catch (RuntimeException | LinkageError ignored) { scope.invalid = true; }
    }

    public static void afterInput(Transfer transfer, long requested, long actual) {
        if (!usable(transfer, true)) return;
        Completion scope = transfer.completion;
        try {
            long available = MekanismResourceStacks.amount(transfer.snapshot);
            if (requested <= 0 || actual != requested || actual > available) { scope.invalid = true; return; }
            scope.inputCalls++;
            append(scope.inputs, transfer.snapshot, actual, scope);
        } catch (RuntimeException | LinkageError ignored) { scope.invalid = true; }
    }

    private static void append(JsonArray target, Object stack, long amount, Completion scope) {
        JsonObject identity = MekanismResourceStacks.identity(stack, scope.process.level.registryAccess());
        if (identity.toString().length() > 4096) { scope.invalid = true; return; }
        JsonObject resource = new JsonObject();
        resource.add("identity", identity); resource.addProperty("resource_id", ResourceIdentity.key(identity));
        resource.addProperty("amount", amount); target.add(resource);
    }

    private static boolean usable(Transfer transfer, boolean input) {
        return transfer != null && transfer.input == input && active() == transfer.completion;
    }
    private static Completion active() {
        State state = STATE.get();
        Completion scope = state == null ? null : state.completions.peek();
        return scope != null && !scope.invalid && !scope.closed && scope.process != null && !scope.process.invalid
                && state.processes.peek() == scope.process ? scope : null;
    }
    private static void failActive() { Completion scope = active(); if (scope != null) scope.invalid = true; }
    private static int expectedInputs(Object cached) {
        if (cached == null) return 0;
        return switch (cached.getClass().getName()) {
            case "mekanism.api.recipes.cache.OneInputCachedRecipe", "mekanism.api.recipes.cache.RotaryCachedRecipe" -> 1;
            case "mekanism.api.recipes.cache.TwoInputCachedRecipe", "mekanism.api.recipes.cache.ChemicalChemicalToChemicalCachedRecipe" -> 2;
            default -> 0; // Constant chemical usage and custom caches need an explicit complete input accounting adapter.
        };
    }
    private static void invalidate(State state) { state.processes.forEach(scope -> scope.invalid = true); }
    private static void cleanup(State state) {
        if (state.processes.isEmpty() && state.completions.isEmpty()) STATE.remove();
    }
}
