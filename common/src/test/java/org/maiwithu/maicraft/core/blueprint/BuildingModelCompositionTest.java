// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.blueprint;

import com.google.gson.JsonObject;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import static org.maiwithu.maicraft.core.blueprint.BuildingModelTestData.*;

/** 检查组件的真实展开结果，包含每份窗洞隔离、阵列留门、嵌套配色和带朝向方块的几何变换。 */
public final class BuildingModelCompositionTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        isolatedWindowsAndSkippedBay(); nestedPaletteMaps(); transformsKeepBlockDirections(); coordinateSystemsAgree();
        System.out.println("BuildingModelCompositionTest: component cuts, array gaps, nested palettes and native directions passed");
    }
    private static void isolatedWindowsAndSkippedBay() {
        var glass = mesh("Glazing","triangle",new double[]{3.5,2,.5},new int[]{3,2,1},"Glass");
        var cut = mesh("WindowCut","triangle",new double[]{3.5,2,.5},new int[]{3,2,1},null);
        var wall = mesh("Wall","cube",new double[]{3.5,2,.5},new int[]{7,4,1},"Body");
        wall.add("modifiers",json("{\"v\":[{\"type\":\"BOOLEAN\",\"operation\":\"DIFFERENCE\",\"object\":\"WindowCut\"}]}").get("v"));
        var bays = instance("Bays","Window",0,0,0);
        bays.add("array",json("{\"count\":[3,1,1],\"step\":[9,0,0],\"skip\":[[1,0,0]]}"));
        var scene = scene(bays); component(scene,"Window",glass,cut,wall);
        String original = scene.toString(); var result = cells(scene);
        check(result.size() == 56 && count(result,"minecraft:glass") == 8 && count(result,"minecraft:stone") == 48,
                "两份窗饰各保留四格三角玻璃，开孔不会串到另一份实例");
        check(block(result,9,0,0).equals("unspecified") && block(result,3,1,0).equals("minecraft:glass")
                && block(result,21,1,0).equals("minecraft:glass"), "阵列中间整份跳过为门留空间，两侧窗洞保持各自位置");
        var compiled = BuildingSceneCompiler.compile(scene);
        check(compiled.getAsJsonObject("metadata").get("expanded_object_count").getAsInt() == 6
                && compiled.getAsJsonObject("metadata").get("cutter_count").getAsInt() == 2, "源组件与实际展开数量分别记录");
        check(scene.toString().equals(original) && compiled.equals(BuildingSceneCompiler.compile(scene)), "编译必须确定且不把展开节点塞回作者原场景");
        var info = BuildingSceneInspection.objectInfo(scene,"Bays[2,0,0]/WindowCut");
        check(!info.get("visible").getAsBoolean() && info.getAsJsonObject("minecraft_block_bounds").getAsJsonArray("from").get(0).getAsInt() == 20,
                "实际展开的切割路径可查询，并保留第二份实例的坐标");
    }
    private static void nestedPaletteMaps() {
        var root = instance("Facade","Outer",0,0,0); root.add("material_map",json("{\"Trim\":\"Accent\"}"));
        var scene = scene(root); var child = instance("Repeated","Inner",0,0,0);
        child.add("material_map",json("{\"Body\":\"Trim\"}"));
        component(scene,"Outer",child);
        component(scene,"Inner",mesh("Block","cube",new double[]{.5,.5,.5},new int[]{1,1,1},"Body"));
        check(block(cells(scene),0,0,0).equals("minecraft:gold_block"), "先应用内层配色，再让外层主题替换结果材质");
        check(scene.getAsJsonObject("components").getAsJsonObject("Inner").getAsJsonArray("objects").get(0).getAsJsonObject().get("material").getAsString().equals("Body"),
                "换色只影响实例，不污染可继续复用的原组件");
    }
    private static void transformsKeepBlockDirections() {
        var instance = instance("Turned","Step",4,0,4);
        instance.add("rotation_euler",json("{\"v\":[0,1.5707963267948966,0]}").get("v"));
        instance.add("mirror",json("{\"v\":[\"x\"]}").get("v"));
        var scene = scene(instance); component(scene,"Step",mesh("Stair","cube",new double[]{.5,.5,.5},new int[]{1,1,1},"Stair"));
        var result = cells(scene); var stair = at(result,4,0,4);
        check(result.size() == 1 && stair != null && stair.getAsJsonObject("properties").get("facing").getAsString().equals("west"),
                "镜像后的偏移和楼梯方向必须接受同一个组合矩阵");
        instance.remove("mirror"); instance.add("rotation_euler",json("{\"v\":[1.5707963267948966,0,0]}").get("v"));
        rejects(() -> cells(scene), "不能为旋转九十度的组件编造竖直楼梯朝向");
        scene.getAsJsonObject("components").getAsJsonObject("Step").getAsJsonArray("objects").get(0).getAsJsonObject().addProperty("block_state_axes","minecraft_world");
        check(cells(scene).values().iterator().next().getAsJsonObject("properties").get("facing").getAsString().equals("north"),
                "作者明确指定世界朝向时只转布局，按声明保留原生楼梯状态");
    }
    private static void coordinateSystemsAgree() {
        var minecraft = mesh("Ramp","wedge",new double[]{3.5,2,.5},new int[]{7,4,1},"Body");
        minecraft.add("rotation_euler",json("{\"v\":[0,1.5707963267948966,0]}").get("v"));
        var blender = mesh("Ramp","wedge",new double[]{3.5,-.5,2},new int[]{7,1,4},"Body");
        blender.add("rotation_euler",json("{\"v\":[0,0,1.5707963267948966]}").get("v"));
        var converted = scene(blender); converted.addProperty("coordinate_system","blender_z_up");
        check(cells(scene(minecraft)).equals(cells(converted)), "Blender的竖直旋转应与游戏Y轴旋转生成相同斜面");
        var custom = mesh("Custom","convex_polyhedron",new double[]{2,2,2},new int[]{4,4,4},"Body");
        custom.add("vertices",json("{\"v\":[[0,0,0],[1,0,0],[0,0,1],[0,1,0]]}").get("v"));
        custom.add("faces",json("{\"v\":[[0,2,1],[0,1,3],[1,2,3],[2,0,3]]}").get("v"));
        var customBlender = custom.deepCopy(); customBlender.add("location",json("{\"v\":[2,-2,2]}").get("v"));
        customBlender.add("vertices",json("{\"v\":[[0,1,0],[1,1,0],[0,0,0],[0,1,1]]}").get("v"));
        var customScene = scene(customBlender); customScene.addProperty("coordinate_system","blender_z_up");
        check(cells(scene(custom)).equals(cells(customScene)), "自定义凸网格的顶点也必须遵循场景坐标系");
    }
}
