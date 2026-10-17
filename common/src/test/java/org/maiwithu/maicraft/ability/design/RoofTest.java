// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.design;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.maiwithu.maicraft.ability.design.DesignSamples.at;
import static org.maiwithu.maicraft.ability.design.DesignSamples.block;
import static org.maiwithu.maicraft.ability.design.DesignSamples.cells;
import static org.maiwithu.maicraft.ability.design.DesignSamples.count;
import static org.maiwithu.maicraft.ability.design.DesignSamples.drawing;
import static org.maiwithu.maicraft.ability.design.DesignSamples.jsonArray;
import static org.maiwithu.maicraft.ability.design.DesignSamples.roof;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 屋顶对象：檐口上半砖、坡面半砖三态、脊高出屋面、博风板与翘角；旋转时随脚印转，沿 y 镜像拒绝。 */
class RoofTest {

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 悬山顶的檐口坡面与脊() {
        var drawing = drawing(roof("Hall", new double[]{3.5, 0, 2}, new int[]{7, 4}, "Body"));
        var cells = cells(drawing);
        // 七乘四的脚印，屋脊沿 x；坡跨 3，半跨 1：檐口那排是上半砖，往里一排已经到脊。
        assertEquals("minecraft:stone_slab", block(cells, 3, 0, 0));
        assertEquals(SlabType.TOP, at(cells, 3, 0, 0).state().getValue(SlabBlock.TYPE), "檐口用上半砖做薄边");
        assertEquals(SlabType.DOUBLE, at(cells, 3, 1, 1).state().getValue(SlabBlock.TYPE), "偶数个半格高的坡面是双层半砖");
        assertTrue(block(cells, 3, 2, 1).equals("minecraft:stone_slab") && block(cells, 3, 3, 1).equals("minecraft:stone_slab"), "正脊在屋面上再加两格");
        assertEquals(SlabType.DOUBLE, at(cells, 3, 3, 1).state().getValue(SlabBlock.TYPE), "脊必须实心");
        assertEquals("minecraft:stone_slab", block(cells, 0, 1, 0), "悬山两端的博风板沿山面边缘高出一格");
        assertEquals("unspecified", block(cells, 3, 0, 1), "屋顶默认空心，坡面下面不填");
        assertTrue(cells.values().stream().allMatch(cell -> cell.state().getBlock() == Blocks.STONE_SLAB), "石头推得出石头半砖，整个屋顶都用半砖砌");
    }

    @Test
    void 填实山墙与翘角() {
        var roof = roof("Hall", new double[]{3.5, 0, 2}, new int[]{7, 4}, "Body");
        roof.addProperty("hollow", false);
        roof.addProperty("gable_material", "Trim");
        roof.addProperty("ridge_material", "Accent");
        roof.addProperty("corner_lift", 2);
        var cells = cells(drawing(roof));
        assertEquals("minecraft:stone", block(cells, 3, 0, 1), "不空心时檐口基准到屋面之间填实");
        assertTrue(count(cells, "minecraft:gold_block") > 0, "脊用指定材料");
        assertTrue(block(cells, 0, 1, 0).equals("minecraft:gold_block") && block(cells, 0, 2, 0).equals("minecraft:gold_block"), "翘角在四个檐角向上叠");
        assertEquals("minecraft:gold_block", block(cells, 1, 1, 0), "抬两格以上时角内相邻的格补过渡");
    }

    @Test
    void 庑殿与单坡() {
        var hip = roof("Hip", new double[]{2.5, 0, 2.5}, new int[]{5, 5}, "Body");
        hip.addProperty("shape", "wudian");
        var hipCells = cells(drawing(hip));
        assertTrue(at(hipCells, 2, 3, 2) != null && at(hipCells, 0, 3, 0) == null, "四坡在中央收顶，檐角处没有那么高");
        assertEquals(block(hipCells, 0, 0, 2), block(hipCells, 2, 0, 0), "四坡对称，四边檐口一样");
        var shed = roof("Shed", new double[]{2, 0, 1.5}, new int[]{4, 3}, "Body");
        shed.addProperty("shape", "shed");
        shed.addProperty("curve", "straight");
        var shedCells = cells(drawing(shed));
        // 直坡从檐口起每步两个半格：檐口那排是第 1 层的上半砖，往里每排高一格，高边没有脊。
        assertEquals(SlabType.TOP, at(shedCells, 1, 1, 0).state().getValue(SlabBlock.TYPE), "单坡的低边是檐口");
        assertEquals(SlabType.BOTTOM, at(shedCells, 1, 3, 2).state().getValue(SlabBlock.TYPE), "高边是奇数个半格，用下半砖");
        assertTrue(at(shedCells, 1, 0, 2) == null && at(shedCells, 1, 4, 2) == null, "单坡一整片倒向一侧，高边没有脊");
    }

    @Test
    void 旋转随脚印转_沿y镜像拒绝() {
        var roof = roof("Hall", new double[]{2, 0, 3.5}, new int[]{7, 4}, "Body");
        roof.add("rotation_euler", jsonArray("[0,1.5707963267948966,0]"));
        var cells = cells(drawing(roof));
        var plain = cells(drawing(roof("Hall", new double[]{3.5, 0, 2}, new int[]{7, 4}, "Body")));
        assertEquals(plain.size(), cells.size(), "转九十度后格数不变");
        assertEquals(SlabType.TOP, at(cells, 0, 0, 3).state().getValue(SlabBlock.TYPE), "转过去后檐口沿另一条轴");
        roof.remove("rotation_euler");
        roof.add("mirror", jsonArray("[\"y\"]"));
        assertThrows(IllegalArgumentException.class, () -> cells(drawing(roof)));
        var bad = roof("Hall", new double[]{3.5, 0, 2}, new int[]{7, 4}, "Body");
        bad.add("modifiers", jsonArray("[{\"type\":\"BOOLEAN\",\"operation\":\"DIFFERENCE\",\"object\":\"Hall\"}]"));
        assertThrows(IllegalArgumentException.class, () -> cells(drawing(bad)));
        var unknown = roof("Hall", new double[]{3.5, 0, 2}, new int[]{7, 4}, "Nope");
        assertThrows(IllegalArgumentException.class, () -> cells(drawing(unknown)));
        var offGrid = roof("Hall", new double[]{3.25, 0, 2}, new int[]{7, 4}, "Body");
        assertThrows(IllegalArgumentException.class, () -> cells(drawing(offGrid)));
    }
}
