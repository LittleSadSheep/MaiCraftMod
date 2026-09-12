// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.supply;

import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/** Parent supply receipts preserve native source proof even if the material goal later fails. */
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
        var failed = (Map<?, ?>) method.invoke(coordinator,
                TaskResult.fail("later shortage", Map.of("attempts", attempts)), TaskState.FAILED, 1, false);
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

    private static void publicEvidenceSurvives(Map<?, ?> receipt) throws Exception {
        var sanitize = Class.forName("org.maiwithu.maicraft.intent.IntentTask").getDeclaredMethod("sanitizeMap", Map.class);
        sanitize.setAccessible(true);
        var gson = new com.google.gson.Gson();
        var value = gson.toJsonTree(sanitize.invoke(null, receipt));
        var attention = Class.forName("org.maiwithu.maicraft.intent.IntentRuntime")
                .getDeclaredMethod("sanitizeAttentionValue", com.google.gson.JsonElement.class);
        attention.setAccessible(true); value = (com.google.gson.JsonElement) attention.invoke(null, value);
        var persist = Class.forName("org.maiwithu.maicraft.intent.persistence.IntentStateCodec")
                .getDeclaredMethod("safeElement", com.google.gson.JsonElement.class, int.class);
        persist.setAccessible(true); value = (com.google.gson.JsonElement) persist.invoke(null, value, 0);
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
