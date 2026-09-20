// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.blueprint;

import com.google.gson.JsonObject;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import static org.maiwithu.maicraft.core.blueprint.BuildingModelTestData.*;

/** 核对网格真正展开的空气、方块和半砖状态；首格为零、负坐标与组件变换都不能改变作者图案。 */
public final class BuildingModelPatternTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        checkerboard(); slabStripes(); transformedComponents(); planesAndBlender(); cutsAndInsertions(); rejectsInvalidPatterns();
        System.out.println("BuildingModelPatternTest: binary panels, slab states, transforms, cuts and validation passed");
    }

    private static void checkerboard() {
        // 从零格开头，在负世界坐标仍按对象最小角重复；尺寸不是图案宽度的整数倍时保留最后一列。
        var panel = panel(); panel.add("location", json("{\"v\":[-2.5,2,0.5]}").get("v"));
        panel.add("dimensions", json("{\"v\":[5,4,1]}").get("v"));
        var cells = cells(scene(panel)); check(cells.size() == 20, "留空格也属于最终蓝图验收目标");
        for (int y = 0; y < 4; y++) for (int x = 0; x < 5; x++)
            check(block(cells,x-5,y,0).equals((x+y)%2 == 0 ? "minecraft:air" : "minecraft:stone"), "零开头棋盘格必须保持原相位");
        panel.getAsJsonObject("pattern").add("rows", json("{\"v\":[\"10\",\"01\"]}").get("v"));
        check(block(cells(scene(panel)),-5,0,0).equals("minecraft:stone"), "一开头同样按输入保留首格");
        panel.getAsJsonObject("pattern").add("rows", json("{\"v\":[\"0\"]}").get("v"));
        check(count(cells(scene(panel)),"minecraft:air") == 20, "图案不要求至少出现一个一格");
    }

    private static void slabStripes() {
        // 一格映射上半砖、零格映射下半砖；零开头时第一列必须是下半砖，而不是自动从上半砖起排。
        var panel = panel(); panel.addProperty("material","Upper");
        panel.add("pattern",json("{\"axes\":[\"x\",\"y\"],\"rows\":[\"01\"],\"materials\":{\"0\":\"Lower\"}}"));
        var scene = scene(panel);
        scene.getAsJsonObject("materials").add("Upper",json("{\"block_id\":\"minecraft:stone_slab\",\"properties\":{\"type\":\"top\"}}"));
        scene.getAsJsonObject("materials").add("Lower",json("{\"block_id\":\"minecraft:stone_slab\",\"properties\":{\"type\":\"bottom\"}}"));
        for (int mode = 0; mode < 3; mode++) {
            if (mode > 0) panel.add("mirror",json(mode == 1 ? "{\"v\":[\"x\"]}" : "{\"v\":[\"y\"]}").get("v"));
            var cells = cells(scene);
            for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) {
                var cell = at(cells,x,y,0);
                check(cell.get("block_id").getAsString().equals("minecraft:stone_slab"), "半砖交替不产生整格空气");
                check(cell.getAsJsonObject("properties").get("type").getAsString().equals((x%2 == 0) == (mode == 0) ? "bottom" : "top"),
                        "横向镜像反转图案，竖向镜像还须翻转半砖上下状态");
            }
        }
        panel.remove("mirror"); panel.add("location",json("{\"v\":[2,0.5,2]}").get("v"));
        panel.add("rotation_euler",json("{\"v\":[1.5707963267948966,0,0]}").get("v"));
        rejects(() -> cells(scene), "不能把上下半砖旋转成游戏不存在的竖直半砖");
    }

    private static void transformedComponents() {
        // 阵列沿局部轴展开后整体旋转，每个窗格保留同样的零开头相位，并沿用实例的材质映射。
        var instance = instance("Screens","Screen",10,0,0);
        instance.add("rotation_euler",json("{\"v\":[0,1.5707963267948966,0]}").get("v"));
        instance.add("array",json("{\"count\":[2,1,1],\"step\":[6,0,0]}"));
        instance.add("material_map",json("{\"Body\":\"Glass\",\"Trim\":\"Accent\"}"));
        var panel = panel(); panel.getAsJsonObject("pattern").add("materials",json("{\"1\":\"Trim\"}"));
        var scene = scene(instance); component(scene,"Screen",panel); var cells = cells(scene);
        for (int i = 0; i < 2; i++) {
            check(block(cells,10,0,-1-6*i).equals("minecraft:air"), "复制和旋转后首格仍留空");
            check(block(cells,10,0,-2-6*i).equals("minecraft:gold_block"), "图案具名材质必须经过组件配色映射");
        }
    }

    private static void planesAndBlender() {
        // 水平天花、侧墙与 Blender 坐标输入共用 Minecraft 局部轴，不把厚度轴误当成图案行。
        for (String axes : new String[]{"[\"x\",\"z\"]", "[\"z\",\"y\"]"}) {
            boolean floor = axes.contains("x");
            var panel = mesh("Grid","panel",floor ? new double[]{2,.5,2} : new double[]{.5,2,2},
                    floor ? new int[]{4,1,4} : new int[]{1,4,4},"Body");
            panel.add("pattern",json("{\"axes\":" + axes + ",\"rows\":[\"01\",\"10\"]}")); var cells = cells(scene(panel));
            for (int v = 0; v < 4; v++) for (int u = 0; u < 4; u++)
                check(block(cells,floor ? u : 0,floor ? 0 : v,floor ? v : u).equals((u+v)%2 == 0 ? "minecraft:air" : "minecraft:stone"), "图案按声明的两条平面轴排列");
        }
        var panel = panel(); panel.add("dimensions",json("{\"v\":[4,1,4]}").get("v")); panel.add("location",json("{\"v\":[2,0.5,2]}").get("v"));
        var scene = scene(panel); scene.addProperty("coordinate_system","blender_z_up"); var cells = cells(scene);
        check(block(cells,0,0,-1).equals("minecraft:air") && block(cells,1,0,-1).equals("minecraft:stone"), "Blender 墙面换轴后保留图案与厚度");
    }

    private static void cutsAndInsertions() {
        // 切割继续作用于最终格网；留孔只约束本对象，独立的玻璃或框架可以填入孔内。
        var panel = panel(); var insert = mesh("Insert","cube",new double[]{.5,.5,.5},new int[]{1,1,1},"Glass");
        check(cells(scene(panel,insert)).equals(cells(scene(insert,panel))), "留孔不能清掉另一个对象的玻璃");
        var cut = mesh("Cut","cube",new double[]{1.5,.5,.5},new int[]{1,1,1},null);
        panel.add("modifiers",json("{\"v\":[{\"type\":\"BOOLEAN\",\"operation\":\"DIFFERENCE\",\"object\":\"Cut\"}]}").get("v"));
        check(block(cells(scene(panel,cut)),1,0,0).equals("minecraft:air"), "布尔切割可以移除图案里原本保留的实体格");
    }

    private static void rejectsInvalidPatterns() {
        // 错误图案和隐藏在未使用组件中的无效材质都在编译前拒绝，不到施工时才猜测作者意图。
        for (String bad : new String[]{"{\"axes\":[\"x\",\"x\"],\"rows\":[\"01\"]}", "{\"axes\":[\"x\",\"z\"],\"rows\":[\"01\"]}",
                "{\"axes\":[\"x\",\"y\"],\"rows\":[\"01\",\"0\"]}", "{\"axes\":[\"x\",\"y\"],\"rows\":[\"02\"]}",
                "{\"axes\":[\"x\",\"y\"],\"rows\":[]}", "{\"axes\":[\"x\",\"y\"],\"rows\":[\"\"]}",
                "{\"axes\":[\"x\",\"y\"],\"rows\":[\"01\"],\"materials\":{\"0\":\"Missing\"}}",
                "{\"axes\":[\"x\",\"y\"],\"rows\":[\"01\"],\"phase\":1}"}) {
            var panel = panel(); panel.add("pattern",json(bad)); rejects(() -> cells(scene(panel)), "错误图案不能静默忽略");
            var unused = scene(panel()); component(unused,"Unused",panel); rejects(() -> cells(unused), "未使用组件也必须通过图案校验");
        }
        var panel = panel(); panel.addProperty("primitive","cube"); rejects(() -> cells(scene(panel)), "图案仅用于平面");
        panel.addProperty("primitive","panel"); panel.addProperty("role","cutter");
        var solid = mesh("Solid","cube",new double[]{.5,.5,.5},new int[]{1,1,1},"Body");
        rejects(() -> cells(scene(solid,panel)), "带孔图案不能充当实心切割体");
        panel.remove("role"); solid.add("modifiers",json("{\"v\":[{\"type\":\"BOOLEAN\",\"operation\":\"DIFFERENCE\",\"object\":\"Grid\"}]}").get("v"));
        rejects(() -> cells(scene(solid,panel)), "引用式切割也不能静默把网格孔洞当成实心");
        panel.remove("role"); var v1 = scene(panel); v1.addProperty("schema_version",1); rejects(() -> cells(v1), "旧版场景不能暗中启用新图案语义");
    }

    private static JsonObject panel() {
        var panel = mesh("Grid","panel",new double[]{2,2,.5},new int[]{4,4,1},"Body");
        panel.add("pattern",json("{\"axes\":[\"x\",\"y\"],\"rows\":[\"01\",\"10\"]}")); return panel;
    }
}
