// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.design;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.maiwithu.maicraft.ability.design.DesignSamples.component;
import static org.maiwithu.maicraft.ability.design.DesignSamples.drawing;
import static org.maiwithu.maicraft.ability.design.DesignSamples.instance;
import static org.maiwithu.maicraft.ability.design.DesignSamples.json;
import static org.maiwithu.maicraft.ability.design.DesignSamples.mesh;

import com.google.gson.JsonObject;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.ability.design.api.DesignCompiler;

/** 改图：按名合并，字段逐项覆盖，组件整条替换，又删又改拒绝；合并结果再过整张图纸的校验。 */
class DesignEditsTest {

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static JsonObject source() {
        var drawing = drawing(
                mesh("Anchor", "cube", new double[]{.5, .5, .5}, new int[]{1, 1, 1}, "Body"),
                instance("Columns", "Column", 4, 0, 0));
        component(drawing, "Column",
                mesh("Base", "cube", new double[]{.5, .5, .5}, new int[]{1, 1, 1}, "Body"),
                mesh("Cap", "cube", new double[]{.5, 1.5, .5}, new int[]{1, 1, 1}, "Body"));
        component(drawing, "Unused", mesh("Other", "cube", new double[]{.5, .5, .5}, new int[]{1, 1, 1}, "Body"));
        return drawing;
    }

    @Test
    void 组件整条替换_实例局部修改_未点名的保持原样() {
        JsonObject source = source();
        String before = source.toString();
        JsonObject edited = DesignEdits.apply(source, json("""
                {"components":{"Column":{"objects":[{"name":"Replacement","type":"MESH","primitive":"cube",
                    "location":[0.5,0.5,0.5],"dimensions":[1,1,1],"material":"Body"}]}},
                 "objects":[{"name":"Columns","mirror":["z"],"array":{"count":[2,1,1],"step":[3,0,0]}}],
                 "materials":{"Accent":{"block_id":"minecraft:gold_block"}}}
                """));
        DesignCompiler.validate(edited);
        var definition = edited.getAsJsonObject("components").getAsJsonObject("Column").getAsJsonArray("objects");
        assertTrue(definition.size() == 1 && definition.get(0).getAsJsonObject().get("name").getAsString().equals("Replacement"), "组件定义整条替换，旧的 Base 与 Cap 不残留");
        var instance = edited.getAsJsonArray("objects").get(1).getAsJsonObject();
        assertTrue(instance.get("component").getAsString().equals("Column") && instance.has("location")
                && instance.getAsJsonArray("mirror").get(0).getAsString().equals("z"), "实例局部修改保留组件引用和原位置");
        assertEquals(source.getAsJsonObject("components").getAsJsonObject("Unused"), edited.getAsJsonObject("components").getAsJsonObject("Unused"), "未点名的组件保持原样");
        assertEquals(before, source.toString(), "原图纸不动");
        JsonObject removed = DesignEdits.apply(edited, json("{\"remove_components\":[\"Column\"],\"remove_objects\":[\"Columns\"]}"));
        DesignCompiler.validate(removed);
        assertTrue(!removed.getAsJsonObject("components").has("Column") && removed.getAsJsonArray("objects").size() == 1, "同一次可以一起移除实例和定义");
    }

    @Test
    void 删掉还在用的组件_引用成环_合并后校验拒绝() {
        JsonObject source = source();
        for (String invalid : new String[]{
                "{\"remove_components\":[\"Column\"]}",
                "{\"remove_components\":[\"Unknown\"]}",
                "{\"components\":{\"Column\":{\"objects\":[{\"name\":\"Again\",\"type\":\"INSTANCE\",\"component\":\"Column\",\"location\":[0,0,0]}]}}}",
                "{\"remove_objects\":[\"Nope\"]}",
                "{\"objects\":[{\"name\":\"Anchor\",\"material\":\"Missing\"}]}"}) {
            assertThrows(IllegalArgumentException.class, () -> DesignCompiler.validate(DesignEdits.apply(source, json(invalid))), invalid);
        }
    }

    @Test
    void 修改本身的格式先查() {
        for (String invalid : new String[]{
                "{}", "{\"schema_version\":2}", "{\"name\":\"\"}",
                "{\"remove_components\":[\"Column\",\"Column\"]}",
                "{\"remove_components\":[\"Column\"],\"components\":{\"Column\":{\"objects\":[]}}}",
                "{\"remove_objects\":[\"Anchor\"],\"objects\":[{\"name\":\"Anchor\",\"material\":\"Body\"}]}",
                "{\"objects\":[{\"name\":\"Columns\",\"array\":{\"count\":[2,1,1],\"step\":[3,0,0],\"clicks\":[]}}]}",
                "{\"objects\":[{\"name\":\"Columns\",\"material_map\":{\"wood\":{\"slot\":1}}}]}",
                "{\"components\":{\"Column\":{\"unknown\":1,\"objects\":[]}}}",
                "{\"components\":{\"Column\":{\"objects\":[{\"name\":\"Part\",\"type\":\"MESH\",\"primitive\":\"cube\",\"location\":[0.5,0.5,0.5],\"dimensions\":[1,1,1],\"material\":\"wood\",\"clicks\":[]}]}}}",
                "{\"materials\":{\"Bad\":{\"block_id\":\"stone\"}}}"}) {
            assertThrows(IllegalArgumentException.class, () -> DesignEdits.validate(json(invalid)), invalid);
        }
        assertFalse(DesignEdits.FIELDS.contains("schema_version"));
    }
}
