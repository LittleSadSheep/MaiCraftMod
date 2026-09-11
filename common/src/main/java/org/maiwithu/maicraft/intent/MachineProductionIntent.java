// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonObject;
import java.util.List;
import org.maiwithu.maicraft.client.server.ServerAssistClient;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionDesignCompiler;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionEvidence;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionManifest;

/** Public goals declare production intent; only runtime adapters can contribute evidence that makes it executable. */
final class MachineProductionIntent {
    private MachineProductionIntent() {}

    static void validate(JsonObject parameters) {
        if (!parameters.has("production") || !parameters.get("production").isJsonObject())
            throw new IllegalArgumentException("production requires a versioned production manifest");
        ProductionManifest.parse(parameters.getAsJsonObject("production"));
        JsonObject report = review(parameters.getAsJsonObject("production"));
        if (!report.get("valid").getAsBoolean())
            throw new IllegalArgumentException("invalid_production_manifest: " + report.get("errors"));
    }

    static JsonObject review(JsonObject production) {
        // A layout-only review must retain unresolved native recipe, port, power and supply requirements.
        return ProductionDesignCompiler.compile(production, new ProductionEvidence() {
            @Override public Recipe recipe(String recipeId) { return null; }
        }).report();
    }

    static void requireRuntime(JsonObject production) {
        for (String operation : List.of("machine.snapshot", "machine.recipe", "machine.connections",
                "machine.production_events", "inventory.quote", "inventory.transfer"))
            require(operation);
        var configurations = ProductionManifest.parse(production).configurations();
        if (!configurations.isEmpty()) require("machine.configuration");
        for (var configuration : configurations) require(configuration.operation());
    }

    private static void require(String operation) {
        if (!ServerAssistClient.supported(operation))
            throw new IllegalArgumentException("production_server_support_required: " + operation
                    + "; client-only build_machine without a production requirement remains available");
    }
}
