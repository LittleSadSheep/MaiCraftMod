// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.param;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 参数规格：参数名必须登记、宽松写法统一规范化、严格校验、错误一次报全。 */
class ParamSpecsTest {
    // 模拟"让背包里某样东西再多几件"的参数：物品必填，数量 1..256 默认 1，途径可选。
    private static final ParamSpecs OBTAIN = ParamSpecs.of(
            ParamSpec.of("item", ParamType.ITEM_OR_TAG).required().doc("要多拿的物品或标签").build(),
            ParamSpec.of("count", ParamType.INTEGER).range(1, 256).defaultValue(1).doc("要再多几件").build(),
            ParamSpec.of("via", ParamType.CHOICE).choices("any", "craft", "smelt", "mine").defaultValue("any")
                    .doc("指定途径").build());

    private static ParseResult parse(String json) {
        return OBTAIN.parse(JsonParser.parseString(json).getAsJsonObject());
    }

    @Test
    void namesMustBeRegistered() {
        // "search_radius" 与已登记的 radius 是同一个意思：没在参数名表里登记的名字一律拒绝。
        assertThrows(IllegalArgumentException.class,
                () -> ParamSpec.of("search_radius", ParamType.INTEGER).doc("搜索范围").build());
    }

    @Test
    void appliesDefaultsAndTreatsNullAsAbsent() {
        ParseResult result = parse("{\"item\":\"minecraft:torch\",\"via\":null}");

        assertTrue(result.ok());
        assertEquals(1L, result.params().integer("count"));
        assertEquals("any", result.params().text("via"));
    }

    @Test
    void normalizesLenientWritingAndSaysSo() {
        ParseResult result = parse("{\"item\":\"#Minecraft:Logs\",\"count\":\"6\",\"via\":\" CRAFT \"}");

        assertTrue(result.ok(), () -> result.errors().toString());
        assertEquals("#minecraft:logs", result.params().text("item"));
        assertEquals(6L, result.params().integer("count"));
        assertEquals("craft", result.params().text("via"));
        assertEquals(3, result.notes().size(), "每一处规范化都要告诉调用方");
    }

    @Test
    void reportsAllErrorsAtOnce() {
        ParseResult result = parse("{\"count\":0,\"via\":\"teleport\",\"radius\":8}");

        assertFalse(result.ok());
        assertNull(result.params());
        List<String> fields = result.errors().stream().map(ParamError::field).toList();
        assertTrue(fields.containsAll(List.of("item", "count", "via", "radius")),
                "缺必填、超范围、选项不对、未声明参数应一次报全：" + fields);
    }

    @Test
    void entityTypesDoNotAcceptTags() {
        ParamSpecs spec = ParamSpecs.of(ParamSpec.of("entity", ParamType.ENTITY_TYPE).required().doc("要找的生物").build());

        assertTrue(spec.parse(JsonParser.parseString("{\"entity\":\"minecraft:sheep\"}").getAsJsonObject()).ok());
        assertFalse(spec.parse(JsonParser.parseString("{\"entity\":\"#minecraft:raiders\"}").getAsJsonObject()).ok());
    }

    @Test
    void wrapsSingleValueIntoList() {
        ParamSpecs spec = ParamSpecs.of(ParamSpec.of("items", ParamType.ITEM_LIST).required().doc("要存进去的物品").build());
        ParseResult result = spec.parse(JsonParser.parseString("{\"items\":\"minecraft:cobblestone\"}").getAsJsonObject());

        assertTrue(result.ok());
        assertEquals(List.of("minecraft:cobblestone"), result.params().list("items"));
    }

    @Test
    void jsonKeepsObjectsAndArraysAsTheyAre() {
        // 图纸、修改、逐格清单这类正文原样带过：入口只认对象或数组的形状，内容由能力自己校验；读出来的是副本。
        ParamSpecs spec = ParamSpecs.of(ParamSpec.of("drawing", ParamType.JSON).required().doc("图纸正文").build());
        ParseResult object = spec.parse(JsonParser.parseString("{\"drawing\":{\"objects\":[1,2]}}").getAsJsonObject());
        ParseResult array = spec.parse(JsonParser.parseString("{\"drawing\":[{\"offset\":[0,0,0]}]}").getAsJsonObject());

        assertTrue(object.ok() && array.ok());
        assertEquals(2, object.params().json("drawing").getAsJsonObject().getAsJsonArray("objects").size());
        assertEquals(1, array.params().json("drawing").getAsJsonArray().size());
        object.params().json("drawing").getAsJsonObject().remove("objects");
        assertTrue(object.params().json("drawing").getAsJsonObject().has("objects"), "改副本不影响存着的参数");
        assertEquals("drawing", object.params().toJson().entrySet().iterator().next().getKey());
        assertFalse(spec.parse(JsonParser.parseString("{\"drawing\":\"text\"}").getAsJsonObject()).ok(), "字符串不是 JSON 正文");
        assertFalse(spec.parse(JsonParser.parseString("{\"drawing\":3}").getAsJsonObject()).ok());
    }

    @Test
    void describesFieldsFromTheSameDefinition() {
        var fields = OBTAIN.describe();

        assertEquals(3, fields.size());
        assertEquals("item", fields.get(0).getAsJsonObject().get("name").getAsString());
        assertEquals(256, fields.get(1).getAsJsonObject().get("max").getAsInt());
    }
}
