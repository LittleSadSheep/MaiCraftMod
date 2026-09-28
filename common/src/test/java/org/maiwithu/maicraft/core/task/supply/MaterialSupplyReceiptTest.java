// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.supply;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import net.minecraft.SharedConstants;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import org.maiwithu.maicraft.intent.SemanticResultView;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.task.build.BuildTaskRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.maiwithu.maicraft.client.preview.PreviewSession.Decision;

/** 供料后来失败时仍保留已确认的原生到货事实，不能把部分到货当成目标已经凑齐。 */
public final class MaterialSupplyReceiptTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var coordinator = new SemanticMaterialSupplyCoordinator();
        set(coordinator, "demand", new SemanticMaterialSupplyCoordinator.Demand(
                List.of(ResourceLocation.parse("minecraft:iron_ingot")), 4, "production input"));
        set(coordinator, "materialPolicy", SemanticMaterialSupplyCoordinator.MaterialPolicy.STORAGE_AVAILABLE);
        var nativeReceipt = Map.of("request_id", "settled-native-request", "operation", "inventory.ae2_supply",
                "amount", 1, "backend", "server", "confirmed", true, "resource_id", "items:iron#exact",
                "server_tick", 10, "player_slot", 7);
        var source = Map.of("terminal_access", "server_fixed_terminal", "server_supply_receipts", List.of(nativeReceipt),
                "server_supply_receipt_count", 1, "server_supply_transferred", 1,
                "server_supply_receipts_truncated", false, "unrelated_payload", "must not propagate");
        var attempts = List.of(Map.of("source", "storage", "child_data", source),
                Map.of("source", "wireless", "child_data", source),
                Map.of("source", "inventory", "child_data", source));
        var method = SemanticMaterialSupplyCoordinator.class.getDeclaredMethod(
                "receipt", TaskResult.class, TaskState.class, int.class, boolean.class);
        method.setAccessible(true);
        var handoff = Map.of("missing_materials", List.of(Map.of("item_id", "create:polished_rose_quartz", "count", 15)),
                "knowledge_uris", List.of("maicraft://knowledge/recipes/create/polished_rose_quartz"));
        // 无线查货失败也可能结束施工供料，不能只保留普通缺料数量而丢掉连接是否读到的事实。
        var stockEvidence = Map.of("last_query", Map.of("status", "failed"), "need_checks", List.of());
        var failed = (Map<?, ?>) method.invoke(coordinator,
                TaskResult.fail("later shortage", Map.of("attempts", attempts, "planning_handoff", handoff,
                        "wireless_stock_evidence", stockEvidence,
                        "body_preparation_required", true, "food_preparation", Map.of("food", 10, "health", 7))), TaskState.FAILED, 1, false);
        check(stockEvidence.equals(failed.get("wireless_stock_evidence")), "supply keeps query failure distinct from empty stock");
        check(handoff.equals(failed.get("planning_handoff")), "supply keeps the material process handoff");
        check(Boolean.TRUE.equals(failed.get("body_preparation_required")), "supply also preserves the body's independent prerequisite");
        constructionKeepsMaterialHandoff(failed, handoff);
        var preserved = (List<?>) failed.get("storage_attempts");
        check(Boolean.FALSE.equals(failed.get("goal_satisfied")) && preserved.size() == 2,
                "partial confirmed source effects survive failure without inventing goal success or inventory extraction");
        // 同样的原生到货事实从随身无线终端取得时也要保留；背包清点不能冒充一次网络提取。
        check("wireless".equals(((Map<?, ?>) preserved.get(1)).get("source")), "wireless source retains confirmed transfers");
        var first = (Map<?, ?>) preserved.getFirst();
        check(first.get("server_supply_transferred").equals(1) && !first.containsKey("unrelated_payload")
                        && !first.containsKey("server_supply_receipts"),
                "the parent exposes completed transfer facts rather than internal slot receipts");
        publicEvidenceSurvives(failed);
        acquisitionHistorySurvives(coordinator, method);
        var ordinary = (Map<?, ?>) method.invoke(coordinator,
                TaskResult.ok("carried", Map.of()), TaskState.SUCCESS, 4, true);
        check(!ordinary.containsKey("storage_attempts"), "carried materials cannot fabricate an AE server receipt");
        System.out.println("MaterialSupplyReceiptTest: passed");
    }

    private static void acquisitionHistorySurvives(SemanticMaterialSupplyCoordinator coordinator,
            Method method) throws Exception {
        // 重放宽木板配方失败：父任务要看到真实选择与未搜完的事实，不能只剩一个看似唯一的缺料名称。
        var alternatives = IntStream.range(0, 24).mapToObj(i -> "example:plank_" + i).toList();
        var attempts = IntStream.range(0, 12).mapToObj(i -> Map.of("source", "wireless", "detail", "attempt-" + i,
                "inventory_before", i, "inventory_after", i + 1, "effects_observed", true,
                "child_data", Map.of("failure_code", "later_shortage", "outcome_uncertain", true,
                        "network_contents", "must not propagate"))).toList();
        var trace = Map.of("recipe_id", "minecraft:chest", "output_item_id", "minecraft:chest",
                "missing_item_ids", alternatives, "missing_count", 8,
                "preparation_plan", Map.of("feasible", true, "search_complete", false, "estimated_cost", 17,
                        "craft_chain", List.of("internal full preparation")));
        var receipt = (Map<?, ?>) method.invoke(coordinator, TaskResult.fail("later shortage",
                Map.of("attempts", attempts, "recipe_trace", List.of(trace))), TaskState.FAILED, 2, false);
        var reported = (List<?>) receipt.get("attempts");
        check(reported.size() == 8 && receipt.get("attempts_reported_count").equals(12)
                        && receipt.get("attempts_omitted_reported_rows").equals(4)
                        && "attempt-4".equals(((Map<?, ?>) reported.getFirst()).get("detail")),
                "the parent retains the latest reported rows and accurately declares omitted rows");
        var last = (Map<?, ?>) reported.getLast();
        check(last.get("inventory_after").equals(12) && Boolean.TRUE.equals(last.get("effects_observed"))
                        && !last.containsKey("child_data")
                        && "later_shortage".equals(((Map<?, ?>) last.get("child_outcome")).get("failure_code")),
                "attempt facts survive without copying the whole child payload");
        var recipe = (Map<?, ?>) ((List<?>) receipt.get("recipe_trace")).getFirst();
        check("minecraft:chest".equals(recipe.get("recipe_id"))
                        && ((List<?>) recipe.get("missing_item_ids")).size() == 16
                        && recipe.get("missing_item_ids_reported_count").equals(24)
                        && recipe.get("missing_item_ids_omitted_count").equals(8)
                        && Boolean.FALSE.equals(((Map<?, ?>) recipe.get("preparation_plan")).get("search_complete")),
                "bounded alternatives preserve recipe identity and incomplete search evidence");
        // 父任务经过通知和持久化后仍应能读回分支事实，不能只在协调器内部存在。
        var publicValue = publicReceipt(receipt).getAsJsonObject();
        check(publicValue.getAsJsonArray("recipe_trace").get(0).getAsJsonObject()
                        .get("recipe_id").getAsString().equals("minecraft:chest"), "public recipe lineage survives");
    }

    private static void constructionKeepsMaterialHandoff(Map<?, ?> failed, Map<?, ?> handoff) throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            // 重放机器尚未开工、原料加工前置未满足；默认施工结果应直接给出交接，而不藏进历史批次。
            var plan = new BuildTaskRecord("material-handoff", 1000, List.of(new BuildTaskRecord.Target(
                    Blocks.DIRT, Items.DIRT, new BlockPos(5, 1, 5), "wall", null, null, null)), true);
            var record = new SemanticBuildSupplyTaskRecord("material-parent", 1000, plan);
            var task = new SemanticBuildSupplyCompanionTask(h.player, record, (owner, frozen) -> Decision.DISABLED);
            task.start(h.player); set(task, "failedSupply", failed); set(task, "failureCode", "material_batch_supply_failed");
            var result = task.result(TaskState.FAILED).data();
            check(handoff.equals(result.get("planning_handoff")) && Boolean.TRUE.equals(result.get("material_planning_required"))
                    && Boolean.FALSE.equals(result.get("goal_satisfied")), "construction preserves the unsatisfied process prerequisite");
            check(Boolean.TRUE.equals(result.get("body_preparation_required"))
                    && ((Map<?, ?>) result.get("food_preparation")).get("food").equals(10), "construction keeps the observed body condition");
        }
    }

    private static void publicEvidenceSurvives(Map<?, ?> receipt) throws Exception {
        // 子任务的 JSON 证据先经过对外结果整理，再核对通知与检查点是否仍保留实际到货数量。
        var value = publicReceipt(receipt);
        var transfer = value.getAsJsonObject().getAsJsonArray("storage_attempts").get(0).getAsJsonObject()
                .getAsJsonArray("server_supply_transfers").get(0).getAsJsonObject();
        check(transfer.get("request_id").getAsString().equals("settled-native-request")
                        && transfer.get("amount").getAsInt() == 1 && transfer.get("confirmed").getAsBoolean()
                        && !transfer.has("player_slot"),
                "public task, attention and persisted results must retain auditable material facts without slot actions");
    }

    private static JsonElement publicReceipt(Map<?, ?> receipt) throws Exception {
        // 使用实际三层结果整理流程，检查对外通知及重启后的检查点都能保留相同的施工证据。
        var gson = new Gson();
        var value = gson.toJsonTree(SemanticResultView.jsonValue(gson.toJsonTree(receipt)));
        var attention = Class.forName("org.maiwithu.maicraft.intent.IntentRuntime")
                .getDeclaredMethod("sanitizeAttentionValue", JsonElement.class);
        attention.setAccessible(true); value = (JsonElement) attention.invoke(null, value);
        var persist = Class.forName("org.maiwithu.maicraft.intent.persistence.IntentStateCodec")
                .getDeclaredMethod("safeElement", JsonElement.class, int.class);
        persist.setAccessible(true); return (JsonElement) persist.invoke(null, value, 0);
    }

    private static void set(Object instance, String name, Object value) throws Exception {
        var field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true); field.set(instance, value);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
