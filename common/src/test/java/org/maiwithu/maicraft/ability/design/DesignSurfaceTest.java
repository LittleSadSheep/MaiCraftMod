// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.design;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.maiwithu.maicraft.ability.design.DesignSamples.block;
import static org.maiwithu.maicraft.ability.design.DesignSamples.cells;
import static org.maiwithu.maicraft.ability.design.DesignSamples.compile;
import static org.maiwithu.maicraft.ability.design.DesignSamples.count;
import static org.maiwithu.maicraft.ability.design.DesignSamples.drawing;
import static org.maiwithu.maicraft.ability.design.DesignSamples.json;
import static org.maiwithu.maicraft.ability.design.DesignSamples.jsonArray;
import static org.maiwithu.maicraft.ability.design.DesignSamples.mesh;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 真正输出的方块层、空腔和涂装位置：几何对象存在不等于已经正确体素化。 */
class DesignSurfaceTest {

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 三角类的逐层轮廓() {
        var triangle = mesh("Gable", "triangle", new double[]{3.5, 2, .5}, new int[]{7, 4, 1}, "Body");
        var cells = cells(drawing(triangle));
        assertEquals(16, cells.size(), "七格宽四格高的山墙应展开为7、5、3、1四层");
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 7; x++) {
                assertEquals(x >= y && x < 7 - y ? "minecraft:stone" : "unspecified", block(cells, x, y, 0), "三角外轮廓以外不额外清空地形");
            }
        }
        var wedge = mesh("Ramp", "wedge", new double[]{2, 2, .5}, new int[]{4, 4, 1}, "Body");
        cells = cells(drawing(wedge));
        assertEquals(10, cells.size(), "直角斜坡是4、3、2、1层");
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) assertEquals(x + y <= 3 ? "minecraft:stone" : "unspecified", block(cells, x, y, 0), "直角斜面不能展开成整块长方体");
        }
        assertEquals(48, cells(drawing(mesh("Prism", "triangular_prism", new double[]{3.5, 2, 1.5}, new int[]{7, 4, 3}, "Body"))).size(),
                "三棱柱须沿厚度保留三个相同三角截面");
        assertEquals(45, cells(drawing(mesh("Roof", "pyramid", new double[]{2.5, 2.5, 2.5}, new int[]{5, 5, 5}, "Body"))).size(),
                "方锥体展开为25、9、9、1、1五层，尖顶不能被包围盒填满");
    }

    @Test
    void 空心与开口面() {
        var box = mesh("Room", "cube", new double[]{2.5, 2.5, 2.5}, new int[]{5, 5, 5}, "Body");
        assertEquals(125, count(cells(drawing(box)), "minecraft:stone"), "实心体保留整个体积");
        box.addProperty("fill", "hollow");
        var hollow = cells(drawing(box));
        assertTrue(count(hollow, "minecraft:stone") == 98 && count(hollow, "minecraft:air") == 27, "一格厚封闭房间有三乘三乘三的明确空腔");
        box.add("open_faces", jsonArray("[\"top\"]"));
        var open = cells(drawing(box));
        assertTrue(count(open, "minecraft:stone") == 89 && block(open, 2, 4, 2).equals("minecraft:air")
                && block(open, 0, 4, 2).equals("minecraft:stone"), "去顶只打开内侧顶盖，侧墙仍延伸到最高一层");
        box.remove("open_faces");
        box.addProperty("wall_thickness", 2);
        assertEquals(1, count(cells(drawing(box)), "minecraft:air"), "壁厚改变的是整个空腔，而不只是显示属性");
    }

    @Test
    void 面与棱的涂装() {
        var box = mesh("Facade", "cube", new double[]{2.5, 2.5, 2.5}, new int[]{5, 5, 5}, "Body");
        box.add("face_materials", json("{\"front\":\"Glass\",\"top\":\"Top\"}"));
        box.addProperty("edge_material", "Trim");
        box.add("edge_materials", json("{\"top+front\":\"Accent\"}"));
        var painted = cells(drawing(box));
        assertTrue(block(painted, 2, 2, 0).equals("minecraft:glass") && block(painted, 2, 4, 2).equals("minecraft:red_concrete"), "各面材质落到对应表面");
        assertTrue(block(painted, 0, 0, 2).equals("minecraft:quartz_block") && block(painted, 2, 4, 0).equals("minecraft:gold_block"), "默认包边和指定棱材质分别生效");
        assertEquals("minecraft:stone", block(painted, 2, 2, 2), "表面涂装不能把实心内部也全部换掉");
        box.add("edge_materials", json("{\"top+front\":\"Accent\",\"front+top\":\"Trim\"}"));
        assertThrows(IllegalArgumentException.class, () -> cells(drawing(box)), "同一条棱的反向别名不能同时绑定两个材料");
    }

    @Test
    void 空腔只影响自己_叠加有账() {
        var shell = mesh("Shell", "cube", new double[]{2.5, 2.5, 2.5}, new int[]{5, 5, 5}, "Body");
        shell.addProperty("fill", "hollow");
        var insert = mesh("Insert", "cube", new double[]{2.5, 2.5, 2.5}, new int[]{1, 1, 1}, "Accent");
        assertEquals(cells(drawing(shell, insert)), cells(drawing(insert, shell)), "先写的内饰不会被另一个对象的空腔清掉");
        var conflicting = drawing(mesh("Base", "cube", new double[]{.5, .5, .5}, new int[]{1, 1, 1}, "Body"),
                mesh("Trim", "cube", new double[]{.5, .5, .5}, new int[]{1, 1, 1}, "Trim"));
        var compiled = compile(conflicting);
        assertTrue(compiled.overlapCells() == 1 && compiled.overlapExamples().getFirst().incomingObject().equals("Trim")
                && block(cells(conflicting), 0, 0, 0).equals("minecraft:quartz_block"), "默认保留后写材质并提供可查的冲突诊断");
        conflicting.addProperty("overlap_policy", "error");
        assertThrows(IllegalArgumentException.class, () -> compile(conflicting), "严格冲突策略须在施工前拒绝不同材质覆盖");
    }
}
