// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;

/** 地点引用的同一份规则同时约束模型 Schema 和入口校验，避免把区域名字与坐标混用。 */
final class PublicTargetContract {
    private record Shape(List<String> kinds, String required, boolean position) {}
    private static final List<Shape> SHAPES = List.of(
            new Shape(List.of("current_place"), null, false),
            new Shape(List.of("coordinates"), "position", true),
            new Shape(List.of("prior_result"), "relation", false),
            new Shape(List.of("landmark", "player", "entity", "nearest", "area"), "label", false));
    private PublicTargetContract() {}

    static void describe(JsonObject schema) {
        // 先公布每种目标需要哪个字段，再用互斥分支表达位置限制；空值沿用公开入口的缺省语义。
        JsonArray kinds = new JsonArray(), branches = new JsonArray();
        for (Shape shape : SHAPES) {
            JsonObject branch = new JsonObject(), properties = new JsonObject(), kind = new JsonObject();
            JsonArray choices = new JsonArray(); shape.kinds().forEach(value -> { choices.add(value); kinds.add(value); });
            kind.add("enum", choices); properties.add("kind", kind);
            properties.add("position", type(shape.position() ? "object" : "null"));
            if (shape.required() != null) {
                JsonArray required = new JsonArray(); required.add(shape.required()); branch.add("required", required);
                if (!shape.position()) properties.add(shape.required(), type("string"));
            }
            branch.add("properties", properties); branches.add(branch);
        }
        schema.getAsJsonObject("properties").getAsJsonObject("kind").add("enum", kinds);
        schema.add("oneOf", branches);
        schema.addProperty("description", "Only coordinates carries position. Named targets including area require label; prior_result requires relation. Null optional fields count as absent.");
    }

    static void validate(JsonObject target, String kind) {
        Shape shape = SHAPES.stream().filter(value -> value.kinds().contains(kind)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("target kind has an unsupported value"));
        if (shape.position() != present(target, "position"))
            throw new IllegalArgumentException("position is required only for a coordinates target");
        if (shape.required() != null && !present(target, shape.required()))
            throw new IllegalArgumentException("target kind " + kind + " requires " + shape.required());
    }
    private static boolean present(JsonObject value, String key) { return value.has(key) && !value.get(key).isJsonNull(); }
    private static JsonObject type(String value) { JsonObject result = new JsonObject(); result.addProperty("type", value); return result; }
}
