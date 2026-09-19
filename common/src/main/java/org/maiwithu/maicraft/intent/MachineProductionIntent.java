// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonObject;
import java.util.List;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.client.server.ServerAssistClient;
import org.maiwithu.maicraft.core.integration.machine.MachineBuildTaskRecord;
import org.maiwithu.maicraft.core.integration.machine.process.NativeProcessRegistry;
import org.maiwithu.maicraft.core.integration.machine.process.NativeProcessRequest;
import org.maiwithu.maicraft.core.integration.machine.process.NativeProcessTaskRecord;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionDesignCompiler;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionEvidence;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionManifest;

/** 统一解析旧网络与有限原生加工；只有实际适配器观察能证明可执行，声明或设计通过不等于已经生产。 */
final class MachineProductionIntent {
    private MachineProductionIntent() {}

    static void validate(JsonObject parameters) {
        if (!parameters.has("production") || !parameters.get("production").isJsonObject())
            throw new IllegalArgumentException("production requires a versioned production manifest");
        if (isNative(parameters.getAsJsonObject("production"))) {
            NativeProcessRegistry.validate(NativeProcessRequest.parse(parameters.getAsJsonObject("production"))); return;
        }
        ProductionManifest.parse(parameters.getAsJsonObject("production"));
        JsonObject report = review(parameters.getAsJsonObject("production"));
        if (!report.get("valid").getAsBoolean())
            throw new IllegalArgumentException("invalid_production_manifest: " + report.get("errors"));
    }

    static JsonObject review(JsonObject production) {
        if (isNative(production)) {
            var request = NativeProcessRequest.parse(production); NativeProcessRegistry.validate(request);
            JsonObject report = new JsonObject(); report.addProperty("schema", "maicraft.native_process.v2");
            report.addProperty("valid", true); report.addProperty("ready", false); report.addProperty("process", request.process());
            report.addProperty("available", NativeProcessRegistry.adapter(request.process()).available());
            report.add("contract", NativeProcessRegistry.adapter(request.process()).contract().deepCopy());
            report.addProperty("machine_production_verified", false);
            report.addProperty("evidence_required", "重新观察实际加工位置、原生配方或菜单报价、输入与真实产物；设计通过不代表已经消费或完成。");
            return report;
        }
        // A layout-only review must retain unresolved native recipe, port, power and supply requirements.
        return ProductionDesignCompiler.compile(production, new ProductionEvidence() {
            @Override public Recipe recipe(String recipeId) { return null; }
        }).report();
    }

    static void requireRuntime(JsonObject production) {
        // v2由实际机制声明可用性；附魔原生GUI不应被v1物流网络的六项服务器协议一并挡住。
        if (isNative(production)) { NativeProcessRegistry.requireAvailable(NativeProcessRequest.parse(production)); return; }
        for (String operation : List.of("machine.snapshot", "machine.recipe", "machine.connections",
                "machine.production_events", "inventory.quote", "inventory.transfer"))
            require(operation);
        var configurations = ProductionManifest.parse(production).configurations();
        if (!configurations.isEmpty()) require("machine.configuration");
        for (var configuration : configurations) require(configuration.operation());
    }

    static boolean isNative(JsonObject production) {
        var version = production == null ? null : production.get("schema_version");
        return version != null && version.isJsonPrimitive() && version.getAsJsonPrimitive().isNumber()
                && version.getAsBigDecimal().compareTo(java.math.BigDecimal.valueOf(2)) == 0;
    }

    static org.maiwithu.maicraft.task.TaskRecord createTask(String callId, long deadline, LocalPlayer player,
            BlockPos anchor, String dimension, JsonObject production, MachineBuildTaskRecord construction,
            List<String> protectedLabels, org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator.MaterialPolicy policy) {
        requireRuntime(production);
        if (isNative(production)) {
            var request = NativeProcessRequest.parse(production);
            // 已有机器直接使用原生任务；新建机器只包装先后顺序，真正创建加工子任务时再次检查建好的场地。
            return construction == null ? NativeProcessRegistry.createTask(callId, deadline, player, anchor, request)
                    : new NativeProcessTaskRecord(callId, deadline, request, anchor, dimension, construction);
        }
        var plan = new org.maiwithu.maicraft.core.integration.machine.runtime.ProductionRunPlan(anchor, dimension, production);
        return new org.maiwithu.maicraft.core.integration.machine.runtime.MachineProductionTaskRecord(
                callId, deadline, plan, construction, protectedLabels, policy);
    }

    private static void require(String operation) {
        if (!ServerAssistClient.supported(operation))
            throw new IllegalArgumentException("production_server_support_required: " + operation
                    + "; client-only build_machine without a production requirement remains available");
    }
}
