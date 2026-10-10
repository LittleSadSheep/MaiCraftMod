// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.design;

import static org.maiwithu.maicraft.ability.design.DesignFormat.array;
import static org.maiwithu.maicraft.ability.design.DesignFormat.bad;
import static org.maiwithu.maicraft.ability.design.DesignFormat.object;
import static org.maiwithu.maicraft.ability.design.DesignFormat.string;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * 改图：按名字合并修改。对象字段逐项覆盖（只改 location 会保留原材质和尺寸），modifiers 若给了就整份替换；
 * 组件定义整条替换；材料按名整条覆盖；未提到的对象保留原顺序，新对象加在末尾。
 * 同一对象不能一次里既删又改，也不能改两次。合并后的整张图纸由调用方重新校验。
 */
public final class DesignEdits {

    static final Set<String> FIELDS = Set.of("name", "objects", "materials", "remove_objects", "components", "remove_components",
            "block_state_axes", "overlap_policy");

    private DesignEdits() {}

    /** 把修改合进原图纸，返回新的一份；原图纸不动。 */
    public static JsonObject apply(JsonObject original, JsonObject edits) {
        validate(edits);
        JsonObject drawing = original.deepCopy();
        for (String setting : Set.of("name", "block_state_axes", "overlap_policy")) {
            if (edits.has(setting)) drawing.add(setting, edits.get(setting).deepCopy());
        }
        if (edits.has("components") || edits.has("remove_components")) {
            JsonObject components = drawing.has("components") ? object(drawing.get("components"), "components") : new JsonObject();
            if (edits.has("remove_components")) {
                for (var value : edits.getAsJsonArray("remove_components")) {
                    String name = value.getAsString();
                    if (components.remove(name) == null) throw bad("remove_components 里没有这个组件：" + name);
                }
            }
            // 一条组件定义含自己的对象与切割引用，按名整条替换，不把旧对象悄悄拼进新版组件。
            if (edits.has("components")) edits.getAsJsonObject("components").entrySet().forEach(entry -> components.add(entry.getKey(), entry.getValue().deepCopy()));
            drawing.add("components", components);
        }
        var objects = new LinkedHashMap<String, JsonObject>();
        for (JsonElement element : drawing.getAsJsonArray("objects")) {
            JsonObject object = element.getAsJsonObject();
            objects.put(object.get("name").getAsString(), object);
        }
        Set<String> removed = new LinkedHashSet<>();
        if (edits.has("remove_objects")) {
            for (JsonElement element : edits.getAsJsonArray("remove_objects")) {
                String name = element.getAsString();
                if (!removed.add(name) || objects.remove(name) == null) throw bad("remove_objects 里没有这个对象或写了两次：" + name);
            }
        }
        if (edits.has("objects")) {
            Set<String> edited = new LinkedHashSet<>();
            for (JsonElement element : edits.getAsJsonArray("objects")) {
                JsonObject object = element.getAsJsonObject();
                String name = object.get("name").getAsString();
                if (!edited.add(name) || removed.contains(name)) throw bad("同一个对象不能改两次，也不能又删又改：" + name);
                JsonObject merged = objects.containsKey(name) ? objects.get(name).deepCopy() : new JsonObject();
                object.entrySet().forEach(entry -> merged.add(entry.getKey(), entry.getValue().deepCopy()));
                objects.put(name, merged);
            }
        }
        JsonArray ordered = new JsonArray();
        objects.values().forEach(ordered::add);
        drawing.add("objects", ordered);
        // 材料按名整条覆盖：没有单独的删材料操作，也不按单个属性深层合并。
        if (edits.has("materials")) {
            JsonObject materials = drawing.has("materials") ? drawing.getAsJsonObject("materials") : new JsonObject();
            edits.getAsJsonObject("materials").entrySet().forEach(entry -> materials.add(entry.getKey(), entry.getValue().deepCopy()));
            drawing.add("materials", materials);
        }
        return drawing;
    }

    /** 修改可以只给一部分字段；先查这一批字段合法，合并后再由整张图纸的校验查完整性和引用。 */
    public static void validate(JsonObject edits) {
        if (edits == null || edits.isEmpty()) throw bad("edits 不能为空");
        DesignFormat.keys(edits, FIELDS, "edits");
        if (edits.has("name")) string(edits.get("name"), 128, "name");
        DesignFormat.choice(edits, "block_state_axes", Set.of("local", "minecraft_world"));
        DesignFormat.choice(edits, "overlap_policy", Set.of("last_wins", "error"));
        Set<String> components = new LinkedHashSet<>();
        if (edits.has("remove_components")) {
            for (var entry : array(edits.get("remove_components"), 0, DesignLimits.MAX_OBJECTS, "remove_components")) {
                if (!components.add(DesignFormat.name(entry))) throw bad("remove_components 里同一个组件写了两次");
            }
        }
        if (edits.has("components")) {
            JsonObject definitions = object(edits.get("components"), "components");
            if (definitions.size() > DesignLimits.MAX_OBJECTS) throw bad("一次改的组件太多");
            for (var entry : definitions.entrySet()) {
                DesignFormat.name(entry.getKey());
                if (!components.add(entry.getKey())) throw bad("同一个组件不能一次里又删又换：" + entry.getKey());
                DesignFormat.validateComponent(object(entry.getValue(), "components[" + entry.getKey() + "]"));
            }
        }
        Set<String> names = new LinkedHashSet<>();
        if (edits.has("remove_objects")) {
            for (var entry : array(edits.get("remove_objects"), 0, DesignLimits.MAX_OBJECTS, "remove_objects")) {
                if (!names.add(DesignFormat.name(entry))) throw bad("remove_objects 里同一个对象写了两次");
            }
        }
        if (edits.has("objects")) {
            for (var entry : array(edits.get("objects"), 0, DesignLimits.MAX_OBJECTS, "objects")) {
                JsonObject object = object(entry, "objects 的一项");
                DesignFormat.validateNodePatch(object);
                if (!names.add(DesignFormat.name(object.get("name")))) throw bad("同一个对象不能改两次，也不能又删又改");
            }
        }
        if (edits.has("materials")) DesignFormat.validateMaterials(object(edits.get("materials"), "materials"));
    }
}
