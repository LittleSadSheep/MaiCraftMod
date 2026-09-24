// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.acquire;

import org.maiwithu.maicraft.core.inventory.StockEvidence;
import org.maiwithu.maicraft.core.integration.ae2.Ae2SupplyTaskRecord;
import java.util.function.Predicate;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import org.maiwithu.maicraft.agent.tool.api.ToolContext;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.Constants;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.PlayerInv;
import org.maiwithu.maicraft.core.combat.Swing;
import org.maiwithu.maicraft.core.integration.ae2.Ae2ResourceSupply;
import org.maiwithu.maicraft.core.inventory.StockEvidence;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.task.acquire.AcquisitionRecipePlanner.Frontier;
import org.maiwithu.maicraft.core.task.acquire.AcquisitionRecipePlanner.IngredientNeed;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.task.base.LandmarkProtection;
import org.maiwithu.maicraft.core.task.collect.CollectItemsTaskRecord;
import org.maiwithu.maicraft.core.task.combat.AttackTaskRecord;
import org.maiwithu.maicraft.core.task.container.ContainerSupplySources;
import org.maiwithu.maicraft.core.task.container.SemanticContainerTaskRecord;
import org.maiwithu.maicraft.core.task.cook.SemanticCookTaskRecord;
import org.maiwithu.maicraft.core.task.craft.CraftPlanCost;
import org.maiwithu.maicraft.core.task.craft.CraftRecoveryCandidate;
import org.maiwithu.maicraft.core.task.craft.CraftTaskRecord;
import org.maiwithu.maicraft.core.task.craft.CraftingWorkstationCoordinator;
import org.maiwithu.maicraft.core.task.entity.EntitySemanticSafety;
import org.maiwithu.maicraft.core.task.entity.GenericEntitySearchCompanionTask;
import org.maiwithu.maicraft.core.task.entity.GenericEntitySearchTaskRecord;
import org.maiwithu.maicraft.core.task.mine.MineBlockTaskRecord;
import org.maiwithu.maicraft.core.task.trade.SemanticTradeTaskRecord;
import org.maiwithu.maicraft.core.tools.CraftOps;
import org.maiwithu.maicraft.core.tools.RecipeProbe;
import org.maiwithu.maicraft.core.tools.ToolParse;
import org.maiwithu.maicraft.intent.IntentRuntime;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/**
 * 把“背包里最终要有这些物品”逐步做成：先看现货，再考虑捡取、库存、合成、烹饪、采矿、交易和狩猎。
 * 缺中间材料时先处理小需求，例如做木剑先要木棍，做木棍又先要木板；实际动作都交给专门子任务。
 * 完成看真实主背包数量，不把配方计划、发出取物请求或打死一只动物当成已经拿到所需物品。
 */
public final class SemanticAcquireCompanionTask
        extends AbstractCompanionTask<SemanticAcquireTaskRecord> {
    private static final int MAX_REPORTED_ATTEMPTS = 64;
    private static final int MAX_REPORTED_ISSUES = 64;
    private static final int PLANNER_STEPS_PER_TICK = 1;
    private static final long PROGRESS_LEASE_TICKS = 60L * 20L;
    private static final long COLLECT_TICKS = 60L * 20L;
    private static final long MINE_MIN_TICKS = 60L * 20L;
    private static final long MINE_PER_UNIT_TICKS = 30L * 20L;
    private static final long STORAGE_TICKS = 10L * 60L * 20L;
    private static final long HUNT_TICKS = 120L * 20L;
    private static final int HUNT_SEARCH_DISTANCE = 512;

    private enum HuntChildStage { NONE, SEARCH, ATTACK }

    private record ExecutableCraft(ResourceLocation outputItem, CraftOps.Plan plan) {}

    private record NearbySurvey(
            int safeCount,
            int protectedCount,
            List<Map<String, Object>> protectedSamples) {}

    private record DimensionBarrier(
            List<ResourceLocation> itemIds,
            List<ResourceLocation> allowedDimensions,
            ResourceLocation currentDimension,
            int depth) {
        DimensionBarrier {
            itemIds = List.copyOf(itemIds);
            allowedDimensions = List.copyOf(new LinkedHashSet<>(allowedDimensions));
        }
    }

    private final CraftOps craftOps = new CraftOps();
    private final Deque<AcquisitionNeed> needs = new ArrayDeque<>();
    private final List<Map<String, Object>> attempts = new ArrayList<>();
    private final List<Map<String, Object>> issues = new ArrayList<>();
    private final List<Map<String, Object>> recipeTrace = new ArrayList<>();
    private final List<AcquisitionNeed> processPlanningNeeds = new ArrayList<>();
    private Map<String, Object> processPlanning = Map.of();
    private final List<DimensionBarrier> dimensionBarriers = new ArrayList<>();
    private final AcquisitionRecipePlanner recipePlanner;
    private final Predicate<LocalPlayer> wirelessAvailable;
    private Map<ResourceLocation, Integer> initialCounts = Map.of();
    private AcquisitionNeed rootNeed;
    private Task activeChild;
    private TaskRecord activeRecord;
    private AcquisitionNeed activeNeed;
    private SemanticAcquireTaskRecord.Source activeSource;
    private String activeDetail;
    private int activeBeforeCount;
    private int activeLastObservedCount;
    private Map<ResourceLocation, Integer> activeBeforeInventory = Map.of();
    private HuntChildStage activeHuntStage = HuntChildStage.NONE;
    private UUID activeHuntTarget;
    private final Set<UUID> rejectedHuntTargets = new LinkedHashSet<>();
    private int harmlessHuntRetargets;
    private int childSerial;
    private int plannerStepsThisTick;
    private String failureCode;
    private AcquisitionNeed failureNeed;
    private DimensionBarrier failureDimension;
    private boolean outcomeUncertain;
    private boolean wirelessStockChecked;
    private long wirelessStockQueryTick;

    public SemanticAcquireCompanionTask(
            LocalPlayer player, SemanticAcquireTaskRecord record) {
        this(player, record, Ae2ResourceSupply::hasCarriedWirelessTerminal);
    }

    /** 网络入口判断单独注入；实际操作仍交给同一 AE 会话，测试无需真的连接外部游戏服务器。 */
    SemanticAcquireCompanionTask(LocalPlayer player, SemanticAcquireTaskRecord record, Predicate<LocalPlayer> wirelessAvailable) {
        super(player, record);
        recipePlanner = new AcquisitionRecipePlanner(player, record.allowHarm, record.storageSearchRadius, record.protectedLabels);
        this.wirelessAvailable = wirelessAvailable;
    }

    @Override
    protected void onStart() {
        // 记下起始库存，并把最终需求放到栈顶；以后缺什么先压上去，凑齐后再回到上一层继续。
        initialCounts = counts(r.itemIds);
        rootNeed = new AcquisitionNeed(
                r.itemIds,
                r.count,
                0,
                new LinkedHashSet<>(r.itemIds),
                Set.of(),
                Set.of(),
                r.allowedSources);
        rootNeed.lastObservedCount = count(rootNeed.itemIds);
        needs.push(rootNeed);
    }

    @Override
    protected TaskState onTick() {
        // 每刻重新找出点名保护的区域；保护名字无法对应当前世界时先停，不把失效保护当成空范围。
        LandmarkProtection protection = LandmarkProtection.resolve(r.protectedLabels,
                IntentRuntime.get().landmarks(), player.level().dimension().location().toString());
        if (!protection.problems().isEmpty()) {
            return failAcquisition("unresolved_protected_label",
                    String.join("; ", protection.problems()), FailureType.TARGET_LOST);
        }
        return protection.run(this::tickAcquisition);
    }

    private TaskState tickAcquisition() {
        // 先看最终数量是否已够，再推进当前子任务或原料需求；通常拿够就停，不为了内部计划继续多做。
        plannerStepsThisTick = 0;
        // 先看上一刻是否已经拿够，不能因为子任务还没报结束就多挖一下或多做一批。
        // 已提交的原生动作例外：让同一子任务结清回执、关闭菜单，再结束取物，不追加新操作。
        if (count(r.itemIds) >= r.count) {
            // 子任务说“已有操作必须先结束”时仍让它做收尾；攻击任务目前把整场战斗和相关拾取都算在内。
            if (activeChild != null
                    && activeChild.mustSettleBeforeSatisfiedCancellation()) {
                activeChild.requestSatisfiedSettlement();
                return tickActiveChild();
            }
            cancelActiveBecauseSatisfied();
            return TaskState.SUCCESS;
        }

        if (activeChild != null) {
            return tickActiveChild();
        }

        // 先一次性读随身终端的网络库存，再比较材料树；查询不领取物品，也不替玩家提交网络合成。
        if (wirelessInventoryAllowed() && (!wirelessStockChecked
                || player.level().getGameTime() - wirelessStockQueryTick >= StockEvidence.MAX_AGE_TICKS)) {
            wirelessStockChecked = true;
            wirelessStockQueryTick = player.level().getGameTime();
            var request = new Ae2ResourceSupply.Request(List.of(), false, Ae2ResourceSupply.Operation.OBSERVE);
            return startChild(rootNeed, SemanticAcquireTaskRecord.Source.INVENTORY,
                    Ae2ResourceSupply.taskRecord(childId("wireless-stock"), wirelessStockQueryTick + STORAGE_TICKS, request),
                    "read wireless network stock for the material tree");
        }

        if (needs.isEmpty()) {
            return failAcquisition(
                    "internal_need_stack_empty",
                    "the acquisition planner lost its remaining need before the inventory fact was true",
                    FailureType.INTERNAL);
        }

        AcquisitionNeed need = needs.peek();
        int observedNeedCount = count(need.itemIds);
        if (need.lastObservedCount >= 0 && observedNeedCount > need.lastObservedCount) {
            need.plannedSourceOrder = null;
            renewProgressLease();
        }
        need.lastObservedCount = observedNeedCount;
        if (observedNeedCount >= need.requiredFinalCount) {
            // 例如木棍已经凑够，移走木棍这个小需求，下一刻回到原先要合成的木剑。
            AcquisitionNeed satisfied = needs.pop();
            propagateSatisfiedNeed(satisfied);
            renewProgressLease();
            return TaskState.RUNNING;
        }
        if (need.decisionRequired) {
            // 某一来源到了授权或保护边界，需要先让调用者判断；不是换一个来源就能自动越过同一问题。
            if (need.depth > 0) return exhaustNeed(need);
            failureNeed = need;
            return failAcquisition(
                    "acquisition_decision_required",
                    "the remaining direct source reached a permission or protection boundary; "
                            + "MaiCraft stopped for narration before trying another effectful route",
                    FailureType.NO_MATERIAL);
        }
        List<SemanticAcquireTaskRecord.Source> sourceOrder = executionSourceOrder(need);
        if (sourceOrder.isEmpty()) {
            return exhaustNeed(need);
        }

        SemanticAcquireTaskRecord.Source source = sourceOrder.getFirst();
        return switch (source) {
            case INVENTORY -> observeInventorySource(need);
            case NEARBY -> attemptNearby(need);
            case STORAGE -> attemptStorage(need);
            case CRAFT -> attemptCraft(need);
            case COOK -> attemptCook(need);
            case MINE -> attemptMine(need);
            case TRADE -> reviewTrade(need);
            case HUNT -> reviewHunt(need);
        };
    }

    private TaskState observeInventorySource(AcquisitionNeed need) {
        // 当前数量不够已在前面确认过，记录这一事实后尝试下一个允许来源，不重复启动“查库存”任务。
        addIssue("inventory", "inventory_insufficient",
                "the final inventory fact is not yet true",
                needFacts(need));
        advanceSource(need);
        return TaskState.RUNNING;
    }

    private TaskState attemptNearby(AcquisitionNeed need) {
        // 先看附近掉落物的归属；当前只要有一个匹配目标归属不明或受保护，就不启用这次按类型拾取。
        if (!sourceDimensionAllowed(need, SemanticAcquireTaskRecord.Source.NEARBY)) {
            return TaskState.RUNNING;
        }
        if (!takePlannerStep()) return TaskState.RUNNING;
        need.attempted(SemanticAcquireTaskRecord.Source.NEARBY);
        NearbySurvey survey = surveyNearby(need.itemIds);
        if (survey.protectedCount() > 0) {
            addIssue("nearby", "drop_ownership_ambiguous",
                    "matching loose items include another entity's drops or a remembered/protected place; "
                            + "the generic collector cannot safely include one and exclude another",
                    Map.of("safe_matching_drops", survey.safeCount(),
                            "protected_matching_drops", survey.protectedCount(),
                            "protected_samples", survey.protectedSamples()));
            advanceSource(need);
            return TaskState.RUNNING;
        }
        if (survey.safeCount() == 0) {
            addIssue("nearby", "no_safe_drop_evidence",
                    "no loaded, pickup-ready, provably unowned matching drops were observed",
                    Map.of("radius", r.searchRadius));
            advanceSource(need);
            return TaskState.RUNNING;
        }

        Set<Item> items = need.itemIds.stream()
                .map(BuiltInRegistries.ITEM::get)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        // 实际交给拾取器的只有物品类型和范围，没有刚才核实过的具体物品实体名单。
        long now = player.level().getGameTime();
        CollectItemsTaskRecord record = new CollectItemsTaskRecord(
                childId("nearby"), now + COLLECT_TICKS, items, r.searchRadius,
                shortLabel(need.itemIds));
        return startChild(need, SemanticAcquireTaskRecord.Source.NEARBY,
                record, "collect loaded unowned drops");
    }

    private TaskState attemptStorage(AcquisitionNeed need) {
        int missing = missing(need);
        if (missing <= 0 || !takePlannerStep()) return TaskState.RUNNING;
        boolean explicitStorage = need.allowedSources.contains(SemanticAcquireTaskRecord.Source.STORAGE);
        if (explicitStorage && need.containerAttempts < ContainerSupplySources.MAX_ATTEMPTS) {
            // 仓库使用独立的有界半径；找得到主城箱子并不意味着可以在同样大的区域内挖矿。
            var ordinary = ContainerSupplySources.candidates(player, player.blockPosition(), r.storageSearchRadius,
                    need.itemIds, need.visitedContainers, r.protectedLabels);
            if (!ordinary.isEmpty()) {
                var source = ordinary.getFirst(); need.visitedContainers.addAll(source.footprint()); need.containerAttempts++;
                need.attempted(SemanticAcquireTaskRecord.Source.STORAGE);
                var record = SemanticContainerTaskRecord.withdrawAvailableAt(childId("container-stock"),
                        player.level().getGameTime() + STORAGE_TICKS, need.itemIds, need.requiredFinalCount,
                        source.position(), source.blockId(), r.protectedLabels);
                return startChild(need, SemanticAcquireTaskRecord.Source.STORAGE, record, "withdraw available missing materials through one visible ordinary container");
            }
        }
        // 普通箱子与 AE2 共用仓库许可；切换后端不会获得挖矿许可，也不能绕过真实取物流程。
        if (!need.wirelessInventory && !Ae2ResourceSupply.available()) {
            addIssue("storage", "storage_adapter_unavailable",
                    "no further safe ordinary container or supported storage-network adapter is available",
                    Map.of("detail", Ae2ResourceSupply.availabilityDetail()));
            advanceSource(need);
            return TaskState.RUNNING;
        }
        need.attempted(SemanticAcquireTaskRecord.Source.STORAGE);
        if (need.wirelessInventory) {
            // 自动网络现货只拿已经查到的数量，先取部分现货，再对余下缺口拆配方；不顺便搜索普通容器。
            var stock = StockEvidence.latestNetwork(player);
            if (stock.isEmpty()) {
                addIssue("storage", "wireless_stock_unknown", "wireless inventory could not be verified; it is not known empty", Map.of());
                advanceSource(need); return TaskState.RUNNING;
            }
            long available = need.itemIds.stream().mapToLong(stock.get()::storedCount).reduce(0L, (a, b) -> a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b);
            if (available <= 0) { advanceSource(need); return TaskState.RUNNING; }
            missing = (int) Math.min(missing, available);
        }
        Ae2ResourceSupply.Group group = new Ae2ResourceSupply.Group(
                need.itemIds.getFirst(), need.itemIds, missing,
                Ae2ResourceSupply.SelectionMode.AGGREGATE);
        // 网络制造也受当前前置需求约束，不能从顶层任务重新取回已经收窄的制造许可。
        boolean allowNetworkCrafting = explicitStorage && !need.wirelessInventory && need.allowedSources.contains(
                SemanticAcquireTaskRecord.Source.CRAFT);
        Ae2ResourceSupply.Request request = new Ae2ResourceSupply.Request(
                List.of(group), allowNetworkCrafting, Ae2ResourceSupply.Operation.SUPPLY, need.wirelessInventory);
        long now = player.level().getGameTime();
        TaskRecord record = Ae2ResourceSupply.taskRecord(
                childId("storage"), now + STORAGE_TICKS, request);
        return startChild(need, SemanticAcquireTaskRecord.Source.STORAGE,
                record, "request exact missing aggregate count from storage"
                        + (allowNetworkCrafting ? " with network crafting allowed" : ""));
    }

    private TaskState attemptCraft(AcquisitionNeed need) {
        // 对每种可接受成品查配方，优先选择材料和工作台都已经满足、成本较低的方案。
        int deficit = missing(need);
        List<CraftRecoveryCandidate> candidates = new ArrayList<>();
        List<ExecutableCraft> executable = new ArrayList<>();
        CraftingWorkstationCoordinator.PlanningSnapshot workstation =
                CraftOps.requiresWorkstationForAny(need.itemIds, player)
                        ? CraftingWorkstationCoordinator.inspect(player) : null;
        if (workstation != null && workstation.surface() == CraftPlanCost.Surface.SEARCHING) {
            return TaskState.RUNNING;
        }
        Set<String> excludedRecipes = new LinkedHashSet<>(need.lineageRecipes);
        excludedRecipes.addAll(need.rejectedRecipes);
        // 先比较所有现在能做的配方，再决定是否继续补旧路线的原料；现成材料不能被旧承诺挡住。
        // 一整轮比较共用一次规划预算，不能按标签成员逐项扣预算，否则可能还没看到便宜配方就停下。
        if (!takePlannerStep()) return TaskState.RUNNING;
        for (ResourceLocation output : need.itemIds) {
            int requestedOwnFinal = PlayerInv.buildableCount(
                    player.getInventory(), BuiltInRegistries.ITEM.get(output)) + deficit;
            ToolContext context = new ToolContext(
                    childId("craft-plan"), player.level().getGameTime());
            CraftOps.Plan plan = craftOps.plan(
                    output.toString(), requestedOwnFinal, player, context,
                    workstation, excludedRecipes);
            if (plan.executable()) {
                executable.add(new ExecutableCraft(output, plan));
            } else {
                candidates.addAll(plan.recoveryCandidates());
            }
        }

        ExecutableCraft selected = executable.stream()
                .filter(candidate -> candidate.plan().cost() != null)
                .min(Comparator.comparing(
                        candidate -> candidate.plan().cost(), CraftPlanCost.ORDER))
                .orElse(null);
        if (selected != null) {
            // 现在有现成可做的配方，就不再为了先前尚未凑齐的方案继续找额外材料。
            need.committedRecipeIds.clear();
            need.committedRecipeIds.add(selected.plan().task().recipeId.toString());
            need.committedRecipeEffectsObserved = false;
            need.attempted(SemanticAcquireTaskRecord.Source.CRAFT);
            return startChild(need, SemanticAcquireTaskRecord.Source.CRAFT,
                    selected.plan().task(),
                    "craft " + selected.outputItem() + " via the cheapest live satisfiable path");
        }

        CraftRecoveryCandidate surfacePrerequisite = candidates.stream()
                .filter(candidate -> recipeAllowedByCommit(need, candidate.recipeId()))
                .filter(candidate -> !need.rejectedRecipes.contains(candidate.recipeId()))
                .filter(candidate -> !need.lineageRecipes.contains(candidate.recipeId()))
                .filter(CraftRecoveryCandidate::surfaceSupported)
                .filter(candidate -> candidate.cost().missingMaterials() == 0)
                .filter(candidate -> candidate.cost().surface()
                        == CraftPlanCost.Surface.PREREQUISITE)
                .filter(candidate -> !candidate.surfacePrerequisiteItems().isEmpty())
                .sorted(Comparator.comparing(CraftRecoveryCandidate::cost, CraftPlanCost.ORDER))
                .findFirst().orElse(null);
        // 原料都齐但缺工作台时，先把取得工作台当作一个小需求，不把它误报为原料缺失。
        if (surfacePrerequisite != null
                && pushCraftingSurfacePrerequisite(need, surfacePrerequisite)) {
            return TaskState.RUNNING;
        }

        List<CraftRecoveryCandidate> viableCandidates = candidates.stream()
                .filter(candidate -> recipeAllowedByCommit(need, candidate.recipeId()))
                .filter(candidate -> !need.rejectedRecipes.contains(candidate.recipeId()))
                .filter(candidate -> !need.lineageRecipes.contains(candidate.recipeId()))
                .filter(CraftRecoveryCandidate::surfaceSupported)
                .filter(candidate -> candidate.cost().missingMaterials() > 0)
                // 某个必需原料组只剩祖先物品时，整条配方都绕回原需求，不能继续为它制造其他配件。
                .filter(candidate -> recipePlanner.chooseIngredient(candidate, need) != null)
                .filter(candidate -> recipePlanner.materialPlan(candidate, need).feasible())
                .sorted(Comparator
                        // 先比较扣除各层现货后的整条补料成本，再比较工作面；无来源的短配方不能抢到前面。
                        .comparingLong((CraftRecoveryCandidate candidate) -> recipePlanner.materialPlan(candidate, need).cost())
                        .thenComparingInt(candidate -> candidate.cost().surface().ordinal())
                        .thenComparing(CraftRecoveryCandidate::cost, CraftPlanCost.ORDER))
                .toList();
        // 还缺原料时，排除循环和已失败配方，再比较所需转换层数与成本，挑一个值得继续补材料的方案。
        CraftRecoveryCandidate chosen = viableCandidates.isEmpty()
                ? null : viableCandidates.getFirst();
        long craftCost = chosen == null ? RecipeMaterialPlan.UNREACHABLE : recipePlanner.materialPlan(chosen, need).cost();
        if (recipePlanner.cookingCost(need) < craftCost) {
            // 直接烧炼的材料树更便宜时切到已有 COOK 执行器；配方比较本身不开始炼制或扣库存。
            var order = new ArrayList<>(executionSourceOrder(need));
            order.remove(SemanticAcquireTaskRecord.Source.COOK);
            order.addFirst(SemanticAcquireTaskRecord.Source.COOK);
            need.plannedSourceOrder = List.copyOf(order);
            return TaskState.RUNNING;
        }
        if (chosen == null) {
            if (!need.committedRecipeIds.isEmpty()) {
                // 选定配方后已实际动过库存或世界，却发现做不下去时先询问，不自动切到另一条未完成的生产链。
                if (need.committedRecipeEffectsObserved) {
                    addIssue("craft", "committed_recipe_unavailable",
                            "the selected recipe stopped exposing a complete frontier after "
                                    + "inventory or world effects were observed",
                            Map.of("recipe_ids", List.copyOf(need.committedRecipeIds),
                                    "requires_narration", true,
                                    "partial_effects", true));
                    failureNeed = need;
                    return failAcquisition(
                            "committed_recipe_unavailable",
                            "the committed recipe stopped exposing a complete cycle-free prerequisite "
                                    + "frontier after effects were observed; MaiCraft stopped instead of "
                                    + "silently switching routes",
                            FailureType.NO_MATERIAL);
                }
                need.rejectedRecipes.addAll(need.committedRecipeIds);
                need.committedRecipeIds.clear();
                need.committedRecipeEffectsObserved = false;
                return TaskState.RUNNING;
            }
            boolean surfaceMissing = candidates.stream().anyMatch(candidate ->
                    candidate.cost().missingMaterials() == 0
                            && candidate.surfaceSupported() && !candidate.surfaceReady());
            addIssue("craft", surfaceMissing
                            ? "crafting_surface_missing" : "no_finite_recipe_path",
                    surfaceMissing
                            ? "materials exist, but no compatible crafting surface can be prepared from live facts"
                            : "no cycle-free client-known ordinary recipe path remained",
                    Map.of("candidate_count", candidates.size(),
                            "rejected_recipes", List.copyOf(need.rejectedRecipes),
                            "lineage_recipes", List.copyOf(need.lineageRecipes)));
            advanceSource(need);
            return TaskState.RUNNING;
        }

        Frontier frontier = recipePlanner.chooseFrontier(chosen, viableCandidates, need);
        if (frontier == null) {
            need.rejectedRecipes.add(chosen.recipeId());
            addIssue("craft", "recipe_cycle_or_missing_evidence",
                    "the closest recipe's missing ingredients were cyclical or did not expose "
                            + "acceptable item IDs",
                    Map.of("recipe_id", chosen.recipeId()));
            return TaskState.RUNNING;
        }
        IngredientNeed ingredient = frontier.ingredient();
        commitRecipe(need, frontier.recipeIds());
        Set<ResourceLocation> lineageItems = new LinkedHashSet<>(need.lineageItems);
        lineageItems.addAll(ingredient.itemIds());
        Set<String> lineageRecipes = new LinkedHashSet<>(need.lineageRecipes);
        lineageRecipes.addAll(frontier.recipeIds());
        int ingredientFinal = count(ingredient.itemIds()) + ingredient.missing();
        // 原料继承相同来源许可；真正先用哪种来源，统一由当前实物条件排序。
        List<SemanticAcquireTaskRecord.Source> childSources = need.allowedSources;
        AcquisitionNeed childNeed = new AcquisitionNeed(
                ingredient.itemIds(), ingredientFinal, need.depth + 1,
                lineageItems, lineageRecipes, frontier.recipeIds(), childSources);
        childNeed.unresolvedMaterialSource = frontier.unknownSource();
        childNeed.lastObservedCount = count(childNeed.itemIds);
        recipeTrace.add(Map.of(
                "recipe_id", chosen.recipeId(),
                "output_item_id", chosen.outputItem().toString(),
                "alternative_recipe_ids", List.copyOf(frontier.recipeIds()),
                "alternative_output_item_ids", itemStrings(frontier.outputItemIds()),
                "depth", need.depth,
                "missing_item_ids", itemStrings(ingredient.itemIds()),
                "missing_count", ingredient.missing(),
                "observed_stock_material_hint", recipePlanner.stockPriority(chosen, need) == 0,
                "preparation_plan", recipePlanner.preparation(chosen, need),
                "allowed_sources", childSources.stream()
                        .map(source -> source.name().toLowerCase(Locale.ROOT))
                        .toList()));
        needs.push(childNeed);
        // 把当前要补的原料压到栈顶，下一刻先解决它，原配方仍在下面等着。
        renewProgressLease();
        return TaskState.RUNNING;
    }

    private TaskState attemptMine(AcquisitionNeed need) {
        if (need.unresolvedMaterialSource) {
            // 工艺缺口不等于地上有同名方块；仍可使用现货，但不能盲找木板、设备等加工品来掩盖缺失步骤。
            addIssue("mine", "material_source_unestablished", "the material tree needs a verified process or acquisition source for this item", needFacts(need));
            advanceSource(need); return TaskState.RUNNING;
        }
        // 从物品对应方块或来源提示找可挖目标；先检查工具要求，再让采矿任务去找实际方块并取回掉落物。
        if (!sourceDimensionAllowed(need, SemanticAcquireTaskRecord.Source.MINE)) {
            return TaskState.RUNNING;
        }
        List<String> refs = new ArrayList<>();
        for (ResourceLocation itemId : need.itemIds) {
            Item item = BuiltInRegistries.ITEM.get(itemId);
            if (item instanceof BlockItem blockItem) {
                refs.add(BuiltInRegistries.BLOCK.getKey(blockItem.getBlock()).toString());
            }
        }
        refs.addAll(sourceHint(need).blockRefs());
        Set<Block> blocks = ToolParse.parseBlocks(refs);
        if (blocks.isEmpty()) {
            addIssue("mine", "mine_source_evidence_missing",
                    "no source block can be derived from a requested BlockItem and no explicit "
                            + "semantic block source_hint was supplied",
                    Map.of("requested_item_ids", itemStrings(need.itemIds)));
            advanceSource(need);
            return TaskState.RUNNING;
        }
        WorkToolPreparation.Choice workTool = WorkToolPreparation.missing(player, blocks,
                need.efficientBatchStarted ? Math.max(WorkToolPreparation.BATCH_SIZE, missing(need)) : missing(need),
                toolMaterialBudget(need, Items.IRON_INGOT), toolMaterialBudget(need, Items.DIAMOND),
                need.preferredToolTierCap);
        int bootstrap = workTool != null && !workTool.stockOnly()
                ? WorkToolPreparation.bootstrapLimit(player, blocks) : 0;
        SemanticSourceKnowledge.ToolRequirement tool = SemanticSourceKnowledge.missingTool(player, blocks);
        if (tool == null && bootstrap == 0 && workTool != null) tool = workTool.requirement();
        boolean stockOnlyUpgrade = workTool != null && tool == workTool.requirement() && workTool.stockOnly();
        if (tool != null) {
            // 没有能正确掉落物品的工具时，先准备工具；工具也走同一套取物／合成需求，而不是凭空生成。
            if (need.miningToolPrerequisitePushed) {
                addIssue("mine", "wrong_tool_prerequisite_unmet",
                        "source blocks are known, but the recursively requested harvesting tool "
                                + "is still absent",
                        Map.of("tool_family", tool.toolFamily(),
                                "minimum_tier", tool.minimumTier(),
                                "acceptable_tool_count", tool.acceptableItemIds().size()));
                advanceSource(need);
                return TaskState.RUNNING;
            }
            List<ResourceLocation> toolItems = tool.acceptableItemIds().stream()
                    .filter(id -> !need.lineageItems.contains(id))
                    .toList();
            if (toolItems.isEmpty()) {
                addIssue("mine", "tool_recipe_cycle",
                        "every suitable harvesting-tool alternative is already in this finite prerequisite lineage",
                        Map.of("tool_family", tool.toolFamily(),
                                "minimum_tier", tool.minimumTier()));
                advanceSource(need);
                return TaskState.RUNNING;
            }
            Set<ResourceLocation> lineage = new LinkedHashSet<>(need.lineageItems);
            lineage.addAll(toolItems);
            List<SemanticAcquireTaskRecord.Source> toolSources =
                    AcquisitionSources.forTool(need.allowedSources, stockOnlyUpgrade);
            AcquisitionNeed toolNeed = new AcquisitionNeed(
                    toolItems, count(toolItems) + 1,
                    need.depth + 1, lineage, need.lineageRecipes, Set.of(),
                    toolSources);
            toolNeed.stockOnlyTool = stockOnlyUpgrade;
            toolNeed.toolPrerequisite = true;
            toolNeed.lastObservedCount = count(toolNeed.itemIds);
            need.miningToolPrerequisitePushed = true;
            addIssue("mine", "preparing_harvesting_tool",
                    "prepare a durable efficient tool before this batch, using available materials",
                    Map.of("tool_family", tool.toolFamily(),
                            "minimum_tier", tool.minimumTier(),
                            "acceptable_tool_count", tool.acceptableItemIds().size()));
            needs.push(toolNeed);
            renewProgressLease();
            return TaskState.RUNNING;
        }
        if (!takePlannerStep()) return TaskState.RUNNING;
        need.attempted(SemanticAcquireTaskRecord.Source.MINE);
        need.miningToolPrerequisitePushed = false;
        int deficit = WorkToolPreparation.batchLimit(player, blocks, Math.min(256, missing(need)));
        if (bootstrap > 0) deficit = Math.min(deficit, bootstrap);
        long now = player.level().getGameTime();
        long budget = Math.max(MINE_MIN_TICKS, deficit * MINE_PER_UNIT_TICKS);
        Set<Item> progressItems = need.itemIds.stream()
                .map(BuiltInRegistries.ITEM::get)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        boolean efficient = MineBlockTaskRecord.hasEfficientTool(player, blocks);
        need.efficientBatchStarted |= efficient && bootstrap == 0 && missing(need) >= WorkToolPreparation.BATCH_SIZE;
        MineBlockTaskRecord record = new MineBlockTaskRecord(
                childId("mine"), now + budget, blocks, deficit, blockLabel(blocks),
                progressItems, efficient, true);
        return startChild(need, SemanticAcquireTaskRecord.Source.MINE,
                record, "mine BlockItem-derived or semantic source blocks");
    }

    private long toolMaterialBudget(AcquisitionNeed need, Item item) {
        // 升级工具前扣掉已经被其他需求预留的铁锭／钻石，避免为了造工具先花掉最终目标要保留的材料。
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        int reserved = needs.stream().filter(pending -> pending.itemIds.contains(id))
                .mapToInt(pending -> pending.requiredFinalCount).max().orElse(0);
        long carried = PlayerInv.buildableCount(player.getInventory(), item);
        // 看见过库存不等于可以取用；仅把已获准且接通取料流程的仓库计入升级预算。
        long external = need.allowedSources.contains(SemanticAcquireTaskRecord.Source.STORAGE)
                ? StockEvidence.latest(player).filter(StockEvidence.Snapshot::supportsToolSupply)
                        .map(stock -> stock.storedCount(id)).orElse(0L)
                : 0L;
        return Math.max(0, carried + Math.min(external, Long.MAX_VALUE - carried) - reserved);
    }

    private TaskState attemptCook(AcquisitionNeed need) {
        // 先挑一个有已知烹饪配方的成品，再交给烹饪任务找原料和燃料；对子来源去掉 COOK，防止自己递归调用自己。
        ResourceLocation output = need.itemIds.stream()
                .filter(this::hasCookingRecipe)
                .findFirst().orElse(null);
        if (output == null) {
            addIssue("cook", "no_cooking_source_path",
                    "no client-known furnace-family recipe produces this recursive need",
                    Map.of("requested_item_ids", itemStrings(need.itemIds)));
            advanceSource(need);
            return TaskState.RUNNING;
        }
        if (!takePlannerStep()) return TaskState.RUNNING;
        need.attempted(SemanticAcquireTaskRecord.Source.COOK);
        int currentSelected = PlayerInv.buildableCount(
                player.getInventory(), BuiltInRegistries.ITEM.get(output));
        int selectedFinal = Math.min(
                SemanticCookTaskRecord.MAX_FINAL_COUNT, currentSelected + missing(need));
        List<SemanticAcquireTaskRecord.Source> childSources =
                AcquisitionSources.forCookingInputs(need.allowedSources);
        long now = player.level().getGameTime();
        SemanticCookTaskRecord child = new SemanticCookTaskRecord(
                childId("cook"),
                now + 12L * 60L * 20L,
                output, selectedFinal, SemanticCookTaskRecord.Preference.AUTO,
                List.of(), childSources, r.allowHarm, r.protectedLabels);
        return startChild(need, SemanticAcquireTaskRecord.Source.COOK, child,
                "cook a client-known output while recursively acquiring its prerequisites");
    }

    private boolean hasCookingRecipe(ResourceLocation outputId) {
        Item output = BuiltInRegistries.ITEM.get(outputId);
        for (var holder : ClientRuntime.requireContext(player)
                .connection().getRecipeManager().getRecipes()) {
            if (!(holder.value() instanceof AbstractCookingRecipe cooking)) continue;
            ItemStack result = RecipeProbe.resultOf(
                    cooking, player.level().registryAccess());
            if (!result.isEmpty() && result.is(output)) return true;
        }
        return false;
    }

    private TaskState reviewTrade(AcquisitionNeed need) {
        // 逐个尝试可接受输出；一次交易失败或没增加目标数量后记住该输出，避免一直找同一种失败交易。
        if (!takePlannerStep()) return TaskState.RUNNING;
        ResourceLocation output = need.preferredTradeOutput;
        if (output == null || need.rejectedTradeOutputs.contains(output)) {
            output = need.itemIds.stream()
                    .filter(id -> !need.rejectedTradeOutputs.contains(id))
                    .findFirst().orElse(null);
            need.preferredTradeOutput = output;
        }
        if (output == null) {
            addIssue("trade", "trade_alternatives_exhausted",
                    "every acceptable output alternative has returned a structured failure or no inventory increment",
                    Map.of("alternative_count", need.itemIds.size(),
                            "rejected_outputs", itemStrings(
                                    List.copyOf(need.rejectedTradeOutputs))));
            advanceSource(need);
            return TaskState.RUNNING;
        }
        need.attempted(SemanticAcquireTaskRecord.Source.TRADE);
        int currentSelected = PlayerInv.buildableCount(
                player.getInventory(), BuiltInRegistries.ITEM.get(output));
        int selectedFinalCount = Math.min(
                SemanticTradeTaskRecord.MAX_FINAL_COUNT,
                currentSelected + missing(need));
        long now = player.level().getGameTime();
        SemanticTradeTaskRecord record = new SemanticTradeTaskRecord(
                childId("trade"),
                now + 8L * 60L * 20L,
                output,
                selectedFinalCount,
                SemanticTradeTaskRecord.MerchantKind.AUTO,
                List.of(),
                r.protectedLabels,
                Math.min(r.searchRadius, SemanticTradeTaskRecord.MAX_RADIUS));
        return startChild(need, SemanticAcquireTaskRecord.Source.TRADE, record,
                "inspect loaded unprotected merchants and execute a synchronized affordable offer");
    }

    private TaskState reviewHunt(AcquisitionNeed need) {
        // 狩猎必须允许伤害，并且有“这种生物能提供目标物品”的来源提示；随后才寻找未被保护的实际目标。
        if (!r.allowHarm) {
            SemanticAcquireTaskRecord.SourceHint hint = sourceHint(need);
            addIssue("hunt", "harm_permission_required",
                    "hunt was allowed as a source family, but allow_harm is false; no entity was attacked",
                    Map.of("requires_narration", true,
                            "ingredient_item_ids", itemStrings(need.itemIds),
                            "entity_type_ids", stringIds(hint.entityTypeIds()),
                            "expected_item_ids", stringIds(hint.expectedItemIds()),
                            "description", hint.description() == null
                                    ? "semantic entity source" : hint.description()));
            need.decisionRequired = true;
            advanceSource(need);
            return TaskState.RUNNING;
        }
        SemanticAcquireTaskRecord.SourceHint hint = sourceHint(need);
        if (hint.entityTypeIds().isEmpty()) {
            addIssue("hunt", "entity_source_evidence_missing",
                    "no semantic entity type source_hint was supplied; no entity was selected",
                    Map.of());
            advanceSource(need);
            return TaskState.RUNNING;
        }
        Set<ResourceLocation> expected = new LinkedHashSet<>(hint.expectedItemIds());
        expected.retainAll(need.itemIds);
        if (expected.isEmpty()) {
            addIssue("hunt", "drop_relation_evidence_missing",
                    "the semantic hint does not identify any requested item as an expected product; "
                            + "no entity was attacked",
                    Map.of("entity_type_ids", stringIds(hint.entityTypeIds())));
            advanceSource(need);
            return TaskState.RUNNING;
        }
        if (!sourceDimensionAllowed(need, SemanticAcquireTaskRecord.Source.HUNT)) {
            return TaskState.RUNNING;
        }

        GenericEntitySearchTaskRecord.Relation relation =
                SemanticSourceKnowledge.huntRelation(hint.entityTypeIds());
        List<Map<String, Object>> protectedCandidates = new ArrayList<>();
        int loadedRadius = need.huntSearchExpandedView
                ? GenericEntitySearchCompanionTask.LOADED_EVIDENCE_RADIUS
                : r.searchRadius;
        List<Entity> safe = safeLoadedHuntCandidates(
                hint, relation, loadedRadius, protectedCandidates);
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("safe_candidate_count", safe.size());
        facts.put("protected_or_ambiguous_candidate_count", protectedCandidates.size());
        facts.put("protected_candidate_samples", protectedCandidates.stream().limit(8).toList());
        facts.put("expected_item_ids", stringIds(expected));
        facts.put("relation", relation.name().toLowerCase(Locale.ROOT));
        facts.put("searched_loaded_radius", loadedRadius);
        if (safe.isEmpty()) {
            // 暂时没看见可用生物时可以走出去找；同一位置和同一组事实已搜过又没变化，就停止重复搜索。
            if (!protectedCandidates.isEmpty()) {
                addIssue("hunt", "protected_hunt_targets_skipped",
                        "loaded matching entities were excluded because they have explicit "
                                + "protection evidence: they are "
                                + "named, tamed, owned, leashed, in a vehicle, carrying a passenger, "
                                + "or inside an explicitly protected enclosed area",
                        facts);
            }
            String searchState = huntSearchState(need, hint, relation);
            if (!need.exhaustedHuntSearchStates.add(searchState)) {
                addIssue("hunt", "hunt_entity_search_state_repeated",
                        "the same dimension, position and semantic entity search state produced no new evidence",
                        Map.of("search_attempts", need.huntSearchAttempts,
                                "max_search_distance", HUNT_SEARCH_DISTANCE,
                                "entity_type_ids", stringIds(hint.entityTypeIds()),
                                "relation", relation.name().toLowerCase(Locale.ROOT)));
                advanceSource(need);
                return TaskState.RUNNING;
            }
            if (!takePlannerStep()) return TaskState.RUNNING;
            need.huntSearchAttempts++;
            need.huntSearchExpandedView = false;
            addIssue("hunt", "no_loaded_hunt_evidence_searching",
                    "no acceptable target is currently loaded; starting first-person frontier search",
                    facts);
            long now = player.level().getGameTime();
            GenericEntitySearchTaskRecord search = new GenericEntitySearchTaskRecord(
                    childId("hunt-search"),
                    now + 10L * 60L * 20L,
                    hint.entityTypeIds(), relation, 1, HUNT_SEARCH_DISTANCE,
                    false, r.protectedLabels, true, rejectedHuntTargets);
            return startHuntChild(need, search, HuntChildStage.SEARCH, null,
                    "find an unprotected semantic hunt source through first-person exploration");
        }

        if (!takePlannerStep()) return TaskState.RUNNING;
        need.attempted(SemanticAcquireTaskRecord.Source.HUNT);
        Entity target = safe.getFirst();
        long now = player.level().getGameTime();
        AttackTaskRecord attack = new AttackTaskRecord(
                childId("hunt-attack"), now + HUNT_TICKS,
                List.of(target.getId()), false, true);
        return startHuntChild(need, attack, HuntChildStage.ATTACK, target.getUUID(),
                "hunt one loaded unprotected "
                        + BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()));
    }

    /** 首次狩猎与首次出手前的换目标共用同一筛选，按已加载、可攻击且不受保护的近处目标排序。 */
    private List<Entity> safeLoadedHuntCandidates(
            SemanticAcquireTaskRecord.SourceHint hint,
            GenericEntitySearchTaskRecord.Relation relation,
            int loadedRadius,
            List<Map<String, Object>> protectedCandidates) {
        // 先筛活着、可攻击、类型匹配的对象，再检查名字、驯服、牵引和区域保护等条件，最后按距离排序。
        List<Entity> safe = new ArrayList<>();
        AABB box = player.getBoundingBox().inflate(loadedRadius);
        for (Entity entity : player.clientLevel.getEntities(player, box, candidate ->
                candidate != player && !candidate.isRemoved() && candidate.isAlive()
                        && candidate.isAttackable()
                        && !rejectedHuntTargets.contains(candidate.getUUID())
                        && hint.entityTypeIds().contains(
                                BuiltInRegistries.ENTITY_TYPE.getKey(candidate.getType())))) {
            if (!EntitySemanticSafety.matchesRelation(entity, relation)) continue;
            List<String> reasons = EntitySemanticSafety.protectionReasons(
                    player, entity, relation, r.protectedLabels, true);
            if (reasons.isEmpty()) {
                safe.add(entity);
            } else if (protectedCandidates != null) {
                protectedCandidates.add(Map.of(
                        "entity_type",
                        BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(),
                        "protection_evidence", reasons));
            }
        }
        safe.sort(Comparator.comparingDouble(player::distanceToSqr));
        return safe;
    }

    private TaskState tickActiveChild() {
        // 每刻观察目标数量，已凑齐时尽早停止多余动作；否则核对狩猎授权、推进子任务并收取它的真实结果。
        r.extendDeadlineTo(activeRecord.getDeadlineGameTime());
        int liveCount = count(activeNeed.itemIds);
        if (liveCount > activeLastObservedCount) {
            activeLastObservedCount = liveCount;
            renewProgressLease();
        }
        activeNeed.lastObservedCount = liveCount;
        // 总目标已经在外层检查，这里还要检查正在补的原料；上一刻的拾取也可能已经把这一项补齐。
        if (liveCount >= activeNeed.requiredFinalCount) {
            if (activeChild.mustSettleBeforeSatisfiedCancellation()) {
                activeChild.requestSatisfiedSettlement();
            } else {
                AcquisitionNeed satisfiedNeed = activeNeed;
                cancelActiveBecauseSatisfied();
                if (!needs.isEmpty() && needs.peek() == satisfiedNeed) {
                    AcquisitionNeed satisfied = needs.pop();
                    propagateSatisfiedNeed(satisfied);
                }
                return TaskState.RUNNING;
            }
        }
        TaskState authorizationStop = revalidateActiveHuntAuthorization();
        if (authorizationStop != null) return authorizationStop;
        TaskState harmlessRetarget = retargetUncommittedHuntToCloserCandidate();
        if (harmlessRetarget != null) return harmlessRetarget;
        TaskState terminal;
        if (player.level().getGameTime() >= activeRecord.getDeadlineGameTime()) {
            activeChild.stop(player, Task.StopReason.REPLACED);
            terminal = TaskState.TIMEOUT;
        } else {
            terminal = runChild(activeChild);
            r.extendDeadlineTo(activeRecord.getDeadlineGameTime());
            if (terminal == null) return TaskState.RUNNING;
        }
        TaskResult result = activeChild.result(terminal);
        if (activeRecord instanceof Ae2SupplyTaskRecord supply
                && supply.request.operation() == Ae2ResourceSupply.Operation.OBSERVE) {
            // 库存查询成功只更新规划事实，不要求背包增加；其界面收尾结果仍须明确，不能忽略未结清操作。
            clearActive();
            if (terminal != TaskState.SUCCESS || result == null || !result.success()) {
                StockEvidence.forgetNetwork(player);
                if (result != null && result.data() != null && Boolean.TRUE.equals(result.data().get("outcome_uncertain")))
                    return failAcquisition("wireless_stock_query_uncertain", result.message(), FailureType.UNKNOWN);
                // 数据源不可读时明确交回连接问题，不能把未知网络当作空仓后展开新的采集任务。
                failureNeed = needs.isEmpty() ? rootNeed : needs.peek();
                return failAcquisition("wireless_stock_unknown", result == null ? "wireless stock query did not settle" : result.message(), FailureType.TARGET_LOST);
            }
            needs.forEach(value -> value.plannedSourceOrder = null);
            return TaskState.RUNNING;
        }
        if (activeRecord instanceof Ae2SupplyTaskRecord supply && supply.request.wirelessOnly()
                && (terminal != TaskState.SUCCESS || result == null || !result.success())) {
            // 取物失败后不继续使用旧网络数量估算缺口；真实背包进展仍由下方原有回执结算。
            StockEvidence.forgetNetwork(player);
        }
        int after = count(activeNeed.itemIds);
        int progress = Math.max(0, after - activeBeforeCount);
        if (progress > 0 && !(activeRecord instanceof Ae2SupplyTaskRecord)) {
            // 合成、采集带来的同名物品增长不等于从 AE 取出；重新观察后再比较剩余缺口，避免把网络库存扣错。
            wirelessStockChecked = false;
        }
        markActiveEffectsIfObserved(terminal, result, progress);
        if (progress > 0) renewProgressLease();
        activeNeed.lastObservedCount = after;
        recordAttempt(terminal, result, after, progress, false);

        AcquisitionNeed completedNeed = activeNeed;
        SemanticAcquireTaskRecord.Source completedSource = activeSource;
        TaskRecord completedRecord = activeRecord;
        HuntChildStage completedHuntStage = activeHuntStage;
        UUID completedHuntTarget = activeHuntTarget;
        clearActive();

        // 击杀之后还要结算本次可归属、可到达的掉落；拿到羊毛却没收完羊肉，不能掩盖收尾失败。
        if (completedSource == SemanticAcquireTaskRecord.Source.HUNT
                && completedHuntStage == HuntChildStage.ATTACK
                && terminal != TaskState.SUCCESS
                && huntDefeated(result)) {
            // 当前狩猎把击杀后的全部相关拾取也当作必需步骤，目标物品已够但副产物未处理完也会失败。
            addIssue("hunt", "hunt_loot_settlement_incomplete",
                    "the target was defeated, but collection of all attributable reachable "
                            + "drops did not settle; requested-item progress is retained but the "
                            + "acquisition is not reported as complete",
                    Map.of("terminal_state", terminal.name().toLowerCase(),
                            "child_message", result == null || result.message() == null
                                    ? "" : result.message(),
                            "inventory_progress", progress,
                            "requires_narration", true,
                            "loot_receipt", result == null || result.data() == null
                                    ? Map.of() : result.data()));
            failureNeed = completedNeed;
            return failAcquisition(
                    "hunt_loot_settlement_incomplete",
                    "a defeated source left attributable reachable loot unsettled; stopped before "
                            + "pretending the acquisition transaction was complete",
                    childFailureType(terminal, result));
        }

        if (completedSource == SemanticAcquireTaskRecord.Source.MINE && terminal != TaskState.SUCCESS
                && result != null && result.data() != null
                && (integer(result.data().get("unreachable_drop_count"), 0) > 0
                        || integer(result.data().get("ambiguous_merged_drop_count"), 0) > 0)) {
            addIssue("mine", "mining_loot_uncollected",
                    "requested inventory progress is retained, but some mining drops were not collected",
                    Map.of("unreachable_drop_count", integer(result.data().get("unreachable_drop_count"), 0),
                            "ambiguous_merged_drop_count", integer(result.data().get("ambiguous_merged_drop_count"), 0),
                            "inventory_progress", progress, "requires_narration", true));
        }
        if (result != null && result.data() != null) {
            boolean uncertain = bool(result.data().get("outcome_uncertain"))
                    || "uncertain".equals(result.data().get("status"));
            boolean outstandingBatch = bool(result.data().get("batch_outstanding"));
            boolean storage = completedSource == SemanticAcquireTaskRecord.Source.STORAGE;
            boolean unsettled = outstandingBatch
                    || storage && terminal != TaskState.SUCCESS && bool(result.data().get("effects_started"));
            if (uncertain || unsettled) {
                // 烹饪等来源也可能已经拿到目标物品却留有未结事务；背包达标不能覆盖子任务的未确认效果。
                outcomeUncertain |= uncertain || outstandingBatch;
                failureNeed = completedNeed;
                String sourcePrefix = storage ? "storage" : "acquisition";
                String code = sourcePrefix + (uncertain ? "_effect_uncertain" : "_settlement_incomplete");
                addIssue(completedSource.name().toLowerCase(Locale.ROOT), code,
                        "acquisition effects did not settle; carried inventory alone cannot confirm them", result.data());
                return failAcquisition(code,
                        "an acquisition source may already have changed state, but its work did not settle; stop and review before retrying",
                        uncertain ? FailureType.UNKNOWN : childFailureType(terminal, result));
            }
        }
        if (count(r.itemIds) >= r.count) return TaskState.SUCCESS;
        if (after >= completedNeed.requiredFinalCount) {
            if (!needs.isEmpty() && needs.peek() == completedNeed) {
                AcquisitionNeed satisfied = needs.pop();
                propagateSatisfiedNeed(satisfied);
            }
            renewProgressLease();
            return TaskState.RUNNING;
        }
        if (completedSource == SemanticAcquireTaskRecord.Source.HUNT) {
            return finishHuntChild(
                    completedNeed, completedRecord, completedHuntStage, completedHuntTarget,
                    terminal, result, progress);
        }
        if (completedSource == SemanticAcquireTaskRecord.Source.MINE
                && result != null && result.data() != null
                && "wrong_tool".equals(result.data().get("failure_type"))) {
            if (completedRecord instanceof MineBlockTaskRecord mine && mine.requireEfficientTool) {
                completedNeed.miningToolPrerequisitePushed = false;
                completedNeed.plannedSourceOrder = null;
                completedNeed.exhaustedSources.remove(SemanticAcquireTaskRecord.Source.MINE);
                addIssue("mine", "work_tool_exhausted", "settled the batch's drops; prepare a replacement work tool",
                        Map.of("gathered", progress));
                return TaskState.RUNNING;
            }
            addIssue("mine", "wrong_tool",
                    "the mining child rejected the current harvesting tools; the next source "
                            + "decision is based on structured failure_type evidence",
                    Map.of("failure_type", "wrong_tool"));
            advanceSource(completedNeed);
            return TaskState.RUNNING;
        }
        if (progress == 0 || terminal != TaskState.SUCCESS) {
            addIssue(completedSource.name().toLowerCase(), "child_task_incomplete",
                    result == null ? "child task ended without a result" : result.message(),
                    result == null || result.data() == null ? Map.of() : result.data());
        }
        boolean structuredFailure = structuredFailure(terminal, result);
        switch (completedSource) {
            case NEARBY -> {
                if (progress == 0 || structuredFailure) advanceSource(completedNeed);
            }
            case CRAFT -> {
                if (progress == 0 || structuredFailure) {
                    if (completedRecord instanceof CraftTaskRecord craft) {
                        if (recoverCraftingSurface(completedNeed, craft, result)) {
                            return TaskState.RUNNING;
                        }
                        if (completedNeed.committedRecipeIds.contains(
                                craft.recipeId.toString())
                                && completedNeed.committedRecipeEffectsObserved) {
                            addIssue("craft", "committed_recipe_failed_after_effects",
                                    "the selected recipe failed after inventory or world effects were observed; "
                                            + "automatic recipe switching was stopped",
                                    Map.of("recipe_id", craft.recipeId.toString(),
                                            "requires_narration", true,
                                            "partial_effects", true));
                            return failAcquisition(
                                    "committed_recipe_failed_after_effects",
                                    "the committed crafting route changed inventory or the world before it failed; "
                                            + "review the receipt before choosing another route",
                                    FailureType.NO_MATERIAL);
                        }
                        // 工作台路线已确实失败后排除该配方，再考虑其他合成路线，例如背包四格配方。
                        completedNeed.rejectedRecipes.add(craft.recipeId.toString());
                        completedNeed.committedRecipeIds.remove(craft.recipeId.toString());
                        if (completedNeed.committedRecipeIds.isEmpty()) {
                            completedNeed.committedRecipeEffectsObserved = false;
                        }
                    } else {
                        advanceSource(completedNeed);
                    }
                }
            }
            case COOK -> {
                if (progress == 0 || structuredFailure) {
                    advanceSource(completedNeed);
                }
            }
            case STORAGE -> {
                if (!(completedRecord instanceof SemanticContainerTaskRecord container && container.storageSupply())
                        && (progress == 0 || structuredFailure)) advanceSource(completedNeed);
            }
            case MINE -> {
                if (progress == 0 || structuredFailure) {
                    advanceSource(completedNeed);
                }
            }
            case TRADE -> {
                if (progress == 0 || structuredFailure) {
                    if (completedNeed.preferredTradeOutput != null) {
                        completedNeed.rejectedTradeOutputs.add(
                                completedNeed.preferredTradeOutput);
                        completedNeed.preferredTradeOutput = null;
                    }
                    if (completedNeed.rejectedTradeOutputs.containsAll(
                            completedNeed.itemIds)) {
                        advanceSource(completedNeed);
                    }
                }
            }
            default -> advanceSource(completedNeed);
        }
        return TaskState.RUNNING;
    }

    private TaskState revalidateActiveHuntAuthorization() {
        // 追赶期间对象可能被驯服、改名或失去原身份；每刻再核对，不能只凭开始时的一次判断继续攻击。
        if (activeSource != SemanticAcquireTaskRecord.Source.HUNT
                || activeHuntStage != HuntChildStage.ATTACK
                || !(activeRecord instanceof AttackTaskRecord attack)
                || attack.entityIds.isEmpty()) {
            return null;
        }
        Entity live = player.clientLevel.getEntity(attack.entityIds.getFirst());
        // 目标死亡或离开加载范围后，由攻击任务继续判断归属和收取掉落；在这里打断会丢下待收物品。
        if (live == null || live.isRemoved() || !live.isAlive()) return null;

        SemanticAcquireTaskRecord.SourceHint hint = sourceHint(activeNeed);
        GenericEntitySearchTaskRecord.Relation relation =
                SemanticSourceKnowledge.huntRelation(hint.entityTypeIds());
        boolean sameIdentity = activeHuntTarget != null
                && activeHuntTarget.equals(live.getUUID());
        boolean sameType = hint.entityTypeIds().contains(
                BuiltInRegistries.ENTITY_TYPE.getKey(live.getType()));
        boolean sameRelation = EntitySemanticSafety.matchesRelation(live, relation);
        if (!sameIdentity || !sameType || !sameRelation) {
            return stopActiveHuntAfterAuthorizationChange(
                    List.of(), "hunt_target_identity_or_relation_changed", false);
        }

        List<String> protectionReasons = EntitySemanticSafety.protectionReasons(
                player, live, relation, r.protectedLabels, true);
        if (protectionReasons.isEmpty()) return null;
        return stopActiveHuntAfterAuthorizationChange(
                protectionReasons, "hunt_target_became_protected", true);
    }

    /** 靠近途中出现明显更近的同类目标时，只允许在首次出手前换目标；出手后必须完成原目标的战斗与掉落收尾。 */
    private TaskState retargetUncommittedHuntToCloserCandidate() {
        // 还没出手时，眼前出现明显更近的同类目标可以换；已经开始伤害或拾取后不再偷偷换对象。
        if (activeSource != SemanticAcquireTaskRecord.Source.HUNT
                || activeHuntStage != HuntChildStage.ATTACK
                || !(activeRecord instanceof AttackTaskRecord attack)
                || attack.entityIds.isEmpty()
                || attack.strikes() > 0
                || !attack.defeated().isEmpty()
                || activeChild.mustSettleBeforeSatisfiedCancellation()) {
            return null;
        }
        Entity current = player.clientLevel.getEntity(attack.entityIds.getFirst());
        if (current == null || current.isRemoved() || !current.isAlive()) return null;

        SemanticAcquireTaskRecord.SourceHint hint = sourceHint(activeNeed);
        GenericEntitySearchTaskRecord.Relation relation =
                SemanticSourceKnowledge.huntRelation(hint.entityTypeIds());
        ResourceLocation currentType = BuiltInRegistries.ENTITY_TYPE.getKey(current.getType());
        Entity replacement = safeLoadedHuntCandidates(
                        hint,
                        relation,
                        GenericEntitySearchCompanionTask.LOADED_EVIDENCE_RADIUS,
                        null)
                .stream()
                .filter(candidate -> currentType.equals(
                        BuiltInRegistries.ENTITY_TYPE.getKey(candidate.getType())))
                .findFirst()
                .orElse(null);
        if (replacement == null
                || replacement.getUUID().equals(current.getUUID())
                || !materiallyCloserForAttack(current, replacement)) {
            return null;
        }

        // 真正切换前再查一次是否已经出手；旧目标仍可供以后选择，不能仅因路更远就记成不可用。
        if (attack.strikes() > 0
                || !attack.defeated().isEmpty()
                || activeChild.mustSettleBeforeSatisfiedCancellation()) {
            return null;
        }
        AcquisitionNeed retargetedNeed = activeNeed;
        double oldDistance = player.distanceTo(current);
        double newDistance = player.distanceTo(replacement);
        activeChild.stop(player, Task.StopReason.REPLACED);
        activeChild.result(TaskState.CANCELLED);
        clearActive();

        harmlessHuntRetargets++;
        Constants.LOG.info(
                "[maicraft-task] harmless pre-strike hunt retarget type={} distance={} -> {}",
                currentType, oldDistance, newDistance);
        long now = player.level().getGameTime();
        AttackTaskRecord redirected = new AttackTaskRecord(
                childId("hunt-attack"), now + HUNT_TICKS,
                List.of(replacement.getId()), false, true);
        return startHuntChild(
                retargetedNeed,
                redirected,
                HuntChildStage.ATTACK,
                replacement.getUUID(),
                "retarget a substantially nearer loaded unprotected " + currentType
                        + " before any combat effect");
    }

    /** 用当前攻击距离判断是否值得换目标：已经能够着的新目标优先，否则至少少走一个攻击距离，避免来回改道。 */
    private boolean materiallyCloserForAttack(Entity current, Entity replacement) {
        // 新目标必须进入可攻击距离，或至少省下一整段攻击距离的接近路程，避免两只来回走动就反复改目标。
        double nativeReach = player.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE);
        double currentReach = Swing.reachTo(nativeReach, current.getBbWidth());
        double replacementReach = Swing.reachTo(nativeReach, replacement.getBbWidth());
        double currentDistance = player.distanceTo(current);
        double replacementDistance = player.distanceTo(replacement);
        if (!(replacementDistance < currentDistance)) return false;

        double currentApproach = Math.max(0.0, currentDistance - currentReach);
        double replacementApproach = Math.max(0.0, replacementDistance - replacementReach);
        if (replacementApproach == 0.0 && currentApproach > 0.0) return true;
        double reachBand = Math.max(currentReach, replacementReach);
        return replacementApproach + reachBand <= currentApproach;
    }

    private TaskState stopActiveHuntAfterAuthorizationChange(
            List<String> protectionReasons, String issueCode, boolean requiresDecision) {
        // 停止旧攻击，保留已发生的效果，记下被拒绝的目标；确实触及保护时要求调用者先决定。
        activeChild.stop(player, Task.StopReason.REPLACED);
        TaskResult stopped = activeChild.result(TaskState.CANCELLED);
        int after = count(activeNeed.itemIds);
        int progress = Math.max(0, after - activeBeforeCount);
        markActiveEffectsIfObserved(TaskState.CANCELLED, stopped, progress);
        recordAttempt(TaskState.CANCELLED, stopped, after, progress, false);

        AcquisitionNeed interruptedNeed = activeNeed;
        UUID interruptedTarget = activeHuntTarget;
        clearActive();
        if (interruptedTarget != null) rejectedHuntTargets.add(interruptedTarget);
        interruptedNeed.huntSearchExpandedView = true;

        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("protection_evidence", List.copyOf(protectionReasons));
        facts.put("inventory_progress", progress);
        facts.put("requires_narration", requiresDecision);
        facts.put("child_receipt", stopped == null || stopped.data() == null
                ? Map.of() : stopped.data());
        addIssue("hunt", issueCode,
                requiresDecision
                        ? "the exact active target gained explicit protection evidence; the attack was stopped"
                        : "the active runtime target no longer matched its retained identity, type or relation",
                facts);
        if (requiresDecision) interruptedNeed.decisionRequired = true;
        renewProgressLease();
        return TaskState.RUNNING;
    }

    private TaskState finishHuntChild(
            AcquisitionNeed need,
            TaskRecord completedRecord,
            HuntChildStage stage,
            UUID targetUuid,
            TaskState terminal,
            TaskResult result,
            int progress) {
        // 找到生物之后还要重新核对，再交给攻击任务；攻击结束也要确认目标物品真的进入背包，不能只看生物死了。
        if (stage == HuntChildStage.SEARCH) {
            if (terminal == TaskState.SUCCESS
                    && result != null && result.success()
                    && bool(result.data() == null ? null : result.data().get("verified"))) {
                Entity retained = completedRecord instanceof GenericEntitySearchTaskRecord search
                        ? revalidateInternalHuntTarget(need, search) : null;
                if (retained != null) {
                    if (takePlannerStep()) {
                        need.attempted(SemanticAcquireTaskRecord.Source.HUNT);
                        long now = player.level().getGameTime();
                        AttackTaskRecord attack = new AttackTaskRecord(
                                childId("hunt-attack"), now + HUNT_TICKS,
                                List.of(retained.getId()), false, true);
                        return startHuntChild(
                                need, attack, HuntChildStage.ATTACK, retained.getUUID(),
                                "hunt the still-live entity verified by the internal search receipt");
                    }
                }
                // 搜索收尾时目标可能刚好离开加载范围；重新观察同类候选，不向外暴露临时实体编号。
                need.huntSearchExpandedView = true;
                renewProgressLease();
                return TaskState.RUNNING;
            }
            need.huntSearchExpandedView = false;
            Map<String, Object> searchReceipt = result == null || result.data() == null
                    ? Map.of() : result.data();
            boolean searchRequiresDecision = bool(searchReceipt.get("requires_decision"))
                    || "only_protected_or_ambiguous_entity_evidence".equals(
                            searchReceipt.get("failure_code"));
            Map<String, Object> searchFacts = new LinkedHashMap<>();
            searchFacts.put("terminal_state", terminal.name().toLowerCase());
            searchFacts.put("search_attempts", need.huntSearchAttempts);
            searchFacts.put("requires_narration", true);
            searchFacts.put("search_receipt", searchReceipt);
            addIssue("hunt", "hunt_entity_search_incomplete",
                    "first-person search ended without a complete acceptable entity observation",
                    searchFacts);
            if (searchRequiresDecision) {
                need.decisionRequired = true;
                return TaskState.RUNNING;
            }
            advanceSource(need);
            return TaskState.RUNNING;
        }

        if (stage == HuntChildStage.ATTACK) {
            if (terminal == TaskState.SUCCESS || huntDefeated(result)) {
                if (progress == 0) {
                    if (targetUuid != null) rejectedHuntTargets.add(targetUuid);
                    addIssue("hunt", "expected_hunt_drop_not_observed",
                            "the authorized target was defeated and its own new-drop sweep finished, "
                                    + "but the requested live inventory fact did not increase",
                            Map.of("inventory_progress", 0,
                                    "expected_item_ids", stringIds(
                                            sourceHint(need).expectedItemIds()),
                                    "loot_gained", result == null || result.data() == null
                                            ? Map.of()
                                            : result.data().getOrDefault("loot_gained", Map.of()),
                                    "unreachable_drop_count", result == null || result.data() == null
                                            ? 0
                                            : result.data().getOrDefault("unreachable_drop_count", 0)));
                    need.huntSearchExpandedView = true;
                    renewProgressLease();
                } else {
                    renewProgressLease();
                }
                // 攻击任务已经区分旧掉落与本次新增、合并的战利品并负责收取；不能再按物品种类泛捡一遍。
                return TaskState.RUNNING;
            }
            if (targetUuid != null) rejectedHuntTargets.add(targetUuid);
            addIssue("hunt", "hunt_target_lost_or_unreachable",
                    "the selected loaded target disappeared or could not be reached; "
                            + "a later authorized attempt would re-observe loaded entities",
                    Map.of("terminal_state", terminal.name().toLowerCase(),
                            "child_message", result == null || result.message() == null
                                    ? "" : result.message(),
                            "inventory_progress", progress,
                            "attempted_targets", need.attempts(
                                    SemanticAcquireTaskRecord.Source.HUNT)));
            need.huntSearchExpandedView = true;
            renewProgressLease();
            return TaskState.RUNNING;
        }

        addIssue("hunt", "hunt_child_stage_missing",
                "a hunt child finished without a retained search/attack stage",
                Map.of("terminal_state", terminal.name().toLowerCase()));
        advanceSource(need);
        return TaskState.RUNNING;
    }

    private Entity revalidateInternalHuntTarget(
            AcquisitionNeed need, GenericEntitySearchTaskRecord search) {
        // 只重新寻找搜索任务确认过的 UUID，并再次核对保护条件，防止旧的运行时数字编号被复用到另一只生物。
        Set<UUID> retained = new LinkedHashSet<>(search.internalVerifiedEntityUuids());
        if (retained.isEmpty()) return null;
        SemanticAcquireTaskRecord.SourceHint hint = sourceHint(need);
        GenericEntitySearchTaskRecord.Relation relation =
                SemanticSourceKnowledge.huntRelation(hint.entityTypeIds());
        AABB box = player.getBoundingBox().inflate(
                GenericEntitySearchCompanionTask.LOADED_EVIDENCE_RADIUS);
        return player.clientLevel.getEntities(player, box, candidate ->
                        candidate != player
                                && retained.contains(candidate.getUUID())
                                && !rejectedHuntTargets.contains(candidate.getUUID())
                                && !candidate.isRemoved()
                                && candidate.isAlive()
                                && candidate.isAttackable()
                                && hint.entityTypeIds().contains(
                                        BuiltInRegistries.ENTITY_TYPE.getKey(candidate.getType())))
                .stream()
                .filter(entity -> EntitySemanticSafety.matchesRelation(entity, relation))
                .filter(entity -> EntitySemanticSafety.protectionReasons(
                                player, entity, relation, r.protectedLabels, true)
                        .isEmpty())
                .min(Comparator.comparingDouble(player::distanceToSqr))
                .orElse(null);
    }

    private TaskState startChild(
            AcquisitionNeed need,
            SemanticAcquireTaskRecord.Source source,
            TaskRecord record,
            String detail) {
        // 保存这个子任务服务哪一项需求、来自哪种来源，以及开始前的库存，结束后才能比较结果。
        activeNeed = need;
        activeSource = source;
        activeRecord = record;
        activeChild = TaskFactory.create(player, record);
        activeDetail = detail;
        activeBeforeCount = count(need.itemIds);
        activeLastObservedCount = activeBeforeCount;
        activeBeforeInventory = inventorySnapshot();
        r.extendDeadlineTo(record.getDeadlineGameTime());
        return TaskState.RUNNING;
    }

    private TaskState startHuntChild(
            AcquisitionNeed need,
            TaskRecord record,
            HuntChildStage stage,
            UUID targetUuid,
            String detail) {
        TaskState state = startChild(
                need, SemanticAcquireTaskRecord.Source.HUNT, record, detail);
        activeHuntStage = stage;
        activeHuntTarget = targetUuid;
        return state;
    }

    private void cancelActiveBecauseSatisfied() {
        // 数量已够就停止普通子任务，记录实际得到的东西；不会为了凑足它原来的动作次数继续多做。
        if (activeChild == null) return;
        activeChild.stop(player, Task.StopReason.REPLACED);
        TaskResult result = activeChild.result(TaskState.CANCELLED);
        int after = count(activeNeed.itemIds);
        int progress = Math.max(0, after - activeBeforeCount);
        markActiveEffectsIfObserved(TaskState.CANCELLED, result, progress);
        recordAttempt(TaskState.CANCELLED, result, after, progress, true);
        clearActive();
    }

    private void clearActive() {
        activeChild = null;
        activeRecord = null;
        activeNeed = null;
        activeSource = null;
        activeDetail = null;
        activeBeforeCount = 0;
        activeLastObservedCount = 0;
        activeBeforeInventory = Map.of();
        activeHuntStage = HuntChildStage.NONE;
        activeHuntTarget = null;
    }

    /** 按实际库存变化和操作回执判断路线是否已经花了东西或改变世界；单纯发起搜索不等于发生效果。 */
    private void markActiveEffectsIfObserved(
            TaskState terminal, TaskResult result, int targetProgress) {
        if (activeNeed == null || !activeChildEffectsObserved(
                terminal, result, targetProgress)) return;
        activeNeed.effectsObserved = true;
        if (activeRecord instanceof CraftTaskRecord craft
                && activeNeed.committedRecipeIds.contains(craft.recipeId.toString())) {
            activeNeed.committedRecipeEffectsObserved = true;
        }
    }

    private boolean activeChildEffectsObserved(
            TaskState terminal, TaskResult result, int targetProgress) {
        // 目标增加、背包总清单变化或结果说明已发生动作，都会被当成有效果；当前没有区分无关的外部库存变化。
        if (targetProgress > 0 || !activeBeforeInventory.equals(inventorySnapshot())) return true;
        Map<String, Object> data = result == null || result.data() == null
                ? Map.of() : result.data();
        for (String key : List.of(
                "effects_started", "outcome_uncertain", "crafting_table_placed",
                "station_placed")) {
            if (bool(data.get(key))) return true;
        }
        for (String key : List.of(
                "crafted", "gathered", "collected", "mined", "broken", "placed",
                "strikes", "defeated_targets", "inventory_progress", "withdrawn",
                "deposited", "transferred")) {
            if (positiveNumber(data.get(key))) return true;
        }
        // 攻击结束时可能已经伤到生物却没有掉落；旧回执缺少攻击计数时也不能当作完全没有效果。
        return activeSource == SemanticAcquireTaskRecord.Source.HUNT
                && activeHuntStage == HuntChildStage.ATTACK
                && terminal != TaskState.PENDING;
    }

    private Map<ResourceLocation, Integer> inventorySnapshot() {
        Map<ResourceLocation, Integer> snapshot = new LinkedHashMap<>();
        int slots = Math.min(
                PlayerInv.BUILDABLE_SLOTS, player.getInventory().getContainerSize());
        for (int slot = 0; slot < slots; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.isEmpty()) continue;
            snapshot.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()),
                    stack.getCount(), Integer::sum);
        }
        return Map.copyOf(snapshot);
    }

    private static boolean huntDefeated(TaskResult result) {
        return result != null && result.data() != null
                && positiveNumber(result.data().get("defeated_targets"));
    }

    private static FailureType childFailureType(TaskState terminal, TaskResult result) {
        if (result != null && result.data() != null) {
            Object raw = result.data().get("failure_type");
            if (raw != null) {
                try {
                    return FailureType.valueOf(
                            String.valueOf(raw).toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ignored) {
                    // 子任务没有可识别的失败类型时，再按超时、取消等实际终态解释。
                }
            }
        }
        return switch (terminal) {
            case TIMEOUT -> FailureType.TIMED_OUT;
            case CANCELLED -> FailureType.INTERRUPTED;
            default -> FailureType.UNKNOWN;
        };
    }

    private boolean structuredFailure(TaskState terminal, TaskResult result) {
        if (terminal == TaskState.SUCCESS && result != null && result.success()) return false;
        if (result != null && result.data() != null) {
            Map<String, Object> data = result.data();
            if (data.containsKey("failure_type") || data.containsKey("failure_code")
                    || bool(data.get("requires_decision"))
                    || bool(data.get("outcome_uncertain"))) {
                return true;
            }
        }
        return terminal == TaskState.FAILED;
    }

    private String huntSearchState(
            AcquisitionNeed need,
            SemanticAcquireTaskRecord.SourceHint hint,
            GenericEntitySearchTaskRecord.Relation relation) {
        BlockPos position = player.blockPosition();
        return player.level().dimension().location()
                + "|" + (position.getX() >> 4) + ',' + (position.getZ() >> 4)
                + "|" + relation.name()
                + "|" + String.join(",", stringIds(hint.entityTypeIds()))
                + "|expanded=" + need.huntSearchExpandedView
                // 同一区块里击杀过目标、增加库存或排除某个实体，也算搜索条件变化；数量没够时应能继续寻找。
                + "|inventory=" + count(need.itemIds)
                + "|rejected=" + rejectedHuntTargets.size();
    }

    private static boolean positiveNumber(Object value) {
        return value instanceof Number number && number.intValue() > 0;
    }

    private TaskState exhaustNeed(AcquisitionNeed need) {
        // 最终需求所有来源都失败就报告做不到；小需求失败则回到原配方，视是否已经动过东西决定能否换方案。
        if (need.depth == 0) {
            DimensionBarrier barrier = preferredDimensionBarrier();
            if (barrier != null) {
                failureDimension = barrier;
                return failAcquisition(
                        "requires_dimension",
                        "a known physical source is valid only in another dimension; no movement "
                                + "or attack was started for that source",
                        FailureType.NO_MATERIAL);
            }
            return failAcquisition(
                    "allowed_sources_exhausted",
                    "none of the allowed source families could make the final inventory fact true",
                    FailureType.NO_MATERIAL);
        }
        // 先有界记住普通路线尚不能提供的子材料，再照常试其他未产生副作用的配方；不能让第一条失败叶子劫持全局规划。
        rememberProcessPlanningNeed(need);
        if (!needs.isEmpty() && needs.peek() == need) needs.pop();
        AcquisitionNeed parent = needs.peek();
        if (parent == null) {
            return failAcquisition(
                    "recursive_need_parent_missing",
                    "a recursive recipe need ended without its parent",
                    FailureType.INTERNAL);
        }
        if (need.stockOnlyTool && !need.decisionRequired) {
            parent.preferredToolTierCap = 1;
            parent.miningToolPrerequisitePushed = false;
            parent.effectsObserved |= need.effectsObserved;
            addIssue("mine", "tool_upgrade_stock_unavailable",
                    "the upgrade could not be supplied from existing stock; prepare the ordinary work tool instead",
                    Map.of("attempted_tool_ids", itemStrings(need.itemIds)));
            return TaskState.RUNNING;
        }
        if (need.decisionRequired) {
            addIssue("planner", "prerequisite_decision_required",
                    "a recursive prerequisite exhausted every non-decision source and reached a "
                            + "player-facing permission or protection boundary",
                    Map.of("ingredient_item_ids", itemStrings(need.itemIds),
                            "requires_narration", true,
                            "partial_effects", need.effectsObserved));
            failureNeed = need;
            return failAcquisition(
                    "prerequisite_decision_required",
                    "a prerequisite now needs a narrated player decision; MaiCraft stopped instead "
                            + "of silently changing the recipe",
                    FailureType.NO_MATERIAL);
        }
        boolean committedFrontier = need.parentRecipeIds.stream()
                .anyMatch(parent.committedRecipeIds::contains);
        if (committedFrontier
                && (need.effectsObserved || parent.committedRecipeEffectsObserved)) {
            addIssue("craft", "committed_prerequisite_unmet",
                    "the committed recipe's prerequisite exhausted allowed sources after child "
                            + "work began; automatic recipe switching was stopped",
                    Map.of("recipe_ids", List.copyOf(need.parentRecipeIds),
                            "ingredient_item_ids", itemStrings(need.itemIds),
                            "partial_effects", true,
                            "requires_narration", true));
            failureNeed = need;
            return failAcquisition(
                    "committed_prerequisite_unmet",
                    "a committed recipe prerequisite could not be completed after work began; "
                            + "review the partial effects and choose a recovery before trying another route",
                    FailureType.NO_MATERIAL);
        }
        if (committedFrontier) {
            parent.committedRecipeIds.clear();
            parent.committedRecipeEffectsObserved = false;
        }
        parent.effectsObserved |= need.effectsObserved;
        parent.rejectedRecipes.addAll(need.parentRecipeIds);
        List<String> parentRecipeIds = need.parentRecipeIds.isEmpty()
                ? List.of("unknown") : List.copyOf(need.parentRecipeIds);
        addIssue("craft", "recursive_ingredient_unmet",
                "one recipe frontier was abandoned after its ingredient alternatives exhausted allowed sources",
                Map.of("recipe_id", parentRecipeIds.getFirst(),
                        "recipe_ids", parentRecipeIds,
                        "ingredient_item_ids", itemStrings(need.itemIds),
                        "required_final_count", need.requiredFinalCount,
                        "observed_final_count", count(need.itemIds)));
        return TaskState.RUNNING;
    }

    private void propagateSatisfiedNeed(AcquisitionNeed satisfied) {
        // 原料或工具小需求完成后，把“已经发生过实际操作”的信息传给上一层，避免上层以为还什么都没做。
        if (needs.isEmpty()) return;
        AcquisitionNeed parent = needs.peek();
        parent.effectsObserved |= satisfied.effectsObserved;
        if (satisfied.toolPrerequisite) parent.miningToolPrerequisitePushed = false;
        if (satisfied.effectsObserved && satisfied.parentRecipeIds.stream()
                .anyMatch(parent.committedRecipeIds::contains)) {
            parent.committedRecipeEffectsObserved = true;
        }
    }

    /** 为原配方先补一个工作台，沿用父需求的来源许可，并记录配方来路以阻止循环。 */
    private boolean pushCraftingSurfacePrerequisite(
            AcquisitionNeed parent, CraftRecoveryCandidate blockedRecipe) {
        return pushCraftingSurfacePrerequisite(
                parent, blockedRecipe.recipeId(), blockedRecipe.outputItem().toString(),
                blockedRecipe.surfacePrerequisiteItems());
    }

    /** 实际走到工作台才发现无法使用时，尝试补可携带工作台，补齐后仍回到同一配方。 */
    private boolean recoverCraftingSurface(
            AcquisitionNeed parent, CraftTaskRecord craft, TaskResult result) {
        // 真正走到工作台发现不能用时，可以先补一个工作台；如果背包已有工作台，问题就不是再取一个能解决的。
        if (result == null || result.data() == null) return false;
        String code = string(result.data().get("failure_code"));
        if (code == null || !code.startsWith("crafting_surface_")) return false;

        List<ResourceLocation> itemIds = new ArrayList<>();
        Object raw = result.data().get("crafting_surface_prerequisite_item_ids");
        if (raw instanceof List<?> values) {
            for (Object value : values) {
                ResourceLocation id = ResourceLocation.tryParse(String.valueOf(value));
                if (id != null && BuiltInRegistries.ITEM.containsKey(id)) itemIds.add(id);
            }
        }
        if (itemIds.isEmpty()) {
            itemIds.addAll(CraftingWorkstationCoordinator.prerequisiteItemIds());
        }
        // 已经带着工作台却找不到摆放位置时，再做一个工作台也解决不了问题。
        if (count(itemIds) > 0) return false;
        return pushCraftingSurfacePrerequisite(
                parent, craft.recipeId.toString(), parent.itemIds.getFirst().toString(), itemIds);
    }

    private boolean pushCraftingSurfacePrerequisite(
            AcquisitionNeed parent, String blockedRecipeId, String outputItemId,
            List<ResourceLocation> candidates) {
        // 工作台只为同一个配方补一次，并继承原来源许可；不要为重复失败的摆台位置不停制造更多工作台。
        List<ResourceLocation> itemIds = candidates.stream()
                .filter(id -> !parent.lineageItems.contains(id))
                .toList();
        if (itemIds.isEmpty() || blockedRecipeId == null || blockedRecipeId.isBlank()) return false;

        Set<ResourceLocation> lineageItems = new LinkedHashSet<>(parent.lineageItems);
        lineageItems.addAll(itemIds);
        Set<String> lineageRecipes = new LinkedHashSet<>(parent.lineageRecipes);
        lineageRecipes.add(blockedRecipeId);
        // 补工作台也是原取物任务的一部分，沿用父需求已经允许的来源。
        List<SemanticAcquireTaskRecord.Source> prerequisiteSources =
                List.copyOf(parent.allowedSources);
        if (prerequisiteSources.isEmpty()) return false;
        if (!parent.committedRecipeIds.isEmpty()
                && !parent.committedRecipeIds.contains(blockedRecipeId)) return false;
        if (!parent.surfaceRecoveryRecipes.add(blockedRecipeId)) return false;
        commitRecipe(parent, Set.of(blockedRecipeId));
        AcquisitionNeed prerequisite = new AcquisitionNeed(
                itemIds, count(itemIds) + 1, parent.depth + 1,
                lineageItems, lineageRecipes, Set.of(blockedRecipeId),
                prerequisiteSources);
        prerequisite.lastObservedCount = count(prerequisite.itemIds);
        recipeTrace.add(Map.of(
                "recipe_id", blockedRecipeId,
                "output_item_id", outputItemId,
                "depth", parent.depth,
                "internal_prerequisite", "crafting_surface",
                "prerequisite_item_ids", itemStrings(itemIds),
                "allowed_sources", prerequisiteSources.stream()
                        .map(source -> source.name().toLowerCase(Locale.ROOT))
                        .toList()));
        needs.push(prerequisite);
        renewProgressLease();
        return true;
    }

    private static boolean recipeAllowedByCommit(AcquisitionNeed need, String recipeId) {
        return need.committedRecipeIds.isEmpty()
                || need.committedRecipeIds.contains(recipeId);
    }

    private static void commitRecipe(AcquisitionNeed need, Set<String> recipeIds) {
        if (need.committedRecipeIds.isEmpty()) {
            need.committedRecipeIds.addAll(recipeIds);
            need.committedRecipeEffectsObserved = false;
        }
    }

    /** 检查当前承诺路线能否直接开做时，先排除其他配方，避免拿另一条路线的条件冒充它已经齐备。 */
    private Set<String> nonCommittedCraftRecipes(AcquisitionNeed need) {
        if (need.committedRecipeIds.isEmpty()) return Set.of();
        Set<ResourceLocation> outputs = Set.copyOf(need.itemIds);
        Set<String> excluded = new LinkedHashSet<>();
        for (var holder : ClientRuntime.requireContext(player)
                .connection().getRecipeManager().getRecipes()) {
            try {
                if (!(holder.value() instanceof CraftingRecipe recipe)) continue;
                ItemStack output = RecipeProbe.resultOf(
                        recipe, player.level().registryAccess());
                if (output.isEmpty()
                        || !outputs.contains(BuiltInRegistries.ITEM.getKey(output.getItem()))) {
                    continue;
                }
                String recipeId = holder.id().toString();
                if (!need.committedRecipeIds.contains(recipeId)) excluded.add(recipeId);
            } catch (RuntimeException unusableRecipe) {
                Constants.LOG.debug(
                        "[maicraft-acquire] skipped unusable committed-recipe probe {}: {}",
                        holder.id(), unusableRecipe.toString());
            }
        }
        return Set.copyOf(excluded);
    }

    private NearbySurvey surveyNearby(List<ResourceLocation> ids) {
        // 当前只接受 getOwner 明确返回自己的掉落物；原版客户端通常拿不到这项信息，null 也会计入不安全组。
        Set<ResourceLocation> accepted = Set.copyOf(ids);
        int safe = 0;
        int protectedCount = 0;
        List<Map<String, Object>> samples = new ArrayList<>();
        AABB box = player.getBoundingBox().inflate(r.searchRadius);
        for (ItemEntity item : player.clientLevel.getEntitiesOfClass(
                ItemEntity.class, box, entity -> !entity.isRemoved()
                        && !entity.hasPickUpDelay()
                        && accepted.contains(BuiltInRegistries.ITEM.getKey(
                                entity.getItem().getItem())))) {
            Entity owner = item.getOwner();
            List<String> reasons = new ArrayList<>();
            // 客户端的物品堆同步不保证带有投掷者信息；owner 为空只表示没证明归属，不能当作无主物品。
            if (owner == null) reasons.add("owner_not_proven_by_client_facts");
            else if (owner != player) reasons.add("owned_by_other_entity");
            reasons.addAll(landmarkReasons(item.blockPosition()));
            if (reasons.isEmpty()) {
                safe++;
            } else {
                protectedCount++;
                if (samples.size() < 8) {
                    samples.add(Map.of(
                            "item_id", BuiltInRegistries.ITEM.getKey(
                                    item.getItem().getItem()).toString(),
                            "reasons", List.copyOf(reasons)));
                }
            }
        }
        return new NearbySurvey(safe, protectedCount, List.copyOf(samples));
    }

    private List<String> landmarkReasons(BlockPos position) {
        return NavigationSafetyContext.protectsMutation(position)
                || NavigationSafetyContext.forbidsBody(position)
                ? List.of("protected_area_cell") : List.of();
    }

    private int count(List<ResourceLocation> ids) {
        int result = 0;
        for (ResourceLocation id : ids) {
            result += PlayerInv.buildableCount(
                    player.getInventory(), BuiltInRegistries.ITEM.get(id));
        }
        return result;
    }

    private Map<ResourceLocation, Integer> counts(List<ResourceLocation> ids) {
        Map<ResourceLocation, Integer> result = new LinkedHashMap<>();
        for (ResourceLocation id : ids) {
            result.put(id, PlayerInv.buildableCount(
                    player.getInventory(), BuiltInRegistries.ITEM.get(id)));
        }
        return Map.copyOf(result);
    }

    private int missing(AcquisitionNeed need) {
        return Math.max(0, need.requiredFinalCount - count(need.itemIds));
    }

    private SemanticAcquireTaskRecord.SourceHint sourceHint(AcquisitionNeed need) {
        SemanticAcquireTaskRecord.SourceHint inferred =
                SemanticSourceKnowledge.infer(need.itemIds);
        return need.depth == 0
                ? SemanticSourceKnowledge.merge(inferred, r.sourceHint)
                : inferred;
    }

    private List<SemanticAcquireTaskRecord.Source> executionSourceOrder(AcquisitionNeed need) {
        // 许可只决定能用哪些来源；每次库存进展或来源耗尽后，再根据现场条件重排剩余来源。
        boolean wireless = wirelessInventoryAllowed();
        if (need.wirelessInventory != wireless) need.plannedSourceOrder = null;
        need.wirelessInventory = wireless;
        if (need.plannedSourceOrder != null) return need.plannedSourceOrder;
        SemanticAcquireTaskRecord.SourceHint hint = sourceHint(need);
        boolean naturalMine = !hint.blockRefs().isEmpty();
        boolean directHunt = r.allowHarm && !hint.entityTypeIds().isEmpty();
        boolean craftReady = need.canTry(SemanticAcquireTaskRecord.Source.CRAFT) && craftExecutableNow(need);
        boolean cookReady = need.canTry(SemanticAcquireTaskRecord.Source.COOK) && cookInputReadyNow(need);
        need.plannedSourceOrder = AcquisitionSources.order(need,
                new AcquisitionSources.Readiness(craftReady, cookReady, naturalMine, directHunt));
        return need.plannedSourceOrder;
    }

    private boolean wirelessInventoryAllowed() {
        // 明确只准用背包的请求保持原边界；通常补料则把持有的无线终端作为随身现货入口。
        return r.allowedSources.stream().anyMatch(source -> source != SemanticAcquireTaskRecord.Source.INVENTORY)
                && wirelessAvailable.test(player);
    }

    private boolean craftExecutableNow(AcquisitionNeed need) {
        // 只做只读规划来判断现在是否能直接合成，用于来源排序，不在这里打开菜单或扣材料。
        Set<String> excluded = new LinkedHashSet<>(need.lineageRecipes);
        excluded.addAll(need.rejectedRecipes);
        excluded.addAll(nonCommittedCraftRecipes(need));
        CraftingWorkstationCoordinator.PlanningSnapshot workstation =
                CraftOps.requiresWorkstationForAny(need.itemIds, player)
                        ? CraftingWorkstationCoordinator.inspect(player) : null;
        int deficit = missing(need);
        for (ResourceLocation output : need.itemIds) {
            int requested = PlayerInv.buildableCount(
                    player.getInventory(), BuiltInRegistries.ITEM.get(output)) + deficit;
            ToolContext context = new ToolContext(
                    (r.getToolCallId() == null ? "acquire" : r.getToolCallId())
                            + "-source-rank",
                    player.level().getGameTime());
            if (craftOps.plan(output.toString(), requested, player, context,
                    workstation, excluded).executable()) return true;
        }
        return false;
    }

    private boolean cookInputReadyNow(AcquisitionNeed need) {
        Set<Item> outputs = need.itemIds.stream()
                .map(BuiltInRegistries.ITEM::get)
                .collect(Collectors.toSet());
        int deficit = missing(need);
        for (var holder : ClientRuntime.requireContext(player)
                .connection().getRecipeManager().getRecipes()) {
            if (!(holder.value() instanceof AbstractCookingRecipe cooking)) continue;
            if (!RecipeProbe.usableIngredients(cooking)) continue;
            ItemStack result = RecipeProbe.resultOf(
                    cooking, player.level().registryAccess());
            if (result.isEmpty() || !outputs.contains(result.getItem())) continue;
            int batches = Math.max(1,
                    (deficit + Math.max(1, result.getCount()) - 1)
                            / Math.max(1, result.getCount()));
            if (cooking.getIngredients().isEmpty()) continue;
            Ingredient input = cooking.getIngredients().getFirst();
            int available = 0;
            int slots = Math.min(
                    PlayerInv.BUILDABLE_SLOTS, player.getInventory().getContainerSize());
            for (int slot = 0; slot < slots; slot++) {
                ItemStack stack = player.getInventory().getItem(slot);
                if (!stack.isEmpty() && input.test(stack)) available += stack.getCount();
            }
            if (available >= batches) return true;
        }
        return false;
    }

    /** 维度门槛只排除当前采矿或狩猎来源；仍保留背包、仓库、加工与交易各自的机会。 */
    private boolean sourceDimensionAllowed(
            AcquisitionNeed need, SemanticAcquireTaskRecord.Source source) {
        // 当前把内置的来源维度当成这一类采集行动的前置条件，检查发生在观察具体矿块／生物之前。
        List<ResourceLocation> allowed = SemanticSourceKnowledge.inferPlan(need.itemIds)
                .allowedDimensions(source);
        if (allowed.isEmpty()) return true;
        ResourceLocation current = player.level().dimension().location();
        if (allowed.contains(current)) return true;

        rememberDimensionBarrier(need, allowed, current);
        addIssue(source.name().toLowerCase(Locale.ROOT), "requires_dimension",
                "this physical source family is not valid in the current dimension; no movement "
                        + "or attack was started",
                Map.of("target_item_family", itemStrings(need.itemIds),
                        "allowed_dimensions", stringIds(allowed),
                        "current_dimension", current.toString()));
        advanceSource(need);
        return false;
    }

    private void rememberDimensionBarrier(
            AcquisitionNeed need,
            List<ResourceLocation> allowed,
            ResourceLocation current) {
        for (int index = 0; index < dimensionBarriers.size(); index++) {
            DimensionBarrier known = dimensionBarriers.get(index);
            if (!known.itemIds().equals(need.itemIds)
                    || !known.currentDimension().equals(current)) {
                continue;
            }
            LinkedHashSet<ResourceLocation> merged = new LinkedHashSet<>(
                    known.allowedDimensions());
            merged.addAll(allowed);
            dimensionBarriers.set(index, new DimensionBarrier(
                    known.itemIds(), List.copyOf(merged), current,
                    Math.min(known.depth(), need.depth)));
            return;
        }
        dimensionBarriers.add(new DimensionBarrier(
                need.itemIds, allowed, current, need.depth));
    }

    private DimensionBarrier preferredDimensionBarrier() {
        return dimensionBarriers.stream()
                .min(Comparator
                        .comparingInt(DimensionBarrier::depth)
                        .thenComparing(barrier -> String.join(",", itemStrings(
                                barrier.itemIds()))))
                .orElse(null);
    }

    private boolean takePlannerStep() {
        if (plannerStepsThisTick >= PLANNER_STEPS_PER_TICK) return false;
        plannerStepsThisTick++;
        return true;
    }

    private void renewProgressLease() {
        r.extendDeadlineTo(player.level().getGameTime() + PROGRESS_LEASE_TICKS);
    }

    private void advanceSource(AcquisitionNeed need) {
        // 按来源身份记住已用尽的一项，再重新排序剩余来源；不能用旧数组下标猜下一项，因为顺序可能变化。
        List<SemanticAcquireTaskRecord.Source> remaining = executionSourceOrder(need);
        if (!remaining.isEmpty()) need.exhaustedSources.add(remaining.getFirst());
        need.plannedSourceOrder = null;
    }

    private String childId(String kind) {
        String parent = r.getToolCallId() == null ? "acquire" : r.getToolCallId();
        return parent + "-internal-" + kind + '-' + (++childSerial);
    }

    private TaskState failAcquisition(String code, String message, FailureType type) {
        if (("allowed_sources_exhausted".equals(code) || "committed_prerequisite_unmet".equals(code)) && !outcomeUncertain) {
            // 别的普通路线可能顺手补齐了旧叶子；交接时只保留仍缺少实物的候选，不请外部再做已经够用的材料。
            processPlanningNeeds.removeIf(need -> missing(need) <= 0);
            AcquisitionNeed blocked = failureNeed != null ? failureNeed : processPlanningNeeds.isEmpty() ? needs.peek() : processPlanningNeeds.getFirst();
            if (blocked != null && !blocked.decisionRequired && !blocked.stockOnlyTool
                    && MaterialProcessPlanning.allowsPlanning(r.allowedSources)
                    && MaterialProcessPlanning.allowsPlanning(blocked.allowedSources)) {
                // 到这里有限普通来源已经耗尽；仅冻结知识状态和URI交给外部恢复，不默认造机，也不改最终库存目标。
                failureNeed = blocked;
                processPlanning = MaterialProcessPlanning.capture(player, r, count(r.itemIds), processPlanningFacts(blocked),
                        processPlanningNeeds.stream().map(this::processPlanningFacts).toList(), effectsObserved(), code);
                if ("allowed_sources_exhausted".equals(code)) code = MaterialProcessPlanning.FAILURE_CODE;
                message += "; inspect the linked recipe knowledge and choose authorized semantic prerequisites, then reassess the unchanged inventory goal";
            }
        }
        failureCode = code;
        if (failureNeed == null) failureNeed = needs.peek();
        fail(message, type);
        return TaskState.FAILED;
    }

    private void rememberProcessPlanningNeed(AcquisitionNeed need) {
        if (need.decisionRequired || need.stockOnlyTool || !MaterialProcessPlanning.allowsPlanning(need.allowedSources)
                || processPlanningNeeds.size() >= MaterialProcessPlanning.MAX_BLOCKED_NEEDS) return;
        if (processPlanningNeeds.stream().noneMatch(old -> old.itemIds.equals(need.itemIds) && old.parentRecipeIds.equals(need.parentRecipeIds)))
            processPlanningNeeds.add(need);
    }

    private MaterialProcessPlanning.NeedEvidence processPlanningFacts(AcquisitionNeed need) {
        return new MaterialProcessPlanning.NeedEvidence(need.itemIds, need.requiredFinalCount, count(need.itemIds), need.depth,
                List.copyOf(need.lineageItems), List.copyOf(need.lineageRecipes), List.copyOf(need.parentRecipeIds),
                List.copyOf(need.committedRecipeIds), need.effectsObserved);
    }

    private boolean effectsObserved() {
        return (rootNeed != null && rootNeed.effectsObserved) || (failureNeed != null && failureNeed.effectsObserved)
                || attempts.stream().anyMatch(attempt -> bool(attempt.get("effects_observed")));
    }

    private void addIssue(
            String source, String code, String summary, Map<String, ?> facts) {
        // 只保留最先出现的六十四条问题，超出的不再追加；这限制报告大小，不限制实际尝试次数。
        if (issues.size() >= MAX_REPORTED_ISSUES) return;
        Map<String, Object> issue = new LinkedHashMap<>();
        issue.put("source", source);
        issue.put("code", code);
        issue.put("summary", summary == null ? "" : summary);
        if (facts != null && !facts.isEmpty()) issue.put("facts", new LinkedHashMap<>(facts));
        issues.add(Map.copyOf(issue));
    }

    private void recordAttempt(
            TaskState state,
            TaskResult result,
            int after,
            int progress,
            boolean stoppedBecauseSatisfied) {
        // 记开始和结束库存、子任务结果及是否因数量已够提前停止；记录数量上限与实际执行次数分开。
        if (attempts.size() >= MAX_REPORTED_ATTEMPTS) return;
        Map<String, Object> attempt = new LinkedHashMap<>();
        attempt.put("source", activeSource.name().toLowerCase());
        attempt.put("detail", activeDetail);
        attempt.put("child_tool", activeRecord.getToolName());
        attempt.put("terminal_state", state.name().toLowerCase());
        attempt.put("inventory_before", activeBeforeCount);
        attempt.put("inventory_after", after);
        attempt.put("inventory_progress", progress);
        attempt.put("effects_observed",
                activeChildEffectsObserved(state, result, progress));
        attempt.put("stopped_because_final_fact_satisfied", stoppedBecauseSatisfied);
        if (result != null) {
            attempt.put("child_success", result.success());
            attempt.put("child_message", result.message() == null ? "" : result.message());
            if (result.data() != null && !result.data().isEmpty()) {
                attempt.put("child_data", childDataForAttempt(result.data()));
            }
        }
        attempts.add(Map.copyOf(attempt));
    }

    private Map<String, Object> childDataForAttempt(Map<String, Object> data) {
        if (activeSource != SemanticAcquireTaskRecord.Source.HUNT
                || !AttackTaskRecord.TOOL_NAME.equals(activeRecord.getToolName())) {
            return data;
        }
        Map<String, Object> safe = new LinkedHashMap<>();
        safe.put("mode", "semantic_loaded_target");
        copyIfPresent(data, safe, "requested_targets");
        copyIfPresent(data, safe, "defeated_targets");
        copyIfPresent(data, safe, "lost_targets");
        copyIfPresent(data, safe, "unreachable_targets");
        copyIfPresent(data, safe, "strikes");
        copyIfPresent(data, safe, "loot_gained");
        copyIfPresent(data, safe, "unreachable_drop_count");
        return Map.copyOf(safe);
    }

    private static void copyIfPresent(
            Map<String, Object> source, Map<String, Object> target, String key) {
        if (source.containsKey(key) && source.get(key) != null) {
            target.put(key, source.get(key));
        }
    }

    private Map<String, Object> needFacts(AcquisitionNeed need) {
        return Map.of(
                "item_ids", itemStrings(need.itemIds),
                "required_final_count", need.requiredFinalCount,
                "observed_final_count", count(need.itemIds),
                "missing", missing(need),
                "depth", need.depth);
    }

    private List<Map<String, Object>> recoveryOptions() {
        // 根据未开放的来源和失败原因给出可选下一步，不在这里自动扩大采矿、交易或伤害许可。
        List<Map<String, Object>> options = new ArrayList<>();
        if (!processPlanning.isEmpty()) options.add(Map.of("id", MaterialProcessPlanning.KIND,
                "summary", "Read the bounded recipe knowledge links, inspect reusable equipment, and recover with authorized semantic prerequisites before re-evaluating this inventory goal.",
                "knowledge_uris", processPlanning.get("knowledge_uris"), "risk", "knowledge_only_until_separately_authorized_actions"));
        Set<SemanticAcquireTaskRecord.Source> allowed = Set.copyOf(r.allowedSources);
        for (SemanticAcquireTaskRecord.Source source : List.of(
                SemanticAcquireTaskRecord.Source.STORAGE,
                SemanticAcquireTaskRecord.Source.COOK,
                SemanticAcquireTaskRecord.Source.MINE,
                SemanticAcquireTaskRecord.Source.TRADE,
                SemanticAcquireTaskRecord.Source.HUNT)) {
            if (!allowed.contains(source)) {
                Map<String, Object> patch = Map.of(
                        "allowed_sources", appendSource(r.allowedSources, source));
                options.add(Map.of(
                        "id", "allow_" + source.name().toLowerCase(),
                        "summary", "Retry the same inventory outcome while permitting "
                                + source.name().toLowerCase() + " as a semantic source family.",
                        "risk", source == SemanticAcquireTaskRecord.Source.HUNT
                                ? "high" : source == SemanticAcquireTaskRecord.Source.MINE
                                ? "medium" : "low",
                        "parameters_patch", patch));
            }
        }
        if (SemanticSourceKnowledge.merge(
                SemanticSourceKnowledge.infer(r.itemIds), r.sourceHint).isEmpty()) {
            options.add(Map.of(
                    "id", "provide_source_hint",
                    "summary", "Provide only a semantic source family (block/tag, entity type or "
                            + "trade profession), never coordinates or runtime entity IDs.",
                    "risk", "none"));
        }
        if (allowed.contains(SemanticAcquireTaskRecord.Source.HUNT) && !r.allowHarm) {
            options.add(Map.of(
                    "id", "narrate_and_allow_harm",
                    "summary", "After telling the audience what would be harmed and why, retry "
                            + "with allow_harm=true. Protection ambiguity still stops execution.",
                    "risk", "high",
                    "parameters_patch", Map.of("allow_harm", true)));
        }
        if (allowed.contains(SemanticAcquireTaskRecord.Source.HUNT)
                && (hasIssue("hunt_entity_search_exhausted")
                        || hasIssue("hunt_entity_search_incomplete"))) {
            options.add(Map.of(
                    "id", "continue_from_another_semantic_area",
                    "summary", "Continue from another unprotected semantic area, then retry the "
                            + "same inventory outcome; MaiCraft will run another progress-driven first-person search.",
                    "risk", "none_until_a_new_target_is_verified_and_harm_is_rechecked"));
        }
        if (allowed.contains(SemanticAcquireTaskRecord.Source.HUNT)
                && hasIssue("protected_hunt_target_requires_decision")) {
            options.add(Map.of(
                    "id", "choose_unprotected_hunt_context",
                    "summary", "Choose or travel to a clearly unprotected matching population; "
                            + "the current named, tamed, player, managed or protected candidates "
                            + "will not be attacked.",
                    "risk", "high_after_a_new_target_is_verified"));
        }
        options.add(Map.of(
                "id", "semantic_prerequisite",
                "summary", "Run a semantic prerequisite such as preparing a crafting surface or "
                        + "a suitable harvesting tool, then retry the unchanged item fact.",
                "risk", "depends_on_prerequisite"));
        Map<String, Object> stop = Map.of(
                "id", "stop",
                "summary", "Leave the inventory fact incomplete and perform no further effects.",
                "risk", "none");
        List<Map<String, Object>> reported = new ArrayList<>(
                options.stream().limit(7).toList());
        reported.add(stop);
        return List.copyOf(reported);
    }

    private boolean hasIssue(String code) {
        return issues.stream().anyMatch(issue -> code.equals(issue.get("code")));
    }

    private static List<String> appendSource(
            List<SemanticAcquireTaskRecord.Source> sources,
            SemanticAcquireTaskRecord.Source extra) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (var source : sources) result.add(source.name().toLowerCase());
        result.add(extra.name().toLowerCase());
        return List.copyOf(result);
    }

    @Override
    protected Map<String, Object> resultData() {
        // 结果明确区分最终数量是否满足、尝试过什么、是否有副作用或不确定性，不只返回一句“拿到了／没拿到”。
        if ("requires_dimension".equals(failureCode) && failureDimension != null) {
            return dimensionFailureData();
        }
        Map<ResourceLocation, Integer> current = counts(r.itemIds);
        Map<String, Object> before = new LinkedHashMap<>();
        Map<String, Object> after = new LinkedHashMap<>();
        for (ResourceLocation id : r.itemIds) {
            before.put(id.toString(), initialCounts.getOrDefault(id, 0));
            after.put(id.toString(), current.getOrDefault(id, 0));
        }
        int observed = count(r.itemIds);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("goal", "final_main_inventory_count");
        data.put("item_ids", itemStrings(r.itemIds));
        data.put("required_final_count", r.count);
        data.put("observed_final_count", observed);
        data.put("missing", Math.max(0, r.count - observed));
        data.put("goal_satisfied", observed >= r.count);
        data.put("inventory_before_by_item", before);
        data.put("inventory_after_by_item", after);
        data.put("allowed_sources", r.allowedSources.stream()
                .map(source -> source.name().toLowerCase()).toList());
        data.put("allow_harm", r.allowHarm);
        data.put("harmless_hunt_retargets", harmlessHuntRetargets);
        data.put("attempts", List.copyOf(attempts));
        data.put("recipe_trace", List.copyOf(recipeTrace));
        data.put("issues", List.copyOf(issues));
        data.put("outcome_uncertain", outcomeUncertain);
        if (!processPlanning.isEmpty()) data.put("planning_handoff", processPlanning);
        if (hasIssue("mining_loot_uncollected")) {
            data.put("collection_complete", false);
            data.put("requires_narration", true);
        }
        boolean effectsObserved = effectsObserved();
        data.put("effects_observed", effectsObserved);
        data.put("partial_effects_observed", observed < r.count && effectsObserved);
        if (failureCode != null) {
            data.put("failure_code", failureCode);
            data.put("requires_decision", true);
            data.put("requires_narration",
                    !processPlanning.isEmpty() || failureRequiresNarration(failureCode)
                            || issues.stream().anyMatch(
                                    SemanticAcquireCompanionTask::issueRequiresNarration));
            AcquisitionNeed blocked = failureNeed == null ? needs.peek() : failureNeed;
            if (blocked != null) {
                data.put("blocked_need", needFacts(blocked));
                Set<String> routeIds = !blocked.parentRecipeIds.isEmpty()
                        ? blocked.parentRecipeIds : blocked.committedRecipeIds;
                if (!routeIds.isEmpty()) {
                    data.put("route_recipe_ids", List.copyOf(routeIds));
                }
            }
            data.put("recovery_options", recoveryOptions());
        }
        return data;
    }

    private static boolean failureRequiresNarration(String code) {
        return code != null && (code.contains("decision_required")
                || "hunt_loot_settlement_incomplete".equals(code)
                || code.startsWith("committed_recipe_"));
    }

    private static boolean issueRequiresNarration(Map<String, Object> issue) {
        if (bool(issue.get("requires_narration"))) return true;
        Object rawFacts = issue.get("facts");
        if (rawFacts instanceof Map<?, ?> facts
                && bool(facts.get("requires_narration"))) return true;
        Object code = issue.get("code");
        return "harm_permission_required".equals(code)
                || "protected_hunt_target_requires_decision".equals(code)
                || "hunt_entity_search_exhausted".equals(code)
                || "hunt_entity_search_incomplete".equals(code)
                || "expected_hunt_drop_not_observed".equals(code)
                || "drop_ownership_ambiguous".equals(code);
    }

    private Map<String, Object> dimensionFailureData() {
        List<String> allowed = stringIds(failureDimension.allowedDimensions());
        Map<String, Object> travel = Map.of(
                "id", "travel_dimension",
                "summary", "Travel through an independently authorized dimension route, then "
                        + "retry the unchanged semantic inventory goal.",
                "allowed_dimensions", allowed,
                "risk", "dimension_travel_requires_separate_authorization");
        Map<String, Object> stop = Map.of(
                "id", "stop",
                "summary", "Leave the inventory goal incomplete and perform no further effects.",
                "risk", "none");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("failure_type", "requires_dimension");
        data.put("target_item_family", itemStrings(failureDimension.itemIds()));
        data.put("allowed_dimensions", allowed);
        data.put("current_dimension", failureDimension.currentDimension().toString());
        data.put("requires_decision", true);
        data.put("requires_narration", true);
        data.put("recovery_options", List.of(travel, stop));
        return Map.copyOf(data);
    }

    @Override
    protected String successMessage() {
        if (hasIssue("mining_loot_uncollected")) return "requested inventory count reached: "
                + count(r.itemIds) + "/" + r.count + "; mining left uncollected drops; see issues for details";
        return "final inventory fact satisfied: carrying " + count(r.itemIds)
                + "/" + r.count + " across acceptable items " + itemStrings(r.itemIds);
    }

    @Override
    protected String timeoutMessage() {
        if (failureCode == null) {
            failureCode = "acquisition_timeout";
            addIssue("planner", "acquisition_timeout",
                    "the no-progress lease elapsed before the final inventory fact was true",
                    Map.of("observed_final_count", count(r.itemIds),
                            "required_final_count", r.count));
        }
        return "semantic acquisition timed out at " + count(r.itemIds) + "/" + r.count
                + "; review attempts before retrying";
    }

    @Override
    protected String cancelledMessage() {
        return "semantic acquisition was interrupted at " + count(r.itemIds) + "/" + r.count;
    }

    @Override
    public Map<String, Object> progress() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("task", name());
        data.put("phase", activeChild == null ? "planning_acquisition" : "acquiring");
        if (!processPlanning.isEmpty()) {
            data.put("phase", MaterialProcessPlanning.KIND); data.put("planning_handoff", processPlanning);
        }
        AcquisitionNeed need = activeNeed == null ? needs.peek() : activeNeed;
        if (need != null) {
            data.put("required_final_count", need.requiredFinalCount);
            if (need.lastObservedCount >= 0) data.put("observed_count", need.lastObservedCount);
            data.put("acceptable_item_count", need.itemIds.size());
            if (need.itemIds.size() == 1) data.put("item_id", need.itemIds.getFirst().toString());
        }
        if (activeChild != null) {
            if (activeSource != null) data.put("source", activeSource.name().toLowerCase(Locale.ROOT));
            data.put("child", activeChild.progress());
        }
        data.put("completed_attempt_count", attempts.size());
        return Map.copyOf(data);
    }

    @Override
    protected void cleanup() {
        // 总取物任务结束前，先停止仍在做的小任务，并留下已经得到的物品和可能发生的副作用证据。
        if (activeChild != null) {
            activeChild.stop(player, Task.StopReason.REPLACED);
            TaskResult childResult = activeChild.result(TaskState.CANCELLED);
            int progress = Math.max(
                    0, count(activeNeed.itemIds) - activeBeforeCount);
            markActiveEffectsIfObserved(TaskState.CANCELLED, childResult, progress);
            if (childResult != null && childResult.data() != null
                    && bool(childResult.data().get("outcome_uncertain"))) {
                outcomeUncertain = true;
            }
            recordAttempt(TaskState.CANCELLED, childResult,
                    count(activeNeed.itemIds), progress, false);
            clearActive();
        }
        super.cleanup();
    }

    private static int integer(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static boolean bool(Object value) {
        return value instanceof Boolean flag && flag;
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static List<String> itemStrings(List<ResourceLocation> ids) {
        return ids.stream().map(ResourceLocation::toString).toList();
    }

    private static List<String> stringIds(Collection<ResourceLocation> ids) {
        return ids.stream().map(ResourceLocation::toString).sorted().toList();
    }

    private static String shortLabel(List<ResourceLocation> ids) {
        String first = ids.getFirst().getPath();
        return ids.size() == 1 ? first : first + "+" + (ids.size() - 1);
    }

    private static String blockLabel(Set<Block> blocks) {
        String first = BuiltInRegistries.BLOCK.getKey(blocks.iterator().next()).getPath();
        return blocks.size() == 1 ? first : first + "+" + (blocks.size() - 1);
    }

    private static List<String> blockStrings(Set<Block> blocks) {
        return blocks.stream().map(BuiltInRegistries.BLOCK::getKey)
                .map(ResourceLocation::toString).sorted().toList();
    }
}
