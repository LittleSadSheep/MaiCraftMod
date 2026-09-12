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
                "transferred", 1, "backend", "server");
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
        check(first.get("server_supply_receipts").equals(List.of(nativeReceipt))
                        && first.get("server_supply_transferred").equals(1) && !first.containsKey("unrelated_payload"),
                "the exact native receipt survives parent aggregation without copying unrelated child data");
        var ordinary = (Map<?, ?>) method.invoke(coordinator,
                TaskResult.ok("carried", Map.of()), TaskState.SUCCESS, 4, true);
        check(!ordinary.containsKey("storage_attempts"), "carried materials cannot fabricate an AE server receipt");
        System.out.println("MaterialSupplyReceiptTest: passed");
    }

    private static void set(Object instance, String name, Object value) throws Exception {
        var field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true); field.set(instance, value);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
