// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.runtime;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import org.maiwithu.maicraft.core.PlayerInv;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionManifest.Port;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionManifest.Resource;
import org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator;
import org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator.Demand;
import org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator.MaterialPolicy;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.server.inventory.ResourceIdentity;

/** Bounded real-player deposits into authored sources; machines transport all intermediate resources. */
public final class ProductionInputSupply {
    private enum Phase { OBSERVE, CARRIED, QUOTE, TRANSFER }
    private record Supply(ProductionSupplyBudget.Key key, Port port, ProductionSupplyBudget budget) {}
    private final LocalPlayer player;
    private final TaskRecord owner;
    private final ProductionRunPlan plan;
    private final ProductionWork work;
    private final List<String> protectedLabels;
    private final List<Supply> supplies = new ArrayList<>();
    private final SemanticMaterialSupplyCoordinator acquisition = new SemanticMaterialSupplyCoordinator();
    private final ProductionSupplyAllocations allocations = new ProductionSupplyAllocations();
    private final Map<ProductionSupplyBudget.Key, Long> observedStock = new LinkedHashMap<>();
    private final Map<ProductionSupplyBudget.Key, String> status = new LinkedHashMap<>();
    private final Map<ProductionSupplyBudget.Key, ProductionSupplyPacer> pacing = new LinkedHashMap<>();
    private final List<Map<String, Object>> acquisitionReceipts = new ArrayList<>();
    private int completedAcquisitions;
    private Phase phase = Phase.OBSERVE;
    private JsonObject body;
    private int cursor;
    private int passes = 1;
    private long nextRefillTick;
    private boolean cancelled;

    public ProductionInputSupply(LocalPlayer player, TaskRecord owner, ProductionRunPlan plan,
                                 ProductionWork work, List<String> protectedLabels) {
        this.player = java.util.Objects.requireNonNull(player);
        this.owner = java.util.Objects.requireNonNull(owner);
        this.plan = java.util.Objects.requireNonNull(plan);
        this.work = java.util.Objects.requireNonNull(work);
        this.protectedLabels = protectedLabels == null ? List.of() : List.copyOf(protectedLabels);
        ProductionSupplyBudget.demands(plan.manifest()).forEach((key, amount) -> {
            Port port = plan.manifest().links().stream().filter(link -> {
                Port from = plan.port(link.from());
                return from.node().equals(key.source()) && link.resource().medium().equals(key.medium())
                        && link.resource().id().equals(key.resource());
            }).map(link -> plan.port(link.from())).findFirst().orElseThrow();
            supplies.add(new Supply(key, port, new ProductionSupplyBudget(amount)));
        });
    }

    /** True means this bounded pass settled, not that native production or all resource requirements succeeded. */
    public boolean tick() {
        if (cancelled) throw new IllegalStateException("Production input supply was cancelled");
        if (!player.level().dimension().location().toString().equals(plan.dimension()))
            throw new IllegalStateException("Production source dimension changed");
        if (cursor >= supplies.size()) return true;
        Supply supply = supplies.get(cursor);
        if (!supply.key().medium().equals("items")) {
            status.put(supply.key(), "deferred_to_native_preflight: no player transfer for " + supply.key().medium());
            return advance();
        }
        Resource selector = selector(supply);
        String registryId = plan.registryId(selector);
        if (acquisition.active()) {
            var result = acquisition.tick(player, work::advanceChild);
            work.extendDeadlineTo(acquisition.childDeadline());
            if (result.status() != SemanticMaterialSupplyCoordinator.Status.RUNNING) {
                completedAcquisitions++;
                if (acquisitionReceipts.size() < 16) {
                    Map<String, Object> evidence = new LinkedHashMap<>(result.receipt());
                    evidence.put("source", supply.key().source());
                    acquisitionReceipts.add(Map.copyOf(evidence));
                }
            }
            if (result.status() == SemanticMaterialSupplyCoordinator.Status.FAILED)
                throw new IllegalStateException("production_input_acquisition_failed: " + result.message());
            if (result.status() == SemanticMaterialSupplyCoordinator.Status.RUNNING) return false;
        }
        if (phase != Phase.OBSERVE && supply.budget().initialized() && supply.budget().remaining() == 0) {
            status.put(supply.key(), "allocation_fully_supplied"); return advance();
        }
        BlockPos position = plan.at(supply.port().offset());
        if (phase == Phase.OBSERVE || phase == Phase.CARRIED) {
            if (!work.approach(position)) return false;
        }
        switch (phase) {
            case OBSERVE -> {
                JsonObject query = plan.snapshotBody(position);
                JsonArray faces = new JsonArray(); faces.add(supply.port().face()); query.add("faces", faces);
                JsonObject snapshot = work.request("machine.snapshot", query, false);
                if (snapshot == null) return false;
                var stock = ProductionSupplyStock.read(snapshot, position, supply.port().face(),
                        plan.resolvedResource(selector).id(), plan.resourceIdentity(selector));
                long unallocated = allocations.observe(supply.key(), stock);
                observedStock.put(supply.key(), stock.amount());
                if (!supply.budget().initialized()) {
                    long credit = supply.budget().initialize(unallocated);
                    allocations.charge(supply.key(), credit);
                    Map<String, Long> consumers = new LinkedHashMap<>();
                    plan.supplyConsumers(supply.key().source(), selector).forEach(id -> consumers.put(id, plan.node(id).batches()));
                    pacing.put(supply.key(), new ProductionSupplyPacer(supply.budget(), plan.supplyBatch(supply.key().source(), selector),
                            consumers, stock.amount(), work.processingProgress()));
                    work.initialSupply(supply.key().source(), selector, credit, snapshot);
                }
                phase = Phase.CARRIED;
                return false;
            }
            case CARRIED -> {
                if (supply.budget().remaining() == 0) return advance();
                var pacer = pacing.get(supply.key());
                pacer.observe(work.processingProgress(), player.level().getGameTime());
                if (pacer.quoteAmount() == 0) { status.put(supply.key(), "waiting_for_native_consumer_event"); return advance(); }
                if (!pacer.ready(player.level().getGameTime())) { work.stopMovement(); return false; }
                int slot = carriedSlot(selector);
                if (slot < 0) {
                    if (observedStock.getOrDefault(supply.key(), 0L) > 0
                            && supply.budget().allocated() >= plan.supplyBatch(supply.key().source(), selector)) {
                        status.put(supply.key(), "source_stock_present_refill_later"); return advance();
                    }
                    MaterialPolicy policy = MaterialPolicy.parse(plan.node(supply.key().source()).materialPolicy());
                    if (policy == MaterialPolicy.INVENTORY_ONLY)
                        throw new IllegalStateException("production_input_missing_inventory: " + supply.key().resource());
                    // Acquire only the currently released chunk, never the entire observation-window budget.
                    acquisition.begin(player, owner.getToolCallId(), owner.getDeadlineGameTime(),
                        new Demand(List.of(ResourceLocation.parse(registryId)), pacer.quoteAmount(), "production source " + supply.key().source()),
                        policy, List.of(), false, protectedLabels);
                    work.extendDeadlineTo(acquisition.childDeadline());
                    status.put(supply.key(), "acquiring_material");
                    return false;
                }
                body = new JsonObject(); body.add("position", ProductionRunPlan.position(position));
                body.addProperty("side", supply.port().face()); body.addProperty("mode", "deposit");
                body.addProperty("player_slot", slot);
                body.addProperty("expected_item_id", registryId);
                body.addProperty("resource_id", plan.resolvedResource(selector).id());
                body.addProperty("amount", pacer.quoteAmount());
                phase = Phase.QUOTE;
                return false;
            }
            case QUOTE -> {
                JsonObject quote = pacing.get(supply.key()).quote(work, body);
                if (quote == null) return false;
                String state = quote.get("status").getAsString();
                if (state.equals("full") || state.equals("empty")) {
                    if (quote.has("truncated") && quote.get("truncated").getAsBoolean())
                        throw new IllegalStateException("production_input_quote_truncated");
                    status.put(supply.key(), state.equals("full") ? "buffer_full_refill_later" : "player_slot_changed_refill_later");
                    pacing.get(supply.key()).settled(player.level().getGameTime(), 0);
                    return advance();
                }
                if (!state.equals("ready")) throw new IllegalStateException("production_input_quote_" + state);
                int amount = quote.get("amount").getAsBigDecimal().intValueExact();
                int playerSlot = quote.get("player_slot").getAsBigDecimal().intValueExact();
                int slot = quote.get("slot").getAsBigDecimal().intValueExact();
                String identity = quote.get("resource_id").getAsString();
                JsonObject resource = quote.has("identity") ? quote.getAsJsonObject("identity") : null;
                if (amount < 1 || amount > body.get("amount").getAsInt() || slot < 0 || slot > 4095
                        || playerSlot != body.get("player_slot").getAsInt()
                        || !plan.resolvedResource(selector).id().equals(identity)
                        || !plan.resourceIdentity(selector).equals(resource))
                    throw new IllegalStateException("production_input_quote_changed");
                body = body.deepCopy(); body.addProperty("amount", amount); body.addProperty("slot", slot);
                body.addProperty("expected_identity", identity); phase = Phase.TRANSFER;
                return false;
            }
            case TRANSFER -> {
                JsonObject receipt = work.request("inventory.transfer", body, true);
                if (receipt == null) return false;
                try {
                    int moved = supply.budget().confirm(receipt, body.get("amount").getAsInt(), body.get("expected_identity").getAsString());
                    allocations.charge(supply.key(), moved);
                    pacing.get(supply.key()).settled(player.level().getGameTime(), moved);
                    if (moved > 0) {
                        if (!receipt.has("maicraft_request_id")) throw new IllegalStateException("production_input_receipt_id_missing");
                        String requestId = receipt.get("maicraft_request_id").getAsString();
                        java.util.UUID.fromString(requestId);
                        work.confirmedSupply(supply.key().source(), selector, requestId, receipt);
                    }
                    status.put(supply.key(), moved == 0 ? "no_change_refill_later" : "confirmed_deposit");
                    if (moved > 0 && pacing.get(supply.key()).quoteAmount() > 0) {
                        phase = Phase.CARRIED; body = null; return false;
                    }
                } catch (RuntimeException invalidReceipt) {
                    // The mutation already settled. A failed evidence callback must never cause a second deposit.
                    cancelled = true; throw invalidReceipt;
                }
                return advance();
            }
        }
        throw new IllegalStateException("Unknown production supply phase");
    }

    public boolean needsRefill() {
        if (cancelled || cursor < supplies.size()) return false;
        long now = player.level().getGameTime(); boolean ready = false;
        var progress = work.processingProgress();
        for (var pacer : pacing.values()) {
            pacer.observe(progress, now);
            ready |= pacer.ready(now);
        }
        return ready && now >= nextRefillTick;
    }
    /** All finite item allocations are accounted for; says nothing about downstream work still in flight. */
    public boolean exhausted() {
        return supplies.stream().filter(supply -> supply.key().medium().equals("items"))
                .allMatch(supply -> supply.budget().initialized() && supply.budget().remaining() == 0);
    }
    /** Bounded source-side diagnostic for the owner's OBSERVE phase; final target timing remains the observer's responsibility. */
    public String pacingFailure() {
        if (cancelled || cursor < supplies.size()) return null;
        long now = player.level().getGameTime(); var progress = work.processingProgress();
        for (var pacer : pacing.values()) {
            pacer.observe(progress, now);
            String problem = pacer.unverifiable(now, plan.manifest().observation().minimumEvents(), plan.manifest().observation().maxIdleTicks());
            if (problem != null) return problem;
        }
        return null;
    }
    public long remaining() {
        long remaining = 0;
        for (Supply supply : supplies) if (supply.key().medium().equals("items")) remaining = Math.addExact(remaining, supply.budget().remaining());
        return remaining;
    }
    public long confirmedInjected() {
        long injected = 0;
        for (Supply supply : supplies) injected = Math.addExact(injected, supply.budget().injected());
        return injected;
    }
    public void refill() {
        if (cancelled || cursor < supplies.size()) throw new IllegalStateException("Finish the current supply pass before refilling");
        cursor = 0; phase = Phase.OBSERVE; body = null; passes++;
    }
    public void cancel() { acquisition.cancel(player); cancelled = true; }
    public Map<String, Object> report() {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Supply supply : supplies) {
            Map<String, Object> entry = new LinkedHashMap<>(supply.budget().report());
            entry.put("source", supply.key().source()); entry.put("medium", supply.key().medium());
            entry.put("resource", supply.key().resource()); entry.put("port", supply.port().id());
            entry.put("native_allocation", allocations.membership(supply.key()));
            entry.put("last_observed_stock", observedStock.getOrDefault(supply.key(), 0L));
            if (pacing.containsKey(supply.key())) entry.putAll(pacing.get(supply.key()).report());
            entry.put("state", status.getOrDefault(supply.key(), "pending")); entries.add(Map.copyOf(entry));
        }
        var report = new LinkedHashMap<String, Object>();
        report.put("pass", passes); report.put("pass_complete", cursor >= supplies.size());
        report.put("cancelled", cancelled); report.put("remaining_to_inject", remaining());
        report.put("confirmed_injected", confirmedInjected()); report.put("sources", List.copyOf(entries));
        report.put("next_refill_tick", nextRefillTick);
        report.put("material_acquisitions", List.copyOf(acquisitionReceipts));
        report.put("material_acquisition_count", completedAcquisitions);
        report.put("material_acquisitions_truncated", completedAcquisitions > acquisitionReceipts.size());
        report.put("nonitem_supply", "native source observation and production preflight required; no fabricated transfers");
        if (acquisition.active()) report.put("acquisition", acquisition.progress());
        return Map.copyOf(report);
    }

    private boolean advance() {
        cursor++; phase = Phase.OBSERVE; body = null;
        if (cursor >= supplies.size()) nextRefillTick = player.level().getGameTime() + 20;
        return cursor >= supplies.size();
    }
    private Resource selector(Supply supply) { return new Resource(supply.key().medium(), supply.key().resource()); }
    private int carriedSlot(Resource selector) {
        String itemId = plan.registryId(selector);
        JsonObject expected = plan.resourceIdentity(selector);
        boolean differentComponents = false;
        for (int slot = 0; slot < Math.min(PlayerInv.BUILDABLE_SLOTS, player.getInventory().items.size()); slot++) {
            var stack = player.getInventory().getItem(slot);
            if (stack.isEmpty() || !BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(itemId)) continue;
            try { if (expected.equals(ResourceIdentity.item(stack, player.registryAccess()))) return slot; }
            catch (IllegalArgumentException unreadable) { /* An unreadable component set cannot satisfy the bound identity. */ }
            differentComponents = true;
        }
        if (differentComponents) throw new IllegalStateException("production_input_component_identity_mismatch: " + itemId);
        return -1;
    }
}
