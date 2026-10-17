// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.param;

import com.google.gson.JsonObject;
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
        // 形状对不上各报各的：对象参数不收数组，数组参数不收对象，读不成 JSON 的文字和数字都不是结构化正文。
        assertFalse(spec.parse(JsonParser.parseString("{\"drawing\":[1]}").getAsJsonObject()).ok(), "对象参数不收数组");
        assertFalse(spec.parse(JsonParser.parseString("{\"drawing\":{},\"cells\":{}}").getAsJsonObject()).ok(), "数组参数不收对象");
        assertFalse(spec.parse(JsonParser.parseString("{\"drawing\":\"text\"}").getAsJsonObject()).ok());
        assertFalse(spec.parse(JsonParser.parseString("{\"drawing\":3}").getAsJsonObject()).ok());
        assertEquals("object", ParamType.JSON_OBJECT.schemaType());
        assertEquals("array", ParamType.JSON_ARRAY.schemaType());
    }

    @Test
    void structuredTextWrittenAsAStringIsReadWhenItHasTheRightShape() {
        // 模型调工具时嵌套数组会被弄坏，调用方把整张图纸、整份逐格清单写成字符串传：内容形状对就照收，notes 里说一句。
        ParamSpecs spec = ParamSpecs.of(
                ParamSpec.of("drawing", ParamType.JSON_OBJECT).required().doc("图纸正文").build(),
                ParamSpec.of("cells", ParamType.JSON_ARRAY).doc("逐格清单").build());
        JsonObject raw = new JsonObject();
        raw.addProperty("drawing", "{\"objects\":[{\"location\":[2.5,1.5,2.5]}]}");
        raw.addProperty("cells", "[{\"offset\":[0,0,0],\"block\":\"minecraft:stone\"}]");
        ParseResult result = spec.parse(raw);

        assertTrue(result.ok(), () -> result.errors().toString());
        assertEquals(3, result.params().json("drawing").getAsJsonObject().getAsJsonArray("objects")
                .get(0).getAsJsonObject().getAsJsonArray("location").size());
        assertEquals("minecraft:stone", result.params().json("cells").getAsJsonArray()
                .get(0).getAsJsonObject().get("block").getAsString());
        assertEquals(List.of("drawing：字符串里的 JSON 按对象处理", "cells：字符串里的 JSON 按数组处理"), result.notes());

        // 字符串里的形状照样要对：对象参数里写成数组的字符串不收。
        JsonObject wrongShape = new JsonObject();
        wrongShape.addProperty("drawing", "[1]");
        assertFalse(spec.parse(wrongShape).ok());

        // 字符串读不成 JSON：报错里带上坏在哪，调用方照着改那一处，不用整段重写。
        JsonObject broken = new JsonObject();
        broken.addProperty("drawing", "{\"objects\":[1,");
        ParseResult unreadable = spec.parse(broken);
        assertFalse(unreadable.ok());
        String message = unreadable.errors().getFirst().message();
        assertTrue(message.startsWith("drawing 必须是 JSON 对象；字符串里的 JSON 读不懂："), message);
        assertFalse(message.contains("\n"), message);
    }

    @Test
    void arraysWrappedUnderASingleItemKeyAreReadAsArrays() {
        // 直播里 MiniMax 把 find 的 blocks、build 的 cells 连每格的 offset 都包成 {"item": [...]}，连拒六次：
        // 期望数组的地方收到这种包装就取出数组照收，正文里面嵌套的也拆，notes 里说一次拆了几处。
        ParamSpecs spec = ParamSpecs.of(
                ParamSpec.of("blocks", ParamType.BLOCK_LIST).doc("要找的方块").build(),
                ParamSpec.of("cells", ParamType.JSON_ARRAY).doc("逐格清单").build(),
                ParamSpec.of("drawing", ParamType.JSON_OBJECT).doc("图纸正文").build());
        ParseResult result = spec.parse(JsonParser.parseString("{"
                + "\"blocks\":{\"item\":[\"minecraft:coal_ore\",\"minecraft:iron_ore\"]},"
                + "\"cells\":{\"item\":[{\"block\":\"minecraft:air\",\"offset\":{\"item\":[0,1,0]}},"
                + "{\"block\":\"minecraft:air\",\"offset\":{\"item\":[0,1,1]}}]},"
                + "\"drawing\":{\"objects\":{\"item\":[{\"location\":{\"item\":[2.5,1.5,2.5]}}]}}}").getAsJsonObject());

        assertTrue(result.ok(), () -> result.errors().toString());
        assertEquals(List.of("minecraft:coal_ore", "minecraft:iron_ore"), result.params().list("blocks"));
        assertEquals(JsonParser.parseString("[{\"block\":\"minecraft:air\",\"offset\":[0,1,0]},"
                + "{\"block\":\"minecraft:air\",\"offset\":[0,1,1]}]"), result.params().json("cells"));
        assertEquals(JsonParser.parseString("{\"objects\":[{\"location\":[2.5,1.5,2.5]}]}"), result.params().json("drawing"));
        assertEquals(List.of("blocks：{\"item\": […]} 按数组处理",
                "cells：{\"item\": […]} 按数组处理（共 3 处，含里面嵌套的）",
                "drawing：{\"item\": […]} 按数组处理（共 2 处，含里面嵌套的）"), result.notes());

        // 只认只有 item 一个键的：多一个键、值是文字的都是真对象，照旧按形状报错。
        assertFalse(spec.parse(JsonParser.parseString("{\"blocks\":{\"item\":[\"minecraft:stone\"],\"count\":1}}")
                .getAsJsonObject()).ok(), "多一个键不是包装");
        assertFalse(spec.parse(JsonParser.parseString("{\"cells\":{\"item\":\"minecraft:air\"}}")
                .getAsJsonObject()).ok(), "item 的值是文字不是包装");
        ParseResult kept = spec.parse(JsonParser.parseString("{\"drawing\":{\"item\":[1]}}").getAsJsonObject());
        assertTrue(kept.ok(), () -> kept.errors().toString());
        assertEquals(JsonParser.parseString("{\"item\":[1]}"), kept.params().json("drawing"), "期望对象的参数本身不拆");
        assertTrue(kept.notes().isEmpty(), kept.notes()::toString);

        // 整段写成字符串的正文里也有包装时一样拆开。
        JsonObject text = new JsonObject();
        text.addProperty("cells", "[{\"block\":\"minecraft:stone\",\"offset\":{\"item\":[0,0,0]}}]");
        ParseResult fromText = spec.parse(text);
        assertTrue(fromText.ok(), () -> fromText.errors().toString());
        assertEquals(3, fromText.params().json("cells").getAsJsonArray().get(0).getAsJsonObject()
                .getAsJsonArray("offset").size());
        assertEquals(List.of("cells：字符串里的 JSON 按数组处理", "cells：{\"item\": […]} 按数组处理"), fromText.notes());
    }

    @Test
    void aSingleWrappedObjectAndIntegersWrittenAsTextAreRepaired() {
        // 直播 21:36 那两次 build：cells 包成 {"item": [...]}、只有一格时包成 {"item": {...}}，每格 offset 也被包，
        // 里面的整数还写成了字符串。都按数组、按整数收，notes 里各说一句。
        ParamSpecs spec = ParamSpecs.of(
                ParamSpec.of("cells", ParamType.JSON_ARRAY).doc("逐格清单").build(),
                ParamSpec.of("drawing", ParamType.JSON_OBJECT).doc("图纸正文").build());
        ParseResult two = spec.parse(JsonParser.parseString("{\"cells\":{\"item\":["
                + "{\"block\":\"minecraft:air\",\"offset\":{\"item\":[\"0\",\"1\",\"0\"]}},"
                + "{\"block\":\"minecraft:air\",\"offset\":{\"item\":[\"0\",\"1\",\"1\"]}}]}}").getAsJsonObject());
        assertTrue(two.ok(), () -> two.errors().toString());
        assertEquals(JsonParser.parseString("[{\"block\":\"minecraft:air\",\"offset\":[0,1,0]},"
                + "{\"block\":\"minecraft:air\",\"offset\":[0,1,1]}]"), two.params().json("cells"));
        assertTrue(two.params().json("cells").getAsJsonArray().get(0).getAsJsonObject().getAsJsonArray("offset")
                .get(1).getAsJsonPrimitive().isNumber(), "换回来的是数字，不是字符串");
        assertEquals(List.of("cells：{\"item\": […]} 按数组处理（共 3 处，含里面嵌套的）",
                "cells：数组里写成字符串的整数按整数处理（共 6 处）"), two.notes());

        ParseResult one = spec.parse(JsonParser.parseString("{\"cells\":{\"item\":"
                + "{\"block\":\"minecraft:air\",\"offset\":{\"item\":[\"0\",\"1\",\"0\"]}}}}").getAsJsonObject());
        assertTrue(one.ok(), () -> one.errors().toString());
        assertEquals(JsonParser.parseString("[{\"block\":\"minecraft:air\",\"offset\":[0,1,0]}]"), one.params().json("cells"));
        assertEquals(List.of("cells：{\"item\": {…}} 按只有一项的数组处理", "cells：{\"item\": […]} 按数组处理",
                "cells：数组里写成字符串的整数按整数处理（共 3 处）"), one.notes());

        // 正文里面不知道该是什么形状：包着单个对象的不猜成数组；混着文字、小数的数组不动；整段字符串里的照样修。
        ParseResult kept = spec.parse(JsonParser.parseString("{\"drawing\":{\"components\":{\"item\":{\"objects\":[]}},"
                + "\"mirror\":[\"x\",\"1\"],\"location\":[\"2.5\",1,2]}}").getAsJsonObject());
        assertTrue(kept.ok(), () -> kept.errors().toString());
        assertEquals(JsonParser.parseString("{\"components\":{\"item\":{\"objects\":[]}},"
                + "\"mirror\":[\"x\",\"1\"],\"location\":[\"2.5\",1,2]}"), kept.params().json("drawing"));
        assertTrue(kept.notes().isEmpty(), kept.notes()::toString);
        JsonObject text = new JsonObject();
        text.addProperty("cells", "[{\"block\":\"minecraft:stone\",\"offset\":[\"-3\",0,\"+2\"]}]");
        ParseResult fromText = spec.parse(text);
        assertTrue(fromText.ok(), () -> fromText.errors().toString());
        assertEquals(JsonParser.parseString("[-3,0,2]"),
                fromText.params().json("cells").getAsJsonArray().get(0).getAsJsonObject().get("offset"));
    }

    @Test
    void describesFieldsFromTheSameDefinition() {
        var fields = OBTAIN.describe();

        assertEquals(3, fields.size());
        assertEquals("item", fields.get(0).getAsJsonObject().get("name").getAsString());
        assertEquals(256, fields.get(1).getAsJsonObject().get("max").getAsInt());
    }
}
