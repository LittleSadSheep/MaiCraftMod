// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonObject;
import org.maiwithu.maicraft.core.task.collect.CollectItemsRequest;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.maiwithu.maicraft.client.chat.ChatMessage;
import org.maiwithu.maicraft.core.task.suicide.SuicideRequest;
import org.maiwithu.maicraft.core.integration.create.CreateManualInput;

/** 检查目标使用了已声明的能力、参数名和目标类型；大多数参数的具体值仍交给各能力自己检查。 */
final class SemanticGoalContract {

    private static final String SEQUENCE = "maicraft:sequence";

    private SemanticGoalContract() {}

    static void validate(Goal goal, Set<String> knownAbilities) {
        validate(goal, knownAbilities, "goal", false, false);
    }

    /** 旧等待、取物和烹饪参数曾被宽松接收；保留历史供查询、取消和修订，重新执行仍须通过当前检查。 */
    static void validateRestored(Goal goal, Set<String> knownAbilities) {
        validate(goal, knownAbilities, "goal", true, false);
    }

    private static void validate(Goal goal, Set<String> knownAbilities, String path, boolean restoredHistory,
                                 boolean parentIsSequence) {
        // 不认识的能力或参数名立即报错，错误中带完整位置，方便调用者找到需要修改的字段。
        String ability = goal.ability();
        if (!knownAbilities.contains(ability)) {
            throw violation("unknown_ability", path + ".ability", ability,
                    "Unknown semantic ability '" + ability + "'.");
        }
        validateOnFailure(goal, path, ability, parentIsSequence);

        validateObjectKeys(goal.parameters(), withRuntimeAuthorizationKeys(
                        SemanticAbilityCatalog.parameterNames(ability)),
                path + ".parameters", ability, "unknown_parameter");
        validateProtectedLabels(goal.parameters(), path + ".parameters", ability);
        validateObjectKeys(goal.preferences(), withRuntimeAuthorizationKeys(
                        SemanticAbilityCatalog.preferenceNames(ability)),
                path + ".preferences", ability, "unknown_preference");
        // 取物数量、来源与地点要求在接单前说明白，不能接管角色后再忽略或改写请求。
        if (!restoredHistory && AcquireAbilityAdapter.ABILITY.equals(ability)) {
            try { AcquireAbilityAdapter.validate(goal); }
            catch (IllegalArgumentException invalid) {
                throw violation("invalid_acquisition_contract", path, ability, invalid.getMessage());
            }
        }
        if (!restoredHistory && CookAbilityAdapter.ABILITY.equals(ability)) {
            try { CookAbilityAdapter.validate(goal); }
            catch (IllegalArgumentException invalid) {
                throw violation("invalid_cooking_contract", path, ability, invalid.getMessage());
            }
        }
        // 接单前检查物品堆引用及类型筛选，拒绝空数组和错字，不能把指定拾取放宽成全捡。
        if (!restoredHistory && GeneralAbilityAdapter.COLLECT.equals(ability)) {
            try { CollectItemsRequest.parse(goal.parameters()); }
            catch (IllegalArgumentException invalid) {
                throw violation("invalid_collection_contract", path + ".parameters", ability, invalid.getMessage());
            }
        }
        validateTarget(goal, path, ability, restoredHistory);
        validateConstraints(goal, path, ability);
        // 起飞前就检查局部补丁和工况，防止执行中把观察请求误当成开桨或建造。
        if (PhysicsAbilityAdapter.ABILITY.equals(ability)) PhysicsAbilityAdapter.validate(goal);
        // 在角色接管前确认 FTB 对象编号与动作种类，不接受混用任务、奖励或自行编写点击序列。
        if (QuestAbilityAdapter.ABILITY.equals(ability)) QuestAbilityAdapter.validate(goal);
        if (ChatAbilityAdapter.ABILITY.equals(ability)) {
            try { ChatMessage.parse(goal.parameters()); }
            catch (IllegalArgumentException invalid) {
                throw violation("invalid_chat_contract", path + ".parameters", ability, invalid.getMessage());
            }
        }
        // 主动寻死的方式与预算必须在接管身体前确定，不能默默放宽未知参数。
        if (SuicideAbilityAdapter.ABILITY.equals(ability)) {
            try { SuicideRequest.parse(goal.parameters()); }
            catch (IllegalArgumentException invalid) {
                throw violation("invalid_suicide_contract", path + ".parameters", ability, invalid.getMessage());
            }
        }
        // 等待的时长和条件在接单前确定，不能等到角色已经暂停干活才发现参数被误读。
        if (!restoredHistory && WaitAbilityAdapter.ABILITY.equals(ability)) {
            try { WaitAbilityAdapter.validate(goal); }
            catch (IllegalArgumentException invalid) {
                throw violation("invalid_wait_contract", path + ".parameters", ability, invalid.getMessage());
            }
        }
        // 附魔成本必须在计划阶段明确；菜单按钮与槽位仍由执行器根据真实报价解析。
        if (EnchantAbilityAdapter.ABILITY.equals(ability)) {
            try { EnchantAbilityAdapter.validate(goal); }
            catch (IllegalArgumentException invalid) { throw violation("invalid_enchant_contract", path + ".parameters", ability, invalid.getMessage()); }
        }
        // 切石的输入产物与次数必须在计划阶段明确；配方与菜单仍由执行器根据真实观察解析。
        if (StonecutAbilityAdapter.ABILITY.equals(ability)) {
            try { StonecutAbilityAdapter.validate(goal); }
            catch (IllegalArgumentException invalid) { throw violation("invalid_stonecut_contract", path + ".parameters", ability, invalid.getMessage()); }
        }
        if ("maicraft:build".equals(ability) || BuildDesignAdapter.ABILITY.equals(ability)) {
            if (BuildingSceneContract.supports(goal)) BuildingSceneContract.validate(goal);
            // 普通续建只引用已冻结的蓝图，不能夹带新尺寸、材质或其他设计参数重新生成建筑。
            else if (goal.parameters().has("project_id")) {
                UUID.fromString(BuildingSceneContract.string(goal.parameters(), "project_id"));
                for (String key : goal.parameters().keySet())
                    if (!Set.of("project_id", "protected_labels").contains(key))
                        throw violation("invalid_build_resume", path + ".parameters", ability,
                                "project_id resumes frozen geometry and materials; other design parameters are not accepted");
            } else {
                // 只有自然语言用途不能开工或预览；先让 LLM 明确设计，角色不会自行找地、换料或套用房屋形体。
                throw violation("missing_build_blueprint", path + ".parameters", ability,
                        "Building requires scene, scene_id or blueprint; use project_id only to resume a frozen blueprint. "
                                + "Natural-language outcomes do not generate geometry.");
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
        if (GeneralAbilityAdapter.INTERACT.equals(ability)) {
            // 编译前就拒绝无穷、负数和非数值持续时间，不等到已经走到曲柄旁才发现请求无法执行。
            try { CreateManualInput.durationTicks(goal.parameters()); }
            catch (IllegalArgumentException invalid) {
                throw violation("invalid_interaction_duration", path + ".parameters", ability, invalid.getMessage());
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
            validate(goal.children().get(i), knownAbilities, path + ".children[" + i + "]", restoredHistory,
                    SEQUENCE.equals(ability));
        }
    }

    /** on_failure 只表达“这一步失败后兄弟步骤继续”，不是能力的业务参数，也只对 sequence 直接子级有意义。 */
    private static void validateOnFailure(Goal goal, String path, String ability, boolean parentIsSequence) {
        if ("stop".equals(goal.onFailure())) return;
        if (!"continue".equals(goal.onFailure())) {
            throw violation("invalid_on_failure", path + ".on_failure", ability,
                    "on_failure accepts \"stop\" (default) or \"continue\".");
        }
        if (!parentIsSequence) {
            throw violation("on_failure_not_allowed", path + ".on_failure", ability,
                    "on_failure is accepted only on direct children of maicraft:sequence; "
                            + "tolerated failures are declared per step, not per task.");
        }
        if (SEQUENCE.equals(ability)) {
            // 嵌套 sequence 会被摊平成扁平清单，“整个内层失败后外层继续”需要分组语义支撑，尚未提供。
            throw violation("on_failure_nested_sequence", path + ".on_failure", ability,
                    "on_failure is not accepted on a sequence child; flatten the inner steps and declare "
                            + "on_failure on the first inner leaf instead.");
        }
    }

    // goal 顶层字段被误嵌进 parameters/preferences 是高频提交错误（实测 build 的 target 曾整会话反复试错）；
    // 拒绝时点名正确位置，省一轮改写重发。
    private static final Set<String> GOAL_LEVEL_FIELDS =
            Set.of("ability", "outcome", "target", "constraints", "children", "parameters", "preferences",
                    "on_failure");

    /**
     * 运行时级死亡自恢复授权键：由 GameplayAttentionMonitor 在死亡时读取，任何能力都可携带。
     * 不进各能力的参数表——授权属于任务意图而非某个能力的工序参数；能力发现入口同步展示这份名单。
     */
    private static final Set<String> RUNTIME_AUTHORIZATION_KEYS =
            Set.of("auto_respawn", "recover_after_death");

    private static Set<String> withRuntimeAuthorizationKeys(Set<String> declared) {
        Set<String> merged = new HashSet<>(declared);
        merged.addAll(RUNTIME_AUTHORIZATION_KEYS);
        return Collections.unmodifiableSet(merged);
    }

    private static void validateObjectKeys(
            JsonObject values, Set<String> allowed, String path, String ability, String code) {
        for (String key : values.keySet()) {
            if (!allowed.contains(key)) {
                throw violation(code, path + "." + key, ability,
                        ability + " does not declare '" + key + "' at " + path
                                + "; the Mod refused to ignore it." + goalLevelHint(key, path));
            }
        }
    }

    private static String goalLevelHint(String key, String path) {
        if (!GOAL_LEVEL_FIELDS.contains(key)) return "";
        return " '" + key + "' is a goal-level field: write it at goal." + key + ", not inside " + path + ".";
    }

    private static void validateTarget(Goal goal, String path, String ability, boolean restoredHistory) {
        Goal.SemanticTarget target = goal.target();
        if (target == null) return;
        String kind = target.kind();
        // 旧版声明支持这些地点却没有执行；保留原请求供审阅，重新启动时由取物适配器明确拒绝。
        if (restoredHistory && AcquireAbilityAdapter.ABILITY.equals(ability) && kind != null
                && Set.of("nearest", "area", "landmark", "prior_result").contains(kind)) return;
        if (restoredHistory && CookAbilityAdapter.ABILITY.equals(ability) && kind != null
                && Set.of("nearest", "prior_result").contains(kind)) return;
        Set<String> accepted = SemanticAbilityCatalog.targetKinds(ability);
        if (kind == null || !accepted.contains(kind)) {
            // 地点填错时一次给出允许值；建造直接沿用场地观察，避免角色反复试探当前位置、最近地点和区域。
            String correction = MachineAbilityAdapter.BUILD.equals(ability)
                    ? " Copy target and snapshot_id from the same perceive(view=construction_site) result; reuse it if already observed."
                    : " Read perceive(view=abilities,focus=" + ability + ") for this ability's fields.";
            throw violation("unsupported_target_kind", path + ".target.kind", ability,
                    ability + " does not accept target kind '" + kind + "'. Accepted target kinds: "
                            + accepted.stream().sorted().toList() + "." + correction);
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
