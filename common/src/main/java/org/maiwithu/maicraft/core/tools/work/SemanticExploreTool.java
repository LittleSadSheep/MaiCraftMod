package org.maiwithu.maicraft.core.tools.work;

import static org.maiwithu.maicraft.task.TaskDispatch.ctx;
import static org.maiwithu.maicraft.task.TaskDispatch.setTask;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.agent.tool.MaiCraftTool;
import org.maiwithu.maicraft.agent.tool.Schema;
import org.maiwithu.maicraft.core.task.explore.SemanticExploreTaskRecord;

/** High-level semantic exploration; all concrete route decisions remain inside the Mod. */
public final class SemanticExploreTool implements MaiCraftTool {
    private static final Gson GSON = new Gson();
    private record Args(String target, Integer max_distance, Boolean may_alter_terrain, String transport_mode) {}

    @Override public String name() { return "explore"; }

    @Override public String description() {
        return "Explore and physically travel until a semantic destination is verified. Give only "
                + "target='coast', a namespaced biome id such as minecraft:desert, or a biome tag "
                + "such as #minecraft:is_forest. One task repeatedly observes loaded client terrain, "
                + "chooses bounded internal waypoints, walks with normal first-person pathing, loads "
                + "new terrain, and rechecks after arrival. It never queries a seed, forces chunk "
                + "generation, invents structure coordinates, or asks the model to plan waypoints. "
                + "By default travel does not alter terrain.";
    }

    @Override public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("target", "Semantic destination: coast, namespaced biome id, or #biome_tag.")
                .optionalInteger("max_distance", "Maximum exploration radius from the starting point "
                        + "(default 768).", SemanticExploreTaskRecord.MIN_DISTANCE,
                        SemanticExploreTaskRecord.MAX_DISTANCE)
                .optionalBool("may_alter_terrain", "Explicit consent for route pathing to dig, bridge or "
                        + "pillar. Default false; target detection itself never changes blocks.")
                .optionalEnum("transport_mode", "Default auto may choose available native transport to observed internal destinations. Ground disables jetpack/elevator use. A forced jetpack/elevator journey needs a located destination through goto.",
                        "auto", "ground")
                .build();
    }

    @Override public void onGameCall(
            String toolCallId, JsonObject args, LocalPlayer player, Consumer<String> reply) {
        Args parsed = GSON.fromJson(args, Args.class);
        var record = SemanticExploreApi.newRecord(
                ctx(toolCallId, player),
                parsed == null ? null : parsed.target(),
                parsed == null ? null : parsed.max_distance(),
                parsed == null ? null : parsed.may_alter_terrain(),
                parsed == null ? null : parsed.transport_mode());
        setTask(player, record, args, reply);
    }
}
