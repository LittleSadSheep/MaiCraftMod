// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.production;

import java.util.List;
import java.util.Map;
import java.util.Set;
import com.google.gson.JsonObject;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionManifest.*;

/** Trusted adapter seam. User-authored manifests cannot assert these native capabilities or observations. */
public interface ProductionEvidence {
    enum Status { VERIFIED, PLANNED, UNKNOWN, UNSUPPORTED }
    record Check(Status status, String provenance, String detail) {
        public Check { if (status == null || provenance == null || provenance.isBlank()) throw new IllegalArgumentException("Evidence requires status and provenance"); }
        public static Check unknown(String detail) { return new Check(Status.UNKNOWN, "not_observed", detail); }
    }
    record Ingredient(String id, Set<Resource> alternatives, long amount, boolean consumed) {
        public Ingredient { alternatives = Set.copyOf(alternatives); if (id == null || id.isBlank() || alternatives.isEmpty() || amount <= 0) throw new IllegalArgumentException("Invalid recipe ingredient"); }
    }
    record Output(Resource resource, long amount, double chance) {
        public Output { if (resource == null || amount <= 0 || !Double.isFinite(chance) || chance <= 0 || chance > 1) throw new IllegalArgumentException("Invalid recipe output"); }
    }
    record Recipe(String id, List<Ingredient> inputs, List<Output> outputs, Map<Resource, Long> minimumPower,
                  Set<String> conditions, boolean complete, String provenance) {
        public Recipe { inputs = List.copyOf(inputs); outputs = List.copyOf(outputs); minimumPower = Map.copyOf(minimumPower); conditions = Set.copyOf(conditions); }
    }
    record Binding(Check check, Resource resource, JsonObject identity) {
        public Binding { identity = identity == null ? null : identity.deepCopy(); }
        @Override public JsonObject identity() { return identity == null ? null : identity.deepCopy(); }
    }
    record RecipeBinding(Check check, String requestedRecipeId, String recipeId) {}
    default Binding resolve(Resource selector) { return new Binding(Check.unknown("Resolve the selector to a native component-sensitive resource identity"),selector,null); }
    Recipe recipe(String recipeId);
    default RecipeBinding bindRecipe(Node node) {
        Recipe recipe = recipe(node.recipeId());
        return new RecipeBinding(recipe != null && node.recipeId().equals(recipe.id())
                ? new Check(Status.VERIFIED,recipe.provenance(),"Recipe identity agrees with the request") : Check.unknown("Resolve exact native recipe identity"),node.recipeId(),node.recipeId());
    }
    default Check topology(Link link, Port from, Port to) { return link(link,from,to); }
    default Check operational(Link link, Port from, Port to) { return link(link,from,to); }
    default Check geometry(Node node) { return Check.unknown("Observe the installed machine structure at its anchored offset"); }
    default Check process(Node node, Recipe recipe) { return Check.unknown("Verify this equipment performs the installed recipe, including native operating conditions"); }
    default Check port(Port port) { return Check.unknown("Verify the declared medium, direction and face against the native capability"); }
    default Check link(Link link, Port from, Port to) { return Check.unknown("Verify the complete native path and configured endpoints; adjacency alone is insufficient"); }
    default Check configuration(Configuration configuration) { return Check.unknown("Discover a supported native operation and verify the requested configuration"); }
    default Check supply(Node source, Resource resource, long amount) { return Check.unknown("Acquire and confirm the required real input quantity"); }
    default Check condition(Node process, String condition) { return Check.unknown("Verify native recipe condition: " + condition); }
    default boolean dynamicCondition(String condition) { return false; }
    default Check configurationAvailable(Configuration configuration) { return configuration(configuration); }
}
