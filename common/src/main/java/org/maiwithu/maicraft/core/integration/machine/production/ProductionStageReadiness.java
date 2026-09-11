// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.production;

import com.google.gson.JsonObject;
import java.util.Set;

/** Mutation admission and live-running diagnostics; a false observe gate never forbids reading historical native evidence. */
public final class ProductionStageReadiness {
    private ProductionStageReadiness() {}
    public static boolean canEnter(JsonObject report, String stage) {
        if (!Set.of("supply","start","observe").contains(stage)) throw new IllegalArgumentException("Unknown production admission stage");
        if (!Boolean.TRUE.equals(ProductionNativeJson.bool(report,"valid"))) return false;
        for (var value : ProductionNativeJson.array(report,"requirements")) {
            JsonObject row = value.getAsJsonObject();
            String gate = ProductionNativeJson.text(row,"gate"), category = ProductionNativeJson.text(row,"category");
            boolean critical = Set.of("resources","geometry","recipe","process","topology").contains(category == null ? "" : category)
                    || stage.equals("observe") && "condition".equals(category);
            boolean required = critical || ("before_" + stage).equals(gate)
                    || stage.equals("start") && "before_supply".equals(gate);
            if (required && !"verified".equals(ProductionNativeJson.text(row,"status"))) return false;
        }
        return true;
    }
}
