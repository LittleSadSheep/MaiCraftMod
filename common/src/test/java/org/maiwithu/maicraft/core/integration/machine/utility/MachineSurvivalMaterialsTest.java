// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.utility;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.Map;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.core.integration.machine.layout.SemanticMachineLayout;

/** 创造物品误选、整合包配方例外、已有实物和普通缺料之间保持独立。 */
public final class MachineSurvivalMaterialsTest {
    public static void main(String[] args) {
        Predicate<String> none = id -> false;
        var blocked = MachineSurvivalMaterials.review(false,
                List.of("create:creative_motor", "create:creative_motor", "ae2:creative_energy_cell",
                        "mekanism:creative_chemical_tank"), none, none);
        check(!blocked.get("allowed").getAsBoolean(), "unavailable known creative sources reject survival construction");
        check(blocked.getAsJsonArray("known_creative_materials").size() == 3, "one precise diagnostic per item ID");
        check(blocked.getAsJsonArray("suggested_external_inputs").get(0).getAsJsonObject()
                .get("medium").getAsString().equals("kinetic"), "creative motor suggests shared kinetic input");
        check(blocked.toString().contains("energy acceptor"), "AE2 power suggestion retains native conversion boundary");
        check(!blocked.get("automatic_substitution").getAsBoolean(), "preflight does not silently redesign sources");

        Predicate<String> unexpected = id -> { throw new AssertionError("ordinary or creative-mode items need no acquisition query: " + id); };
        var ordinary = MachineSurvivalMaterials.review(false, List.of("create:water_wheel", "create:large_water_wheel",
                "create:windmill_bearing", "create:steam_engine", "mekanism:basic_energy_cube", "ae2:energy_acceptor",
                "minecraft:barrel", "custom:creative_named_survival_machine"), unexpected, unexpected);
        check(ordinary.get("allowed").getAsBoolean(), "ordinary recipe absence and name substrings are not deny rules");
        check(ordinary.getAsJsonArray("known_creative_materials").isEmpty(), "ordinary missing materials stay in supply workflow");
        check(MachineSurvivalMaterials.review(true, List.of("create:creative_motor"), unexpected, unexpected)
                .get("allowed").getAsBoolean(), "creative mode does not require survival evidence");

        var carried = MachineSurvivalMaterials.review(false, List.of("mekanism:creative_energy_cube"), id -> true, unexpected);
        check(carried.get("allowed").getAsBoolean() && carried.toString().contains("carried_build_material"),
                "actually carried quest or administrator-provided item remains usable without recipe");
        var recipe = MachineSurvivalMaterials.review(false, List.of("create:creative_motor"), none, id -> true);
        check(recipe.get("allowed").getAsBoolean() && recipe.get("status").getAsString().equals("conditional_recipe_evidence"),
                "installed custom recipe prevents unconditional creative-only rejection");
        check(!recipe.get("acquisition_plan_verified").getAsBoolean(), "recipe display result does not prove obtainable inputs");
        var mixed = MachineSurvivalMaterials.review(false, List.of("create:creative_motor", "create:creative_fluid_tank"),
                none, id -> id.equals("create:creative_motor"));
        check(!mixed.get("allowed").getAsBoolean(), "one custom recipe does not authorize a different creative material");
        check(mixed.getAsJsonArray("suggested_external_inputs").get(0).getAsJsonObject().get("medium").getAsString().equals("fluids"),
                "fluid suggestion uses the supported external-input medium");
        check(blocked.getAsJsonArray("suggested_external_inputs").get(2).getAsJsonObject().get("medium").getAsString().equals("chemicals"),
                "chemical suggestion uses the supported external-input medium");
        var heat = MachineSurvivalMaterials.review(false, List.of("create:creative_blaze_cake"), none, none);
        check(heat.getAsJsonArray("suggested_external_inputs").isEmpty(), "unsupported heat hookup is never suggested");
        check(heat.getAsJsonArray("alternate_design_requirements").get(0).getAsJsonObject().get("code").getAsString()
                .equals("survival_heat_or_process_design_required"), "creative-only fuel requires explicit alternate process design");

        JsonObject blueprint = json("""
                {"blocks":[{"offset":[0,0,0],"block_id":"create:creative_motor"},
                  {"offset":[1,0,0],"item_id":"ae2:fluix_glass_cable","part":"center"}],
                 "metadata":{"survival_obtainable":true,"item_id":"create:creative_crate"}}
                """);
        JsonObject report = json("""
                {"buildable":true,"validation":{"valid":true,"errors":[],"error_count":0,"errors_truncated":false},
                 "logical_material_counts":{"ae2:creative_storage_cell":1},
                 "initial_contents":[{"item_id":"create:creative_blaze_cake","count":1}]}
                """);
        Set<String> ids = MachineSurvivalMaterials.materialIds(blueprint, report);
        check(ids.containsAll(Set.of("create:creative_motor", "ae2:creative_storage_cell", "create:creative_blaze_cake")),
                "preflight covers physical targets and compiler-declared initial contents");
        check(!ids.contains("create:creative_crate"), "arbitrary metadata is not native material evidence");
        var layout = new SemanticMachineLayout.Result(true, blueprint, report);
        var denied = MachineSurvivalMaterials.withReview(layout, MachineSurvivalMaterials.review(false, ids, none, none));
        check(!denied.buildable() && !denied.report().getAsJsonObject("validation").get("valid").getAsBoolean(),
                "layout cannot remain executable after failed material eligibility");
        check(denied.blueprint().equals(blueprint), "original geometry stays available for explicit redesign");
        check(report.get("buildable").getAsBoolean() && !report.has("survival_materials"), "review does not mutate caller report");
        var invalid = new SemanticMachineLayout.Result(false, blueprint, report);
        check(!MachineSurvivalMaterials.withReview(invalid, ordinary).buildable(), "passing materials cannot override geometry failure");
        // 四轮车把电机装进车体后背包会归零，续建应复用这些原生方块；缺一个、新增一个或未加载都不能补造实物。
        var installed=json("{\"blocks\":[{\"offset\":[-1,0,1],\"block_id\":\"create:creative_motor\"},{\"offset\":[1,0,1],\"block_id\":\"create:creative_motor\"}]}");
        BlockPos anchor=new BlockPos(40,70,80);
        var actual=Map.of(anchor.offset(-1,0,1),"create:creative_motor",anchor.offset(1,0,1),"create:creative_motor");
        var existing=MachineSurvivalMaterials.existingMaterials(installed,new JsonObject(),anchor,actual::get);
        check(existing.equals(Set.of("create:creative_motor")),"all existing targets are resolved relative to the bound construction anchor");
        var reused=MachineSurvivalMaterials.review(false,existing,none,unexpected,existing::contains);
        check(reused.get("allowed").getAsBoolean()&&reused.toString().contains("declared_blocks_already_present"),"existing machinery must not require duplicate carried material");
        check(MachineSurvivalMaterials.existingMaterials(installed,new JsonObject(),anchor,p->p.equals(anchor.offset(-1,0,1))?"create:creative_motor":null).isEmpty(),
                "an unloaded or missing second motor cannot be invented from the first");
        check(MachineSurvivalMaterials.existingMaterials(installed,new JsonObject(),anchor.offset(0,1,0),actual::get).isEmpty(),"a different construction height cannot reuse the old blocks");
        check(MachineSurvivalMaterials.existingMaterials(installed,new JsonObject(),null,p->{throw new AssertionError("unbound design read the world");}).isEmpty(),"unbound designs need acquisition evidence");
        check(MachineSurvivalMaterials.existingMaterials(installed,json("{\"initial_contents\":[{\"item_id\":\"create:creative_motor\"}]}"),anchor,actual::get).isEmpty(),
                "already installed blocks cannot supply declared container contents");
        check(MachineSurvivalMaterials.existingMaterials(installed,json("{\"logical_material_counts\":{\"create:creative_motor\":3}}"),anchor,actual::get).isEmpty(),
                "extra logical material demand cannot be hidden by two existing blocks");
    }

    private static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
    private static void check(boolean condition, String detail) { if (!condition) throw new AssertionError(detail); }
}
