// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.blueprint;

import com.google.gson.JsonObject;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import static org.maiwithu.maicraft.core.blueprint.BuildingModelTestData.*;

/** 验证坏组件和超量阵列在编译前被拒绝，不能靠未实例化、深层复用或重叠去绕过工作预算。 */
public final class BuildingModelGuardTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        unusedDefinitionsAreChecked(); loopsAndExpansionAreBounded(); invalidPropertiesDoNotHide(); voxelBudgetsAreBounded();
        System.out.println("BuildingModelGuardTest: unused definitions, cycles, expansion and voxel budgets passed");
    }
    private static JsonObject base() { return scene(mesh("Base","cube",new double[]{.5,.5,.5},new int[]{1,1,1},"Body")); }
    private static void unusedDefinitionsAreChecked() {
        for (String edit : new String[]{"{\"material\":\"Missing\"}","{\"face_materials\":{\"bogus\":\"Body\"}}",
                "{\"edge_materials\":{\"front+back\":\"Trim\"}}","{\"fill\":\"hollow\",\"open_faces\":[\"unknown\"]}"}) {
            var source = base(); var member = mesh("Stored","cube",new double[]{.5,.5,.5},new int[]{1,1,1},"Body");
            json(edit).entrySet().forEach(entry -> member.add(entry.getKey(),entry.getValue())); component(source,"Unused",member);
            rejects(() -> BuildingSceneCompiler.validateWire(source), "暂未使用的组件也必须检查材质和面棱定义");
        }
        var source = base(); component(source,"Unused",instance("NoDefinition","Missing",0,0,0));
        rejects(() -> BuildingSceneCompiler.validateWire(source), "未使用组件的悬空引用不能延后到施工时才暴露");
    }
    private static void loopsAndExpansionAreBounded() {
        var cycle = base(); component(cycle,"A",instance("B","B",0,0,0)); component(cycle,"B",instance("A","A",0,0,0));
        rejects(() -> BuildingSceneCompiler.validateWire(cycle), "不绘制的组件引用环也须拒绝");
        var root = instance("Root","L2",0,0,0); root.add("array",json("{\"count\":[1,1,11],\"step\":[0,0,1]}"));
        var expanded = scene(root);
        component(expanded,"L0",mesh("Unit","cube",new double[]{.5,.5,.5},new int[]{1,1,1},"Body"));
        var x = instance("X","L0",0,0,0); x.add("array",json("{\"count\":[11,1,1],\"step\":[1,0,0]}")); component(expanded,"L1",x);
        var y = instance("Y","L1",0,0,0); y.add("array",json("{\"count\":[1,11,1],\"step\":[0,1,0]}")); component(expanded,"L2",y);
        rejectsWith(() -> BuildingSceneCompiler.validateWire(expanded), "expanded model", "小定义的相乘复制也计入实际展开预算");
        var huge = base(); huge.getAsJsonArray("objects").get(0).getAsJsonObject().add("array",json("{\"count\":[1000000000,1,1],\"step\":[1,0,0]}"));
        rejects(() -> BuildingSceneCompiler.validateWire(huge), "巨大计数必须在分配实例前拒绝");
    }
    private static void invalidPropertiesDoNotHide() {
        for (String edit : new String[]{"{\"rotation_euler\":[0,0.7853981633974483,0]}", "{\"mirror\":[\"x\",\"x\"]}",
                "{\"array\":{\"count\":[2,1,1],\"step\":[0,0,0]}}", "{\"array\":{\"count\":[2,1,1],\"step\":[1,0,0],\"skip\":[[2,0,0]]}}",
                "{\"wall_thickness\":0}","{\"segments\":12}","{\"fill\":\"solid\",\"open_faces\":[\"top\"]}","{\"name\":\"Fake[0]\"}"}) {
            var source = base(); var node = source.getAsJsonArray("objects").get(0).getAsJsonObject();
            json(edit).entrySet().forEach(entry -> node.add(entry.getKey(),entry.getValue()));
            rejects(() -> BuildingSceneCompiler.validateWire(source), "不支持或含糊的几何参数不能被静默忽略");
        }
        var source = base(); source.getAsJsonArray("objects").get(0).getAsJsonObject().add("modifiers",json("{\"v\":[{\"type\":\"BOOLEAN\",\"operation\":\"DIFFERENCE\",\"object\":\"Base\"}]}").get("v"));
        rejects(() -> BuildingSceneCompiler.validateWire(source), "布尔修改器不能引用自身");
    }
    private static void voxelBudgetsAreBounded() {
        var shell = mesh("Shell","cube",new double[]{2.5,2.5,2.5},new int[]{5,5,5},"Body"); shell.addProperty("fill","hollow");
        shell.add("face_materials",json("{\"front\":\"Trim\",\"back\":\"Trim\",\"left\":\"Trim\",\"right\":\"Trim\",\"top\":\"Trim\",\"bottom\":\"Trim\"}"));
        check(BuildingSceneCompiler.compile(scene(shell)).getAsJsonObject("metadata").get("voxel_work").getAsLong() == 125L * 19,
                "预算包括源包含判定、空腔判定和六面涂装三组比较");
        shell.add("location",json("{\"v\":[48.5,48.5,48.5]}").get("v")); shell.add("dimensions",json("{\"v\":[97,97,97]}").get("v"));
        rejectsWith(() -> BuildingSceneCompiler.validateWire(scene(shell)), "comparison budget", "大空心模型不能漏计源图元包含检查来绕过预算");
        var expensive = scene(mesh("Huge","cube",new double[]{64,64,64},new int[]{128,128,128},"Body"));
        rejectsWith(() -> BuildingSceneCompiler.validateWire(expensive), "comparison budget", "包围体积和面比较次数必须在体素循环前限制");
        var tooMany = scene(mesh("Large","cube",new double[]{25,25,25},new int[]{50,50,50},"Body"));
        rejectsWith(() -> BuildingSceneCompiler.compile(tooMany), "final block budget", "工作预算内仍不能输出超量最终方块");
    }
    private static void rejectsWith(Runnable operation, String fragment, String message) {
        try { operation.run(); } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains(fragment), message + ": " + expected.getMessage()); return;
        }
        throw new AssertionError(message);
    }
}
