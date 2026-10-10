// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.design;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.maiwithu.maicraft.ability.design.DesignSamples.at;
import static org.maiwithu.maicraft.ability.design.DesignSamples.block;
import static org.maiwithu.maicraft.ability.design.DesignSamples.cells;
import static org.maiwithu.maicraft.ability.design.DesignSamples.compile;
import static org.maiwithu.maicraft.ability.design.DesignSamples.component;
import static org.maiwithu.maicraft.ability.design.DesignSamples.count;
import static org.maiwithu.maicraft.ability.design.DesignSamples.drawing;
import static org.maiwithu.maicraft.ability.design.DesignSamples.instance;
import static org.maiwithu.maicraft.ability.design.DesignSamples.json;
import static org.maiwithu.maicraft.ability.design.DesignSamples.jsonArray;
import static org.maiwithu.maicraft.ability.design.DesignSamples.mesh;

import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.StairBlock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 组件的真实展开：每份窗洞各自隔离、阵列留门、嵌套配色、带朝向的方块跟着几何一起变换。 */
class DesignCompositionTest {

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 窗洞各自隔离_阵列跳过一份() {
        var glass = mesh("Glazing", "triangle", new double[]{3.5, 2, .5}, new int[]{3, 2, 1}, "Glass");
        var cut = mesh("WindowCut", "triangle", new double[]{3.5, 2, .5}, new int[]{3, 2, 1}, null);
        var wall = mesh("Wall", "cube", new double[]{3.5, 2, .5}, new int[]{7, 4, 1}, "Body");
        wall.add("modifiers", jsonArray("[{\"type\":\"BOOLEAN\",\"operation\":\"DIFFERENCE\",\"object\":\"WindowCut\"}]"));
        var bays = instance("Bays", "Window", 0, 0, 0);
        bays.add("array", json("{\"count\":[3,1,1],\"step\":[9,0,0],\"skip\":[[1,0,0]]}"));
        var drawing = drawing(bays);
        component(drawing, "Window", glass, cut, wall);
        String original = drawing.toString();
        var result = cells(drawing);
        assertTrue(result.size() == 56 && count(result, "minecraft:glass") == 8 && count(result, "minecraft:stone") == 48,
                "两份窗饰各保留四格三角玻璃，开孔不会串到另一份实例");
        assertTrue(block(result, 9, 0, 0).equals("unspecified") && block(result, 3, 1, 0).equals("minecraft:glass")
                && block(result, 21, 1, 0).equals("minecraft:glass"), "阵列中间整份跳过为门留空间，两侧窗洞保持各自位置");
        var compiled = compile(drawing);
        assertTrue(compiled.expandedCount() == 6 && compiled.cutterCount() == 2, "源组件与实际展开数量分别记录");
        assertTrue(drawing.toString().equals(original) && compiled.equals(compile(drawing)), "编译必须确定且不把展开节点塞回作者原图纸");
        var described = DesignInspection.describeObject(drawing, "Bays[2,0,0]/WindowCut", 0);
        assertTrue(!described.get("visible").getAsBoolean()
                && described.getAsJsonObject("minecraft_block_bounds").getAsJsonArray("from").get(0).getAsInt() == 20,
                "实际展开的切割路径可查询，并保留第二份实例的坐标");
    }

    @Test
    void 嵌套的配色映射() {
        var root = instance("Facade", "Outer", 0, 0, 0);
        root.add("material_map", json("{\"Trim\":\"Accent\"}"));
        var drawing = drawing(root);
        var child = instance("Repeated", "Inner", 0, 0, 0);
        child.add("material_map", json("{\"Body\":\"Trim\"}"));
        component(drawing, "Outer", child);
        component(drawing, "Inner", mesh("Block", "cube", new double[]{.5, .5, .5}, new int[]{1, 1, 1}, "Body"));
        assertEquals("minecraft:gold_block", block(cells(drawing), 0, 0, 0), "先应用内层配色，再让外层主题替换结果材质");
        assertEquals("Body", drawing.getAsJsonObject("components").getAsJsonObject("Inner").getAsJsonArray("objects").get(0).getAsJsonObject().get("material").getAsString(),
                "换色只影响实例，不污染可继续复用的原组件");
    }

    @Test
    void 变换保住方块朝向() {
        var instance = instance("Turned", "Step", 4, 0, 4);
        instance.add("rotation_euler", jsonArray("[0,1.5707963267948966,0]"));
        instance.add("mirror", jsonArray("[\"x\"]"));
        var drawing = drawing(instance);
        component(drawing, "Step", mesh("Stair", "cube", new double[]{.5, .5, .5}, new int[]{1, 1, 1}, "Stair"));
        var result = cells(drawing);
        var stair = at(result, 4, 0, 4);
        assertTrue(result.size() == 1 && stair != null && stair.state().getValue(StairBlock.FACING) == Direction.WEST,
                "镜像后的偏移和楼梯方向必须接受同一个组合矩阵");
        assertTrue(stair.required().contains("facing"), "作者点名的朝向转过去后仍是验收标准");
        instance.remove("mirror");
        instance.add("rotation_euler", jsonArray("[1.5707963267948966,0,0]"));
        assertThrows(IllegalArgumentException.class, () -> cells(drawing), "不能为旋转九十度的组件编造竖直楼梯朝向");
        drawing.getAsJsonObject("components").getAsJsonObject("Step").getAsJsonArray("objects").get(0).getAsJsonObject().addProperty("block_state_axes", "minecraft_world");
        assertEquals(Direction.NORTH, cells(drawing).values().iterator().next().state().getValue(StairBlock.FACING),
                "作者明确指定世界朝向时只转布局，按声明保留原生楼梯状态");
    }

    @Test
    void 两种坐标系给出同样的格() {
        var minecraft = mesh("Ramp", "wedge", new double[]{3.5, 2, .5}, new int[]{7, 4, 1}, "Body");
        minecraft.add("rotation_euler", jsonArray("[0,1.5707963267948966,0]"));
        var blender = mesh("Ramp", "wedge", new double[]{3.5, -.5, 2}, new int[]{7, 1, 4}, "Body");
        blender.add("rotation_euler", jsonArray("[0,0,1.5707963267948966]"));
        var converted = drawing(blender);
        converted.addProperty("coordinate_system", "blender_z_up");
        assertEquals(cells(drawing(minecraft)), cells(converted), "Blender 的竖直旋转应与游戏 Y 轴旋转生成相同斜面");
        var custom = mesh("Custom", "convex_polyhedron", new double[]{2, 2, 2}, new int[]{4, 4, 4}, "Body");
        custom.add("vertices", jsonArray("[[0,0,0],[1,0,0],[0,0,1],[0,1,0]]"));
        custom.add("faces", jsonArray("[[0,2,1],[0,1,3],[1,2,3],[2,0,3]]"));
        var customBlender = custom.deepCopy();
        customBlender.add("location", jsonArray("[2,-2,2]"));
        customBlender.add("vertices", jsonArray("[[0,1,0],[1,1,0],[0,0,0],[0,1,1]]"));
        var customDrawing = drawing(customBlender);
        customDrawing.addProperty("coordinate_system", "blender_z_up");
        assertEquals(cells(drawing(custom)), cells(customDrawing), "自定义凸网格的顶点也必须遵循图纸坐标系");
    }

    @Test
    void 运行态属性不能当验收标准() {
        var drawing = drawing(mesh("Pool", "cube", new double[]{.5, .5, .5}, new int[]{1, 1, 1}, "Wet"));
        drawing.getAsJsonObject("materials").add("Wet", json("{\"block_id\":\"minecraft:oak_stairs\",\"properties\":{\"waterlogged\":\"true\"}}"));
        String message = assertThrows(IllegalArgumentException.class, () -> cells(drawing)).getMessage();
        assertTrue(message.contains("waterlogged"), message);
        assertFalse(message.isBlank());
    }
}
