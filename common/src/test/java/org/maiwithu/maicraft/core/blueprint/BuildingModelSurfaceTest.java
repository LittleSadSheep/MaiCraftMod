// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.blueprint;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import static org.maiwithu.maicraft.core.blueprint.BuildingModelTestData.*;

/** 核对真正输出的方块层、空腔和涂装位置，不把几何对象存在就当作已经正确体素化。 */
public final class BuildingModelSurfaceTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        triangularProfiles(); hollowAndOpenFaces(); faceAndEdgePaint(); scopedVoidsAndOverlap();
        System.out.println("BuildingModelSurfaceTest: triangular voxels, cavities, openings, face/edge paint and scoped voids passed");
    }
    private static void triangularProfiles() {
        var triangle = mesh("Gable", "triangle", new double[]{3.5,2,.5}, new int[]{7,4,1}, "Body");
        var cells = cells(scene(triangle));
        check(cells.size() == 16, "七格宽四格高的山墙应展开为7、5、3、1四层");
        for (int y = 0; y < 4; y++) for (int x = 0; x < 7; x++)
            check(block(cells,x,y,0).equals(x >= y && x < 7-y ? "minecraft:stone" : "unspecified"), "三角外轮廓以外不额外清空地形");
        var wedge = mesh("Ramp", "wedge", new double[]{2,2,.5}, new int[]{4,4,1}, "Body");
        cells = cells(scene(wedge)); check(cells.size() == 10, "直角斜坡是4、3、2、1层");
        for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++)
            check(block(cells,x,y,0).equals(x+y <= 3 ? "minecraft:stone" : "unspecified"), "直角斜面不能展开成整块长方体");
        check(cells(scene(mesh("Prism", "triangular_prism", new double[]{3.5,2,1.5},new int[]{7,4,3},"Body"))).size() == 48,
                "三棱柱须沿厚度保留三个相同三角截面");
        check(cells(scene(mesh("Roof", "pyramid", new double[]{2.5,2.5,2.5},new int[]{5,5,5},"Body"))).size() == 45,
                "方锥体展开为25、9、9、1、1五层，尖顶不能被包围盒填满");
    }
    private static void hollowAndOpenFaces() {
        var box = mesh("Room", "cube", new double[]{2.5,2.5,2.5},new int[]{5,5,5},"Body");
        check(count(cells(scene(box)),"minecraft:stone") == 125, "实心体保留整个体积");
        box.addProperty("fill","hollow"); var hollow = cells(scene(box));
        check(count(hollow,"minecraft:stone") == 98 && count(hollow,"minecraft:air") == 27, "一格厚封闭房间有三乘三乘三的明确空腔");
        box.add("open_faces", json("{\"v\":[\"top\"]}").get("v")); var open = cells(scene(box));
        check(count(open,"minecraft:stone") == 89 && block(open,2,4,2).equals("minecraft:air")
                && block(open,0,4,2).equals("minecraft:stone"), "去顶只打开内侧顶盖，侧墙仍延伸到最高一层");
        box.remove("open_faces"); box.addProperty("wall_thickness",2);
        check(count(cells(scene(box)),"minecraft:air") == 1, "壁厚改变的是整个空腔，而不只是显示属性");
    }
    private static void faceAndEdgePaint() {
        var box = mesh("Facade", "cube",new double[]{2.5,2.5,2.5},new int[]{5,5,5},"Body");
        box.add("face_materials",json("{\"front\":\"Glass\",\"top\":\"Top\"}"));
        box.addProperty("edge_material","Trim"); box.add("edge_materials",json("{\"top+front\":\"Accent\"}"));
        var painted = cells(scene(box));
        check(block(painted,2,2,0).equals("minecraft:glass") && block(painted,2,4,2).equals("minecraft:red_concrete"), "各面材质落到对应表面");
        check(block(painted,0,0,2).equals("minecraft:quartz_block") && block(painted,2,4,0).equals("minecraft:gold_block"), "默认包边和指定棱材质分别生效");
        check(block(painted,2,2,2).equals("minecraft:stone"), "表面涂装不能把实心内部也全部换掉");
        box.add("edge_materials",json("{\"top+front\":\"Accent\",\"front+top\":\"Trim\"}"));
        rejects(() -> cells(scene(box)), "同一条棱的反向别名不能同时绑定两个材料");
    }
    private static void scopedVoidsAndOverlap() {
        var shell = mesh("Shell","cube",new double[]{2.5,2.5,2.5},new int[]{5,5,5},"Body"); shell.addProperty("fill","hollow");
        var insert = mesh("Insert","cube",new double[]{2.5,2.5,2.5},new int[]{1,1,1},"Accent");
        check(cells(scene(shell,insert)).equals(cells(scene(insert,shell))), "先写的内饰不会被另一个对象的空腔清掉");
        var conflicting = scene(mesh("Base","cube",new double[]{.5,.5,.5},new int[]{1,1,1},"Body"),
                mesh("Trim","cube",new double[]{.5,.5,.5},new int[]{1,1,1},"Trim"));
        var compiled = BuildingSceneCompiler.compile(conflicting);
        check(compiled.getAsJsonObject("metadata").get("overlap_conflicting_cells").getAsInt() == 1
                && block(cells(conflicting),0,0,0).equals("minecraft:quartz_block"), "默认保留后写材质并提供可查的冲突诊断");
        conflicting.addProperty("overlap_policy","error"); rejects(() -> BuildingSceneCompiler.compile(conflicting), "严格冲突策略须在施工前拒绝不同材质覆盖");
    }
}
