// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.tools.work;

import static org.maiwithu.maicraft.core.tools.SemanticParameters.strings;
import static org.maiwithu.maicraft.task.TaskDispatch.ctx;
import static org.maiwithu.maicraft.task.TaskDispatch.setTask;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.agent.tool.MaiCraftTool;
import org.maiwithu.maicraft.agent.tool.Schema;
import org.maiwithu.maicraft.core.task.endgame.DragonFightTaskRecord;

/** 内部语义末影龙战斗能力。 */
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
        return Schema.object()
                .bool("allow_combat",
                        "Must be true: permits destroying End Crystals and killing the Ender Dragon.")
                .optionalBool("may_alter_terrain",
                        "Permits bounded safe construction and removal of freshly verified iron bars "
                                + "around a caged crystal. Defaults to false.")
                .optionalBool("allow_rare_consumables",
                        "Explicitly permits consuming golden apples or enchanted golden apples for "
                                + "recovery. Defaults to false; ordinary no-effect food remains allowed.")
                .optionalNumber("minimum_health",
                        "Pause combat below this many health points, evade hazards and recover before "
                                + "rebuilding the combat subtask. Defaults to 10.",
                        1.0, DragonFightTaskRecord.MAXIMUM_HEALTH_SETTING)
                .optionalStringArray("protected_labels",
                        "Remembered places whose nearby blocks and entities must not be altered or "
                                + "exploded by this encounter.",
                        DragonFightTaskRecord.MAX_PROTECTED_LABELS)
                .build();
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
        if (protectedLabels.size() > DragonFightTaskRecord.MAX_PROTECTED_LABELS)
            throw new IllegalArgumentException(
                    "protected_labels accepts at most "
                            + DragonFightTaskRecord.MAX_PROTECTED_LABELS + " values");

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
}
