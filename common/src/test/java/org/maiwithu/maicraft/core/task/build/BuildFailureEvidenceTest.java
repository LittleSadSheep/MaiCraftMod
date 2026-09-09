// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.maiwithu.maicraft.intent.IntentRuntime;
import org.maiwithu.maicraft.task.TaskResult;

public final class BuildFailureEvidenceTest {
    public static void main(String[] args) throws Exception {
        var target = new BuildTaskRecord.Target(Blocks.OAK_DOOR.defaultBlockState(), Items.OAK_DOOR,
                new BlockPos(307, -59, -460), "entrance", null, null, null, false, java.util.Set.of("open"), true);
        var evidence = BuildFailureEvidence.describe("replacement_policy", target.pos(), List.of(target),
                ignored -> true, ignored -> target.desiredState().setValue(BlockStateProperties.OPEN, true));
        var raw = TaskResult.fail("Cannot continue", Map.of("build_diagnostics", List.of(evidence), "placed", 5,
                "completed", 410, "temporary_supports_remaining", 3, "blocked_cells", List.of(Map.of("x", 307))));
        var sanitizer = Class.forName("org.maiwithu.maicraft.intent.IntentTask").getDeclaredMethod("semanticResult", TaskResult.class);
        sanitizer.setAccessible(true);
        var clean = JsonParser.parseString(((TaskResult) sanitizer.invoke(null, raw)).toJson()).getAsJsonObject();
        var compact = IntentRuntime.class.getDeclaredMethod("compactAttentionResult", com.google.gson.JsonObject.class);
        compact.setAccessible(true); clean = (com.google.gson.JsonObject) compact.invoke(null, clean);
        var data = clean.getAsJsonObject("data"); var row = data.getAsJsonArray("build_diagnostics").get(0).getAsJsonObject();
        check(row.get("target_index").getAsInt() == 0, "model target identity survives both public filters");
        check(row.getAsJsonObject("observed").getAsJsonObject("attributes").get("open").getAsString().equals("true"), "actual door state is diagnosable");
        check(data.get("placed").getAsInt() == 5 && data.get("completed").getAsInt() == 410
                && data.get("temporary_supports_remaining").getAsInt() == 3, "attention cannot hide partial effects or scaffolds");
        check(!data.has("blocked_cells") && !row.has("position"), "raw routes and coordinates remain outside this model-level receipt");
        System.out.println("BuildFailureEvidenceTest: actionable model diagnostics survive semantic and attention filtering");
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
