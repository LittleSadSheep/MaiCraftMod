// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.design;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/** 自定义凸体：先查拓扑和真实外形；缺面、内凹与自交都拒绝，不偷偷补面或改写作者的网格。 */
class ConvexMeshTest {

    private static final Vec3 SIZE = new Vec3(6, 8, 10);

    @Test
    void 合法立方体与反向绕序() {
        JsonObject object = cube();
        String before = object.toString();
        var first = shape(object);
        assertTrue(first.faces().size() == 6 && first.edges().size() == 12 && first.contains(Vec3.ZERO)
                && !first.contains(new Vec3(3.1, 0, 0)), "归一化自定义立方体应与实际六乘八乘十边界一致");
        assertTrue(first.boundsMin().equals(SIZE.scale(-.5)) && first.boundsMax().equals(SIZE.scale(.5)), "自定义顶点按单位框中心缩放");
        assertEquals(before, object.toString(), "几何构建不能改写作者的顶点或绕序");
        for (int i = 0; i < object.getAsJsonArray("faces").size(); i++) {
            JsonArray original = object.getAsJsonArray("faces").get(i).getAsJsonArray(), reversed = new JsonArray();
            for (int j = original.size() - 1; j >= 0; j--) reversed.add(original.get(j));
            object.getAsJsonArray("faces").set(i, reversed);
        }
        var reversed = shape(object);
        assertEquals(first.edges(), reversed.edges(), "反转面绕序后棱编号、相邻面与端点不能变化");
        for (int i = 0; i < first.faces().size(); i++) {
            var a = first.faces().get(i);
            var b = reversed.faces().get(i);
            assertTrue(a.id().equals("face_" + i) && a.id().equals(b.id()) && a.normal().distanceTo(b.normal()) < 1e-9
                    && Math.abs(a.offset() - b.offset()) < 1e-9, "面ID按作者索引稳定，法线统一朝向外侧");
        }
        assertThrows(UnsupportedOperationException.class, () -> first.faces().clear(), "检查接口不能让外部调用者删改已验证的面集合");
    }

    @Test
    void 八面体用它的八个斜面() {
        var octahedron = shape(octahedron());
        assertTrue(octahedron.faces().size() == 8 && octahedron.edges().size() == 12, "八面体保持八个真实斜面和十二条棱");
        assertTrue(octahedron.contains(new Vec3(1, 1, 1)) && !octahedron.contains(new Vec3(2, 3, 4)), "凸体判定必须按各斜面，不能只按包围盒");
        for (var edge : octahedron.edges()) {
            for (String name : edge.faces()) {
                var face = octahedron.faces().stream().filter(value -> value.id().equals(name)).findFirst().orElseThrow();
                assertTrue(Math.abs(face.distance(edge.from())) < 1e-8 && Math.abs(face.distance(edge.to())) < 1e-8, "非等比缩放后，真实棱仍同时位于相邻两面");
            }
        }
    }

    @Test
    void 坏坐标与坏下标拒绝() {
        for (String vertex : List.of("[0,0]", "[2,0,0]", "[-0.01,0,0]", "[\"0\",0,0]", "null")) {
            var object = cube();
            object.getAsJsonArray("vertices").set(0, JsonParser.parseString(vertex));
            rejected(object, "错误顶点不得进入几何搜索");
        }
        var nonfinite = cube();
        nonfinite.getAsJsonArray("vertices").get(0).getAsJsonArray().set(0, new JsonPrimitive(Double.NaN));
        rejected(nonfinite, "非有限顶点必须拒绝");
        for (String face : List.of("[0,1]", "[0,1,1,3]", "[0,1,99]", "[0,-1,2]", "[0,1.5,2]", "[0,\"1\",2]")) {
            var object = cube();
            object.getAsJsonArray("faces").set(0, JsonParser.parseString(face));
            rejected(object, "错误索引或退化面必须拒绝");
        }
        var duplicate = cube();
        duplicate.getAsJsonArray("vertices").set(7, duplicate.getAsJsonArray("vertices").get(0).deepCopy());
        rejected(duplicate, "重复顶点不能伪造独立拓扑节点");
        var unused = cube();
        unused.getAsJsonArray("vertices").add(JsonParser.parseString("[0.5,0.5,0.5]"));
        rejected(unused, "未被任何面使用的顶点不能混入凸壳");
        var vertices = cube();
        while (vertices.getAsJsonArray("vertices").size() <= 64) vertices.getAsJsonArray("vertices").add(JsonParser.parseString("[0.5,0.5,0.5]"));
        rejected(vertices, "顶点上限在拓扑搜索前检查");
        var faces = cube();
        while (faces.getAsJsonArray("faces").size() <= 64) faces.getAsJsonArray("faces").add(JsonParser.parseString("[0,1,2]"));
        rejected(faces, "面数量上限在拓扑搜索前检查");
    }

    @Test
    void 开口内凹与退化表面拒绝() {
        var open = cube();
        open.getAsJsonArray("faces").remove(4);
        rejected(open, "少一面形成开口，不能自动封口当实体");
        var crossing = cube();
        crossing.getAsJsonArray("faces").set(4, JsonParser.parseString("[0,2,1,3]"));
        rejected(crossing, "四角交叉绕序必须拒绝");
        var nonplanar = cube();
        nonplanar.getAsJsonArray("vertices").get(2).getAsJsonArray().set(1, new JsonPrimitive(.75));
        rejected(nonplanar, "一个角翘起的四边面不能冒充平面");
        var concave = octahedron();
        concave.getAsJsonArray("vertices").set(2, JsonParser.parseString("[0.5,0.25,0.5]"));
        rejected(concave, "各面仍是平面三角形的内凹顶点，必须由全局凸性检查拒绝");
        var flat = json("{\"vertices\":[[0,0.5,0],[1,0.5,0],[1,0.5,1],[0,0.5,1]],\"faces\":[[0,1,2],[0,3,1],[0,2,3],[1,3,2]]}");
        rejected(flat, "共面封壳没有内部体积，不能制造零厚度建筑");
        var doubled = cube();
        doubled.getAsJsonArray("faces").add(doubled.getAsJsonArray("faces").get(0).deepCopy());
        rejected(doubled, "一条边连接三个面的非流形表面不能接受");
    }

    @Test
    void 共面细分不是几何棱() {
        var split = cube();
        split.getAsJsonArray("faces").remove(4);
        split.getAsJsonArray("faces").add(JsonParser.parseString("[0,1,2]"));
        split.getAsJsonArray("faces").add(JsonParser.parseString("[0,2,3]"));
        rejected(split, "共面的两片须合并，避免面材质重复匹配和凭空出现装饰对角棱");
        var collinear = cube();
        collinear.getAsJsonArray("vertices").add(JsonParser.parseString("[0,0.5,0]"));
        collinear.getAsJsonArray("faces").set(0, JsonParser.parseString("[0,8,3,7,4]"));
        collinear.getAsJsonArray("faces").set(4, JsonParser.parseString("[0,1,2,3,8]"));
        rejected(collinear, "同一几何棱的冗余中间点须去掉，不能生成相同相邻面编号的两条棱");
    }

    private static JsonObject cube() {
        return json("{\"vertices\":[[0,0,0],[1,0,0],[1,1,0],[0,1,0],[0,0,1],[1,0,1],[1,1,1],[0,1,1]],"
                + "\"faces\":[[0,3,7,4],[1,5,6,2],[0,4,5,1],[3,2,6,7],[0,1,2,3],[4,7,6,5]]}");
    }

    private static JsonObject octahedron() {
        return json("{\"vertices\":[[1,0.5,0.5],[0,0.5,0.5],[0.5,1,0.5],[0.5,0,0.5],[0.5,0.5,1],[0.5,0.5,0]],"
                + "\"faces\":[[0,2,4],[4,2,1],[1,2,5],[5,2,0],[4,3,0],[1,3,4],[5,3,1],[0,3,5]]}");
    }

    private static DesignShape shape(JsonObject object) {
        return DesignShape.create("convex_polyhedron", object, SIZE);
    }

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static void rejected(JsonObject object, String message) {
        assertThrows(IllegalArgumentException.class, () -> shape(object), message);
    }
}
