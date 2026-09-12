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
            if (required(row,stage) && !"verified".equals(ProductionNativeJson.text(row,"status"))) return false;
        }
        return true;
    }

    /** A bounded failure message; the complete authored plan stays in the structured result. */
    public static String failureSummary(JsonObject report, String stage) {
        if (!Set.of("supply","start","observe").contains(stage)) throw new IllegalArgumentException("Unknown production admission stage");
        if (!Boolean.TRUE.equals(ProductionNativeJson.bool(report,"valid")))
            return "Production design is invalid; inspect the structured preparation errors";
        var reasons = new java.util.ArrayList<String>();
        int blocked = 0;
        for (var value : ProductionNativeJson.array(report,"requirements")) {
            JsonObject row = value.getAsJsonObject();
            if (!required(row,stage) || "verified".equals(ProductionNativeJson.text(row,"status"))) continue;
            blocked++;
            if (reasons.size() < 4) reasons.add(shortText(row,"category") + " " + shortText(row,"subject")
                    + ": " + shortText(row,"detail"));
        }
        return "Production " + stage + " blocked by " + blocked + " unverified requirements: " + String.join("; ",reasons)
                + ". Inspect preparation progress and native evidence before continuing";
    }

    private static boolean required(JsonObject row, String stage) {
        String gate = ProductionNativeJson.text(row,"gate"), category = ProductionNativeJson.text(row,"category");
        return Set.of("resources","geometry","recipe","process","topology").contains(category == null ? "" : category)
                || stage.equals("observe") && "condition".equals(category) || ("before_" + stage).equals(gate)
                || stage.equals("start") && "before_supply".equals(gate);
    }
    private static String shortText(JsonObject row, String key) {
        String text = ProductionNativeJson.text(row,key);
        return text == null ? "unknown" : text.length() <= 160 ? text : text.substring(0,157) + "...";
    }
}
