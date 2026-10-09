// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.tool;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.maiwithu.maicraft.kernel.goal.Permissions;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * 读目标里的 permissions：这次任务允许角色改变世界到什么程度。没写的字段用默认值，
 * 默认值就是"像正常玩家一样"；这不是游戏或服务器的 OP 权限。
 */
final class PermissionsReader {
    private static final List<String> FIELDS = List.of(
            "change_blocks", "fight", "use_rare_items", "kill_animals", "survival_needs", "protected_landmarks");

    private PermissionsReader() {}

    static Permissions read(JsonElement raw, String path, RequestCheck check) {
        if (!raw.isJsonObject()) {
            check.error(path, "permissions 应该是一个对象", "{\"fight\": \"self_defense\"}");
            return Permissions.DEFAULT;
        }
        JsonObject object = raw.getAsJsonObject();
        check.rejectUnknownFields(object, path, FIELDS);
        Permissions fallback = Permissions.DEFAULT;
        Boolean rare = check.bool(object, "use_rare_items", path + ".use_rare_items");
        List<String> protectedLandmarks = check.texts(object, "protected_landmarks", path + ".protected_landmarks");
        return new Permissions(
                choice(object, "change_blocks", path, Permissions.BlockChanges.values(), fallback.changeBlocks(), check),
                choice(object, "fight", path, Permissions.Fight.values(), fallback.fight(), check),
                rare == null ? fallback.useRareItems() : rare,
                choice(object, "kill_animals", path, Permissions.AnimalKilling.values(), fallback.killAnimals(), check),
                survivalNeeds(object, path, fallback.survivalNeeds(), check),
                protectedLandmarks == null ? fallback.protectedLandmarks() : new HashSet<>(protectedLandmarks));
    }

    private static <E extends Enum<E>> E choice(JsonObject object, String key, String path, E[] values,
                                                E fallback, RequestCheck check) {
        List<String> names = Arrays.stream(values).map(value -> value.name().toLowerCase(Locale.ROOT)).toList();
        String chosen = check.choice(object, key, path + "." + key, names);
        return chosen == null ? fallback : values[names.indexOf(chosen)];
    }

    /** on / off；写成 true / false 时按开 / 关处理并说明，LLM 常把开关写成布尔值。 */
    private static Permissions.SurvivalNeeds survivalNeeds(JsonObject object, String path,
                                                           Permissions.SurvivalNeeds fallback, RequestCheck check) {
        JsonElement element = object.get("survival_needs");
        String field = path + ".survival_needs";
        if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean()) {
            boolean on = element.getAsBoolean();
            check.note(field + "：" + on + " 按 " + (on ? "on" : "off") + " 处理");
            return on ? Permissions.SurvivalNeeds.ON : Permissions.SurvivalNeeds.OFF;
        }
        return choice(object, "survival_needs", path, Permissions.SurvivalNeeds.values(), fallback, check);
    }
}
