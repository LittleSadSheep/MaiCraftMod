// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import org.maiwithu.maicraft.core.build.BuildingBudgets;
import org.maiwithu.maicraft.core.blueprint.BuildingSceneCompiler;
import org.maiwithu.maicraft.core.integration.machine.MachineBlueprintDocument;

/** 调整配置后从实际公共契约复核能力说明、建筑目标与机器预算，避免只改显示或误放宽其他能力。 */
public final class BuildingBudgetContractTest {
    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
        var directory = Files.createTempDirectory("building-contract-");
        var restore = Files.createTempDirectory("building-contract-defaults-");
        try {
            configure(directory, "maxTargets=4\nmaxRadius=700\nmaxObjects=32\npreview.maxCells=6\n");
            JsonObject limits = SemanticAbilityCatalog.describe("maicraft:build").getAsJsonObject("planning_budget");
            check(limits.get("max_targets").getAsInt() == 4 && limits.get("max_radius").getAsInt() == 700
                    && limits.getAsJsonObject("preview").get("max_cells").getAsInt() == 6,
                    "public budget reflects the actual owner configuration");
            check(SemanticAbilityCatalog.describe("maicraft:design_build").getAsJsonObject("planning_budget").equals(limits),
                    "design and construction expose the same budget");
            JsonObject scene = JsonParser.parseString("""
                {"schema_version":2,"coordinate_system":"minecraft_y_up","materials":{"Stone":{"block_id":"minecraft:stone"}},
                 "objects":[{"name":"Floor","type":"MESH","primitive":"cube","location":[200.5,0.5,2.5],
                             "dimensions":[1,1,5],"material":"Stone"}]}
                """).getAsJsonObject();
            rejects(() -> BuildingSceneCompiler.compile(scene), "small configured target cap rejects before building");
            configure(directory, "maxTargets=5\nmaxRadius=700\n");
            JsonObject blueprint = BuildingSceneCompiler.compile(scene);
            check(blueprint.getAsJsonArray("blocks").size() == 5, "raising only the configured cap accepts the same scene");
            MachineBlueprintDocument.validateBuildingWire(blueprint);
            rejects(() -> MachineBlueprintDocument.validateWire(blueprint), "machine radius remains independent of a building's wider area");
            var arguments = BuildingSceneAdapter.buildArguments(blueprint,
                    new Goal.WorldPosition(0,64,0,"minecraft:overworld"), new JsonObject());
            check(arguments.getAsJsonArray("ops").get(0).getAsJsonObject().get("y").getAsInt() == 64,
                    "larger budget never changes local coordinates or the fixed world anchor");
            configure(directory, "maxTargets=5\nmaxRadius=700\nmaxVoxelWork=1\n");
            rejects(() -> BuildingSceneCompiler.compile(scene), "work budget is independently enforced");
        } finally { BuildingBudgets.initialize(restore); }
        System.out.println("BuildingBudgetContractTest: passed");
    }
    private static void configure(java.nio.file.Path directory,String text) throws Exception {
        Files.createDirectories(directory.resolve("config")); Files.writeString(directory.resolve(BuildingBudgets.CONFIG_PATH),text);
        BuildingBudgets.initialize(directory);
    }
    private static void rejects(Runnable action,String message) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError(message);
    }
    private static void check(boolean value,String message) { if (!value) throw new AssertionError(message); }
}
