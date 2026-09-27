// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.emi;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import org.maiwithu.maicraft.server.inventory.ResourceIdentity;
import org.maiwithu.maicraft.server.machine.NativeApi;

/** 读取安装版本序列装配的原生权重与主产物概率，避免把 EMI 的展示值当成必定产出。 */
final class CreateAssemblyOutcomeFacts {
    private static final String ASSEMBLY = "com.simibubi.create.content.processing.sequenced.SequencedAssemblyRecipe";
    private static final String OUTPUT = "com.simibubi.create.content.processing.recipe.ProcessingOutput";
    record Outcome(ItemStack stack, float weight) {}

    private CreateAssemblyOutcomeFacts() {}

    static JsonObject read(Recipe<?> recipe, HolderLookup.Provider registries) {
        if (!NativeApi.is(recipe, ASSEMBLY)) return null;
        try {
            // 不替覆盖原生行为的模组子类解释抽样规则，也不调用 assemble、advance 或 rollResult 去试抽产物。
            if (recipe.getClass() != NativeApi.type(ASSEMBLY)) return unavailable("custom_assembly_subclass");
            int loops = ((Number) NativeApi.call(recipe, ASSEMBLY, "getLoops")).intValue();
            int steps = ((List<?>) NativeApi.call(recipe, ASSEMBLY, "getSequence")).size();
            var pool = (List<?>) NativeApi.field(recipe, ASSEMBLY, "resultPool");
            if (pool.isEmpty() || pool.size() > 32) return unavailable("native_result_pool_outside_budget");
            var outcomes = new ArrayList<Outcome>();
            for (Object entry : pool) outcomes.add(new Outcome(
                    ((ItemStack) NativeApi.call(entry, OUTPUT, "getStack")).copy(),
                    ((Number) NativeApi.call(entry, OUTPUT, "getChance")).floatValue()));
            float primary = ((Number) NativeApi.call(recipe, ASSEMBLY, "getOutputChance")).floatValue();
            JsonObject facts = describe(loops, steps, outcomes, primary, registries);
            return NativeRecipeDefinition.withinBudget(facts) ? facts : unavailable("native_outcome_facts_exceed_budget");
        } catch (RuntimeException | LinkageError unavailable) {
            return unavailable("native_outcome_api_unavailable:" + unavailable.getClass().getSimpleName());
        }
    }

    static JsonObject describe(int loops, int steps, List<Outcome> outcomes, float nativePrimary,
                               HolderLookup.Provider registries) {
        if (loops <= 0 || steps <= 0 || outcomes.isEmpty() || outcomes.size() > 32) throw new IllegalArgumentException("invalid native assembly dimensions");
        float total = 0;
        for (Outcome outcome : outcomes) {
            if (!Float.isFinite(outcome.weight()) || outcome.weight() < 0) throw new IllegalArgumentException("invalid native result weight");
            total += outcome.weight();
        }
        if (!Float.isFinite(total) || total <= 0 || !Float.isFinite(nativePrimary) || nativePrimary < 0 || nativePrimary > 1
                || Math.abs(nativePrimary - outcomes.getFirst().weight() / total) > 1e-6)
            throw new IllegalArgumentException("native primary chance does not verify weighted result semantics");
        JsonObject facts = new JsonObject(); facts.addProperty("status", "observed");
        facts.addProperty("provenance", "installed_create_sequenced_assembly_api");
        facts.addProperty("result_selection", "one_weighted_result_after_all_loops");
        facts.addProperty("serialized_chance_meaning", "relative_weight_not_probability");
        facts.addProperty("probability_meaning", "native_weight_divided_by_total_weight; one completed sequence does not guarantee the primary result");
        facts.addProperty("loops", loops); facts.addProperty("steps_per_loop", steps);
        facts.addProperty("total_steps", Math.multiplyExact(loops, steps));
        facts.addProperty("native_primary_output_chance", nativePrimary); facts.addProperty("total_weight", total);
        JsonArray rows = new JsonArray();
        // 每一项都取原生结果堆栈和权重，包括序列化器省略的默认权重；保留组件，不按物品名补出副产物。
        for (int index = 0; index < outcomes.size(); index++) {
            Outcome outcome = outcomes.get(index); JsonObject row = new JsonObject();
            row.addProperty("index", index); row.addProperty("primary", index == 0);
            row.add("identity", ResourceIdentity.item(outcome.stack(), registries));
            row.addProperty("count", outcome.stack().getCount()); row.addProperty("empty", outcome.stack().isEmpty());
            row.addProperty("native_weight", outcome.weight()); row.addProperty("probability", outcome.weight() / (double) total);
            rows.add(row);
        }
        facts.add("outcomes", rows);
        facts.addProperty("production_verified", false);
        return facts;
    }

    private static JsonObject unavailable(String reason) {
        JsonObject facts = new JsonObject(); facts.addProperty("status", "unknown"); facts.addProperty("reason", reason);
        return facts;
    }
}
