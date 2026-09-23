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
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.maiwithu.maicraft.client.actor.MenuVisibility;
import org.maiwithu.maicraft.core.pathing.baritone.landing.BoatLandingSnapshot;

/**
 * AE2 供料的共用入口：调用者说明可接受哪些物品、要多少和是否允许合成，具体找终端、选网络条目、拿取与收尾交给会话。
 * SUPPLY 要求背包净增加指定数量；PREPARE 只准备网络库存。结果分别记录确认数量、已产生影响和是否还有不确定事务。
 */
public final class Ae2ResourceSupply {
    /** 仅由明确且有界的机器观察生成的证据。 */
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

    /** 请求是将物品转入玩家背包，还是仅准备足量的网络库存。 */
    public enum Operation {
        SUPPLY,
        PREPARE,
        /** 把指定数量的普通物品经可见终端存入网络，不合成、不通过服务器供料接口写库存。 */
        DEPOSIT
    }

    public enum SelectionMode {
        /** 可接受的物品 ID 可任意混合计数以满足需求。 */
        AGGREGATE,
        /** 整组操作会选定一个具体可接受物品 ID，并在任务期间锁定它。 */
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
     * 一组可接受物品，主物品总在清单里。取物模式的 count 是背包要新增多少；准备模式把它解释为网络应有的数量。
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

    /** 完整且已获准的请求；不同组之间的可接受物品 ID 不得重叠。 */
    public record Request(List<Group> groups, boolean allowCrafting, Operation operation) {
        public Request {
            Objects.requireNonNull(groups, "groups");
            Objects.requireNonNull(operation, "operation");
            if (groups.isEmpty() || groups.size() > 128) {
                throw new IllegalArgumentException("an AE2 request needs between 1 and 128 groups");
            }
            groups = List.copyOf(groups);
            if (operation == Operation.DEPOSIT && (allowCrafting || groups.stream().anyMatch(group -> group.acceptableItemIds().size() != 1)
                    || groups.stream().mapToLong(Group::count).sum() > 65_536))
                throw new IllegalArgumentException("AE2 deposit requires exact single item IDs, no crafting and at most 65536 items");
            // 不同组不能接受同一种物品，避免同一库存被两份要求重复算作够用。
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

    /** 针对一个具体可接受物品 ID 的精确前后状态证据。 */
    public record ItemDelta(
            ResourceLocation itemId,
            int before,
            int after,
            int acquired,
            int deposited,
            boolean selected) {
        public ItemDelta {
            Objects.requireNonNull(itemId, "itemId");
        }
        public ItemDelta(ResourceLocation itemId, int before, int after, int acquired, boolean selected) {
            this(itemId, before, after, acquired, 0, selected);
        }
    }

    /** 针对一个语义组以及其中每种可接受具体物品的精确前后状态证据。 */
    public record GroupDelta(
            ResourceLocation itemId,
            List<ResourceLocation> acceptableItemIds,
            SelectionMode selectionMode,
            ResourceLocation selectedItemId,
            int requested,
            int before,
            int after,
            int acquired,
            int deposited,
            int missing,
            List<ItemDelta> acceptedItemEvidence) {
        public GroupDelta {
            acceptableItemIds = List.copyOf(acceptableItemIds);
            acceptedItemEvidence = List.copyOf(acceptedItemEvidence);
        }
        public GroupDelta(ResourceLocation itemId, List<ResourceLocation> acceptableItemIds, SelectionMode selectionMode,
                          ResourceLocation selectedItemId, int requested, int before, int after, int acquired, int missing,
                          List<ItemDelta> acceptedItemEvidence) {
            this(itemId, acceptableItemIds, selectionMode, selectedItemId, requested, before, after, acquired, 0, missing, acceptedItemEvidence);
        }
    }

    /** 会话终态结果；{@code uncertain=true} 时禁止盲目重试原生操作。 */
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
            List<Map<String, Object>> containerFillReceipts,
            Map<String, Object> serverSupplyEvidence) {
        public Outcome {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(operation, "operation");
            code = code == null ? "unknown" : code;
            message = message == null ? "" : message;
            groups = List.copyOf(groups);
            terminalAccess = terminalAccess == null ? "unavailable" : terminalAccess;
            containerFillReceipts = List.copyOf(containerFillReceipts);
            serverSupplyEvidence = Map.copyOf(serverSupplyEvidence);
        }

        public Outcome(Status status, String code, String message, List<GroupDelta> groups, Operation operation,
                       boolean craftingAllowed, int craftingRequests, int craftingJobsSubmitted, boolean effectsStarted,
                       boolean uncertain, String terminalAccess, List<Map<String, Object>> containerFillReceipts) {
            this(status, code, message, groups, operation, craftingAllowed, craftingRequests, craftingJobsSubmitted,
                    effectsStarted, uncertain, terminalAccess, containerFillReceipts, Map.of());
        }

        public boolean terminal() {
            return status != Status.RUNNING;
        }

        /** 可直接用于 {@code TaskResult} 的结构化数据。 */
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
                value.put(operation == Operation.DEPOSIT ? "deposited" : "acquired",
                        operation == Operation.DEPOSIT ? delta.deposited() : delta.acquired());
                if (operation == Operation.DEPOSIT) value.put("inventory_net_decrease", delta.before() - delta.after());
                value.put("missing", delta.missing());
                List<Map<String, Object>> itemEvidence = new ArrayList<>();
                for (ItemDelta item : delta.acceptedItemEvidence()) {
                    itemEvidence.add(Map.of(
                            "item_id", item.itemId().toString(),
                            "before", item.before(),
                            "after", item.after(),
                            operation == Operation.DEPOSIT ? "deposited" : "acquired", operation == Operation.DEPOSIT ? item.deposited() : item.acquired(),
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
            if (operation == Operation.DEPOSIT) {
                Map<String, Integer> deposited = new LinkedHashMap<>();
                groups.stream().filter(group -> group.deposited() > 0).forEach(group -> deposited.put(group.itemId().toString(), group.deposited()));
                data.put("deposited", Map.copyOf(deposited));
                data.put("network_deposit_completed", status == Status.SUCCEEDED);
                data.put("confirmed_deposited_total", groups.stream().mapToInt(GroupDelta::deposited).sum());
                data.put("server_insert_api_used", false);
            }
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
            data.putAll(serverSupplyEvidence);
            return Map.copyOf(data);
        }
    }

    /** 跨 tick 执行；每个调度器 tick 都要用最新角色上下文恰好调用一次。 */
    public interface Session {
        Optional<Outcome> tick(LocalPlayerContext context);

        Optional<Outcome> outcome();

        String phase();

        /** 背包物品到达后，不能取消已提交效果的回执结算和清理。 */
        default boolean mustSettleBeforeSatisfiedCancellation() { return false; }

        default void requestSatisfiedSettlement() {}

        /**
         * 有界原生回执、已核实路线或已提交的外部合成任务仍在合法执行时返回 true。调用方可据此续期存活期限；
         * 这不代表应该提交新的副作用。
         */
        boolean livenessActive();

        /** 被抢占时释放移动，但不重新选策略，也不重复发送数据包。 */
        void pause(LocalPlayerContext context);

        /** 因任务替换或身体丢失而进行的尽力第一人称交接；绝不据此报告成功。 */
        Outcome cancel(LocalPlayerContext context, String reason);

        /** 停止获取物品，并在将控制权交还反射链前完成原生菜单关闭核对。 */
        default Optional<Outcome> finishInPlace(LocalPlayerContext context, String reason) {
            return Optional.of(cancel(context, reason));
        }
    }

    private Ae2ResourceSupply() {}

    public static boolean available() {
        return Ae2ReflectionBridge.availability().available();
    }

    public static String availabilityDetail() {
        return Ae2ReflectionBridge.availability().detail();
    }

    /** 实体终端增强是可选的；无线供料和可见菜单供料仍沿用各自原生路径。 */
    public static boolean serverAssistanceSupported() { return Ae2ServerSupply.available(); }

    /** 仅统计当前已同步的仓库库存；可合成的样板绝不计入已存数量。 */
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

    /** 只读的水源获取证据；条目缺失不能证明尚未完成的同步结果为空。 */
    public static Optional<JsonObject> observeOpenWaterInventory(
            AbstractContainerMenu menu) {
        try {
            var available = Ae2ReflectionBridge.availability().bridge();
            if (available.isEmpty()) return Optional.empty();
            var bridge = available.orElseThrow();
            if (!bridge.isStorageMenu(menu) || !MenuVisibility.matches(
                    Minecraft.getInstance(), menu)) return Optional.empty();
            var out = new JsonObject();
            out.addProperty("connected", bridge.connected(menu));
            var entries = bridge.entries(menu);
            out.addProperty("repository_available", entries != null);
            out.add("synchronization_complete", JsonNull.INSTANCE);
            out.addProperty("absence_is_authoritative", false);
            out.addProperty("evidence_source", "ae2_synchronized_client_repository");
            if (entries == null) return Optional.of(out);
            var water = bridge.waterEntry(menu);
            out.addProperty("water_entry_observed", water != null);
            out.addProperty("water_native_units", water == null ? 0 : water.storedAmount());
            out.addProperty("units_per_bucket", bridge.fluidBucketUnits());
            out.addProperty("network_empty_buckets", Ae2WaterBucketFill.count(entries, Ae2WaterBucketFill.EMPTY_BUCKET));
            out.addProperty("network_water_buckets", Ae2WaterBucketFill.count(entries, Ae2WaterBucketFill.WATER_BUCKET));
            var level = Minecraft.getInstance().level;
            if (level != null) out.addProperty("observed_game_tick", level.getGameTime());
            return Optional.of(out);
        } catch (RuntimeException | LinkageError unavailable) {
            return Optional.empty();
        }
    }

    /**
     * 记录在明确的 {@code inspect_machine} 观察中确认的固定 AE 访问点。角色仅仅走到终端附近不会调用此方法；供料流程还有独立的有界搜索。
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
        return begin(player, request, position -> true);
    }

    /** 存入可限制固定终端位置；携带的无线终端沿用其原生权限，其他操作忽略此新增约束。 */
    public static Session begin(LocalPlayer player, Request request, Predicate<BlockPos> depositAccess) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(request, "request");
        Ae2ReflectionBridge bridge = Ae2ReflectionBridge.availability().bridge().orElseThrow(
                () -> new IllegalStateException(
                        "AE2 client integration is unavailable: " + availabilityDetail()));
        return new Ae2SupplySession(player, request, bridge, false, Objects.requireNonNull(depositAccess));
    }

    /** 坠落期间可用：只使用无线或当前可达的访问点，绝不启动导航。 */
    // 原地自救一次只补一件；只有落地船允许再走自动合成，不能把紧急补料变成任意合成任务。
    public static Session beginInPlace(LocalPlayer player, Request request) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(request, "request");
        if (request.operation() != Operation.SUPPLY || request.groups().size() != 1 || request.totalCount() != 1)
            throw new IllegalArgumentException("in-place reflex supply accepts one required item");
        if (request.allowCrafting() && request.acceptedItemIds().stream().anyMatch(id ->
                !BoatLandingSnapshot.plainBoat(BuiltInRegistries.ITEM.get(id))))
            throw new IllegalArgumentException("in-place crafting is reserved for one landing boat");
        Ae2ReflectionBridge bridge = Ae2ReflectionBridge.availability().bridge().orElseThrow(
                () -> new IllegalStateException("AE2 client integration is unavailable: " + availabilityDetail()));
        return new Ae2SupplySession(player, request, bridge, true);
    }

    /** 供获取适配器使用的任务运行时便捷入口。 */
    public static Ae2SupplyTaskRecord taskRecord(
            String toolCallId, long deadlineGameTime, Request request) {
        return new Ae2SupplyTaskRecord(toolCallId, deadlineGameTime, request);
    }

    public static Ae2SupplyTaskRecord taskRecord(String toolCallId, long deadlineGameTime, Request request,
                                                Predicate<BlockPos> depositAccess) {
        return new Ae2SupplyTaskRecord(toolCallId, deadlineGameTime, request, depositAccess);
    }
}
