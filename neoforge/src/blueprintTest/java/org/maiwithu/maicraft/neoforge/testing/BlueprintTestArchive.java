// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.testing;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import net.minecraft.client.Minecraft;
import org.maiwithu.maicraft.core.integration.machine.MachineBlueprintDocument;
import org.maiwithu.maicraft.core.integration.machine.MachineConstructionPlan;
import org.maiwithu.maicraft.core.integration.machine.catalog.ClientMachineCatalog;

/** 显式维护开关只重建已应用请求的比较档案，绝不重新生成方块、补料、配置机器或移动玩家。 */
final class BlueprintTestArchive {
    private static boolean handled;
    private BlueprintTestArchive() {}
    static void replay(Minecraft game, Path directory) throws Exception {
        if (handled || !Boolean.getBoolean("maicraft.blueprintTest.rebuildCatalog")) return;
        handled = true; var labels = new HashSet<String>(); int count = 0;
        try (var requests = Files.list(directory.resolve("requests"))) {
            for (Path path : requests.filter(value -> value.getFileName().toString().endsWith(".json")).sorted().toList()) {
                Path receipt = directory.resolve("receipts").resolve(path.getFileName()); if (!Files.exists(receipt)) continue;
                var proof = JsonParser.parseString(Files.readString(receipt)).getAsJsonObject();
                if (!proof.has("status") || !proof.get("status").getAsString().equals("applied")) continue;
                var request = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
                var anchor = BlueprintTestNative.position(request.getAsJsonArray("anchor")); String label = request.get("label").getAsString();
                var layout = MachineBlueprintDocument.compile(request.getAsJsonObject("blueprint"), MachineConstructionPlan.registry());
                var plan = MachineConstructionPlan.compile(anchor, layout, true, true);
                // 每台机器从第一份真实已应用的设计开始，再按原顺序叠加补丁，保留被旧编译关系遮住的声明格。
                if (!labels.add(label + "/" + anchor)) plan.markModification();
                var recorded = ClientMachineCatalog.installationBuilt(game.player, plan, label);
                if (!recorded.has("archive_status") || !recorded.get("archive_status").getAsString().equals("recorded"))
                    throw new IllegalStateException("test archive replay failed: " + recorded);
                count++;
            }
        }
        var result = new JsonObject(); result.addProperty("replayed_applied_requests", count);
        result.addProperty("world_mutated", false); result.addProperty("inputs_inserted", 0);
        Files.writeString(directory.resolve("catalog-replay.json"), result.toString());
    }
}
