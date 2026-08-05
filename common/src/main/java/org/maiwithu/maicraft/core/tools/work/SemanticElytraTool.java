// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.tools.work;

import static org.maiwithu.maicraft.task.TaskDispatch.ctx;
import static org.maiwithu.maicraft.task.TaskDispatch.setTask;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.agent.tool.MaiCraftTool;
import org.maiwithu.maicraft.agent.tool.Schema;
import org.maiwithu.maicraft.core.task.endgame.SemanticElytraTaskRecord;

/** Hidden execution tool; public callers express only the semantic obtain-elytra intent. */
public final class SemanticElytraTool implements MaiCraftTool {
    @Override public String name() { return SemanticElytraTaskRecord.TOOL_NAME; }

    @Override public String description() {
        return "Obtain one real elytra after the dragon fight. The Mod finds a loaded End Gateway, "
                + "acquires and throws an ender pearl through first-person actions, verifies the "
                + "same-dimension teleport, physically searches for an End City, verifies an End "
                + "Ship item frame holding an elytra, breaks it and collects the drop. Coordinates, "
                + "entity IDs, routes, slots and clicks are internal and never arguments.";
    }

    @Override public Map<String, Object> parameterSchema() {
        return Schema.object()
                .optionalInteger(
                        "max_search_distance",
                        "Bounded first-person End City search distance.",
                        SemanticElytraTaskRecord.MIN_SEARCH_DISTANCE,
                        SemanticElytraTaskRecord.MAX_SEARCH_DISTANCE)
                .optionalBool(
                        "may_alter_terrain",
                        "Allow internal navigation to bridge, pillar or clear terrain when required.")
                .optionalBool(
                        "allow_combat",
                        "Allow combat only against loaded hostiles actively targeting the player and blocking progress.")
                .optionalBool(
                        "allow_rare_consumables",
                        "Explicitly allow one real ender-pearl use for End Gateway traversal; default false.")
                .optionalStringArray(
                        "protected_labels",
                        "Remembered areas or possessions that must not be touched.")
                .build();
    }

    @Override public void onGameCall(
            String toolCallId, JsonObject args, LocalPlayer player, Consumer<String> reply) {
        JsonObject input = args == null ? new JsonObject() : args;
        int maxDistance = integer(
                input, "max_search_distance", SemanticElytraTaskRecord.DEFAULT_SEARCH_DISTANCE,
                SemanticElytraTaskRecord.MIN_SEARCH_DISTANCE,
                SemanticElytraTaskRecord.MAX_SEARCH_DISTANCE);
        boolean mayAlterTerrain = bool(input, "may_alter_terrain", false);
        boolean allowCombat = bool(input, "allow_combat", false);
        boolean allowRareConsumables = bool(input, "allow_rare_consumables", false);
        List<String> protectedLabels = strings(
                input.get("protected_labels"), "protected_labels");
        long ticks = Math.clamp(
                10L * 60L * 20L + (long) maxDistance * 18L,
                20L * 60L * 20L,
                90L * 60L * 20L);
        var context = ctx(toolCallId, player);
        var record = new SemanticElytraTaskRecord(
                context.toolCallId(), context.deadline(ticks), maxDistance,
                mayAlterTerrain, allowCombat, allowRareConsumables, protectedLabels);
        setTask(player, record, input, reply);
    }

    private static int integer(
            JsonObject args, String key, int fallback, int minimum, int maximum) {
        if (!args.has(key) || args.get(key).isJsonNull()) return fallback;
        try {
            return Math.clamp(args.get(key).getAsInt(), minimum, maximum);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
    }

    private static boolean bool(JsonObject args, String key, boolean fallback) {
        if (!args.has(key) || args.get(key).isJsonNull()) return fallback;
        try {
            return args.get(key).getAsBoolean();
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(key + " must be a boolean");
        }
    }

    private static List<String> strings(JsonElement value, String label) {
        if (value == null || value.isJsonNull()) return List.of();
        if (!value.isJsonArray()) {
            throw new IllegalArgumentException(label + " must be an array of strings");
        }
        List<String> result = new ArrayList<>();
        for (JsonElement element : value.getAsJsonArray()) {
            if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
                throw new IllegalArgumentException(label + " must contain only strings");
            }
            String text = element.getAsString().strip();
            if (!text.isEmpty()) result.add(text);
        }
        return List.copyOf(result);
    }
}
