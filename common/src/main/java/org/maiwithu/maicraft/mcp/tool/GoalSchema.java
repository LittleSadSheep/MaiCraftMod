// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.tool;

import java.util.Arrays;
import java.util.Locale;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.goal.TargetKind;

/**
 * execute 的 goal 长什么样：交给客户端的参数表里写全每个字段、target 的几种写法和许可的取值，
 * LLM 照着写就行，不用靠 lookup 猜结构。取值从入口真正认的那几个枚举里来，和读目标的规矩是同一份。
 * 原先 goal 只是一个不透明的 object，实机一场被入口拒了二十多次（target 写成 "here"、坐标外面又套一层 position、
 * steps 写成对象）。
 */
final class GoalSchema {

    private GoalSchema() {}

    /** goal 字段的完整结构。 */
    static JsonObject goal() {
        JsonObject properties = new JsonObject();
        properties.add("ability", text("能力名，例如 obtain、travel、fight；lookup() 列出全部，可以省略命名空间"));
        properties.add("purpose", text("为什么做这件事，一句话；会写进结果和备料的用途"));
        properties.add("target", target());
        JsonObject parameters = new JsonObject();
        parameters.addProperty("type", "object");
        parameters.addProperty("description", "能力自己的参数，按 lookup(topic=ability, id=能力名) 给的参数表写；"
                + "要去的地方、要处理的东西写在 target，不写在这里");
        properties.add("parameters", parameters);
        properties.add("permissions", permissions());
        JsonObject steps = new JsonObject();
        steps.addProperty("type", "array");
        steps.addProperty("description", "只给 sequence：按顺序做的每一步，是一个列表，每一项都是写法和 goal 一样的完整目标");
        JsonObject step = new JsonObject();
        step.addProperty("type", "object");
        step.addProperty("description", "一步：写法同 goal（ability、target、parameters……）");
        steps.add("items", step);
        properties.add("steps", steps);
        properties.add("on_failure", choice("只给 sequence：一步失败后 stop（停下，默认）还是 continue（接着做下一步）",
                "stop", "continue"));
        JsonObject goal = new JsonObject();
        goal.addProperty("type", "object");
        goal.addProperty("description", "目标：要做什么（ability）、对谁或去哪（target）、怎么做（parameters）、按什么许可（permissions）");
        goal.add("properties", properties);
        goal.add("required", array("ability"));
        return goal;
    }

    // target：一个对象，kind 决定还要哪些字段。
    private static JsonObject target() {
        JsonObject properties = new JsonObject();
        properties.add("kind", choice("here 原地；seen 看到的东西（id）；landmark 记住的地点（name）；player 玩家（name）；"
                        + "position 坐标（x、z 必填，y、dimension 可省；直接写在 target 里，不要再套一层 position）；"
                        + "direction 朝某个方向走多远（toward、distance）；dimension 去某个维度（name，例如 minecraft:the_nether）；"
                        + "previous 前面某一步的结果（step，只在 sequence 里）",
                Arrays.stream(TargetKind.values()).map(GoalSchema::lower).toArray(String[]::new)));
        properties.add("id", text("kind=seen：observe 给的观察编号，例如 e12、b5、f3"));
        properties.add("name", text("kind=landmark、player 或 dimension：地标名、玩家名或维度 ID"));
        properties.add("x", integer("kind=position：坐标 x"));
        properties.add("y", integer("kind=position：坐标 y，不确定可以不给，到了附近自己找能站的格子"));
        properties.add("z", integer("kind=position：坐标 z"));
        properties.add("dimension", text("kind=position：维度，例如 minecraft:the_nether；不给就是当前维度"));
        properties.add("toward", choice("kind=direction：朝哪个方向",
                Arrays.stream(Target.Toward.values()).map(GoalSchema::lower).toArray(String[]::new)));
        properties.add("distance", integer("kind=direction：走多远（格）"));
        properties.add("step", integer("kind=previous：前面第几步的结果，从 0 开始；不给就是上一步"));
        JsonObject target = new JsonObject();
        target.addProperty("type", "object");
        target.addProperty("description", "要去的地方或要处理的东西：一个对象，不是一句文字（原地写 {\"kind\": \"here\"}）");
        target.add("properties", properties);
        target.add("required", array("kind"));
        return target;
    }

    // 许可：每一项都有默认值，只写要改的那几项。
    private static JsonObject permissions() {
        JsonObject properties = new JsonObject();
        properties.add("change_blocks", choice("能改变哪些方块：none 不改、temporary 只垫临时方块、natural 挖天然方块与垫（默认）、"
                + "any 一切不受保护的", names(Permissions.BlockChanges.values())));
        properties.add("fight", choice("能打谁", names(Permissions.Fight.values())));
        properties.add("kill_animals", choice("能杀哪些动物", names(Permissions.AnimalKilling.values())));
        properties.add("survival_needs", choice("吃饭、躲怪这些生存需求要不要插进来（默认 on）",
                names(Permissions.SurvivalNeeds.values())));
        JsonObject rare = new JsonObject();
        rare.addProperty("type", "boolean");
        rare.addProperty("description", "能不能动用稀有物品；目标本身就要用的不受它管（去末地插的末影之眼、过折跃门扔的末影珍珠照用）");
        properties.add("use_rare_items", rare);
        JsonObject landmarks = new JsonObject();
        landmarks.addProperty("type", "array");
        landmarks.addProperty("description", "这次额外不许碰的地标名");
        JsonObject item = new JsonObject();
        item.addProperty("type", "string");
        landmarks.add("items", item);
        properties.add("protected_landmarks", landmarks);
        JsonObject permissions = new JsonObject();
        permissions.addProperty("type", "object");
        permissions.addProperty("description", "这次的许可；不写的项用默认值");
        permissions.add("properties", properties);
        return permissions;
    }

    private static JsonObject text(String description) {
        return typed("string", description);
    }

    private static JsonObject integer(String description) {
        return typed("integer", description);
    }

    private static JsonObject typed(String type, String description) {
        JsonObject field = new JsonObject();
        field.addProperty("type", type);
        field.addProperty("description", description);
        return field;
    }

    private static JsonObject choice(String description, String... values) {
        JsonObject field = text(description);
        field.add("enum", array(values));
        return field;
    }

    private static JsonArray array(String... values) {
        JsonArray array = new JsonArray();
        for (String value : values) array.add(value);
        return array;
    }

    private static String[] names(Enum<?>[] values) {
        return Arrays.stream(values).map(GoalSchema::lower).toArray(String[]::new);
    }

    private static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
