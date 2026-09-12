// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonParser;
import java.util.List;
import org.maiwithu.maicraft.core.task.dimension.DimensionTravelTaskRecord;
import org.maiwithu.maicraft.core.task.dimension.PortalPreparationPolicy;
import org.maiwithu.maicraft.core.task.progression.ProgressionChildFactory;
import org.maiwithu.maicraft.core.task.progression.ReachMilestoneTaskRecord;
import org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator.MaterialPolicy;
import org.maiwithu.maicraft.core.tools.work.SemanticDimensionTravelTool;
import org.maiwithu.maicraft.core.tools.work.SemanticMilestoneTool;

public final class PortalPreparationContractTest {
    public static void main(String[] args) {
        for (String ability : List.of("maicraft:travel_dimension", "maicraft:reach_milestone")) {
            var parameters = JsonParser.parseString("""
                    {"destination_dimension":"minecraft:the_end","milestone":"elytra",
                     "prepare_portal":true,"may_alter_terrain":true,"allow_rare_consumables":true,
                     "allow_combat":true,"max_search_distance":512,"material_policy":"inventory_only",
                     "allowed_sources":["inventory"],"protected_labels":["home"]}
                    """).getAsJsonObject();
            var goal = new Goal(ability, "visit the next dimension", null, parameters.toString(), "{}", List.of(), List.of());
            var action = (IntentAction.Tool) AbilityAdapter.adapt(goal, null, null);
            check(action.arguments().get("prepare_portal").getAsBoolean(), "semantic preparation permission reaches the executor");
            var policy = PortalPreparationPolicy.parse(action.arguments());
            check(policy.allowRareConsumables() && policy.allowCombat() && policy.maxStructureDistance() == 512
                    && policy.materialPolicy() == MaterialPolicy.INVENTORY_ONLY && policy.protectedLabels().equals(List.of("home")),
                    "adaptation preserves every preparation constraint");
            check(SemanticAbilityCatalog.describe(ability).toString().contains("prepare_portal"), "the public contract explains preparation");
        }
        var milestone = new ReachMilestoneTaskRecord("progress", 1200, ReachMilestoneTaskRecord.Milestone.ELYTRA,
                512, 64, 10, true, true, true, List.of(), MaterialPolicy.INVENTORY_ONLY, List.of("home"), true);
        var travel = new ProgressionChildFactory(null, milestone).travel("minecraft:the_end");
        check(travel.preparation.enabled() && travel.mayAlterTerrain && travel.preparation.allowRareConsumables()
                        && travel.preparation.materialPolicy() == MaterialPolicy.INVENTORY_ONLY
                        && travel.preparation.protectedLabels().equals(List.of("home")),
                "progression children retain preparation, terrain, consumption, supply and protection permissions");
        check(!new DimensionTravelTaskRecord("legacy", 100, "minecraft:the_nether", 128, true).preparation.enabled(),
                "legacy internal calls do not gain construction authority");
        check(new SemanticDimensionTravelTool().parameterSchema().toString().contains("prepare_portal")
                        && new SemanticMilestoneTool().parameterSchema().toString().contains("prepare_portal"),
                "both executable schemas expose the declared permission");
        System.out.println("PortalPreparationContractTest: public adaptation and progression propagation passed");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
