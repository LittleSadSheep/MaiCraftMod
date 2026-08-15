// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.tools.work;

import static org.maiwithu.maicraft.task.TaskDispatch.ctx;
import static org.maiwithu.maicraft.task.TaskDispatch.setTask;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.agent.tool.MaiCraftTool;
import org.maiwithu.maicraft.core.task.endgame.DragonFightTaskRecord;

/** Internal semantic Ender Dragon encounter capability. */
public final class SemanticDragonFightTool implements MaiCraftTool {
    private static final long INITIAL_LIVENESS_LEASE_TICKS = 90L * 60L * 20L;

    @Override
    public String name() {
        return DragonFightTaskRecord.TOOL_NAME;
    }

    @Override
    public String description() {
        return "Complete the currently observed Ender Dragon encounter in first person. "
                + "Declare combat and terrain permissions plus a recovery health floor; the Mod "
                + "observes crystals, chooses weapons, avoids dragon breath, opens only verified "
                + "iron-bar cages when allowed, and confirms the real death outcome. No entity ids, "
                + "routes, clicks, slots or block-by-block instructions are accepted.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("allow_combat", property(
                "boolean",
                "Must be true: permits destroying End Crystals and killing the Ender Dragon."));
        properties.put("may_alter_terrain", property(
                "boolean",
                "Permits bounded safe construction and removal of freshly verified iron bars "
                        + "around a caged crystal. Defaults to false."));
        properties.put("allow_rare_consumables", property(
                "boolean",
                "Explicitly permits consuming golden apples or enchanted golden apples for "
                        + "recovery. Defaults to false; ordinary no-effect food remains allowed."));
        Map<String, Object> minimumHealth = property(
                "number",
                "Pause combat below this many health points, evade hazards and recover before "
                        + "rebuilding the combat subtask. Defaults to 10." );
        minimumHealth.put("minimum", 1);
        minimumHealth.put("maximum", DragonFightTaskRecord.MAXIMUM_HEALTH_SETTING);
        properties.put("minimum_health", minimumHealth);
        properties.put("protected_labels", arrayProperty(
                "Remembered places whose nearby blocks and entities must not be altered or "
                        + "exploded by this encounter."));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("allow_combat"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public void onGameCall(
            String toolCallId, JsonObject args, LocalPlayer player, Consumer<String> reply) {
        boolean allowCombat = requiredBoolean(args, "allow_combat");
        boolean mayAlterTerrain = optionalBoolean(args, "may_alter_terrain", false);
        boolean allowRareConsumables = optionalBoolean(
                args, "allow_rare_consumables", false);
        float minimumHealth = optionalFloat(
                args,
                "minimum_health",
                DragonFightTaskRecord.DEFAULT_MINIMUM_HEALTH,
                1.0F,
                DragonFightTaskRecord.MAXIMUM_HEALTH_SETTING);
        List<String> protectedLabels = strings(
                args.get("protected_labels"), "protected_labels");

        var context = ctx(toolCallId, player);
        var record = new DragonFightTaskRecord(
                context.toolCallId(),
                context.deadline(INITIAL_LIVENESS_LEASE_TICKS),
                allowCombat,
                mayAlterTerrain,
                allowRareConsumables,
                minimumHealth,
                protectedLabels);
        setTask(player, record, args, reply);
    }

    private static Map<String, Object> property(String type, String description) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", type);
        result.put("description", description);
        return result;
    }

    private static Map<String, Object> arrayProperty(String description) {
        Map<String, Object> result = property("array", description);
        result.put("items", Map.of("type", "string"));
        result.put("maxItems", DragonFightTaskRecord.MAX_PROTECTED_LABELS);
        return result;
    }

    private static boolean requiredBoolean(JsonObject args, String key) {
        if (!args.has(key) || args.get(key).isJsonNull() || !args.get(key).isJsonPrimitive()) {
            throw new IllegalArgumentException(key + " must be the boolean true");
        }
        JsonElement value = args.get(key);
        if (!value.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException(key + " must be a boolean");
        }
        return value.getAsBoolean();
    }

    private static boolean optionalBoolean(JsonObject args, String key, boolean fallback) {
        if (!args.has(key) || args.get(key).isJsonNull()) return fallback;
        JsonElement value = args.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException(key + " must be a boolean");
        }
        return value.getAsBoolean();
    }

    private static float optionalFloat(
            JsonObject args, String key, float fallback, float minimum, float maximum) {
        if (!args.has(key) || args.get(key).isJsonNull()) return fallback;
        try {
            JsonElement raw = args.get(key);
            if (!raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isNumber()) {
                throw new IllegalArgumentException(key + " must be a JSON number");
            }
            float value = raw.getAsFloat();
            if (!Float.isFinite(value) || value < minimum || value > maximum) {
                throw new IllegalArgumentException(
                        key + " must be between " + minimum + " and " + maximum);
            }
            return value;
        } catch (NumberFormatException | UnsupportedOperationException invalid) {
            throw new IllegalArgumentException(key + " must be a number");
        }
    }

    private static List<String> strings(JsonElement value, String key) {
        if (value == null || value.isJsonNull()) return List.of();
        if (!value.isJsonArray()) throw new IllegalArgumentException(key + " must be an array");
        List<String> result = new ArrayList<>();
        for (JsonElement element : value.getAsJsonArray()) {
            if (!element.isJsonPrimitive()
                    || !element.getAsJsonPrimitive().isString()
                    || element.getAsString().isBlank()) {
                throw new IllegalArgumentException(key + " must contain only non-blank strings");
            }
            result.add(element.getAsString().trim());
            if (result.size() > DragonFightTaskRecord.MAX_PROTECTED_LABELS) {
                throw new IllegalArgumentException(
                        key + " accepts at most "
                                + DragonFightTaskRecord.MAX_PROTECTED_LABELS + " values");
            }
        }
        return List.copyOf(result);
    }
}
