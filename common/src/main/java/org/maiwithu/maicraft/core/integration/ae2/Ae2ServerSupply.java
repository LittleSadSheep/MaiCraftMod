// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.ae2;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Set;
import java.util.function.ToIntFunction;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.server.ClientRequestReceipt;
import org.maiwithu.maicraft.client.server.ServerAssistClient;

/** Optional fixed-terminal transactions inside the existing supply session, planner and cleanup. */
final class Ae2ServerSupply {
    enum State { RUNNING, FALLBACK, SUCCEEDED, FAILED, UNCERTAIN }
    record Progress(State state, String code, String message) {}
    private final LocalPlayer player;
    private final Ae2ResourceSupply.Request request;
    private final Ae2TerminalAccess.FixedTarget target;
    private final Set<Integer> reserved;
    private final ToIntFunction<Ae2ResourceSupply.Group> groupProgress;
    private final List<ResourceLocation> queryItems;
    private final Ae2ServerStock stock = new Ae2ServerStock();
    private int queryIndex;
    private int offset;
    private ClientRequestReceipt pending;
    private long pendingTick;
    private Ae2SupplyPlanner.Plan plan;
    private Ae2SupplyPlanner.Allocation allocation;
    private Ae2ReflectionBridge.Entry entry;
    private int slot;
    private int requested;
    private int transferred;
    private ItemStack destinationBefore;
    private boolean awaitingInventory;
    private boolean effectsStarted;
    private Progress terminal;
    private Ae2ServerCraftJob craft;
    private boolean refreshingStock;
    private int craftPlans;
    private int craftStarts;
    private String awaitingOutputKey;
    private int awaitingOutputAmount;
    private long craftCompletedTick;
    private long nextStockQueryTick;

    Ae2ServerSupply(LocalPlayer player, Ae2ResourceSupply.Request request, Ae2TerminalAccess.FixedTarget target,
                    Set<Integer> reserved, ToIntFunction<Ae2ResourceSupply.Group> groupProgress) {
        this.player = player;
        this.request = request;
        this.target = target;
        this.reserved = Set.copyOf(reserved);
        this.groupProgress = groupProgress;
        this.queryItems = request.acceptedItemIds().stream().sorted().toList();
    }

    static boolean available() {
        return ServerAssistClient.serverSupported("inventory.ae2_network")
                && ServerAssistClient.serverSupported("inventory.ae2_supply");
    }

    Progress tick(LocalPlayerContext context) {
        if (terminal != null) return terminal;
        try {
            if (craft != null) {
                var progress = craft.tick(context.tickRevision());
                if (progress.state() == State.RUNNING) return progress;
                effectsStarted |= craft.effectStarted();
                craftPlans += craft.planned() ? 1 : 0;
                craftStarts += craft.started() ? 1 : 0;
                if (progress.state() != State.SUCCEEDED) {
                    craft.cancel();
                    return finish(progress.state(), progress.code(), progress.message());
                }
                awaitingOutputKey = craft.resource();
                awaitingOutputAmount = craft.amount();
                craftCompletedTick = context.tickRevision();
                craft = null;
                stock.clear();
                queryIndex = 0;
                offset = 0;
                refreshingStock = true;
                return running();
            }
            if (pending != null) return poll(context);
            if (plan == null || refreshingStock) {
                if (queryIndex < queryItems.size()) {
                    if (context.tickRevision() < nextStockQueryTick) return running();
                    JsonObject body = targetBody();
                    body.addProperty("item_id", queryItems.get(queryIndex).toString());
                    body.addProperty("resource_offset", offset);
                    body.addProperty("resource_limit", 128);
                    pending = ServerAssistClient.submit("inventory.ae2_network", body, false);
                    pendingTick = context.tickRevision();
                    return running();
                }
                if (refreshingStock) {
                    long output = stock.entries().stream().filter(value -> stock.identity(value.serial()).equals(awaitingOutputKey))
                            .mapToLong(Ae2ReflectionBridge.Entry::storedAmount).sum();
                    if (output < awaitingOutputAmount) {
                        if (context.tickRevision() - craftCompletedTick > 100)
                            return finish(State.UNCERTAIN, "native_craft_output_unobserved", "completed CPU job has no matching native stock; no second job will be submitted");
                        queryIndex = 0;
                        stock.clear();
                        nextStockQueryTick = context.tickRevision() + 20;
                        return running();
                    }
                    refreshingStock = false;
                    awaitingOutputKey = null;
                    return running();
                }
                var stockOnly = new Ae2ResourceSupply.Request(request.groups(),
                        request.allowCrafting() && Ae2ServerCraftJob.available(), request.operation());
                var built = Ae2SupplyPlanner.build(player, stockOnly, stock.entries(), reserved);
                if (built.failure() != null) return finish(State.FALLBACK, "server_stock_plan_unavailable",
                        "no server mutation was submitted; the existing native supply planner may continue");
                plan = built.plan();
                return running();
            }
            for (var group : plan.groups()) {
                if (groupProgress.applyAsInt(group.group()) != group.confirmedCount())
                    return finish(State.UNCERTAIN, "inventory_changed", "inventory no longer matches confirmed AE2 transfers");
            }
            allocation = plan.groups().stream().flatMap(group -> group.allocations().stream())
                    .filter(value -> value.remaining() > 0).findFirst().orElse(null);
            if (allocation == null) return finish(State.SUCCEEDED, "resources_supplied", "server transfers and client inventory agree");
            entry = stock.select(allocation);
            if (entry == null && request.allowCrafting() && Ae2ServerCraftJob.available()) {
                var pattern = stock.craftable(allocation);
                if (pattern != null) {
                    craft = new Ae2ServerCraftJob(targetBody(), stock.identity(pattern.serial()), stock.membership(), allocation.remaining());
                    return running();
                }
            }
            slot = destination(player, allocation.sample(), reserved);
            if (entry == null || slot < 0) return finish(State.FAILED, "server_supply_plan_changed", "exact stock or destination capacity changed");
            destinationBefore = player.getInventory().getItem(slot).copy();
            requested = Math.min(64, Math.min(allocation.remaining(), Math.min((int) Math.min(Integer.MAX_VALUE,
                    stock.remaining(entry.serial())), allocation.sample().getMaxStackSize() - destinationBefore.getCount())));
            JsonObject body = targetBody();
            body.addProperty("mode", "withdraw");
            body.addProperty("resource_id", stock.identity(entry.serial()));
            body.addProperty("player_slot", slot);
            body.addProperty("amount", requested);
            pending = ServerAssistClient.submit("inventory.ae2_supply", body, true);
            pendingTick = context.tickRevision();
            return running();
        } catch (RuntimeException invalid) {
            boolean uncertain = effectsStarted();
            boolean untouched = pending == null || !pending.snapshot().mutating()
                    || pending.snapshot().effect() == ClientRequestReceipt.Effect.NOT_APPLIED;
            cancel();
            boolean nativeFallback = !uncertain && untouched && ServerAssistClient.nativeFallbackAllowed("inventory.ae2_supply");
            return finish(uncertain ? State.UNCERTAIN : nativeFallback ? State.FALLBACK : State.FAILED,
                    "server_supply_invalid_evidence", "server supply evidence could not be validated: " + invalid.getMessage());
        }
    }

    private Progress poll(LocalPlayerContext context) {
        var receipt = pending.snapshot();
        if (receipt.status() == ClientRequestReceipt.Status.QUEUED || receipt.status() == ClientRequestReceipt.Status.PENDING) {
            if (context.tickRevision() - pendingTick > 200) {
                cancel();
                return finish(effectsStarted() ? State.UNCERTAIN : State.FAILED, "server_supply_timeout", "pending supply was cancelled without replay");
            }
            return running();
        }
        if (receipt.effect() == ClientRequestReceipt.Effect.UNKNOWN || receipt.status() == ClientRequestReceipt.Status.UNKNOWN)
            return finish(State.UNCERTAIN, "server_supply_outcome_unknown", "submitted requests require reconciliation; native fallback is forbidden");
        if (receipt.retired() || receipt.status() != ClientRequestReceipt.Status.SUCCEEDED) {
            boolean safe = !effectsStarted && safeFallback(receipt)
                    && ServerAssistClient.nativeFallbackAllowed("inventory.ae2_supply");
            return finish(safe ? State.FALLBACK : State.FAILED, receipt.code(), receipt.message());
        }
        if (!receipt.mutating()) {
            JsonObject result = receipt.result();
            stock.append(result, player.registryAccess());
            int next = result.has("next_resource_offset") ? result.get("next_resource_offset").getAsInt() : 0;
            if (result.get("truncated").getAsBoolean() && next > offset && next <= 4096) offset = next;
            else { queryIndex++; offset = 0; }
            pending = null;
            return running();
        }
        if (!awaitingInventory) {
            JsonObject result = receipt.result();
            transferred = result.get("transferred").getAsBigDecimal().intValueExact();
            if (transferred < 0 || transferred > requested || result.get("requested").getAsInt() != requested
                    || !stock.identity(entry.serial()).equals(result.get("resource_id").getAsString())
                    || !stock.membership().equals(result.get("membership").getAsString()))
                return finish(State.UNCERTAIN, "server_supply_receipt_mismatch", "transfer result differs from the approved exact-key request");
            if (transferred == 0) return finish(State.FAILED, "server_supply_no_change", "native extraction moved no items");
            effectsStarted = true;
            awaitingInventory = true;
            pendingTick = context.tickRevision();
        }
        ItemStack after = player.getInventory().getItem(slot);
        int expected = destinationBefore.getCount() + transferred;
        if (ItemStack.isSameItemSameComponents(after, allocation.sample()) && after.getCount() == expected) {
            allocation.confirm(transferred);
            stock.debit(entry.serial(), transferred);
            awaitingInventory = false;
            pending = null;
            return running();
        }
        if (context.tickRevision() - pendingTick > 100)
            return finish(State.UNCERTAIN, "server_supply_inventory_unconfirmed", "authoritative transfer did not reconcile with the client inventory");
        return running();
    }

    static boolean safeFallback(ClientRequestReceipt.Snapshot receipt) {
        return !receipt.retired() && receipt.effect() == ClientRequestReceipt.Effect.NOT_APPLIED
                && receipt.status() == ClientRequestReceipt.Status.REJECTED
                && (receipt.code().equals("unsupported_operation") || receipt.code().equals("unsupported_version"));
    }

    static int destination(LocalPlayer player, ItemStack sample, Set<Integer> reserved) {
        int empty = -1;
        for (int index = 0; index <= 35; index++) {
            if (reserved.contains(index)) continue;
            ItemStack stack = player.getInventory().getItem(index);
            if (stack.isEmpty()) { if (empty < 0) empty = index; }
            else if (ItemStack.isSameItemSameComponents(stack, sample) && stack.getCount() < sample.getMaxStackSize()) return index;
        }
        return empty;
    }

    void cancel() {
        if (pending != null) ServerAssistClient.cancel(pending.id());
        if (craft != null) craft.cancel();
    }
    boolean effectsStarted() {
        if (effectsStarted) return true;
        if (craft != null && craft.effectStarted()) return true;
        if (pending == null) return false;
        var receipt = pending.snapshot();
        return receipt.mutating() && receipt.effect() != ClientRequestReceipt.Effect.NOT_APPLIED;
    }
    Ae2SupplyPlanner.Plan plan() { return plan; }
    int craftPlans() { return craftPlans + (craft != null && craft.planned() ? 1 : 0); }
    int craftStarts() { return craftStarts + (craft != null && craft.started() ? 1 : 0); }
    boolean runningRequest() { return terminal == null && (pending != null || craft != null); }
    private Progress running() { return new Progress(State.RUNNING, "server_supply_pending", "waiting for authoritative supply evidence"); }
    private Progress finish(State state, String code, String message) { return terminal = new Progress(state, code, message); }
    private JsonObject targetBody() {
        JsonObject position = new JsonObject();
        position.addProperty("x", target.position().getX());
        position.addProperty("y", target.position().getY());
        position.addProperty("z", target.position().getZ());
        JsonObject body = new JsonObject();
        body.add("position", position);
        body.addProperty("side", target.side().getSerializedName());
        return body;
    }
}
