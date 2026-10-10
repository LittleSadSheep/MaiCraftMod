// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.design;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.maiwithu.maicraft.ability.design.DesignSamples.component;
import static org.maiwithu.maicraft.ability.design.DesignSamples.drawing;
import static org.maiwithu.maicraft.ability.design.DesignSamples.instance;
import static org.maiwithu.maicraft.ability.design.DesignSamples.json;
import static org.maiwithu.maicraft.ability.design.DesignSamples.jsonArray;
import static org.maiwithu.maicraft.ability.design.DesignSamples.mesh;

import com.google.gson.JsonObject;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.ability.design.api.DesignCompiler;

/** 图纸格式：坏组件、超量阵列和超过上限的采样在编译前就拒绝；错误一次报全并带定位。 */
class DesignFormatTest {

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static JsonObject base() {
        return drawing(mesh("Base", "cube", new double[]{.5, .5, .5}, new int[]{1, 1, 1}, "Body"));
    }

    @Test
    void 没摆出来的组件也查材料和面棱() {
        for (String edit : new String[]{"{\"material\":\"Missing\"}", "{\"face_materials\":{\"bogus\":\"Body\"}}",
                "{\"edge_materials\":{\"front+back\":\"Trim\"}}", "{\"fill\":\"hollow\",\"open_faces\":[\"unknown\"]}"}) {
            var source = base();
            var member = mesh("Stored", "cube", new double[]{.5, .5, .5}, new int[]{1, 1, 1}, "Body");
            json(edit).entrySet().forEach(entry -> member.add(entry.getKey(), entry.getValue()));
            component(source, "Unused", member);
            assertThrows(IllegalArgumentException.class, () -> DesignCompiler.validate(source), edit);
        }
        var source = base();
        component(source, "Unused", instance("NoDefinition", "Missing", 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> DesignCompiler.validate(source));
    }

    @Test
    void 组件环与超量展开在复制前拒绝() {
        var cycle = base();
        component(cycle, "A", instance("B", "B", 0, 0, 0));
        component(cycle, "B", instance("A", "A", 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> DesignCompiler.validate(cycle));
        var root = instance("Root", "L2", 0, 0, 0);
        root.add("array", json("{\"count\":[1,1,21],\"step\":[0,0,1]}"));
        var expanded = drawing(root);
        component(expanded, "L0", mesh("Unit", "cube", new double[]{.5, .5, .5}, new int[]{1, 1, 1}, "Body"));
        var x = instance("X", "L0", 0, 0, 0);
        x.add("array", json("{\"count\":[21,1,1],\"step\":[1,0,0]}"));
        component(expanded, "L1", x);
        var y = instance("Y", "L1", 0, 0, 0);
        y.add("array", json("{\"count\":[1,21,1],\"step\":[0,1,0]}"));
        component(expanded, "L2", y);
        var message = assertThrows(IllegalArgumentException.class, () -> DesignCompiler.validate(expanded)).getMessage();
        assertTrue(message.contains("展开后的对象超过上限"), message);
        var huge = base();
        huge.getAsJsonArray("objects").get(0).getAsJsonObject().add("array", json("{\"count\":[1000000000,1,1],\"step\":[1,0,0]}"));
        assertThrows(IllegalArgumentException.class, () -> DesignCompiler.validate(huge));
    }

    @Test
    void 含糊的几何参数不静默忽略() {
        for (String edit : new String[]{"{\"rotation_euler\":[0,0.7853981633974483,0]}", "{\"mirror\":[\"x\",\"x\"]}",
                "{\"array\":{\"count\":[2,1,1],\"step\":[0,0,0]}}", "{\"array\":{\"count\":[2,1,1],\"step\":[1,0,0],\"skip\":[[2,0,0]]}}",
                "{\"wall_thickness\":0}", "{\"segments\":12}", "{\"fill\":\"solid\",\"open_faces\":[\"top\"]}", "{\"name\":\"Fake[0]\"}",
                "{\"schema_version\":2}", "{\"type\":\"cube\"}"}) {
            var source = base();
            var node = source.getAsJsonArray("objects").get(0).getAsJsonObject();
            json(edit).entrySet().forEach(entry -> node.add(entry.getKey(), entry.getValue()));
            assertThrows(IllegalArgumentException.class, () -> DesignCompiler.validate(source), edit);
        }
        var source = base();
        source.getAsJsonArray("objects").get(0).getAsJsonObject().add("modifiers", jsonArray("[{\"type\":\"BOOLEAN\",\"operation\":\"DIFFERENCE\",\"object\":\"Base\"}]"));
        assertThrows(IllegalArgumentException.class, () -> DesignCompiler.validate(source));
    }

    @Test
    void 错误一次报全并带对象定位() {
        var source = drawing(
                mesh("Wall", "cube", new double[]{.5, .5, .5}, new int[]{1, 1, 1}, "Body"),
                mesh("Roof", "cube", new double[]{.5, .5, .5}, new int[]{1, 1, 1}, "Body"));
        source.getAsJsonArray("objects").get(0).getAsJsonObject().addProperty("fill", "sideways");
        source.getAsJsonArray("objects").get(1).getAsJsonObject().addProperty("primitive", "sphere");
        String message = assertThrows(IllegalArgumentException.class, () -> DesignFormat.validate(source)).getMessage();
        assertTrue(message.contains("objects[0](Wall)") && message.contains("objects[1](Roof)"), message);
    }

    @Test
    void 采样工作量与格数有上限() {
        var shell = mesh("Shell", "cube", new double[]{2.5, 2.5, 2.5}, new int[]{5, 5, 5}, "Body");
        shell.addProperty("fill", "hollow");
        shell.add("face_materials", json("{\"front\":\"Trim\",\"back\":\"Trim\",\"left\":\"Trim\",\"right\":\"Trim\",\"top\":\"Trim\",\"bottom\":\"Trim\"}"));
        // 工作量包括图元包含判定、空腔判定和六面涂装三组比较。
        assertEquals(125L * 19, DesignSamples.compile(drawing(shell)).voxelWork());
        shell.add("location", jsonArray("[100,100,100]"));
        shell.add("dimensions", jsonArray("[200,200,200]"));
        var message = assertThrows(IllegalArgumentException.class, () -> DesignCompiler.validate(drawing(shell))).getMessage();
        assertTrue(message.contains("采样工作量超过上限"), message);
        var expensive = drawing(mesh("Huge", "cube", new double[]{80, 80, 80}, new int[]{160, 160, 160}, "Body"));
        assertThrows(IllegalArgumentException.class, () -> DesignCompiler.validate(expensive));
        var tooMany = drawing(mesh("Large", "cube", new double[]{35, 35, 35}, new int[]{70, 70, 70}, "Body"));
        message = assertThrows(IllegalArgumentException.class, () -> DesignCompiler.compile(tooMany)).getMessage();
        assertTrue(message.contains("格"), message);
    }

    @Test
    void 混色材料按坐标确定性取色() {
        var drawing = drawing(mesh("Wall", "cube", new double[]{5, .5, .5}, new int[]{10, 1, 1}, "Mix"));
        drawing.getAsJsonObject("materials").add("Mix", json(
                "{\"mix\":[{\"block_id\":\"minecraft:stone_bricks\",\"weight\":3},{\"block_id\":\"minecraft:mossy_stone_bricks\"}]}"));
        var cells = DesignSamples.cells(drawing);
        assertEquals(10, cells.size());
        assertTrue(DesignSamples.count(cells, "minecraft:stone_bricks") + DesignSamples.count(cells, "minecraft:mossy_stone_bricks") == 10);
        assertEquals(cells, DesignSamples.cells(drawing), "同一张图纸再编译一次取色不变");
        drawing.getAsJsonObject("materials").add("Mix", json("{\"mix\":[{\"block_id\":\"minecraft:stone\",\"weight\":0}]}"));
        assertThrows(IllegalArgumentException.class, () -> DesignCompiler.validate(drawing));
    }
}
