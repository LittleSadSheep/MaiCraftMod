// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.supply;

import java.util.List;
import java.util.Map;
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
                Map.of("source", "inventory", "child_data", source));
        var method = SemanticMaterialSupplyCoordinator.class.getDeclaredMethod(
                "receipt", TaskResult.class, TaskState.class, int.class, boolean.class);
        method.setAccessible(true);
        var handoff = Map.of("missing_materials", List.of(Map.of("item_id", "create:polished_rose_quartz", "count", 15)),
                "knowledge_uris", List.of("maicraft://knowledge/recipes/create/polished_rose_quartz"));
        var failed = (Map<?, ?>) method.invoke(coordinator,
                TaskResult.fail("later shortage", Map.of("attempts", attempts, "planning_handoff", handoff,
                        "body_preparation_required", true, "food_preparation", Map.of("food", 10, "health", 7))), TaskState.FAILED, 1, false);
        check(handoff.equals(failed.get("planning_handoff")), "supply keeps the material process handoff");
        check(Boolean.TRUE.equals(failed.get("body_preparation_required")), "supply also preserves the body's independent prerequisite");
        constructionKeepsMaterialHandoff(failed, handoff);
        var preserved = (List<?>) failed.get("storage_attempts");
        check(Boolean.FALSE.equals(failed.get("goal_satisfied")) && preserved.size() == 1,
                "partial confirmed source effects survive failure without inventing goal success or inventory extraction");
        var first = (Map<?, ?>) preserved.getFirst();
        check(first.get("server_supply_transferred").equals(1) && !first.containsKey("unrelated_payload")
                        && !first.containsKey("server_supply_receipts"),
                "the parent exposes completed transfer facts rather than internal slot receipts");
        publicEvidenceSurvives(failed);
        var ordinary = (Map<?, ?>) method.invoke(coordinator,
                TaskResult.ok("carried", Map.of()), TaskState.SUCCESS, 4, true);
        check(!ordinary.containsKey("storage_attempts"), "carried materials cannot fabricate an AE server receipt");
        System.out.println("MaterialSupplyReceiptTest: passed");
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
        var gson = new Gson();
        var value = gson.toJsonTree(SemanticResultView.jsonValue(gson.toJsonTree(receipt)));
        var attention = Class.forName("org.maiwithu.maicraft.intent.IntentRuntime")
                .getDeclaredMethod("sanitizeAttentionValue", JsonElement.class);
        attention.setAccessible(true); value = (JsonElement) attention.invoke(null, value);
        var persist = Class.forName("org.maiwithu.maicraft.intent.persistence.IntentStateCodec")
                .getDeclaredMethod("safeElement", JsonElement.class, int.class);
        persist.setAccessible(true); value = (JsonElement) persist.invoke(null, value, 0);
        var transfer = value.getAsJsonObject().getAsJsonArray("storage_attempts").get(0).getAsJsonObject()
                .getAsJsonArray("server_supply_transfers").get(0).getAsJsonObject();
        check(transfer.get("request_id").getAsString().equals("settled-native-request")
                        && transfer.get("amount").getAsInt() == 1 && transfer.get("confirmed").getAsBoolean()
                        && !transfer.has("player_slot"),
                "public task, attention and persisted results must retain auditable material facts without slot actions");
    }

    private static void set(Object instance, String name, Object value) throws Exception {
        var field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true); field.set(instance, value);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
