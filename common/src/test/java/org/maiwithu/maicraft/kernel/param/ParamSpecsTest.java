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
    void jsonObjectsAndArraysAreKeptAsTheyAre() {
        // 图纸、修改这类正文是对象，逐格清单是数组：入口只认声明的形状，内容由能力自己校验；读出来的是副本。
        ParamSpecs spec = ParamSpecs.of(
                ParamSpec.of("drawing", ParamType.JSON_OBJECT).required().doc("图纸正文").build(),
                ParamSpec.of("cells", ParamType.JSON_ARRAY).doc("逐格清单").build());
        ParseResult result = spec.parse(JsonParser.parseString(
                "{\"drawing\":{\"objects\":[1,2]},\"cells\":[{\"offset\":[0,0,0]}]}").getAsJsonObject());

        assertTrue(result.ok(), () -> result.errors().toString());
        assertEquals(2, result.params().json("drawing").getAsJsonObject().getAsJsonArray("objects").size());
        assertEquals(1, result.params().json("cells").getAsJsonArray().size());
        result.params().json("drawing").getAsJsonObject().remove("objects");
        assertTrue(result.params().json("drawing").getAsJsonObject().has("objects"), "改副本不影响存着的参数");
        assertEquals("drawing", result.params().toJson().entrySet().iterator().next().getKey());
        // 形状对不上各报各的：对象参数不收数组，数组参数不收对象，字符串和数字都不是结构化正文。
        assertFalse(spec.parse(JsonParser.parseString("{\"drawing\":[1]}").getAsJsonObject()).ok(), "对象参数不收数组");
        assertFalse(spec.parse(JsonParser.parseString("{\"drawing\":{},\"cells\":{}}").getAsJsonObject()).ok(), "数组参数不收对象");
        assertFalse(spec.parse(JsonParser.parseString("{\"drawing\":\"text\"}").getAsJsonObject()).ok());
        assertFalse(spec.parse(JsonParser.parseString("{\"drawing\":3}").getAsJsonObject()).ok());
        assertEquals("object", ParamType.JSON_OBJECT.schemaType());
        assertEquals("array", ParamType.JSON_ARRAY.schemaType());
    }

    @Test
    void describesFieldsFromTheSameDefinition() {
        var fields = OBTAIN.describe();

        assertEquals(3, fields.size());
        assertEquals("item", fields.get(0).getAsJsonObject().get("name").getAsString());
        assertEquals(256, fields.get(1).getAsJsonObject().get("max").getAsInt());
    }
}
