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
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.task.dimension.PortalPreparationTaskRecord;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.core.task.dimension.PortalPreparationTask;

public final class PortalPreparationContractTest {
    public static void main(String[] args) throws Exception {
        for (String ability : List.of("maicraft:travel_dimension", "maicraft:reach_milestone")) {
            var parameters = JsonParser.parseString("""
                    {"destination_dimension":"minecraft:the_end","milestone":"elytra",
                     "prepare_portal":true,"portal_method":"lava_cast","may_alter_terrain":true,"allow_rare_consumables":true,
                     "allow_combat":true,"max_search_distance":512,"material_policy":"inventory_only",
                     "allowed_sources":["inventory"],"protected_labels":["home"]}
                    """).getAsJsonObject();
            var goal = new Goal(ability, "visit the next dimension", null, parameters.toString(), "{}", List.of(), List.of());
            var action = (IntentAction.Tool) AbilityAdapter.adapt(goal, null, null);
            check(action.arguments().get("prepare_portal").getAsBoolean(), "semantic preparation permission reaches the executor");
            var policy = PortalPreparationPolicy.parse(action.arguments());
            check(policy.method() == PortalPreparationPolicy.Method.LAVA_CAST, "casting method survives both semantic entry points");
            check(policy.allowRareConsumables() && policy.allowCombat() && policy.maxStructureDistance() == 512
                    && policy.materialPolicy() == MaterialPolicy.INVENTORY_ONLY && policy.protectedLabels().equals(List.of("home")),
                    "adaptation preserves every preparation constraint");
            check(SemanticAbilityCatalog.describe(ability).toString().contains("prepare_portal"), "the public contract explains preparation");
        }
        // 契约文档写 "Nether, stronghold..."；大小写在语义层规范化，直提不再多烧一轮决策。
        var capitalized = JsonParser.parseString("""
                {"milestone":"Nether"}
                """).getAsJsonObject();
        var normalizedGoal = new Goal("maicraft:reach_milestone", "visit the next dimension", null,
                capitalized.toString(), "{}", List.of(), List.of());
        var normalizedAction = (IntentAction.Tool) AbilityAdapter.adapt(normalizedGoal, null, null);
        check(normalizedAction.arguments().get("milestone").getAsString().equals("nether"),
                "documented capitalization adapts directly instead of requesting a decision");
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
        // 独立备门应创建备门任务而非旅行任务，模型选择浇筑后可在门外检查真实门面。
        try (var world = new InteractionWorldTestHarness()) {
            var goal = new Goal("maicraft:prepare_portal", "用单桶浇筑并点燃地狱门", null,
                    "{\"portal_method\":\"lava_cast\",\"may_alter_terrain\":true}", "{}", List.of(), List.of());
            var action = (IntentAction.Native) AbilityAdapter.adapt(goal, world.player, null);
            check(action.record() instanceof PortalPreparationTaskRecord, "independent preparation cannot silently start dimension travel");
            check(TaskFactory.create(world.player, action.record()) instanceof PortalPreparationTask, "public preparation has a registered executor");
            check(SemanticAbilityCatalog.describe(goal.ability()).toString().contains("lava_cast"), "the model can discover the native casting method");
        }
        System.out.println("PortalPreparationContractTest: public adaptation and progression propagation passed");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
