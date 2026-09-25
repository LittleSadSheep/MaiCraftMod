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
import org.maiwithu.maicraft.core.pathing.calc.NavGoal;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireCompanionTask;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;
import net.minecraft.client.Minecraft;

/**
 * 按总任务的缺料需求取物，默认返回出发工位；建筑可明确接管后续站位，让角色在仓库继续取齐材料。
 * 它只负责材料和返程；房子怎么建、管线怎么接、原计划是否仍有效，仍由调用它的总任务判断。
 */
public final class SemanticMaterialSupplyCoordinator {
    /** 初始无进展期限；活动的材料获取记录可依据已核实进展延长。 */
    public static final long SUPPLY_INITIAL_LEASE_TICKS = 3L * 60L * 20L;
    private static final long RETURN_PROGRESS_LEASE_TICKS = 30L * 20L;
    private static final int RETURN_PROGRESS_GRACE_TICKS = 100;

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

    /** 一项材料需求：列出的物品可互相替代，最终主背包中合计数量达到 requiredFinalCount 才算够。 */
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
    /** 通用机器任务仍回原工位；建筑交接只免去本协调器的返程，不增加导航或修改地形的权限。 */
    public enum ReturnPolicy { RETURN_ORIGIN, CALLER_HANDOFF }

    public record Tick(
            Status status,
            Map<String, Object> receipt,
            FailureType failureType,
            String message) {}

    private SemanticAcquireCompanionTask child;
    private SemanticAcquireTaskRecord childRecord;
    private Demand demand;
    private MaterialPolicy materialPolicy;
    private ReturnPolicy returnPolicy = ReturnPolicy.RETURN_ORIGIN;
    private List<SemanticAcquireTaskRecord.Source> sources = List.of();
    private int serial;
    private long childDeadline;
    private BlockPos investigationOrigin;
    private String originDimension;
    private PlayerNav returnNavigation;
    private Map<String, Object> pendingReceipt;
    private String pendingMessage;
    private List<BlockPos> forbiddenNavigationCells = List.of();

    public boolean active() {
        return child != null || pendingReceipt != null || returnNavigation != null;
    }

    public Map<String, Object> progress() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("phase", child != null ? "acquiring_material" : returnPolicy == ReturnPolicy.CALLER_HANDOFF
                ? "handing_materials_to_caller" : "returning_to_work_site");
        data.put("return_policy", returnPolicy.name().toLowerCase(Locale.ROOT));
        if (demand != null) {
            data.put("required_final_count", demand.requiredFinalCount());
            data.put("acceptable_item_count", demand.acceptableItemIds().size());
            if (demand.acceptableItemIds().size() == 1)
                data.put("item_id", demand.acceptableItemIds().getFirst().toString());
        }
        if (child != null) data.put("child", child.progress());
        return Map.copyOf(data);
    }

    public long childDeadline() {
        return childRecord == null
                ? childDeadline
                : Math.max(childDeadline, childRecord.getDeadlineGameTime());
    }

    /** 传入当前确实缺少的材料；已经够用时不应该再启动这一趟取料。 */
    public void begin(
            LocalPlayer player,
            String parentCallId,
            long parentDeadline,
            Demand demand,
            MaterialPolicy policy,
            List<SemanticAcquireTaskRecord.Source> requestedSources,
            boolean allowHarm,
            List<String> protectedLabels) {
        begin(player, parentCallId, parentDeadline, demand, policy, requestedSources,
                allowHarm, protectedLabels, List.of());
    }

    /**
     * 针对一种精确缺料启动获取任务，并在整个嵌套获取和返程导航过程中传递父任务已观察到的禁行格。
     * 子任务仅在内部接收这些位置，它们不会成为公开语义工具契约的一部分。
     */
    public void begin(
            LocalPlayer player,
            String parentCallId,
            long parentDeadline,
            Demand demand,
            MaterialPolicy policy,
            List<SemanticAcquireTaskRecord.Source> requestedSources,
            boolean allowHarm,
            List<String> protectedLabels,
            Iterable<BlockPos> forbiddenNavigationCells) {
        begin(player, parentCallId, parentDeadline, demand, policy, requestedSources, allowHarm,
                protectedLabels, forbiddenNavigationCells, ReturnPolicy.RETURN_ORIGIN);
    }

    /** 建筑使用明确交接策略：确认取料并收好界面后留在当前位置，下一站由原施工任务选择。 */
    public void begin(LocalPlayer player, String parentCallId, long parentDeadline, Demand demand,
            MaterialPolicy policy, List<SemanticAcquireTaskRecord.Source> requestedSources, boolean allowHarm,
            List<String> protectedLabels, Iterable<BlockPos> forbiddenNavigationCells, ReturnPolicy returnPolicy) {
        // 同时只做一趟供料，记住出发维度和位置；取料期间仍要遵守总任务不许进入的区域。
        if (active()) throw new IllegalStateException("material supply child is already active");
        this.demand = Objects.requireNonNull(demand, "demand");
        this.materialPolicy = policy == null ? MaterialPolicy.ORDINARY : policy;
        this.returnPolicy = Objects.requireNonNull(returnPolicy, "return policy");
        this.sources = resolveSources(this.materialPolicy, requestedSources);
        LinkedHashSet<BlockPos> forbidden = new LinkedHashSet<>();
        if (forbiddenNavigationCells != null) {
            for (BlockPos cell : forbiddenNavigationCells) {
                if (cell != null) forbidden.add(cell.immutable());
            }
        }
        this.forbiddenNavigationCells = List.copyOf(forbidden);
        long now = player.level().getGameTime();
        investigationOrigin = player.blockPosition().immutable();
        originDimension = player.level().dimension().location().toString();
        childDeadline = now + SUPPLY_INITIAL_LEASE_TICKS;
        // 初始留三分钟，后续可按真实进展延长；传入的 parentDeadline 当前没有用于限制这个值。
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
                // 出坑后仓库可能超过十六格；只扩大已授权仓库的查找，不能顺带扩大采矿或附近采集范围。
                sources.contains(SemanticAcquireTaskRecord.Source.STORAGE)
                        ? SemanticAcquireTaskRecord.MAX_RADIUS : SemanticAcquireTaskRecord.DEFAULT_RADIUS);
        childRecord = record;
        child = new SemanticAcquireCompanionTask(player, record);
    }

    /**
     * 通过父任务的常规子任务生命周期推进当前子任务；回调通常来自 AbstractCompanionTask 中的 {@code this::runChild}。
     */
    public Tick tick(LocalPlayer player, Function<Task, TaskState> childRunner) {
        // 让总任务按原来的子任务机制推进取料，结束后还要现场数一次背包，不只信子任务一句成功。
        if (demand == null) {
            throw new IllegalStateException("no material supply child is active");
        }
        if (pendingReceipt != null) return tickReturn(player);
        if (child == null) throw new IllegalStateException("material supply child is missing");
        TaskState terminal = NavigationSafetyContext.withForbiddenBodyCells(
                forbiddenNavigationCells, () -> childRunner.apply(child));
        childDeadline = Math.max(childDeadline, childRecord.getDeadlineGameTime());
        if (terminal == null) {
            return new Tick(Status.RUNNING, Map.of(), FailureType.UNKNOWN, "material supply running");
        }
        TaskResult result = child.result(terminal);
        int observed = inventoryCount(player, demand.acceptableItemIds());
        boolean proven = terminal == TaskState.SUCCESS && result != null && result.success()
                && (result.data() == null || !Boolean.TRUE.equals(result.data().get("outcome_uncertain"))
                        && !Boolean.TRUE.equals(result.data().get("world_change_uncertain")))
                && observed >= demand.requiredFinalCount();
        Map<String, Object> receipt = receipt(result, terminal, observed, proven);
        FailureType type = proven ? FailureType.UNKNOWN : failureType(result, terminal);
        String message = result == null || result.message() == null
                ? (proven ? "material fact satisfied" : "material supply did not complete")
                : result.message();
        child = null;
        childRecord = null;
        if (!proven) {
            clear();
            return new Tick(Status.FAILED, receipt, type, message);
        }
        pendingReceipt = receipt;
        // 取料子任务先完成自己的点击与界面收尾，再按调用方事先选好的策略返回或原地交接。
        pendingMessage = message;
        return tickReturn(player);
    }

    public void cancel(LocalPlayer player) {
        // 停止取物子任务及返程导航，再清掉本趟状态；已经取得的物品留在玩家背包，不自动退回。
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

    /** 按材料策略排列来源：先背包，允许库存时先查库存；当前代码也会同时加入合成来源。 */
    public static List<SemanticAcquireTaskRecord.Source> resolveSources(
            MaterialPolicy policy,
            List<SemanticAcquireTaskRecord.Source> requested) {
        MaterialPolicy effective = policy == null ? MaterialPolicy.ORDINARY : policy;
        List<SemanticAcquireTaskRecord.Source> supplied = requested == null
                ? List.of() : requested;
        // 当前认为显式 STORAGE 优先于 inventory_only；因此这两个条件冲突时不会在这里拒绝，而是开放库存和合成。
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
            // Acquire 的 STORAGE 分支会依据此许可请求应用能源网络合成。
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
        // 把可替代物品在主背包中的数量加总，不计装备和副手。
        int total = 0;
        for (ResourceLocation id : acceptableItemIds) {
            Item item = BuiltInRegistries.ITEM.get(id);
            total += PlayerInv.buildableCount(player.getInventory(), item);
        }
        return total;
    }

    private Map<String, Object> receipt(
            TaskResult result, TaskState terminal, int observed, boolean proven) {
        // 返回需要多少、实际多少、还差多少，并保留少量失败字段；不是把内部取物任务整个对象交出去。
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("purpose", demand.purpose());
        receipt.put("material_policy", materialPolicy.id());
        receipt.put("item_ids", demand.acceptableItemIds().stream()
                .map(ResourceLocation::toString).toList());
        receipt.put("required_final_count", demand.requiredFinalCount());
        receipt.put("observed_final_count", observed);
        receipt.put("missing", Math.max(0, demand.requiredFinalCount() - observed));
        receipt.put("goal_satisfied", proven);
        receipt.put("materials_obtained", proven);
        receipt.put("return_policy", returnPolicy.name().toLowerCase(Locale.ROOT));
        receipt.put("return_required", returnPolicy == ReturnPolicy.RETURN_ORIGIN);
        receipt.put("returned_to_investigation_site", false);
        receipt.put("terminal_state", terminal.name().toLowerCase(Locale.ROOT));
        receipt.put("allowed_sources", sources.stream()
                .map(source -> source.name().toLowerCase(Locale.ROOT)).toList());
        if (result != null && result.data() != null) {
            Map<String, Object> childData = result.data();
            copy(childData, receipt, "failure_type", "failure_code", "requires_decision",
                    "requires_narration", "outcome_uncertain", "world_change_uncertain", "planning_handoff",
                    "body_preparation_required", "food_preparation", "preparation_failure");
            // 加工前置和原生配方链接是下一次规划所需证据，不能在封装材料回执时丢掉，让模型再取同一种失败材料。
            Object options = childData.get("recovery_options");
            if (options instanceof List<?> list) receipt.put("recovery_options", safeOptions(list));
            Object issues = childData.get("issues");
            if (issues instanceof List<?> list) receipt.put("issues", safeIssues(list));
            List<Map<String, Object>> storage = new ArrayList<>();
            if (childData.get("attempts") instanceof List<?> attempts) {
                for (Object value : attempts) {
                    if (value instanceof Map<?, ?> attempt && "storage".equals(attempt.get("source"))
                            && attempt.get("child_data") instanceof Map<?, ?> evidence) {
                        Map<String, Object> entry = new LinkedHashMap<>();
                        for (String key : List.of("terminal_access",
                                "server_supply_receipt_count", "server_supply_transferred",
                                "server_supply_receipts_truncated")) {
                            if (evidence.containsKey(key)) entry.put(key, evidence.get(key));
                        }
                        // 发布已完成的材料转移结果，而不是内部动作或槽位回执。
                        if (evidence.get("server_supply_receipts") instanceof List<?> raw) {
                            List<Map<String, Object>> transfers = new ArrayList<>();
                            for (Object item : raw) {
                                if (transfers.size() == 64) break;
                                if (!(item instanceof Map<?, ?> row) || !Boolean.TRUE.equals(row.get("confirmed"))
                                        || !"server".equals(row.get("backend"))
                                        || !"inventory.ae2_supply".equals(row.get("operation"))) continue;
                                Map<String, Object> transfer = new LinkedHashMap<>();
                                for (String key : List.of("request_id", "operation", "backend", "confirmed",
                                        "resource_id", "amount", "server_tick")) {
                                    if (row.containsKey(key)) transfer.put(key, row.get(key));
                                }
                                transfers.add(Map.copyOf(transfer));
                            }
                            entry.put("server_supply_transfers", List.copyOf(transfers));
                        }
                        if (!entry.isEmpty()) storage.add(Map.copyOf(entry));
                    }
                }
            }
            if (!storage.isEmpty()) receipt.put("storage_attempts", List.copyOf(storage));
        }
        return Map.copyOf(receipt);
    }

    /** 默认仍须回原工位两格内；只有事先选择建筑交接的调用方可以在仓库接回控制。 */
    private Tick tickReturn(LocalPlayer player) {
        Map<String, Object> receipt = new LinkedHashMap<>(pendingReceipt);
        if (!player.level().dimension().location().toString().equals(originDimension)) {
            // 换了维度不能拿同一组坐标当原工位，也不自动决定再过一次传送门。
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
        if (returnPolicy == ReturnPolicy.CALLER_HANDOFF) {
            // 不建返程导航、不瞬移，也不把当前位置说成原墙顶；先确认背包仍够且没有遗留菜单或鼠标物品。
            int carried = inventoryCount(player, demand.acceptableItemIds());
            boolean settled = player.containerMenu == player.inventoryMenu && player.inventoryMenu.getCarried() != null
                    && player.inventoryMenu.getCarried().isEmpty() && Minecraft.getInstance().screen == null;
            if (!settled || carried < demand.requiredFinalCount()) {
                receipt.put("goal_satisfied", false); receipt.put("caller_handoff", false);
                receipt.put("observed_final_count", carried); receipt.put("missing", Math.max(0, demand.requiredFinalCount() - carried));
                receipt.put("failure_code", settled ? "supply_handoff_inventory_changed" : "supply_handoff_unsettled");
                if (!settled) receipt.put("outcome_uncertain", true);
                clear();
                return new Tick(Status.FAILED, Map.copyOf(receipt), settled ? FailureType.NO_MATERIAL : FailureType.UNKNOWN,
                        "material supply could not hand control back before inventory and menu settlement were verified");
            }
            receipt.put("caller_handoff", true);
            String message = pendingMessage + "; materials confirmed; the caller owns the next work position";
            clear();
            return new Tick(Status.SUPPLIED_REPLAN, Map.copyOf(receipt), FailureType.UNKNOWN, message);
        }
        if (player.blockPosition().distSqr(investigationOrigin) <= 4.0D) {
            receipt.put("returned_to_investigation_site", true);
            String message = pendingMessage;
            if (returnNavigation != null) returnNavigation.stop();
            clear();
            return new Tick(Status.SUPPLIED_REPLAN, Map.copyOf(receipt),
                    FailureType.UNKNOWN, message);
        }
        PlayerNav.Status status = NavigationSafetyContext.withForbiddenBodyCells(
                forbiddenNavigationCells, () -> {
                    if (returnNavigation == null) {
                        BlockPos origin = investigationOrigin;
                        returnNavigation = PlayerNav.toGoal(
                                player, () -> NavGoal.near(origin, 2.0D), 1.0,
                                () -> player.blockPosition().distSqr(origin) <= 4.0D);
                    }
                    return returnNavigation.tick();
                });
        if (status == PlayerNav.Status.RUNNING) {
            // 返程仍在算路或有身体进展时给它时间，避免取完材料后正常返程被初始估计打断。
            if (returnNavigation.planningInFlight()) {
                childDeadline++;
            } else if (returnNavigation.hasRecentPhysicalProgress(
                    RETURN_PROGRESS_GRACE_TICKS)) {
                childDeadline = Math.max(childDeadline,
                        player.level().getGameTime() + RETURN_PROGRESS_LEASE_TICKS);
            }
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
        // 优先保留超时／取消，再尝试读子任务原因；完全没有结构化原因时默认按缺料处理。
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
                // 继续使用不会绕过前置条件的默认分支。
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
        // 只清本次供料的记忆；停止仍在活动的任务或导航要由调用方先做，不能仅丢掉引用。
        child = null;
        childRecord = null;
        demand = null;
        sources = List.of();
        childDeadline = 0L;
        investigationOrigin = null;
        originDimension = null;
        returnNavigation = null;
        pendingReceipt = null;
        pendingMessage = null;
        forbiddenNavigationCells = List.of();
        returnPolicy = ReturnPolicy.RETURN_ORIGIN;
    }
}
