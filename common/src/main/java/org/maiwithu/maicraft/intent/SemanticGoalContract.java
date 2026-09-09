// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonObject;

import java.util.Set;

/** 检查目标使用了已声明的能力、参数名和目标类型；大多数参数的具体值仍交给各能力自己检查。 */
final class SemanticGoalContract {

    private static final String SEQUENCE = "maicraft:sequence";

    private SemanticGoalContract() {}

    static void validate(Goal goal, Set<String> knownAbilities) {
        validate(goal, knownAbilities, "goal");
    }

    private static void validate(Goal goal, Set<String> knownAbilities, String path) {
        // 不认识的能力或参数名立即报错，错误中带完整位置，方便调用者找到需要修改的字段。
        String ability = goal.ability();
        if (!knownAbilities.contains(ability)) {
            throw violation("unknown_ability", path + ".ability", ability,
                    "Unknown semantic ability '" + ability + "'.");
        }

        validateObjectKeys(goal.parameters(), SemanticAbilityCatalog.parameterNames(ability),
                path + ".parameters", ability, "unknown_parameter");
        validateProtectedLabels(goal.parameters(), path + ".parameters", ability);
        validateObjectKeys(goal.preferences(), SemanticAbilityCatalog.preferenceNames(ability),
                path + ".preferences", ability, "unknown_preference");
        validateTarget(goal, path, ability);
        validateConstraints(goal, path, ability);
        if ("maicraft:build".equals(ability) || BuildDesignAdapter.ABILITY.equals(ability)) {
            // 建造功能名单在计划阶段就检查；不能先承诺有阳台等功能，施工时再悄悄忽略。
            var parameters = goal.parameters();
            if (parameters.has("features")) {
                var features = parameters.get("features");
                if (!features.isJsonArray() || features.getAsJsonArray().asList().stream().anyMatch(value ->
                        !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                                || value.getAsString().isBlank()))
                    throw violation("invalid_build_features", path + ".parameters.features", ability,
                            "features must be an array of supported semantic feature names.");
                var unsupported = new java.util.LinkedHashSet<>(SemanticBuildPlanner.normalizedFeatures(features));
                unsupported.removeAll(SemanticBuildPlanner.SUPPORTED_FEATURES);
                if (!unsupported.isEmpty()) throw violation("unsupported_build_features",
                        path + ".parameters.features", ability, "Unsupported semantic build features: " + unsupported);
            }
        }
        if ("maicraft:travel".equals(ability)) {
            // 移动额外检查坐标与到达误差；这部分会读值，不只是检查参数名字。
            try {
                TravelDestination.validatePrecision(goal.parameters());
                TravelDestination.fromGoal(goal);
            } catch (IllegalArgumentException invalid) {
                throw violation("invalid_travel_contract", path + ".parameters", ability, invalid.getMessage());
            }
        }
        if (MachineAbilityAdapter.supports(ability)) {
            try {
                MachineAbilityAdapter.validate(goal);
            } catch (IllegalArgumentException invalid) {
                throw violation("invalid_machine_contract", path + ".parameters", ability, invalid.getMessage());
            }
        }

        if (SEQUENCE.equals(ability)) {
            // sequence 只表示依次做子目标，不能另外指定一个总目的地；偏好和限制放到相关子目标上。
            if (goal.target() != null) {
                throw violation("sequence_target_not_allowed", path + ".target", ability,
                        "maicraft:sequence accepts ordered children only; target is not allowed.");
            }
            if (!goal.preferences().entrySet().isEmpty()) {
                throw violation("sequence_preferences_not_allowed", path + ".preferences", ability,
                        "maicraft:sequence accepts ordered children only; preferences are not allowed.");
            }
            if (!goal.constraints().isEmpty()) {
                throw violation("sequence_constraints_not_allowed", path + ".constraints", ability,
                        "maicraft:sequence accepts ordered children only; put a supported hard constraint on the relevant child.");
            }
            if (goal.children().isEmpty()) {
                throw violation("sequence_children_required", path + ".children", ability,
                        "maicraft:sequence needs at least one semantic child goal.");
            }
        } else if (!goal.children().isEmpty()) {
            throw violation("children_not_allowed", path + ".children", ability,
                    ability + " does not accept child goals; use maicraft:sequence for ordered outcomes.");
        }

        for (int i = 0; i < goal.children().size(); i++) {
            // 组合目标的每个子目标也要经过同样检查，不能把不合法参数藏到子步骤里。
            validate(goal.children().get(i), knownAbilities, path + ".children[" + i + "]");
        }
    }

    private static void validateObjectKeys(
            JsonObject values, Set<String> allowed, String path, String ability, String code) {
        for (String key : values.keySet()) {
            if (!allowed.contains(key)) {
                throw violation(code, path + "." + key, ability,
                        ability + " does not declare '" + key + "' at " + path
                                + "; the Mod refused to ignore it.");
            }
        }
    }

    private static void validateTarget(Goal goal, String path, String ability) {
        Goal.SemanticTarget target = goal.target();
        if (target == null) return;
        String kind = target.kind();
        if (kind == null || !SemanticAbilityCatalog.targetKinds(ability).contains(kind)) {
            throw violation("unsupported_target_kind", path + ".target.kind", ability,
                    ability + " does not accept target kind '" + kind + "'.");
        }
    }

    private static void validateProtectedLabels(
            JsonObject parameters, String path, String ability) {
        // 要保护的地点必须是一组非空名字；这里只查格式，不在这里查地点是否已经记住。
        if (!parameters.has("protected_labels")) return;
        if (!parameters.get("protected_labels").isJsonArray()) {
            throw violation("invalid_protected_labels", path + ".protected_labels", ability,
                    "protected_labels must be an array of remembered semantic labels.");
        }
        for (int index = 0; index < parameters.getAsJsonArray("protected_labels").size(); index++) {
            var value = parameters.getAsJsonArray("protected_labels").get(index);
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                    || value.getAsString().isBlank()) {
                throw violation("invalid_protected_label",
                        path + ".protected_labels[" + index + "]", ability,
                        "Each protected label must be a non-empty semantic name, never coordinates.");
            }
        }
    }

    private static void validateConstraints(Goal goal, String path, String ability) {
        // 当前只允许能力声明过、无需附加参数的硬性条件；没有真正支持的条件就拒绝，不假装会遵守。
        Set<String> allowed = SemanticAbilityCatalog.hardConstraintKinds(ability);
        for (int i = 0; i < goal.constraints().size(); i++) {
            Goal.Constraint constraint = goal.constraints().get(i);
            String constraintPath = path + ".constraints[" + i + "]";
            if (!constraint.hard()) {
                throw violation("soft_constraint_not_supported", constraintPath + ".hard", ability,
                        "Soft constraints are not executable contracts; use a declared parameter or remove it.");
            }
            if (!constraint.parametersJson().equals("{}")) {
                throw violation("constraint_parameters_not_supported", constraintPath + ".parameters", ability,
                        "Constraint '" + constraint.kind() + "' does not accept parameters.");
            }
            if (constraint.kind() == null || !allowed.contains(constraint.kind())) {
                throw violation("unknown_constraint", constraintPath + ".kind", ability,
                        ability + " does not declare hard constraint '" + constraint.kind()
                                + "'; the Mod refused to promise behavior it cannot prove.");
            }
        }
    }

    private static SemanticContractException violation(
            String code, String path, String ability, String message) {
        return new SemanticContractException(code, path, ability, message);
    }
}
