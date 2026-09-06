// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.ae2;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.core.inventory.StockEvidence;

/**
 * Internal semantic entry point for exact AE2 resource supply.
 *
 * <p>Callers name acceptable item groups and an approved count; they never name a terminal,
 * repository serial, menu slot, click, or route. The session binds all concrete choices before
 * its first inventory/network effect and confirms completion only from the player's net inventory
 * increase. AE2 remains an optional runtime dependency.</p>
 */
public final class Ae2ResourceSupply {
    /** Evidence produced only by an explicit bounded machine observation. */
    public record ExplicitAccessObservation(
            boolean integrationAvailable,
            int radius,
            int fixedTerminalsObserved,
            int terminalFacesObserved,
            boolean memoryUpdated,
            BlockPos rememberedPosition,
            String detail) {
        public ExplicitAccessObservation {
            if (rememberedPosition != null) rememberedPosition = rememberedPosition.immutable();
            detail = detail == null ? "" : detail;
        }
    }

    /** Whether a request moves items to the player or only prepares complete network stock. */
    public enum Operation {
        SUPPLY,
        PREPARE
    }

    public enum SelectionMode {
        /** Any mixture of the acceptable item IDs may satisfy the count. */
        AGGREGATE,
        /** One concrete acceptable item ID is selected and locked for the whole group. */
        SINGLE_VARIANT
    }

    public enum Status {
        RUNNING,
        SUCCEEDED,
        FAILED,
        RETRYABLE_FAILURE,
        UNCERTAIN,
        CANCELLED
    }

    /**
     * One semantic acceptable-item group. The primary ID is always included in acceptable IDs.
     * {@code count} is the exact approved net inventory increase, not a final inventory target.
     */
    public record Group(
            ResourceLocation itemId,
            List<ResourceLocation> acceptableItemIds,
            int count,
            SelectionMode selectionMode) {
        public Group {
            Objects.requireNonNull(itemId, "itemId");
            Objects.requireNonNull(acceptableItemIds, "acceptableItemIds");
            Objects.requireNonNull(selectionMode, "selectionMode");
            if (count < 1 || count > 65_536) {
                throw new IllegalArgumentException("an AE2 group count must be between 1 and 65536");
            }
            LinkedHashSet<ResourceLocation> ids = new LinkedHashSet<>();
            ids.add(itemId);
            ids.addAll(acceptableItemIds);
            if (ids.size() > 256) {
                throw new IllegalArgumentException("an AE2 group may accept at most 256 item IDs");
            }
            for (ResourceLocation id : ids) {
                Objects.requireNonNull(id, "acceptable item ID");
                if (!BuiltInRegistries.ITEM.containsKey(id)
                        || BuiltInRegistries.ITEM.get(id) == Items.AIR) {
                    throw new IllegalArgumentException("unknown item ID: " + id);
                }
            }
            acceptableItemIds = ids.stream().sorted().toList();
        }

        public Group(ResourceLocation itemId, int count) {
            this(itemId, List.of(itemId), count, SelectionMode.AGGREGATE);
        }
    }

    /** Complete approved request. Accepted item IDs may not overlap across groups. */
    public record Request(List<Group> groups, boolean allowCrafting, Operation operation) {
        public Request {
            Objects.requireNonNull(groups, "groups");
            Objects.requireNonNull(operation, "operation");
            if (groups.isEmpty() || groups.size() > 128) {
                throw new IllegalArgumentException("an AE2 request needs between 1 and 128 groups");
            }
            groups = List.copyOf(groups);
            Map<ResourceLocation, ResourceLocation> owners = new LinkedHashMap<>();
            for (Group group : groups) {
                for (ResourceLocation accepted : group.acceptableItemIds()) {
                    ResourceLocation prior = owners.putIfAbsent(accepted, group.itemId());
                    if (prior != null) {
                        throw new IllegalArgumentException(
                                "accepted item ID " + accepted + " overlaps groups "
                                        + prior + " and " + group.itemId());
                    }
                }
            }
            if (owners.size() > 2_048) {
                throw new IllegalArgumentException(
                        "one AE2 request may accept at most 2048 distinct item IDs");
            }
        }

        public Request(List<Group> groups, boolean allowCrafting) {
            this(groups, allowCrafting, Operation.SUPPLY);
        }

        public long totalCount() {
            return groups.stream().mapToLong(Group::count).sum();
        }

        public Set<ResourceLocation> acceptedItemIds() {
            LinkedHashSet<ResourceLocation> result = new LinkedHashSet<>();
            groups.forEach(group -> result.addAll(group.acceptableItemIds()));
            return Set.copyOf(result);
        }
    }

    /** Exact before/after evidence for one concrete acceptable item ID. */
    public record ItemDelta(
            ResourceLocation itemId,
            int before,
            int after,
            int acquired,
            boolean selected) {
        public ItemDelta {
            Objects.requireNonNull(itemId, "itemId");
        }
    }

    /** Exact before/after evidence for one semantic group and each accepted concrete item. */
    public record GroupDelta(
            ResourceLocation itemId,
            List<ResourceLocation> acceptableItemIds,
            SelectionMode selectionMode,
            ResourceLocation selectedItemId,
            int requested,
            int before,
            int after,
            int acquired,
            int missing,
            List<ItemDelta> acceptedItemEvidence) {
        public GroupDelta {
            acceptableItemIds = List.copyOf(acceptableItemIds);
            acceptedItemEvidence = List.copyOf(acceptedItemEvidence);
        }
    }

    /** Terminal session outcome. {@code uncertain=true} forbids blind mechanical retry. */
    public record Outcome(
            Status status,
            String code,
            String message,
            List<GroupDelta> groups,
            Operation operation,
            boolean craftingAllowed,
            int craftingRequests,
            int craftingJobsSubmitted,
            boolean effectsStarted,
            boolean uncertain,
            String terminalAccess,
            List<Map<String, Object>> containerFillReceipts) {
        public Outcome {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(operation, "operation");
            code = code == null ? "unknown" : code;
            message = message == null ? "" : message;
            groups = List.copyOf(groups);
            terminalAccess = terminalAccess == null ? "unavailable" : terminalAccess;
            containerFillReceipts = List.copyOf(containerFillReceipts);
        }

        public boolean terminal() {
            return status != Status.RUNNING;
        }

        /** Structured data suitable for {@code TaskResult}. */
        public Map<String, Object> data() {
            List<Map<String, Object>> deltas = new ArrayList<>();
            for (GroupDelta delta : groups) {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("item_id", delta.itemId().toString());
                value.put("acceptable_item_ids",
                        delta.acceptableItemIds().stream().map(ResourceLocation::toString).toList());
                value.put("selection_mode", delta.selectionMode().name().toLowerCase(Locale.ROOT));
                if (delta.selectedItemId() != null) {
                    value.put("selected_item_id", delta.selectedItemId().toString());
                }
                value.put("requested", delta.requested());
                value.put("before", delta.before());
                value.put("after", delta.after());
                value.put("acquired", delta.acquired());
                value.put("missing", delta.missing());
                List<Map<String, Object>> itemEvidence = new ArrayList<>();
                for (ItemDelta item : delta.acceptedItemEvidence()) {
                    itemEvidence.add(Map.of(
                            "item_id", item.itemId().toString(),
                            "before", item.before(),
                            "after", item.after(),
                            "acquired", item.acquired(),
                            "selected", item.selected()));
                }
                value.put("accepted_item_evidence", List.copyOf(itemEvidence));
                deltas.add(Map.copyOf(value));
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("failure_code", code);
            data.put("status", status.name().toLowerCase(Locale.ROOT));
            data.put("operation", operation.name().toLowerCase(Locale.ROOT));
            data.put("actual_delta", List.copyOf(deltas));
            if (operation == Operation.PREPARE) {
                data.put("network_supply_prepared", status == Status.SUCCEEDED);
                data.put("prepared_groups", groups.stream().map(group -> Map.of(
                        "item_id", group.itemId().toString(),
                        "required_network_stock", group.requested())).toList());
            }
            data.put("allow_crafting", craftingAllowed);
            data.put("crafting_requests", craftingRequests);
            data.put("crafting_jobs_submitted", craftingJobsSubmitted);
            data.put("effects_started", effectsStarted);
            data.put("outcome_uncertain", uncertain);
            data.put("mechanical_retry_allowed", !effectsStarted && !uncertain);
            data.put("terminal_access", terminalAccess);
            if (!containerFillReceipts.isEmpty()) data.put("container_fill_receipts", containerFillReceipts);
            return Map.copyOf(data);
        }
    }

    /** Cross-tick execution. Call exactly once per scheduler tick with the fresh actor context. */
    public interface Session {
        Optional<Outcome> tick(LocalPlayerContext context);

        Optional<Outcome> outcome();

        String phase();

        /**
         * True while a bounded native receipt, verified route, or already-submitted external
         * crafting job is still legitimately in flight. Callers may renew a liveness lease;
         * this is not evidence that a new side effect should be submitted.
         */
        boolean livenessActive();

        /** Release locomotion on preemption without selecting a new strategy or repeating a packet. */
        void pause(LocalPlayerContext context);

        /** Best-effort first-person handoff for replacement/body loss; never claims success. */
        Outcome cancel(LocalPlayerContext context, String reason);
    }

    private Ae2ResourceSupply() {}

    public static boolean available() {
        return Ae2ReflectionBridge.availability().available();
    }

    public static String availabilityDetail() {
        return Ae2ReflectionBridge.availability().detail();
    }

    /** Current synchronized repository only; craftable patterns never contribute to stored counts. */
    public static Optional<StockEvidence.Snapshot> observeOpenStock(
            Object menu, long observedGameTick) {
        try {
            var available = Ae2ReflectionBridge.availability().bridge();
            if (available.isEmpty()) return Optional.empty();
            var bridge = available.orElseThrow();
            if (!bridge.isStorageMenu(menu) || !bridge.connected(menu)) return Optional.empty();
            var entries = bridge.entries(menu);
            if (entries == null) return Optional.empty();
            Map<ResourceLocation, Long> stored = new LinkedHashMap<>();
            Set<ResourceLocation> craftable = new LinkedHashSet<>();
            for (var entry : entries) {
                StockEvidence.add(stored, entry.sample(), entry.storedAmount());
                if (entry.craftable()) craftable.add(entry.itemId());
            }
            return Optional.of(new StockEvidence.Snapshot(
                    StockEvidence.Source.AE2,
                    stored, craftable, observedGameTick));
        } catch (RuntimeException | LinkageError unavailable) {
            return Optional.empty();
        }
    }

    /** Read-only water acquisition evidence; absent entries cannot prove an unfinished sync is empty. */
    public static Optional<com.google.gson.JsonObject> observeOpenWaterInventory(
            net.minecraft.world.inventory.AbstractContainerMenu menu) {
        try {
            var available = Ae2ReflectionBridge.availability().bridge();
            if (available.isEmpty()) return Optional.empty();
            var bridge = available.orElseThrow();
            if (!bridge.isStorageMenu(menu) || !org.maiwithu.maicraft.client.actor.MenuVisibility.matches(
                    net.minecraft.client.Minecraft.getInstance(), menu)) return Optional.empty();
            var out = new com.google.gson.JsonObject();
            out.addProperty("connected", bridge.connected(menu));
            var entries = bridge.entries(menu);
            out.addProperty("repository_available", entries != null);
            out.add("synchronization_complete", com.google.gson.JsonNull.INSTANCE);
            out.addProperty("absence_is_authoritative", false);
            out.addProperty("evidence_source", "ae2_synchronized_client_repository");
            if (entries == null) return Optional.of(out);
            var water = bridge.waterEntry(menu);
            out.addProperty("water_entry_observed", water != null);
            out.addProperty("water_native_units", water == null ? 0 : water.storedAmount());
            out.addProperty("units_per_bucket", bridge.fluidBucketUnits());
            out.addProperty("network_empty_buckets", Ae2WaterBucketFill.count(entries, Ae2WaterBucketFill.EMPTY_BUCKET));
            out.addProperty("network_water_buckets", Ae2WaterBucketFill.count(entries, Ae2WaterBucketFill.WATER_BUCKET));
            var level = net.minecraft.client.Minecraft.getInstance().level;
            if (level != null) out.addProperty("observed_game_tick", level.getGameTime());
            return Optional.of(out);
        } catch (RuntimeException | LinkageError unavailable) {
            return Optional.empty();
        }
    }

    /**
     * Record fixed AE access seen inside an explicit {@code inspect_machine} observation. Merely
     * walking near a terminal never calls this method and resource supply never performs discovery.
     */
    public static ExplicitAccessObservation rememberObservedAccess(
            LocalPlayer player, BlockPos center, int radius) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(center, "center");
        Ae2ReflectionBridge.Availability availability = Ae2ReflectionBridge.availability();
        Optional<Ae2ReflectionBridge> bridge = availability.bridge();
        if (bridge.isEmpty()) {
            return new ExplicitAccessObservation(false, radius, 0, 0, false, null,
                    availability.detail());
        }
        try {
            Ae2TerminalAccess.ExplicitObservation observed =
                    Ae2TerminalAccess.rememberObservedWithin(
                            player, bridge.orElseThrow(), center, radius);
            Ae2TerminalAccess.Known selected = observed.selected();
            return new ExplicitAccessObservation(
                    true,
                    observed.radius(),
                    observed.fixedTerminalsObserved(),
                    observed.terminalFacesObserved(),
                    selected != null,
                    selected == null ? null : selected.position(),
                    selected == null
                            ? "no fixed AE terminal was observed inside this explicit machine survey"
                            : "remembered the nearest fixed AE terminal observed by this machine survey");
        } catch (RuntimeException failure) {
            String message = failure.getMessage();
            return new ExplicitAccessObservation(true, radius, 0, 0, false, null,
                    "AE terminal observation failed: "
                            + (message == null ? failure.getClass().getSimpleName() : message));
        }
    }

    public static Session begin(LocalPlayer player, Request request) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(request, "request");
        Ae2ReflectionBridge bridge = Ae2ReflectionBridge.availability().bridge().orElseThrow(
                () -> new IllegalStateException(
                        "AE2 client integration is unavailable: " + availabilityDetail()));
        return new Ae2SupplySession(player, request, bridge);
    }

    /** Task-runtime convenience used by the acquire adapter. */
    public static Ae2SupplyTaskRecord taskRecord(
            String toolCallId, long deadlineGameTime, Request request) {
        return new Ae2SupplyTaskRecord(toolCallId, deadlineGameTime, request);
    }
}
