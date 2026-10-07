// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

import java.util.ArrayList;
import java.util.List;

/**
 * 直播实测里宿主把 -86、1、true 写成字符串，把数组包成 {"item":[...]}，同一个拉杆、取料、走坐标被拒收几十次。
 * 本回归锁住：语义唯一的编码按契约类型还原后通过原有契约检查；无法唯一还原的值仍被如实拒绝。
 */
public final class ParameterNormalizerTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        restoresAe2SupplyScalars();
        restoresItemWrappedArrays();
        restoresTravelDestinationAndTargetPosition();
        movesDeclaredParameterIntoParameters();
        keepsUnrestorableValuesForRejection();
        keepsStringFieldsUntouched();
        restoresNestedBlueprintCoordinates();
        restoresConstraintAndRuntimeKeys();
        System.out.println("ParameterNormalizerTest OK: host encodings restored by declared contract types");
    }

    private static void restoresAe2SupplyScalars() {
        // AE 取一块铁板：count 与两个授权布尔都被写成字符串，还原后机器契约应直接受理。
        JsonObject goal = goal("""
                {"ability":"maicraft:operate_machine","outcome":"从AE2终端取一块铁板","target":{"kind":"nearest"},
                 "parameters":{"operation":"ae2_supply","item_id":"minecraft:iron_ingot","count":"1",
                 "allow_use":"true","allow_crafting":"false"}}""");
        List<String> notes = normalize(goal);
        JsonObject parameters = goal.getAsJsonObject("parameters");
        check(parameters.get("count").getAsJsonPrimitive().isNumber(), "count restored to integer");
        check(parameters.get("allow_use").getAsJsonPrimitive().isBoolean(), "allow_use restored to boolean");
        check(!parameters.get("allow_crafting").getAsBoolean(), "allow_crafting keeps false");
        check(notes.size() == 3, "three restorations reported: " + notes);
        validate(goal);
    }

    private static void restoresItemWrappedArrays() {
        // 找方块：block_ids 被包成 {"item":[...]}、count 与距离是字符串；取物来源是包裹的单个字符串。
        JsonObject find = goal("""
                {"ability":"maicraft:find_block","outcome":"找铁矿","target":{"kind":"current_place"},
                 "parameters":{"block_ids":{"item":["minecraft:iron_ore","minecraft:deepslate_iron_ore"]},
                 "purpose":"blocks","count":"6","max_distance":"40"}}""");
        normalize(find);
        check(find.getAsJsonObject("parameters").get("block_ids").isJsonArray(), "block_ids unwrapped to array");
        check(find.getAsJsonObject("parameters").getAsJsonArray("block_ids").size() == 2, "both block ids kept");
        validate(find);
        JsonObject acquire = goal("""
                {"ability":"maicraft:acquire_items","outcome":"取铁锭",
                 "parameters":{"item_id":"minecraft:iron_ingot","count":"4","allowed_sources":{"item":"wireless"}}}""");
        normalize(acquire);
        check(acquire.getAsJsonObject("parameters").getAsJsonArray("allowed_sources").size() == 1,
                "single wrapped source becomes a one-item array");
        validate(acquire);
    }

    private static void restoresTravelDestinationAndTargetPosition() {
        // 走到坐标：destination 的 x/z 与坐标目标的 x/y/z 都只还原整数写法。
        JsonObject travel = goal("""
                {"ability":"maicraft:travel","outcome":"走到蜂房旁",
                 "parameters":{"destination":{"x":"-82","z":"24"}}}""");
        normalize(travel);
        check(travel.getAsJsonObject("parameters").getAsJsonObject("destination").get("x").getAsInt() == -82,
                "destination.x restored");
        validate(travel);
        JsonObject interact = goal("""
                {"ability":"maicraft:interact","outcome":"右键拉杆",
                 "target":{"kind":"coordinates","position":{"x":"-86","y":"106","z":"28"}},
                 "parameters":{"block_id":"minecraft:lever"}}""");
        normalize(interact);
        check(interact.getAsJsonObject("target").getAsJsonObject("position").get("y").getAsInt() == 106,
                "target.position.y restored");
        validate(interact);
    }

    private static void movesDeclaredParameterIntoParameters() {
        // count 写在 goal 顶层：该能力声明了 count 且 parameters 里没有同名值，才搬进 parameters。
        JsonObject goal = goal("""
                {"ability":"maicraft:acquire_items","outcome":"取四个铁锭","count":"4",
                 "parameters":{"item_id":"minecraft:iron_ingot"}}""");
        List<String> notes = normalize(goal);
        check(!goal.has("count"), "goal-level count removed");
        check(goal.getAsJsonObject("parameters").get("count").getAsInt() == 4, "count moved and restored");
        check(notes.stream().anyMatch(note -> note.contains("goal.count -> goal.parameters.count")), "move reported");
        // 两处同名时不替模型选择，原样留给入口拒绝。
        JsonObject conflict = goal("""
                {"ability":"maicraft:acquire_items","outcome":"取铁锭","count":3,
                 "parameters":{"item_id":"minecraft:iron_ingot","count":4}}""");
        normalize(conflict);
        check(conflict.has("count"), "conflicting goal-level count stays for rejection");
    }

    private static void keepsUnrestorableValuesForRejection() {
        // "yes" 与 "1.5" 没有唯一的整数/布尔含义，保持原样，由机器契约拒绝。
        JsonObject goal = goal("""
                {"ability":"maicraft:operate_machine","outcome":"取料","target":{"kind":"nearest"},
                 "parameters":{"operation":"ae2_supply","item_id":"minecraft:iron_ingot","count":"1.5","allow_use":"yes"}}""");
        normalize(goal);
        check(goal.getAsJsonObject("parameters").get("allow_use").getAsJsonPrimitive().isString(), "yes stays a string");
        check(goal.getAsJsonObject("parameters").get("count").getAsJsonPrimitive().isString(), "1.5 stays a string");
        boolean rejected = false;
        try { validate(goal); } catch (SemanticContractException expected) { rejected = true; }
        check(rejected, "unrestorable values are still rejected by the contract");
    }

    private static void keepsStringFieldsUntouched() {
        // 字符串字段即使内容是数字也不改，避免把标签 "123" 变成数字。
        JsonObject goal = goal("""
                {"ability":"maicraft:inspect_machine","outcome":"检视","target":{"kind":"current_place"},
                 "parameters":{"label":"123","mode":"full","radius":"4"}}""");
        normalize(goal);
        check(goal.getAsJsonObject("parameters").get("label").getAsJsonPrimitive().isString(), "label stays a string");
        check(goal.getAsJsonObject("parameters").get("radius").getAsJsonPrimitive().isNumber(), "radius restored");
    }

    private static void restoresNestedBlueprintCoordinates() {
        // 显式蓝图里的版本号、偏移三元组、传送带端点和带轮列表都被写成字符串时，按坐标逐层还原；
        // 方块状态值本来就是字符串，含小数的"三元组"也不是方块坐标，两者都保持原样交给施工解析。
        JsonObject goal = goal("""
                {"ability":"maicraft:design_machine","outcome":"审阅置物台与传送带",
                 "parameters":{"blueprint":{"schema_version":"1",
                   "blocks":[{"offset":["0","1","-2"],"block_id":"minecraft:stone",
                              "properties":{"count":"2","powered":"true"}},
                             {"offset":["0.5","1","0"],"block_id":"minecraft:dirt"}],
                   "assembly":{"installations":[{"type":"create:belt","first":["0","0","0"],"second":[2,"0",0],
                                                 "pulleys":[["1","0","0"]]}]}}}}""");
        normalize(goal);
        JsonObject blueprint = goal.getAsJsonObject("parameters").getAsJsonObject("blueprint");
        check(blueprint.get("schema_version").getAsJsonPrimitive().isNumber(), "schema_version restored");
        JsonObject first = blueprint.getAsJsonArray("blocks").get(0).getAsJsonObject();
        check(first.getAsJsonArray("offset").get(2).getAsJsonPrimitive().isNumber()
                && first.getAsJsonArray("offset").get(2).getAsInt() == -2, "offset triple restored");
        check(first.getAsJsonObject("properties").get("count").getAsJsonPrimitive().isString()
                && first.getAsJsonObject("properties").get("powered").getAsJsonPrimitive().isString(),
                "block-state properties stay serialized strings");
        check(blueprint.getAsJsonArray("blocks").get(1).getAsJsonObject().getAsJsonArray("offset").get(0)
                .getAsJsonPrimitive().isString(), "fractional triple is not treated as block coordinates");
        JsonObject belt = blueprint.getAsJsonObject("assembly").getAsJsonArray("installations").get(0).getAsJsonObject();
        check(belt.getAsJsonArray("second").get(1).getAsJsonPrimitive().isNumber(), "mixed belt endpoint restored");
        check(belt.getAsJsonArray("pulleys").get(0).getAsJsonArray().get(0).getAsJsonPrimitive().isNumber(),
                "pulley triple inside list restored");
    }

    private static void restoresConstraintAndRuntimeKeys() {
        // 约束 hard 与死亡自恢复授权键都只有真假含义：parameters 与 preferences 两处的 "true"/"false" 都还原为布尔。
        JsonObject goal = goal("""
                {"ability":"maicraft:acquire_items","outcome":"取铁锭",
                 "parameters":{"item_id":"minecraft:iron_ingot","auto_respawn":"true"},
                 "preferences":{"recover_after_death":"false"},
                 "constraints":[{"kind":"maicraft:keep_area","description":"不碰仓库","hard":"false"}]}""");
        List<String> notes = normalize(goal);
        check(goal.getAsJsonObject("parameters").get("auto_respawn").getAsJsonPrimitive().isBoolean(),
                "auto_respawn in parameters restored");
        check(!goal.getAsJsonObject("preferences").get("recover_after_death").getAsBoolean()
                && goal.getAsJsonObject("preferences").get("recover_after_death").getAsJsonPrimitive().isBoolean(),
                "recover_after_death in preferences restored to false");
        check(!goal.getAsJsonArray("constraints").get(0).getAsJsonObject().get("hard").getAsBoolean(),
                "constraint hard restored to false");
        check(notes.size() == 3, "three restorations reported: " + notes);
        // 顺序任务根只收 protected_labels 与运行时键，根上的字符串布尔同样还原。
        JsonObject sequence = goal("""
                {"ability":"maicraft:sequence","outcome":"先取料再返回","parameters":{"auto_respawn":"false"},
                 "children":[{"ability":"maicraft:acquire_items","outcome":"取铁锭",
                              "parameters":{"item_id":"minecraft:iron_ingot","count":"2"}}]}""");
        normalize(sequence);
        check(sequence.getAsJsonObject("parameters").get("auto_respawn").getAsJsonPrimitive().isBoolean(),
                "sequence root runtime key restored");
        // "yes" 没有唯一真假含义，保留原样。
        JsonObject vague = goal("""
                {"ability":"maicraft:acquire_items","outcome":"取铁锭","parameters":{"item_id":"minecraft:iron_ingot",
                 "auto_respawn":"yes"}}""");
        normalize(vague);
        check(vague.getAsJsonObject("parameters").get("auto_respawn").getAsJsonPrimitive().isString(), "yes stays a string");
    }

    private static List<String> normalize(JsonObject goal) {
        List<String> notes = new ArrayList<>();
        ParameterNormalizer.normalizeGoal(goal, "goal", notes);
        return notes;
    }

    private static void validate(JsonObject goal) {
        SemanticGoalContract.validate(Goal.fromJson(goal), IntentRuntime.KNOWN_ABILITIES);
    }

    private static JsonObject goal(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError("parameter normalization: " + what);
    }
}
