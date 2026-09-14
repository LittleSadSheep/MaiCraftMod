// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.blueprint;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import static org.maiwithu.maicraft.core.blueprint.BuildingModelTestData.*;

/** 检查大阵列能逐页定位，原始凸面索引可继续编辑，以及旧世界朝向和合法旧名称在升级前后保持明确。 */
public final class BuildingModelInspectionTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        allInstancePathsCanBeRead(); componentLocalInfoIsNotAScenePath(); originalFacesStayEditable(); legacyNamesAndOrientationRemainExplicit();
        System.out.println("BuildingModelInspectionTest: paged paths, local definitions, authored faces and v1 migration passed");
    }
    private static void allInstancePathsCanBeRead() {
        var row = mesh("Rows","cube",new double[]{.5,.5,.5},new int[]{1,1,1},"Body");
        row.add("array",json("{\"count\":[129,1,1],\"step\":[1,0,0]}")); var scene = scene(row);
        var second = BuildingSceneInspection.objectInfo(scene,"Rows",1); var tail = BuildingSceneInspection.objectInfo(scene,"Rows",2);
        check(second.getAsJsonArray("expanded_paths").size() == 64 && second.get("has_more").getAsBoolean()
                && tail.getAsJsonArray("expanded_paths").size() == 1 && !tail.get("has_more").getAsBoolean(), "第六十四份以后的实例仍可完整分页发现");
        String path = tail.getAsJsonArray("expanded_paths").get(0).getAsString();
        check(BuildingSceneInspection.objectInfo(scene,path).getAsJsonObject("minecraft_block_bounds").getAsJsonArray("from").get(0).getAsInt() == 128,
                "返回的实际实例路径可继续读取同一场景的对应对象");
    }
    private static void componentLocalInfoIsNotAScenePath() {
        var scene = scene(instance("Actual","Piece",0,0,0));
        component(scene,"Piece",mesh("Block","cube",new double[]{.5,.5,.5},new int[]{1,1,1},"Body"));
        var info = BuildingModelInspection.componentInfo(scene,"Piece");
        check(info.has("definition") && info.has("local_expanded_paths") && !info.has("expanded_paths")
                && !info.get("local_paths_are_scene_queries").getAsBoolean(), "组件原点的示意路径不能冒充真实实例路径");
        scene.getAsJsonArray("objects").get(0).getAsJsonObject().add("location",json("{\"v\":[0.25,0,0]}").get("v"));
        scene.getAsJsonObject("components").getAsJsonObject("Piece").getAsJsonArray("objects").get(0).getAsJsonObject().add("location",json("{\"v\":[0.25,0.5,0.5]}").get("v"));
        check(cells(scene).size() == 1, "局部四分之一格平移可在真实实例中合成对齐的一个方块");
        info = BuildingModelInspection.componentInfo(scene,"Piece");
        check(info.has("definition") && info.has("local_geometry_unavailable"), "原点示意不对齐时仍须返回合法定义及具体原因");
    }
    private static void originalFacesStayEditable() {
        var mesh = mesh("Custom","convex_polyhedron",new double[]{2,2,2},new int[]{4,4,4},"Body");
        mesh.add("vertices",json("{\"v\":[[0,0,0],[1,0,0],[0,0,1],[0,1,0]]}").get("v"));
        mesh.add("faces",json("{\"v\":[[0,2,1],[0,1,3],[1,2,3],[2,0,3]]}").get("v"));
        var info = BuildingSceneInspection.objectInfo(scene(mesh),"Custom");
        check(info.get("faces").equals(mesh.get("faces")) && info.getAsJsonArray("surface_faces").size() == 4
                && info.getAsJsonArray("surface_edges").size() == 6, "派生面棱说明不能覆盖作者原来的面顶点索引");
    }
    private static void legacyNamesAndOrientationRemainExplicit() {
        var old = scene(mesh("Wall[Left]","cube",new double[]{.5,.5,.5},new int[]{1,1,1},"Body")); old.addProperty("schema_version",1);
        var patched = BuildingSceneStore.applyPatch(old,json("{\"objects\":[{\"name\":\"Wall[Left]\",\"location\":[0.5,1,0.5],\"dimensions\":[1,2,1]}]}"));
        check(cells(patched).size() == 2, "合法v1旧名称在普通编辑时不能被v2路径规则拦截");
        var rotated = mesh("Beam","cube",new double[]{2,.5,2},new int[]{2,1,4},"Wood");
        rotated.add("rotation_euler",json("{\"v\":[0,1.5707963267948966,0]}").get("v"));
        var source = scene(rotated); source.addProperty("schema_version",1);
        var upgraded = BuildingSceneStore.applyPatch(source,json("{\"schema_version\":2}"));
        check(cells(source).equals(cells(upgraded)), "显式升级仍保持旧节点按世界轴解释的原木方向");
        var settings = BuildingSceneStore.applyPatch(upgraded,json("{\"overlap_policy\":\"error\",\"block_state_axes\":\"minecraft_world\"}"));
        check(settings.get("overlap_policy").getAsString().equals("error"), "v2全局建模设置可正常编辑和保存");
    }
}
