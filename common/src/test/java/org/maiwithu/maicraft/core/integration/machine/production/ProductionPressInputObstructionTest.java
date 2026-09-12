// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.production;

import com.google.gson.JsonObject;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionManifest.Point;

/** A current, anchored native obstruction blocks admission; unknown ports and diagnostic unknowns do not. */
public final class ProductionPressInputObstructionTest {
    private ProductionPressInputObstructionTest() {}
    public static void main(String[] args) {
        var authored = ProductionNativeFixture.manifest();
        var evidence = ProductionNativeFixture.prepared(authored, 1);
        var recipe = ProductionNativeFixture.recipe();
        JsonObject obstruction = new JsonObject(); obstruction.addProperty("status", "blocked");
        obstruction.addProperty("provenance", "native_create_depot_held_item_and_press_recipe_selection");
        obstruction.add("receiver_position", ProductionNativeFixture.absolute(new Point(0, 0, 0)));
        JsonObject identity = ProductionNativeFixture.identity("minecraft:cobblestone");
        obstruction.add("identity", identity); obstruction.addProperty("resource_id", ProductionNativeJson.identityKey(identity));
        obstruction.addProperty("amount", 1); recipe.add("input_obstruction", obstruction);
        evidence.observeRecipe("press", recipe);
        var blocked = ProductionDesignCompiler.compile(authored, evidence);
        check(blocked.valid() && !blocked.canEnter("supply") && !blocked.canEnter("start"),
                "Known depot obstruction did not block initial supply and start");
        check(blocked.report().getAsJsonArray("requirements").asList().stream().map(value -> value.getAsJsonObject())
                .anyMatch(row -> "process".equals(ProductionNativeJson.text(row, "category"))
                        && ProductionNativeJson.text(row, "detail").contains("minecraft:cobblestone")),
                "Process readiness did not name the observed offending item");
        for (String status : new String[]{"not_blocked", "unknown"}) {
            obstruction.addProperty("status", status); evidence.observeRecipe("press", recipe);
            check(ProductionDesignCompiler.compile(authored, evidence).canEnter("supply"),
                    "A clear/unknown depot condition became generic input admission failure");
        }
        obstruction.addProperty("status", "blocked"); evidence.observeRecipe("press", recipe);
        evidence.observeRecipe("press", ProductionNativeFixture.recipe());
        check(ProductionDesignCompiler.compile(authored, evidence).canEnter("supply"), "Independent re-observation retained the old obstruction");
        evidence.observeRecipe("press", recipe); evidence.advance(1401);
        var node = ProductionManifest.parse(authored).nodes().stream().filter(value -> value.id().equals("press")).findFirst().orElseThrow();
        var check = evidence.process(node, ProductionNativeRecipes.decode(ProductionNativeFixture.recipe()));
        check(check.status() == ProductionEvidence.Status.UNKNOWN && !check.detail().contains("cobblestone"),
                "Expired obstruction was reported as current physical evidence");
        System.out.println("ProductionPressInputObstructionTest: fresh critical admission, offending identity and independent refresh passed");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
