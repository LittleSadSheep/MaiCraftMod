// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.design;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.maiwithu.maicraft.ability.design.DesignSamples.cells;
import static org.maiwithu.maicraft.ability.design.DesignSamples.component;
import static org.maiwithu.maicraft.ability.design.DesignSamples.drawing;
import static org.maiwithu.maicraft.ability.design.DesignSamples.instance;
import static org.maiwithu.maicraft.ability.design.DesignSamples.json;
import static org.maiwithu.maicraft.ability.design.DesignSamples.jsonArray;
import static org.maiwithu.maicraft.ability.design.DesignSamples.mesh;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 看图：阵列的展开路径压成一组，确实太多才分页；组件在自己原点的示意不冒充真实路径；作者的面顶点索引可继续编辑。 */
class DesignInspectionTest {

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 阵列的路径压成一组_跳过的逐条列() {
        var row = mesh("Rows", "cube", new double[]{.5, .5, .5}, new int[]{1, 1, 1}, "Body");
        row.add("array", json("{\"count\":[129,1,1],\"step\":[1,0,0]}"));
        var drawing = drawing(row);
        var described = DesignInspection.describeObject(drawing, "Rows", 0);
        assertEquals(129, described.get("expanded_object_count").getAsInt());
        assertEquals(1, described.getAsJsonArray("expanded_paths").size(), "铺满整盒的阵列压成一行");
        assertEquals("Rows[0..128,0,0]", described.getAsJsonArray("expanded_paths").get(0).getAsString());
        assertFalse(described.has("next_page"));
        assertEquals(128, DesignInspection.describeObject(drawing, "Rows[128,0,0]", 0).getAsJsonObject("minecraft_block_bounds").getAsJsonArray("from").get(0).getAsInt(),
                "压缩前的实际实例路径仍可继续读同一张图纸的对应对象");
        row.add("array", json("{\"count\":[3,1,1],\"step\":[1,0,0],\"skip\":[[1,0,0]]}"));
        var sparse = DesignInspection.describeObject(drawing, "Rows", 0);
        assertEquals(List.of("Rows[0,0,0]", "Rows[2,0,0]"), sparse.getAsJsonArray("expanded_paths").asList().stream().map(value -> value.getAsString()).toList(),
                "铺不满的阵列逐条列出");
    }

    @Test
    void 路径分组的规则() {
        assertEquals(List.of("Arcade[0..2,0,0]/Shell"), DesignInspection.pathGroups(List.of("Arcade[0,0,0]/Shell", "Arcade[1,0,0]/Shell", "Arcade[2,0,0]/Shell")));
        assertEquals(List.of("Wall"), DesignInspection.pathGroups(List.of("Wall")));
        assertEquals(List.of("A[0..1,0,0]/B[0..1,0,0]"),
                DesignInspection.pathGroups(List.of("A[0,0,0]/B[0,0,0]", "A[0,0,0]/B[1,0,0]", "A[1,0,0]/B[0,0,0]", "A[1,0,0]/B[1,0,0]")),
                "两层阵列对得上份数时整体压成一组");
        assertEquals(List.of("A[0,0,0]/B[0,0,0]", "A[0,0,0]/B[1,0,0]", "A[1,0,0]/B[0,0,0]"),
                DesignInspection.pathGroups(List.of("A[0,0,0]/B[0,0,0]", "A[0,0,0]/B[1,0,0]", "A[1,0,0]/B[0,0,0]")),
                "对不上份数就逐条列，不丢路径");
    }

    @Test
    void 太多组才分页() {
        var objects = new ArrayList<JsonObject>();
        for (int i = 0; i < 70; i++) objects.add(mesh("Post" + i, "cube", new double[]{i + .5, .5, .5}, new int[]{1, 1, 1}, "Body"));
        var drawing = drawing(objects.toArray(new JsonObject[0]));
        var first = DesignInspection.describeDesign(drawing, 0);
        assertEquals(32, first.getAsJsonArray("objects").size());
        assertEquals(1, first.get("next_page").getAsInt());
        var last = DesignInspection.describeDesign(drawing, 2);
        assertEquals(6, last.getAsJsonArray("objects").size());
        assertFalse(last.has("next_page"));
    }

    @Test
    void 组件原点的示意不冒充真实路径() {
        var drawing = drawing(instance("Actual", "Piece", 0, 0, 0));
        component(drawing, "Piece", mesh("Block", "cube", new double[]{.5, .5, .5}, new int[]{1, 1, 1}, "Body"));
        var described = DesignInspection.describeComponent(drawing, "Piece", 0);
        assertTrue(described.has("definition") && described.has("local_expanded_paths") && !described.has("expanded_paths")
                && !described.get("local_paths_are_scene_queries").getAsBoolean(), "组件原点的示意路径不能冒充真实实例路径");
        drawing.getAsJsonArray("objects").get(0).getAsJsonObject().add("location", jsonArray("[0.25,0,0]"));
        drawing.getAsJsonObject("components").getAsJsonObject("Piece").getAsJsonArray("objects").get(0).getAsJsonObject().add("location", jsonArray("[0.25,0.5,0.5]"));
        assertEquals(1, cells(drawing).size(), "局部四分之一格平移可在真实实例中合成对齐的一个方块");
        described = DesignInspection.describeComponent(drawing, "Piece", 0);
        assertTrue(described.has("definition") && described.has("local_geometry_unavailable"), "原点示意不对齐时仍须返回合法定义及具体原因");
    }

    @Test
    void 作者的面顶点索引可继续编辑() {
        var mesh = mesh("Custom", "convex_polyhedron", new double[]{2, 2, 2}, new int[]{4, 4, 4}, "Body");
        mesh.add("vertices", jsonArray("[[0,0,0],[1,0,0],[0,0,1],[0,1,0]]"));
        mesh.add("faces", jsonArray("[[0,2,1],[0,1,3],[1,2,3],[2,0,3]]"));
        var described = DesignInspection.describeObject(drawing(mesh), "Custom", 0);
        assertTrue(described.get("faces").equals(mesh.get("faces")) && described.getAsJsonArray("surface_faces").size() == 4
                && described.getAsJsonArray("surface_edges").size() == 6, "派生面棱说明不能覆盖作者原来的面顶点索引");
    }
}
