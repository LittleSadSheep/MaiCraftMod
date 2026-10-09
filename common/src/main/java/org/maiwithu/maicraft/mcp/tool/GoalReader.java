// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.maiwithu.maicraft.game.ModIdentity;
import org.maiwithu.maicraft.kernel.ability.AbilityModule;
import org.maiwithu.maicraft.kernel.ability.AbilityRegistry;
import org.maiwithu.maicraft.kernel.goal.Goal;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.param.Param;
import org.maiwithu.maicraft.kernel.param.ParamError;
import org.maiwithu.maicraft.kernel.param.ParseResult;
import org.maiwithu.maicraft.kernel.param.Params;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 把 execute 收到的 goal 读成目标：一次查出全部问题，宽松写法按规矩整理并在 notes 里说明。
 *
 * <p>能力名可以省略命名空间（sleep 按本 Mod 的 sleep 处理）；能力的参数写到了 goal 这一层时，
 * 挪进 parameters 再读。能力不认识时其余部分照样检查写法，一次把能查的都报出来。
 */
public final class GoalReader {
    private static final List<String> FIELDS =
            List.of("ability", "purpose", "target", "parameters", "permissions", "steps", "on_failure");
    /** sequence 里还能再套 sequence，但套太深多半是写错了。 */
    private static final int MAX_DEPTH = 3;

    private final AbilityRegistry registry;

    public GoalReader(AbilityRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /** 读一个目标；路径前缀是 {@code goal}。 */
    public Reading read(JsonElement raw) {
        RequestCheck check = new RequestCheck();
        Goal goal = readGoal(raw, "goal", 0, null, check);
        return new Reading(check.ok() ? goal : null, check.code(), check.errors(), check.notes());
    }

    /** @param inherited sequence 里的一步时是整件事的许可，步骤没写的字段沿用它；最外层为 null */
    private Goal readGoal(JsonElement raw, String path, int depth, Permissions inherited, RequestCheck check) {
        if (raw == null || !raw.isJsonObject()) {
            check.error(path, "目标应该是一个对象", "{\"ability\": \"…\", \"parameters\": {…}}");
            return null;
        }
        JsonObject object = raw.getAsJsonObject().deepCopy();
        AbilityModule module = ability(object, path, check);
        moveMisplacedParameters(object, path, module, check);
        check.rejectUnknownFields(object, path, FIELDS);
        String purpose = check.text(object, "purpose", path + ".purpose", false);
        Target target = present(object, "target")
                ? TargetReader.read(object.get("target"), path + ".target",
                module == null ? null : module.spec().targets(), check)
                : null;
        Params params = parameters(object, path, module, check);
        Permissions permissions = present(object, "permissions")
                ? PermissionsReader.read(object.get("permissions"), path + ".permissions", inherited, check)
                : inherited;
        List<Goal> steps = steps(object, path, depth, module, permissions, check);
        String onFailure = check.choice(object, "on_failure", path + ".on_failure", List.of("stop", "continue"));
        if (module == null) {
            return null;
        }
        return new Goal(module.spec().id(), purpose, target, params, permissions, steps,
                onFailure == null ? null : Goal.OnFailure.valueOf(onFailure.toUpperCase(Locale.ROOT)));
    }

    /** 读能力名并找到能力；找不到时报"没有这个能力"并附上相近的名字。 */
    private AbilityModule ability(JsonObject object, String path, RequestCheck check) {
        String raw = check.text(object, "ability", path + ".ability", true);
        if (raw == null) return null;
        String id = raw.toLowerCase(Locale.ROOT);
        if (!id.contains(":")) {
            id = ModIdentity.MOD_ID + ":" + id;
            check.note(path + ".ability：\"" + raw + "\" 按 \"" + id + "\" 处理");
        }
        AbilityModule module = registry.find(id).orElse(null);
        if (module == null) {
            check.markUnknownAbility();
            check.error(path + ".ability", "没有能力 " + raw, SimilarAbilities.hint(registry, id));
        }
        return module;
    }

    /** 能力的参数写到了 goal 这一层：挪进 parameters，并说明做过这样的整理。 */
    private static void moveMisplacedParameters(JsonObject object, String path, AbilityModule module, RequestCheck check) {
        if (module == null) return;
        List<String> paramNames = module.spec().params().params().stream().map(Param::name).toList();
        for (String key : List.copyOf(object.keySet())) {
            if (FIELDS.contains(key) || !paramNames.contains(key)) continue;
            JsonElement parameters = object.get("parameters");
            if (parameters != null && !parameters.isJsonObject()) return;
            if (parameters == null) {
                parameters = new JsonObject();
                object.add("parameters", parameters);
            }
            if (parameters.getAsJsonObject().has(key)) continue;
            parameters.getAsJsonObject().add(key, object.remove(key));
            check.note(path + "." + key + " 按 " + path + ".parameters." + key + " 处理");
        }
    }

    /** 按能力的参数规格读 parameters；能力不认识时只检查它是不是对象。 */
    private static Params parameters(JsonObject object, String path, AbilityModule module, RequestCheck check) {
        JsonElement raw = object.get("parameters");
        if (raw != null && !raw.isJsonNull() && !raw.isJsonObject()) {
            check.error(path + ".parameters", "parameters 应该是一个对象", "{\"count\": 8}");
            return null;
        }
        if (module == null) return null;
        ParseResult parsed = module.spec().params().parse(raw == null || raw.isJsonNull() ? null : raw.getAsJsonObject());
        for (ParamError error : parsed.errors()) {
            check.error(path + ".parameters." + error.field(), error.message(), error.expected());
        }
        for (String note : parsed.notes()) {
            check.note(path + ".parameters." + note);
        }
        return parsed.params();
    }

    /**
     * steps 只交给按顺序做几件事的能力；每一步本身是一个完整的目标。
     * 整件事开始时说好的许可每一步都照样守：步骤没写的许可字段沿用整件事的。
     */
    private List<Goal> steps(JsonObject object, String path, int depth, AbilityModule module,
                             Permissions permissions, RequestCheck check) {
        JsonElement raw = object.get("steps");
        boolean takesSteps = module != null && module.acceptsSteps();
        if (raw == null || raw.isJsonNull()) {
            if (takesSteps) check.error(path + ".steps", "要列出按顺序做的每一步", "[{\"ability\": \"…\"}, …]");
            return List.of();
        }
        if (module != null && !takesSteps) {
            check.error(path + ".steps", "能力 " + module.spec().id() + " 不接受 steps",
                    "按顺序做几件事用 sequence 能力");
            return List.of();
        }
        if (!raw.isJsonArray() || raw.getAsJsonArray().isEmpty()) {
            check.error(path + ".steps", "steps 应该是至少有一步的列表", "[{\"ability\": \"…\"}, …]");
            return List.of();
        }
        if (depth + 1 > MAX_DEPTH) {
            check.error(path + ".steps", "步骤套得太深（最多 " + MAX_DEPTH + " 层）", "把步骤摊平成一层");
            return List.of();
        }
        List<Goal> steps = new ArrayList<>();
        JsonArray array = raw.getAsJsonArray();
        for (int i = 0; i < array.size(); i++) {
            Goal step = readGoal(array.get(i), path + ".steps[" + i + "]", depth + 1, permissions, check);
            if (step != null) steps.add(step);
        }
        return steps;
    }

    private static boolean present(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull();
    }

    /**
     * 读一个目标的结果。
     *
     * @param goal   读出来的目标；有错误时为 null
     * @param code   有错误时报哪种错（能力名写错时是 unknown_ability，其余是 invalid_parameter）
     * @param errors 全部写错的地方
     * @param notes  做过的规范化
     */
    public record Reading(Goal goal, ErrorCode code, List<FieldError> errors, List<String> notes) {
        public Reading {
            errors = List.copyOf(errors);
            notes = List.copyOf(notes);
        }

        public boolean ok() {
            return errors.isEmpty();
        }
    }
}
