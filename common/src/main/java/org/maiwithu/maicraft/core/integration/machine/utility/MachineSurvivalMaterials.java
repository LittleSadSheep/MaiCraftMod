// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.utility;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.PlayerInv;
import org.maiwithu.maicraft.core.integration.machine.MachineRecipeEvidence;
import org.maiwithu.maicraft.core.integration.machine.layout.SemanticMachineLayout;
import org.maiwithu.maicraft.core.tools.RecipeProbe;

/** 生存预检只识别已核实的创造物品；没有普通配方不等于普通机器无法获取。 */
public final class MachineSurvivalMaterials {
    // Exact installed IDs audited against Create 6.0.10, Mekanism 10.7.19.85 and AE2 19.2.17.
    private static final Map<String, String> KNOWN_CREATIVE = Map.ofEntries(
            Map.entry("create:creative_motor", "kinetic"), Map.entry("create:creative_fluid_tank", "fluids"),
            Map.entry("create:creative_crate", "items"), Map.entry("create:creative_blaze_cake", "heat"),
            Map.entry("mekanism:creative_energy_cube", "energy"), Map.entry("mekanism:creative_fluid_tank", "fluids"),
            Map.entry("mekanism:creative_chemical_tank", "chemicals"), Map.entry("mekanism:creative_bin", "items"),
            Map.entry("ae2:creative_energy_cell", "energy"), Map.entry("ae2:creative_storage_cell", "items"));

    private MachineSurvivalMaterials() {}

    /** 注入的是实物与已安装配方证据，不能用蓝图 metadata 自报“可获取”绕过预检。 */
    public static JsonObject review(boolean instabuild, Collection<String> materialIds,
            Predicate<String> actuallyCarried, Predicate<String> installedRecipeResultKnown) {
        JsonObject report = new JsonObject(); JsonArray rows = new JsonArray(), suggestions = new JsonArray(), alternateDesigns = new JsonArray();
        boolean blocked = false, conditional = false; Set<String> suggested = new LinkedHashSet<>();
        for (String id : new LinkedHashSet<>(materialIds)) {
            String medium = KNOWN_CREATIVE.get(id); if (medium == null) continue;
            JsonObject row = new JsonObject(); row.addProperty("item_id", id);
            String status;
            if (instabuild) status = "creative_mode_allowed";
            else if (actuallyCarried.test(id)) status = "carried_build_material";
            else if (installedRecipeResultKnown.test(id)) { status = "conditional_recipe_evidence"; conditional = true; }
            else {
                status = "known_creative_material_without_acquisition_evidence"; blocked = true;
                if (medium.equals("heat")) {
                    JsonObject requirement = new JsonObject(); requirement.addProperty("item_id", id);
                    requirement.addProperty("code", "survival_heat_or_process_design_required");
                    requirement.addProperty("detail", "Explicitly design an obtainable heat source, fuel supply or alternate process; external_inputs does not support heat hookup.");
                    alternateDesigns.add(requirement);
                } else suggested.add(medium);
            }
            row.addProperty("status", status); rows.add(row);
        }
        for (String medium : suggested) {
            JsonObject suggestion = new JsonObject(); suggestion.addProperty("medium", medium);
            suggestion.addProperty("policy", "prefer_existing_shared_source; explicitly design any deliberate onsite source");
            if (medium.equals("energy")) suggestion.addProperty("native_connection_required",
                    "Use the actual machine energy interface; AE2 requires an energy acceptor for external FE input.");
            suggestions.add(suggestion);
        }
        report.addProperty("scope", "known_creative_materials_only");
        report.addProperty("allowed", !blocked);
        report.addProperty("status", blocked ? "survival_creative_material_blocked"
                : conditional ? "conditional_recipe_evidence" : "known_creative_check_passed");
        report.addProperty("creative_mode", instabuild);
        report.addProperty("acquisition_plan_verified", false);
        report.addProperty("ordinary_missing_materials_are_not_rejected", true);
        report.addProperty("automatic_substitution", false);
        report.addProperty("evidence_contract", "A carried item or installed static recipe result permits this known-only check. "
                + "Neither proves sufficient quantities, obtainable recipe inputs or executable custom recipe conditions.");
        report.add("known_creative_materials", rows); report.add("suggested_external_inputs", suggestions);
        report.add("alternate_design_requirements", alternateDesigns);
        return report;
    }

    public static JsonObject review(LocalPlayer player, JsonObject compiledBlueprint, JsonObject compiledReport) {
        if (player == null) throw new IllegalArgumentException("survival material review requires the current player");
        Set<String> ids = materialIds(compiledBlueprint, compiledReport), carried = new LinkedHashSet<>();
        boolean creative = player.getAbilities().instabuild;
        if (!creative) for (String id : ids) {
            if (!KNOWN_CREATIVE.containsKey(id)) continue;
            ResourceLocation key = ResourceLocation.parse(id);
            if (BuiltInRegistries.ITEM.containsKey(key)
                    && PlayerInv.buildableCount(player.getInventory(), BuiltInRegistries.ITEM.get(key)) > 0) carried.add(id);
        }
        Set<String> needed = new LinkedHashSet<>(ids); needed.retainAll(KNOWN_CREATIVE.keySet()); needed.removeAll(carried);
        Set<String> recipes = new LinkedHashSet<>(); JsonObject scan = new JsonObject();
        if (!creative && !needed.isEmpty()) scanRecipes(player, needed, recipes, scan);
        else scan.addProperty("status", "not_needed");
        JsonObject report = review(creative, ids, carried::contains, recipes::contains);
        report.add("recipe_scan", scan); return report;
    }

    /** 保留原蓝图供修改；阻断的是施工资格，不自动把创造马达换成另一套动力系统。 */
    public static SemanticMachineLayout.Result requireSurvivalBlueprint(LocalPlayer player, SemanticMachineLayout.Result layout) {
        return withReview(layout, review(player, layout.blueprint(), layout.report()));
    }

    static SemanticMachineLayout.Result withReview(SemanticMachineLayout.Result layout, JsonObject review) {
        JsonObject report = layout.report().deepCopy(); report.add("survival_materials", review.deepCopy());
        boolean allowed = review.get("allowed").getAsBoolean();
        report.addProperty("buildable", layout.buildable() && allowed);
        if (!allowed && report.has("validation")) {
            JsonObject validation = report.getAsJsonObject("validation"); validation.addProperty("valid", false);
            JsonArray errors = validation.getAsJsonArray("errors");
            int prior = validation.has("error_count") ? validation.get("error_count").getAsInt() : errors.size();
            if (errors.size() < 32) errors.add("survival_creative_material_blocked: inspect survival_materials for exact item IDs and acquisition evidence");
            validation.addProperty("error_count", prior + 1); validation.addProperty("errors_truncated", prior + 1 > errors.size());
        }
        return new SemanticMachineLayout.Result(layout.buildable() && allowed, layout.blueprint(), report);
    }

    static Set<String> materialIds(JsonObject blueprint, JsonObject report) {
        Set<String> ids = new LinkedHashSet<>();
        if (blueprint.has("blocks")) for (var element : blueprint.getAsJsonArray("blocks")) {
            JsonObject cell = element.getAsJsonObject();
            for (String field : Set.of("block_id", "item_id")) if (cell.has(field)) ids.add(cell.get(field).getAsString());
        }
        // The compiler adds consumables and drive contents here; filters and tutorial evidence are not inventory requirements.
        if (report.has("logical_material_counts")) ids.addAll(report.getAsJsonObject("logical_material_counts").keySet());
        if (report.has("initial_contents")) for (var element : report.getAsJsonArray("initial_contents")) {
            JsonObject content = element.getAsJsonObject(); if (content.has("item_id")) ids.add(content.get("item_id").getAsString());
        }
        return ids;
    }

    private static void scanRecipes(LocalPlayer player, Set<String> needed, Set<String> matches, JsonObject report) {
        int examined = 0, unreadable = 0; boolean exhausted = false;
        JsonArray evidence = new JsonArray(); report.add("matching_results", evidence);
        try {
            var context = ClientRuntime.requireContext(player);
            var iterator = context.connection().getRecipeManager().getRecipes().iterator();
            while (examined < MachineRecipeEvidence.MAX_EXAMINED_RECIPES && iterator.hasNext() && !matches.containsAll(needed)) {
                var holder = iterator.next(); examined++;
                try {
                    var output = RecipeProbe.resultOf(holder.value(), context.level().registryAccess());
                    if (!output.isEmpty()) {
                        String id = BuiltInRegistries.ITEM.getKey(output.getItem()).toString();
                        if (needed.contains(id) && matches.add(id)) {
                            JsonObject row = new JsonObject(); row.addProperty("item_id", id);
                            row.addProperty("recipe_id", holder.id().toString()); evidence.add(row);
                        }
                    }
                } catch (RuntimeException | LinkageError unavailable) { unreadable++; }
            }
            exhausted = !iterator.hasNext();
            report.addProperty("status", matches.isEmpty() ? "no_matching_display_result_observed" : "conditional_recipe_evidence");
        } catch (RuntimeException | LinkageError unavailable) { report.addProperty("status", "unavailable_or_interrupted"); }
        report.addProperty("source", "client_recipe_manager"); report.addProperty("examined_recipe_count", examined);
        report.addProperty("unreadable_recipe_count", unreadable); report.addProperty("scan_complete", exhausted);
        report.addProperty("scan_truncated", !exhausted && examined >= MachineRecipeEvidence.MAX_EXAMINED_RECIPES);
        report.addProperty("recipe_semantics_complete", false);
    }
}
