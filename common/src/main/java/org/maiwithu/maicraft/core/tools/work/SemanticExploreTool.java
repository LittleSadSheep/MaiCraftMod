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

/** 高层语义探索；所有具体路线决策都保留在 Mod 内部。 */
public final class SemanticExploreTool implements MaiCraftTool {
    private static final Gson GSON = new Gson();
    private record Args(String target, Integer max_distance, Boolean may_alter_terrain, String transport_mode,
            String direction, Integer angle_degrees, Integer min_distance, java.util.List<String> interests) {}

    @Override public String name() { return "explore"; }

    @Override public String description() {
        return "Explore a bounded map sector. target=survey records observations; biome targets require arrival verification. Give "
                + "target='coast' (minecraft:beach), a namespaced biome id such as minecraft:desert, or a biome tag "
                + "such as #minecraft:is_forest. One task repeatedly observes loaded client terrain, "
                + "chooses bounded internal waypoints, walks with normal first-person pathing, loads "
                + "new terrain, and rechecks after arrival. It never queries a seed, forces chunk "
                + "generation, invents structure coordinates, or asks the model to plan waypoints. "
                + "By default travel does not alter terrain.";
    }

    @Override public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("target", "Destination: coast (minecraft:beach), biome id, #biome_tag, or survey to explore without a target kind.")
                // 方向只约束探索候选，Mod 仍负责选路并在原生地形上绕障碍。
                .optionalString("direction", "Horizontal cardinal/diagonal or forward/backward/left/right; fixed at start.")
                .optionalInteger("angle_degrees", "Full directional sector width; default 90 with direction, otherwise 360.", 1, 360)
                .optionalInteger("min_distance", "Ignore target matches nearer than this distance; default 16 with direction, otherwise 0.", 0, 2048)
                .optionalInteger("max_distance", "Maximum exploration radius from the starting point "
                        + "(default 768).", SemanticExploreTaskRecord.MIN_DISTANCE,
                        SemanticExploreTaskRecord.MAX_DISTANCE)
                .optionalBool("may_alter_terrain", "Explicit consent for route pathing to dig, bridge or "
                        + "pillar. Default false; target detection itself never changes blocks.")
                .optionalEnumStringArray("interests", "Side interests: the task pauses with a continue/stop decision "
                        + "the first time a NEW matching terrain feature is recorded. Legal value: lava_pool.", "lava_pool")
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
                parsed == null ? null : parsed.transport_mode(),
                parsed == null ? null : parsed.direction(), parsed == null ? null : parsed.angle_degrees(),
                parsed == null ? null : parsed.min_distance(),
                parsed == null ? null : parsed.interests());
        setTask(player, record, args, reply);
    }
}
