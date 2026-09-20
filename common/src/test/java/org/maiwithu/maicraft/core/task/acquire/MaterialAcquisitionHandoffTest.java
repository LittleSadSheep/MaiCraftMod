// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.acquire;

import java.lang.reflect.Constructor;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.task.TaskState;

/** 在真实获取栈的有限路线耗尽边界注入已观察事实；只检查交接与恢复，不制造机器配方成功或执行游戏操作。 */
public final class MaterialAcquisitionHandoffTest {
    private static final ResourceLocation FINAL = id("diamond"), LEAF = id("quartz");
    private static final String RECIPE = "test:component_recipe";

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        leafWaitsForOrdinaryAlternatives(); committedEffectsSurvive(); permissionAndStockOnlyRemainUnchanged();
        candidateBoundAndChangedInventory();
        System.out.println("MaterialAcquisitionHandoffTest: exhausted frontiers, partial effects, permissions and safe stock reassessment passed");
    }

    private static void leafWaitsForOrdinaryAlternatives() throws Exception {
        try (var f = new Fixture(List.of(SemanticAcquireTaskRecord.Source.CRAFT))) {
            Object child = f.leaf(LEAF); f.push(child);
            check(f.tick() == TaskState.RUNNING, "首条配方叶子失败应先回到普通替代路线，不立即抢走规划");
            check(!f.task.resultData().containsKey("planning_handoff"), "仍有普通配方可试时不提前查询并交接EMI");
            check(((Set<?>) get(f.root, "rejectedRecipes")).contains(RECIPE), "未产生效果的旧配方仍按原规则被有界排除");
            f.exhaustRoot(); check(f.tick() == TaskState.FAILED, "所有普通来源耗尽后才交回语义恢复");
            var data = f.task.resultData(); var handoff = map(data.get("planning_handoff"));
            check(data.get("failure_code").equals(MaterialProcessPlanning.FAILURE_CODE), "区分需要工艺知识规划的终止原因");
            check(map(data.get("blocked_need")).get("item_ids").equals(List.of(LEAF.toString()))
                            && map(handoff.get("final_inventory_goal")).get("item_ids").equals(List.of(FINAL.toString())),
                    "缺的是子材料，原最终库存目标不能被替换成子材料目标");
            check(map(handoff.get("blocked_need")).get("parent_recipe_ids").equals(List.of(RECIPE)), "失败叶子的配方来路必须保留");
            check(Boolean.FALSE.equals(data.get("outcome_uncertain")) && !Boolean.FALSE.equals(data.get("mechanical_retry_allowed")),
                    "无消费歧义的知识缺口不能一刀切禁止安全重新查库存");
            check(f.task.progress().get("planning_handoff").equals(handoff), "进度读取同一冻结知识交接，不再执行新的查询");
            f.unchanged();
        }
    }

    private static void committedEffectsSurvive() throws Exception {
        try (var f = new Fixture(List.of(SemanticAcquireTaskRecord.Source.CRAFT))) {
            Object child = f.leaf(LEAF); set(child, "effectsObserved", true); set(f.root, "committedRecipeEffectsObserved", true);
            // 模拟此前已经保留下来的子动作消耗回执；本回归不会真的合成或修改材料，只要求恢复时不能丢失它。
            Map<String, Object> earlier = Map.of("source", "craft", "effects_observed", true,
                    "child_data", Map.of("actual_consumed_count", 2));
            list(f.task, "attempts").add(earlier); f.push(child);
            check(f.tick() == TaskState.FAILED, "承诺配方已有实物效果后仍保持原来的停止边界");
            var data = f.task.resultData(); var handoff = map(data.get("planning_handoff"));
            check(data.get("failure_code").equals("committed_prerequisite_unmet") && data.get("partial_effects_observed").equals(true),
                    "知识交接不能改写原有部分消耗与承诺路线失败含义");
            check(((List<?>) data.get("attempts")).contains(earlier) && handoff.get("effects_observed").equals(true),
                    "实际子动作回执及其效果标记继续随交接公开");
            check(handoff.get("ordinary_route_failure_code").equals("committed_prerequisite_unmet"), "外部规划可识别原来的停止原因");
            f.unchanged();
        }
    }

    private static void permissionAndStockOnlyRemainUnchanged() throws Exception {
        try (var f = new Fixture(List.of(SemanticAcquireTaskRecord.Source.INVENTORY))) {
            f.exhaustRoot(); check(f.tick() == TaskState.FAILED, "只查库存请求仍会诚实报告缺料");
            check(f.task.resultData().get("failure_code").equals("allowed_sources_exhausted")
                            && !f.task.resultData().containsKey("planning_handoff"), "库存限定不能被升级为制造工艺规划");
            f.unchanged();
        }
        try (var f = new Fixture(List.of(SemanticAcquireTaskRecord.Source.CRAFT))) {
            set(f.root, "decisionRequired", true);
            check(f.tick() == TaskState.FAILED && f.task.resultData().get("failure_code").equals("acquisition_decision_required")
                            && !f.task.resultData().containsKey("planning_handoff"), "授权或保护边界不能被普通工艺交接掩盖");
        }
        try (var f = new Fixture(List.of(SemanticAcquireTaskRecord.Source.CRAFT))) {
            set(f.task, "outcomeUncertain", true); f.exhaustRoot(); f.tick();
            check(f.task.resultData().get("outcome_uncertain").equals(true) && !f.task.resultData().containsKey("planning_handoff"),
                    "已有不确定动作继续走原来的安全恢复，不能变成单纯知识缺口");
        }
    }

    private static void candidateBoundAndChangedInventory() throws Exception {
        try (var f = new Fixture(List.of(SemanticAcquireTaskRecord.Source.CRAFT))) {
            for (String item : List.of("quartz", "coal", "redstone", "lapis_lazuli", "iron_ingot", "copper_ingot")) {
                Object leaf = f.leaf(id(item)); invoke(f.task, "rememberProcessPlanningNeed", leaf.getClass(), leaf);
            }
            check(((List<?>) get(f.task, "processPlanningNeeds")).size() == 4, "失败叶子缓存不能随配方分支无界增长");
            f.world.inventory.setItem(1, new ItemStack(Items.QUARTZ, 3)); f.exhaustRoot(); f.tick();
            check(map(f.task.resultData().get("blocked_need")).get("item_ids").equals(List.of(id("coal").toString())),
                    "别的路线已经补齐旧叶子时，交接不能再请外部制造它");
            f.world.inventory.setItem(0, new ItemStack(Items.DIAMOND, 2));
            var refreshed = new SemanticAcquireCompanionTask(f.world.player, f.record); refreshed.onStart();
            check(invoke(refreshed, "tickAcquisition", null, null) == TaskState.SUCCESS && !refreshed.resultData().containsKey("planning_handoff"),
                    "外部前置工作已交付实物后，原最终库存目标可安全重估并直接满足");
        }
    }

    private static final class Fixture implements AutoCloseable {
        final InteractionWorldTestHarness world = new InteractionWorldTestHarness();
        final SemanticAcquireTaskRecord record;
        final SemanticAcquireCompanionTask task;
        final Object root;
        Fixture(List<SemanticAcquireTaskRecord.Source> sources) throws Exception {
            world.inventory.setItem(0, new ItemStack(Items.DIAMOND));
            record = new SemanticAcquireTaskRecord("material-handoff", 1000, List.of(FINAL), 2, sources, false,
                    SemanticAcquireTaskRecord.SourceHint.empty(), List.of(), 16);
            task = new SemanticAcquireCompanionTask(world.player, record); task.onStart(); root = get(task, "rootNeed");
        }
        Object leaf(ResourceLocation item) throws Exception {
            Constructor<?> ctor = root.getClass().getDeclaredConstructor(List.class, int.class, int.class, Set.class, Set.class, Set.class, List.class);
            ctor.setAccessible(true);
            Object child = ctor.newInstance(List.of(item), 3, 1, Set.of(FINAL, item), Set.of(RECIPE), Set.of(RECIPE), record.allowedSources);
            set(child, "plannedSourceOrder", List.of()); return child;
        }
        void exhaustRoot() throws Exception { set(root, "plannedSourceOrder", List.of()); }
        @SuppressWarnings("unchecked") void push(Object child) throws Exception {
            ((Set<String>) get(root, "committedRecipeIds")).add(RECIPE); ((Deque<Object>) get(task, "needs")).push(child);
        }
        TaskState tick() throws Exception { return (TaskState) invoke(task, "tickAcquisition", null, null); }
        void unchanged() throws Exception {
            check(world.inventory.getItem(0).getCount() == 1 && world.itemUses() == 0 && world.blockUses() == 0
                            && get(task, "activeChild") == null, "交接不能启动设备动作或改写库存");
        }
        @Override public void close() throws Exception { world.close(); }
    }
    private static Object invoke(Object target, String name, Class<?> argumentType, Object argument) throws Exception {
        var method = argumentType == null ? target.getClass().getDeclaredMethod(name) : target.getClass().getDeclaredMethod(name, argumentType);
        method.setAccessible(true); return argumentType == null ? method.invoke(target) : method.invoke(target, argument);
    }
    private static Object get(Object target, String name) throws Exception { var field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
    private static void set(Object target, String name, Object value) throws Exception { var field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value); }
    @SuppressWarnings("unchecked") private static List<Map<String, Object>> list(Object target, String name) throws Exception { return (List<Map<String, Object>>) get(target, name); }
    @SuppressWarnings("unchecked") private static Map<String, Object> map(Object value) { return (Map<String, Object>) value; }
    private static ResourceLocation id(String path) { return ResourceLocation.withDefaultNamespace(path); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
