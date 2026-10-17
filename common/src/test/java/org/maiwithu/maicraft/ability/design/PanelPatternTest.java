// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.design;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.maiwithu.maicraft.ability.design.DesignSamples.at;
import static org.maiwithu.maicraft.ability.design.DesignSamples.block;
import static org.maiwithu.maicraft.ability.design.DesignSamples.cells;
import static org.maiwithu.maicraft.ability.design.DesignSamples.component;
import static org.maiwithu.maicraft.ability.design.DesignSamples.count;
import static org.maiwithu.maicraft.ability.design.DesignSamples.drawing;
import static org.maiwithu.maicraft.ability.design.DesignSamples.instance;
import static org.maiwithu.maicraft.ability.design.DesignSamples.json;
import static org.maiwithu.maicraft.ability.design.DesignSamples.jsonArray;
import static org.maiwithu.maicraft.ability.design.DesignSamples.mesh;

import com.google.gson.JsonObject;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 板上的平面图案：真正展开的空气、方块和半砖状态；首格为零、负坐标与组件变换都不改变作者的图案。 */
class PanelPatternTest {

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 棋盘格保持相位() {
        // 从零格开头，在负坐标仍按对象最小角重复；尺寸不是图案宽度的整数倍时保留最后一列。
        var panel = panel();
        panel.add("location", jsonArray("[-2.5,2,0.5]"));
        panel.add("dimensions", jsonArray("[5,4,1]"));
        var cells = cells(drawing(panel));
        assertEquals(20, cells.size(), "留空格也属于最终蓝图的目标");
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 5; x++) assertEquals((x + y) % 2 == 0 ? "minecraft:air" : "minecraft:stone", block(cells, x - 5, y, 0), "零开头棋盘格必须保持原相位");
        }
        panel.getAsJsonObject("pattern").add("rows", jsonArray("[\"10\",\"01\"]"));
        assertEquals("minecraft:stone", block(cells(drawing(panel)), -5, 0, 0), "一开头同样按输入保留首格");
        panel.getAsJsonObject("pattern").add("rows", jsonArray("[\"0\"]"));
        assertEquals(20, count(cells(drawing(panel)), "minecraft:air"), "图案不要求至少出现一个一格");
    }

    @Test
    void 半砖条纹与镜像() {
        // 一格映射上半砖、零格映射下半砖；零开头时第一列必须是下半砖，而不是自动从上半砖起排。
        var panel = panel();
        panel.addProperty("material", "Upper");
        panel.add("pattern", json("{\"axes\":[\"x\",\"y\"],\"rows\":[\"01\"],\"materials\":{\"0\":\"Lower\"}}"));
        var drawing = drawing(panel);
        drawing.getAsJsonObject("materials").add("Upper", json("{\"block_id\":\"minecraft:stone_slab\",\"properties\":{\"type\":\"top\"}}"));
        drawing.getAsJsonObject("materials").add("Lower", json("{\"block_id\":\"minecraft:stone_slab\",\"properties\":{\"type\":\"bottom\"}}"));
        for (int mode = 0; mode < 3; mode++) {
            if (mode > 0) panel.add("mirror", jsonArray(mode == 1 ? "[\"x\"]" : "[\"y\"]"));
            var cells = cells(drawing);
            for (int y = 0; y < 4; y++) {
                for (int x = 0; x < 4; x++) {
                    var cell = at(cells, x, y, 0);
                    assertEquals("minecraft:stone_slab", block(cells, x, y, 0), "半砖交替不产生整格空气");
                    assertEquals((x % 2 == 0) == (mode == 0) ? SlabType.BOTTOM : SlabType.TOP, cell.state().getValue(SlabBlock.TYPE),
                            "横向镜像反转图案，竖向镜像还须翻转半砖上下状态");
                }
            }
        }
        panel.remove("mirror");
        panel.add("location", jsonArray("[2,0.5,2]"));
        panel.add("rotation_euler", jsonArray("[1.5707963267948966,0,0]"));
        assertThrows(IllegalArgumentException.class, () -> cells(drawing), "不能把上下半砖旋转成游戏不存在的竖直半砖");
    }

    @Test
    void 变换后的组件保留相位与配色() {
        // 阵列沿局部轴展开后整体旋转，每个窗格保留同样的零开头相位，并沿用实例的材质映射。
        var instance = instance("Screens", "Screen", 10, 0, 0);
        instance.add("rotation_euler", jsonArray("[0,1.5707963267948966,0]"));
        instance.add("array", json("{\"count\":[2,1,1],\"step\":[6,0,0]}"));
        instance.add("material_map", json("{\"Body\":\"Glass\",\"Trim\":\"Accent\"}"));
        var panel = panel();
        panel.getAsJsonObject("pattern").add("materials", json("{\"1\":\"Trim\"}"));
        var drawing = drawing(instance);
        component(drawing, "Screen", panel);
        var cells = cells(drawing);
        for (int i = 0; i < 2; i++) {
            assertEquals("minecraft:air", block(cells, 10, 0, -1 - 6 * i), "复制和旋转后首格仍留空");
            assertEquals("minecraft:gold_block", block(cells, 10, 0, -2 - 6 * i), "图案具名材质必须经过组件配色映射");
        }
    }

    @Test
    void 平面方向与Blender坐标() {
        // 水平天花、侧墙与 Blender 坐标输入共用 Minecraft 局部轴，不把厚度轴误当成图案行。
        for (String axes : new String[]{"[\"x\",\"z\"]", "[\"z\",\"y\"]"}) {
            boolean floor = axes.contains("x");
            var panel = mesh("Grid", "panel", floor ? new double[]{2, .5, 2} : new double[]{.5, 2, 2},
                    floor ? new int[]{4, 1, 4} : new int[]{1, 4, 4}, "Body");
            panel.add("pattern", json("{\"axes\":" + axes + ",\"rows\":[\"01\",\"10\"]}"));
            var cells = cells(drawing(panel));
            for (int v = 0; v < 4; v++) {
                for (int u = 0; u < 4; u++) {
                    assertEquals((u + v) % 2 == 0 ? "minecraft:air" : "minecraft:stone",
                            block(cells, floor ? u : 0, floor ? 0 : v, floor ? v : u), "图案按声明的两条平面轴排列");
                }
            }
        }
        var panel = panel();
        panel.add("dimensions", jsonArray("[4,1,4]"));
        panel.add("location", jsonArray("[2,0.5,2]"));
        var drawing = drawing(panel);
        drawing.addProperty("coordinate_system", "blender_z_up");
        var cells = cells(drawing);
        assertTrue(block(cells, 0, 0, -1).equals("minecraft:air") && block(cells, 1, 0, -1).equals("minecraft:stone"), "Blender 墙面换轴后保留图案与厚度");
    }

    @Test
    void 切割与填入() {
        // 切割继续作用于最终格网；留孔只约束本对象，独立的玻璃或框架可以填入孔内。
        var panel = panel();
        var insert = mesh("Insert", "cube", new double[]{.5, .5, .5}, new int[]{1, 1, 1}, "Glass");
        assertEquals(cells(drawing(panel, insert)), cells(drawing(insert, panel)), "留孔不能清掉另一个对象的玻璃");
        var cut = mesh("Cut", "cube", new double[]{1.5, .5, .5}, new int[]{1, 1, 1}, null);
        panel.add("modifiers", jsonArray("[{\"type\":\"BOOLEAN\",\"operation\":\"DIFFERENCE\",\"object\":\"Cut\"}]"));
        assertEquals("minecraft:air", block(cells(drawing(panel, cut)), 1, 0, 0), "布尔切割可以移除图案里原本保留的实体格");
    }

    @Test
    void 错误图案拒绝() {
        for (String bad : new String[]{"{\"axes\":[\"x\",\"x\"],\"rows\":[\"01\"]}", "{\"axes\":[\"x\",\"z\"],\"rows\":[\"01\"]}",
                "{\"axes\":[\"x\",\"y\"],\"rows\":[\"01\",\"0\"]}", "{\"axes\":[\"x\",\"y\"],\"rows\":[\"02\"]}",
                "{\"axes\":[\"x\",\"y\"],\"rows\":[]}", "{\"axes\":[\"x\",\"y\"],\"rows\":[\"\"]}",
                "{\"axes\":[\"x\",\"y\"],\"rows\":[\"01\"],\"materials\":{\"0\":\"Missing\"}}",
                "{\"axes\":[\"x\",\"y\"],\"rows\":[\"01\"],\"phase\":1}"}) {
            var panel = panel();
            panel.add("pattern", json(bad));
            assertThrows(IllegalArgumentException.class, () -> cells(drawing(panel)), "错误图案不能静默忽略：" + bad);
            var unused = drawing(panel());
            component(unused, "Unused", panel);
            assertThrows(IllegalArgumentException.class, () -> cells(unused), "未使用组件也必须通过图案校验：" + bad);
        }
        var panel = panel();
        panel.addProperty("primitive", "cube");
        assertThrows(IllegalArgumentException.class, () -> cells(drawing(panel)), "图案仅用于平面");
        panel.addProperty("primitive", "panel");
        panel.addProperty("role", "cutter");
        var solid = mesh("Solid", "cube", new double[]{.5, .5, .5}, new int[]{1, 1, 1}, "Body");
        assertThrows(IllegalArgumentException.class, () -> cells(drawing(solid, panel)), "带孔图案不能充当实心切割体");
        panel.remove("role");
        solid.add("modifiers", jsonArray("[{\"type\":\"BOOLEAN\",\"operation\":\"DIFFERENCE\",\"object\":\"Grid\"}]"));
        assertThrows(IllegalArgumentException.class, () -> cells(drawing(solid, panel)), "引用式切割也不能静默把网格孔洞当成实心");
    }

    private static JsonObject panel() {
        var panel = mesh("Grid", "panel", new double[]{2, 2, .5}, new int[]{4, 4, 1}, "Body");
        panel.add("pattern", json("{\"axes\":[\"x\",\"y\"],\"rows\":[\"01\",\"10\"]}"));
        return panel;
    }
}
