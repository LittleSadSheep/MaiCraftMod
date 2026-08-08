// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.supply;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.PlayerInv;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireCompanionTask;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/**
 * Reusable investigate/supply/re-investigate seam for semantic parent tasks.
 *
 * <p>The parent owns the live-world plan.  This coordinator only accepts the plan's aggregate
 * final-inventory fact, delegates that one bounded prerequisite to {@code acquire_items}, and
 * returns a semantic receipt.  A successful tick deliberately means "discard the old plan and
 * investigate again", never "continue executing the cells that were planned before supply".
 */
public final class SemanticMaterialSupplyCoordinator {
    /** Includes bounded acquisition and a terrain-preserving first-person return to the survey. */
    public static final long SUPPLY_DEADLINE_TICKS = 30L * 60L * 20L;

    public enum MaterialPolicy {
        ORDINARY("ordinary"),
        STORAGE_AVAILABLE("storage_available"),
        INVENTORY_ONLY("inventory_only");

        private final String id;

        MaterialPolicy(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public static MaterialPolicy parse(String value) {
            if (value == null || value.isBlank()) return ORDINARY;
            return switch (value.strip().toLowerCase(Locale.ROOT)) {
                case "ordinary", "auto", "survival" -> ORDINARY;
                case "storage_available", "storage", "ae" -> STORAGE_AVAILABLE;
                case "inventory_only", "carried_only" -> INVENTORY_ONLY;
                default -> throw new IllegalArgumentException(
                        "material_policy must be ordinary, storage_available or inventory_only");
            };
        }
    }

    /** One aggregate material fact.  Alternatives are interchangeable for this exact demand. */
    public record Demand(
            List<ResourceLocation> acceptableItemIds,
            int requiredFinalCount,
            String purpose) {
        public Demand {
            if (acceptableItemIds == null || acceptableItemIds.isEmpty()) {
                throw new IllegalArgumentException("material demand needs at least one item id");
            }
            LinkedHashSet<ResourceLocation> normalized = new LinkedHashSet<>();
            for (ResourceLocation id : acceptableItemIds) {
                Objects.requireNonNull(id, "material item id");
                if (!BuiltInRegistries.ITEM.containsKey(id)) {
                    throw new IllegalArgumentException("unknown material item id: " + id);
                }
                normalized.add(id);
            }
            acceptableItemIds = List.copyOf(normalized);
            if (requiredFinalCount < 1
                    || requiredFinalCount > SemanticAcquireTaskRecord.MAX_FINAL_COUNT) {
                throw new IllegalArgumentException("required final material count must be in 1.."
                        + SemanticAcquireTaskRecord.MAX_FINAL_COUNT);
            }
            purpose = purpose == null || purpose.isBlank()
                    ? "planned semantic work" : purpose.strip();
        }
    }

    public enum Status { RUNNING, SUPPLIED_REPLAN, FAILED }

    public record Tick(
            Status status,
            Map<String, Object> receipt,
            FailureType failureType,
            String message) {}

    private SemanticAcquireCompanionTask child;
    private Demand demand;
    private MaterialPolicy materialPolicy;
    private List<SemanticAcquireTaskRecord.Source> sources = List.of();
    private int serial;
    private long childDeadline;
    private BlockPos investigationOrigin;
    private String originDimension;
    private PlayerNav returnNavigation;
    private Map<String, Object> pendingReceipt;
    private String pendingMessage;

    public boolean active() {
        return child != null || pendingReceipt != null || returnNavigation != null;
    }

    public long childDeadline() {
        return childDeadline;
    }

    /** Start one exact, currently observed shortage.  Parents must not call this for a met fact. */
    public void begin(
            LocalPlayer player,
            String parentCallId,
            long parentDeadline,
            Demand demand,
            MaterialPolicy policy,
            List<SemanticAcquireTaskRecord.Source> requestedSources,
            boolean allowHarm,
            List<String> protectedLabels) {
        if (active()) throw new IllegalStateException("material supply child is already active");
        this.demand = Objects.requireNonNull(demand, "demand");
        this.materialPolicy = policy == null ? MaterialPolicy.ORDINARY : policy;
        this.sources = resolveSources(this.materialPolicy, requestedSources);
        long now = player.level().getGameTime();
        investigationOrigin = player.blockPosition().immutable();
        originDimension = player.level().dimension().location().toString();
        childDeadline = Math.max(parentDeadline, now + SUPPLY_DEADLINE_TICKS);
        String prefix = parentCallId == null || parentCallId.isBlank()
                ? "semantic-work" : parentCallId;
        SemanticAcquireTaskRecord record = new SemanticAcquireTaskRecord(
                prefix + "-internal-material-supply-" + (++serial),
                childDeadline,
                demand.acceptableItemIds(),
                demand.requiredFinalCount(),
                sources,
                allowHarm,
                SemanticAcquireTaskRecord.SourceHint.empty(),
                protectedLabels,
                SemanticAcquireTaskRecord.DEFAULT_RADIUS,
                8,
                128);
        child = new SemanticAcquireCompanionTask(player, record);
    }

    /**
     * Advance the current child through the parent's normal child-task lifecycle.
     * The callback is normally {@code this::runChild} from an AbstractCompanionTask.
     */
    public Tick tick(LocalPlayer player, Function<Task, TaskState> childRunner) {
        if (demand == null) {
            throw new IllegalStateException("no material supply child is active");
        }
        if (pendingReceipt != null) return tickReturn(player);
        if (child == null) throw new IllegalStateException("material supply child is missing");
        TaskState terminal = childRunner.apply(child);
        if (terminal == null) {
            return new Tick(Status.RUNNING, Map.of(), FailureType.UNKNOWN, "material supply running");
        }
        TaskResult result = child.result(terminal);
        int observed = inventoryCount(player, demand.acceptableItemIds());
        boolean proven = terminal == TaskState.SUCCESS && result != null && result.success()
                && observed >= demand.requiredFinalCount();
        Map<String, Object> receipt = receipt(result, terminal, observed, proven);
        FailureType type = proven ? FailureType.UNKNOWN : failureType(result, terminal);
        String message = result == null || result.message() == null
                ? (proven ? "material fact satisfied" : "material supply did not complete")
                : result.message();
        child = null;
        if (!proven) {
            clear();
            return new Tick(Status.FAILED, receipt, type, message);
        }
        pendingReceipt = receipt;
        pendingMessage = message;
        return tickReturn(player);
    }

    public void cancel(LocalPlayer player) {
        if (child != null) {
            child.stop(player, Task.StopReason.REPLACED);
            child.result(TaskState.CANCELLED);
        }
        if (returnNavigation != null) returnNavigation.stop();
        clear();
    }

    public static List<SemanticAcquireTaskRecord.Source> parseSources(List<String> values) {
        if (values == null || values.isEmpty()) return List.of();
        LinkedHashSet<SemanticAcquireTaskRecord.Source> result = new LinkedHashSet<>();
        for (String value : values) result.add(SemanticAcquireTaskRecord.Source.parse(value));
        return List.copyOf(result);
    }

    /**
     * Storage permission is a semantic policy: inspect inventory, ask AE first, permit its
     * network crafting, then fall back to the other explicitly/default-safe sources.
     */
    public static List<SemanticAcquireTaskRecord.Source> resolveSources(
            MaterialPolicy policy,
            List<SemanticAcquireTaskRecord.Source> requested) {
        MaterialPolicy effective = policy == null ? MaterialPolicy.ORDINARY : policy;
        List<SemanticAcquireTaskRecord.Source> supplied = requested == null
                ? List.of() : requested;
        // An explicit STORAGE source is itself permission and must obey the documented AE-first
        // contract even if a stale/default policy string says inventory_only.
        if (effective == MaterialPolicy.INVENTORY_ONLY
                && !supplied.contains(SemanticAcquireTaskRecord.Source.STORAGE)) {
            return List.of(SemanticAcquireTaskRecord.Source.INVENTORY);
        }
        boolean storage = effective == MaterialPolicy.STORAGE_AVAILABLE
                || supplied.contains(SemanticAcquireTaskRecord.Source.STORAGE);
        LinkedHashSet<SemanticAcquireTaskRecord.Source> result = new LinkedHashSet<>();
        result.add(SemanticAcquireTaskRecord.Source.INVENTORY);
        if (storage) {
            result.add(SemanticAcquireTaskRecord.Source.STORAGE);
            // Acquire's STORAGE branch uses this permission to request AE network crafting.
            result.add(SemanticAcquireTaskRecord.Source.CRAFT);
        }
        List<SemanticAcquireTaskRecord.Source> defaults = storage
                ? List.of(SemanticAcquireTaskRecord.Source.NEARBY,
                        SemanticAcquireTaskRecord.Source.COOK,
                        SemanticAcquireTaskRecord.Source.MINE)
                : List.of(SemanticAcquireTaskRecord.Source.NEARBY,
                        SemanticAcquireTaskRecord.Source.CRAFT,
                        SemanticAcquireTaskRecord.Source.COOK,
                        SemanticAcquireTaskRecord.Source.MINE);
        for (SemanticAcquireTaskRecord.Source source :
                supplied.isEmpty() ? defaults : supplied) {
            if (source != SemanticAcquireTaskRecord.Source.STORAGE || !storage) result.add(source);
        }
        return List.copyOf(result);
    }

    public static int inventoryCount(
            LocalPlayer player, List<ResourceLocation> acceptableItemIds) {
        int total = 0;
        for (ResourceLocation id : acceptableItemIds) {
            Item item = BuiltInRegistries.ITEM.get(id);
            total += PlayerInv.buildableCount(player.getInventory(), item);
        }
        return total;
    }

    private Map<String, Object> receipt(
            TaskResult result, TaskState terminal, int observed, boolean proven) {
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("purpose", demand.purpose());
        receipt.put("material_policy", materialPolicy.id());
        receipt.put("item_ids", demand.acceptableItemIds().stream()
                .map(ResourceLocation::toString).toList());
        receipt.put("required_final_count", demand.requiredFinalCount());
        receipt.put("observed_final_count", observed);
        receipt.put("missing", Math.max(0, demand.requiredFinalCount() - observed));
        receipt.put("goal_satisfied", proven);
        receipt.put("terminal_state", terminal.name().toLowerCase(Locale.ROOT));
        receipt.put("allowed_sources", sources.stream()
                .map(source -> source.name().toLowerCase(Locale.ROOT)).toList());
        if (result != null && result.data() != null) {
            Map<String, Object> childData = result.data();
            copy(childData, receipt, "failure_type", "failure_code", "requires_decision",
                    "requires_narration", "outcome_uncertain");
            Object options = childData.get("recovery_options");
            if (options instanceof List<?> list) receipt.put("recovery_options", safeOptions(list));
            Object issues = childData.get("issues");
            if (issues instanceof List<?> list) receipt.put("issues", safeIssues(list));
        }
        return Map.copyOf(receipt);
    }

    /** Return to the investigation body position before telling the parent to re-plan. */
    private Tick tickReturn(LocalPlayer player) {
        Map<String, Object> receipt = new LinkedHashMap<>(pendingReceipt);
        if (!player.level().dimension().location().toString().equals(originDimension)) {
            receipt.put("goal_satisfied", false);
            receipt.put("failure_code", "supply_return_world_changed");
            receipt.put("requires_decision", true);
            receipt.put("recovery_options", List.of(
                    Map.of("id", "return_to_worksite", "risk", "none"),
                    Map.of("id", "stop", "risk", "none")));
            receipt.put("returned_to_investigation_site", false);
            String message = "materials were obtained, but the body changed dimension before the worksite could be re-investigated";
            clear();
            return new Tick(Status.FAILED, Map.copyOf(receipt),
                    FailureType.TARGET_LOST, message);
        }
        if (player.blockPosition().distSqr(investigationOrigin) <= 4.0D) {
            receipt.put("returned_to_investigation_site", true);
            String message = pendingMessage;
            if (returnNavigation != null) returnNavigation.stop();
            clear();
            return new Tick(Status.SUPPLIED_REPLAN, Map.copyOf(receipt),
                    FailureType.UNKNOWN, message);
        }
        if (returnNavigation == null) {
            BlockPos origin = investigationOrigin;
            returnNavigation = new PlayerNav(player, origin, 1.0,
                    () -> player.blockPosition().distSqr(origin) <= 4.0D);
        }
        PlayerNav.Status status = returnNavigation.tick();
        if (status == PlayerNav.Status.RUNNING) {
            return new Tick(Status.RUNNING, Map.of(), FailureType.UNKNOWN,
                    "returning to the investigated worksite");
        }
        if (status == PlayerNav.Status.ARRIVED
                && player.blockPosition().distSqr(investigationOrigin) <= 4.0D) {
            receipt.put("returned_to_investigation_site", true);
            String message = pendingMessage;
            returnNavigation.stop();
            clear();
            return new Tick(Status.SUPPLIED_REPLAN, Map.copyOf(receipt),
                    FailureType.UNKNOWN, message);
        }
        String detail = returnNavigation.failReason();
        returnNavigation.stop();
        receipt.put("goal_satisfied", false);
        receipt.put("failure_code", "supply_return_path_blocked");
        receipt.put("requires_decision", true);
        receipt.put("recovery_options", List.of(
                Map.of("id", "make_return_path_accessible", "risk", "world_change"),
                Map.of("id", "return_to_worksite", "risk", "none"),
                Map.of("id", "stop", "risk", "none")));
        receipt.put("returned_to_investigation_site", false);
        clear();
        return new Tick(Status.FAILED, Map.copyOf(receipt), FailureType.NO_PATH,
                "materials were obtained, but the worksite return path failed: " + detail);
    }

    private static FailureType failureType(TaskResult result, TaskState terminal) {
        if (terminal == TaskState.TIMEOUT || result != null && result.timedOut()) {
            return FailureType.TIMED_OUT;
        }
        if (terminal == TaskState.CANCELLED || result != null && result.interrupted()) {
            return FailureType.INTERRUPTED;
        }
        Object raw = result == null || result.data() == null
                ? null : result.data().get("failure_type");
        if (raw != null) {
            try {
                return FailureType.valueOf(raw.toString().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // Fall through to the prerequisite-safe default.
            }
        }
        return FailureType.NO_MATERIAL;
    }

    private static List<Map<String, Object>> safeOptions(List<?> values) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object value : values) {
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> safe = new LinkedHashMap<>();
                copyUnknown(map, safe, "id", "summary", "risk");
                if (!safe.isEmpty()) result.add(Map.copyOf(safe));
            } else if (value != null) {
                result.add(Map.of("id", value.toString()));
            }
        }
        return List.copyOf(result);
    }

    private static List<Map<String, Object>> safeIssues(List<?> values) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> map)) continue;
            Map<String, Object> safe = new LinkedHashMap<>();
            copyUnknown(map, safe, "source", "code", "summary");
            if (!safe.isEmpty()) result.add(Map.copyOf(safe));
        }
        return List.copyOf(result);
    }

    private static void copy(
            Map<String, Object> source, Map<String, Object> target, String... keys) {
        for (String key : keys) if (source.containsKey(key)) target.put(key, source.get(key));
    }

    private static void copyUnknown(
            Map<?, ?> source, Map<String, Object> target, String... keys) {
        for (String key : keys) {
            Object value = source.get(key);
            if (value != null) target.put(key, value);
        }
    }

    private void clear() {
        child = null;
        demand = null;
        sources = List.of();
        childDeadline = 0L;
        investigationOrigin = null;
        originDimension = null;
        returnNavigation = null;
        pendingReceipt = null;
        pendingMessage = null;
    }
}
