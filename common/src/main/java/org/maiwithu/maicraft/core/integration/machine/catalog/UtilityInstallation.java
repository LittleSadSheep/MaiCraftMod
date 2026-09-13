// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.catalog;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import org.maiwithu.maicraft.core.integration.machine.utility.MachineUtilityInputs;
import static org.maiwithu.maicraft.core.integration.machine.catalog.MachineCatalogModels.*;

/** Remembered boundary ports and historical construction, independent of any production manifest. */
public record UtilityInstallation(String id, String label, String dimension, Position anchor, String inputsJson,
                                  String inputsFingerprint, long registeredAtMillis, long builtAtMillis) {
    public UtilityInstallation {
        id = CatalogLimits.text(id, 80, "installation id"); label = CatalogLimits.text(label, 160, "installation label");
        dimension = CatalogLimits.registry(dimension, "dimension"); java.util.Objects.requireNonNull(anchor);
        if (inputsJson == null || inputsJson.length() > 16384) throw new IllegalArgumentException("catalog_utility_inputs_budget");
        CatalogLimits.jsonDepth(inputsJson);
        var inputs = MachineUtilityInputs.parseStoredDeclarations(JsonParser.parseString(inputsJson).getAsJsonArray());
        if (inputs.isEmpty()) throw new IllegalArgumentException("catalog_utility_inputs_missing");
        inputsJson = encode(inputs);
        if (!CatalogLimits.hash(inputsJson).equals(inputsFingerprint)) throw new IllegalArgumentException("catalog_utility_fingerprint_mismatch");
        for (var input : inputs) anchor.plus(input.offset().getX(), input.offset().getY(), input.offset().getZ());
        CatalogLimits.nonnegative(registeredAtMillis, "installation time"); CatalogLimits.nonnegative(builtAtMillis, "construction time");
    }
    public List<MachineUtilityInputs.Input> inputs() { return MachineUtilityInputs.parseStoredDeclarations(JsonParser.parseString(inputsJson).getAsJsonArray()); }
    public JsonObject json(boolean includeLocation) {
        var result = new JsonObject(); result.addProperty("id", id); result.addProperty("label", label);
        result.addProperty("dimension", dimension); result.addProperty("inputs_fingerprint", inputsFingerprint);
        result.addProperty("construction_status", builtAtMillis == 0 ? "planned" : "historically_verified");
        result.addProperty("last_construction_at_ms", builtAtMillis); result.addProperty("current_connection_verified", false);
        result.addProperty("machine_production_verified", false); result.addProperty("operation_authorized", false);
        var entries = new JsonArray();
        for (var input : inputs()) {
            var entry = input.json(); entry.addProperty("location_ref", label + "/" + input.id());
            if (includeLocation) entry.add("position", CatalogViews.position(anchor.plus(input.offset().getX(), input.offset().getY(), input.offset().getZ())));
            else entry.remove("offset");
            entries.add(entry);
        }
        result.add("external_inputs", entries); if (includeLocation) result.add("anchor", CatalogViews.position(anchor));
        result.addProperty("next_operation", "inspect_machine then modify_machine connect_external_input with an explicit source_label");
        return result;
    }
    static String encode(List<MachineUtilityInputs.Input> inputs) { var entries = new JsonArray(); inputs.forEach(input -> entries.add(input.json())); return entries.toString(); }
    static String locationId(String identity, String dimension, Position anchor) { return "installation:" + CatalogLimits.hash(identity + "\n" + dimension + "\n" + anchor.x() + "," + anchor.y() + "," + anchor.z()); }
}
