// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.blueprint;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.phys.Vec3;

/** 只用几何直接检查建筑图元；不初始化方块注册表、不生成世界，也不把线段外的延长线当成建筑棱。 */
public final class BuildingModelShapeTest {
    private static final Vec3 SIZE = new Vec3(6, 8, 10);
    public static void main(String[] args) {
        boxesUseNamedWorldUnitPlanes();
        triangularSolidsKeepTheirRealProfiles();
        pyramidsNarrowTowardTheTop();
        segmentedSolidsAreClosedAndBounded();
        invalidParametersAreRejected();
        System.out.println("BuildingModelShapeTest: passed");
    }

    private static void boxesUseNamedWorldUnitPlanes() {
        var cube = shape("cube"); topology(cube, 8, 6, 12); bounds(cube, SIZE);
        var normals = Map.of("left",new Vec3(-1,0,0),"right",new Vec3(1,0,0),"bottom",new Vec3(0,-1,0),
                "top",new Vec3(0,1,0),"front",new Vec3(0,0,-1),"back",new Vec3(0,0,1));
        for (var face : cube.faces()) close(face.normal(), normals.get(face.id()), "盒子面名称必须对应固定局部方向");
        close(face(cube,"right").distance(new Vec3(2,0,0)),1,"右墙内侧距离按真正方块单位计算");
        close(face(cube,"right").distance(new Vec3(4,0,0)),-1,"墙外距离必须为负");
        check(cube.contains(Vec3.ZERO) && cube.contains(new Vec3(3,4,5)) && !cube.contains(new Vec3(3.001,0,0)), "边界包含而真实墙外排除");
        var edge = cube.edges().stream().filter(value -> value.id().equals("left+top")).findFirst().orElseThrow();
        close(edge.distance(new Vec3(-3,6,0)),2,"棱中段距离");
        close(edge.distance(new Vec3(-3,4,7)),2,"越过棱端点后应量到端点");
        close(edge.distance(new Vec3(-3,6,7)),Math.sqrt(8),"同时越过端点与侧面时不能只量到无限延长线");
        var panel = BuildingModelShape.create("panel",new JsonObject(),new Vec3(6,8,1)); topology(panel,8,6,12); bounds(panel,new Vec3(6,8,1));
        check(!panel.contains(new Vec3(0,0,.51)),"薄墙厚度必须保持作者给定的 Z 尺寸");
    }

    private static void triangularSolidsKeepTheirRealProfiles() {
        BuildingModelShape triangle = shape("triangle"), prism = shape("triangular_prism"), wedge = shape("wedge");
        topology(triangle,6,5,9); topology(wedge,6,5,9); bounds(triangle,SIZE); bounds(wedge,SIZE);
        check(triangle.faces().equals(prism.faces()) && triangle.edges().equals(prism.edges()), "三角板与等腰三角棱柱使用同一 XY 轮廓和 Z 厚度");
        check(ids(triangle).equals(Set.of("bottom","left_slope","right_slope","front","back")), "斜屋面名称稳定");
        check(triangle.contains(new Vec3(0,3,0)) && !triangle.contains(new Vec3(2,3,0))
                && triangle.contains(new Vec3(2,-3,4.9)) && !triangle.contains(new Vec3(0,0,5.01)), "等腰轮廓向顶部收窄但厚度保持不变");
        check(wedge.contains(new Vec3(-2.5,3,0)) && !wedge.contains(new Vec3(2,3,0))
                && !wedge.contains(new Vec3(-3.01,-3,0)), "楔体保留左侧直墙，不能填满右上角");
        close(face(wedge,"slope").normal(),new Vec3(.8,.6,0),"非等比尺寸必须以逆转置变换斜面法线");
        close(face(wedge,"slope").distance(new Vec3(.6,.45,0)),-.75,"斜面外移零点七五格后的带符号距离");
    }

    private static void pyramidsNarrowTowardTheTop() {
        BuildingModelShape tetra = shape("tetrahedron"), triangular = shape("triangular_pyramid"), pyramid = shape("pyramid");
        topology(tetra,4,4,6); topology(pyramid,5,5,8); bounds(tetra,SIZE); bounds(pyramid,SIZE);
        check(tetra.faces().equals(triangular.faces()), "三角锥的两个公开名称保持相同几何");
        for (var solid : List.of(tetra,pyramid)) {
            check(solid.contains(new Vec3(0,4,0)) && solid.contains(Vec3.ZERO)
                    && !solid.contains(new Vec3(2,3,0)) && !solid.contains(new Vec3(0,4.01,0)), "锥体必须收敛到顶点而不是直柱");
        }
        check(ids(tetra).equals(Set.of("bottom","side_0","side_1","side_2")), "三角底面的侧面编号稳定");
        check(ids(pyramid).containsAll(Set.of("front_slope","back_slope","left_slope","right_slope")), "四角锥面可分别选择材质");
    }

    private static void segmentedSolidsAreClosedAndBounded() {
        topology(shape("prism"),12,8,18); topology(shape("cylinder"),32,18,48); topology(shape("cone"),17,17,32);
        for (int count : new int[]{3,5,6,16,31,32}) for (String kind : List.of("prism","cylinder","cone")) {
            var object = json("{\"segments\":"+count+"}"); String before = object.toString();
            var solid = BuildingModelShape.create(kind,object,SIZE); boolean cone = kind.equals("cone");
            topology(solid,cone ? count+1 : count*2,cone ? count+1 : count+2,cone ? count*2 : count*3); bounds(solid,SIZE);
            check(solid.contains(Vec3.ZERO) && solid.contains(new Vec3(0,-4,0)) && !solid.contains(new Vec3(3,3,5)), "多边形截面不能被替换成方盒");
            for (int side = 0; side < count; side++) check(ids(solid).contains("side_"+side), "材质选择所用 side 编号不得随几何验证改名");
            check(before.equals(object.toString()) && solid.edges().equals(BuildingModelShape.create(kind,object,SIZE).edges()), "编译不能修改源数据，重复几何必须一致");
        }
    }

    private static void invalidParametersAreRejected() {
        for (String value : List.of("2","33","6.5","\"6\"","null","1e100"))
            rejected(() -> BuildingModelShape.create("cylinder",json("{\"segments\":"+value+"}"),SIZE),"非法分段数量必须有界拒绝");
        for (Vec3 dimensions : List.of(Vec3.ZERO,new Vec3(-1,2,3),new Vec3(1,Double.NaN,1),new Vec3(1,1,Double.POSITIVE_INFINITY)))
            rejected(() -> BuildingModelShape.create("cube",new JsonObject(),dimensions),"非法尺寸不能进入体素扫描");
        rejected(() -> BuildingModelShape.create("sphere",new JsonObject(),SIZE),"未实现球体时明确拒绝，不悄悄用方块替代");
        check(!shape("cube").contains(new Vec3(Double.NaN,0,0)),"非有限检测点不能算在建筑内");
        var thin = BuildingModelShape.create("wedge",new JsonObject(),new Vec3(1e-5,1e5,2));
        topology(thin,6,5,9);
        check(!thin.contains(new Vec3(1e-5,0,0)) && thin.contains(new Vec3(-2e-6,0,0)), "细长图元的法线和内部判定不能沿用未缩放距离");
    }

    private static void topology(BuildingModelShape shape, int vertices, int faces, int edges) {
        check(shape.faces().size() == faces && shape.edges().size() == edges, "图元应保留预期的面数和棱数");
        var points = new HashSet<Vec3>(); var names = new HashSet<String>();
        for (var edge : shape.edges()) {
            points.add(edge.from()); points.add(edge.to()); check(names.add(edge.id()),"棱编号不能重复");
            check(edge.faces().equals(edge.faces().stream().sorted().toList()) && edge.id().equals(String.join("+",edge.faces())),"棱编号由有序相邻面唯一决定");
            for (String id : edge.faces()) { close(face(shape,id).distance(edge.from()),0,"棱起点在相邻面上"); close(face(shape,id).distance(edge.to()),0,"棱终点在相邻面上"); }
            close(edge.distance(edge.from().add(edge.to()).scale(.5)),0,"棱中点距离为零");
        }
        check(points.size() == vertices && vertices - edges + faces == 2,"闭合凸多面体符合顶点棱面关系");
        for (Vec3 point : points) check(shape.contains(point),"原始顶点必须位于各平面的内部或边界");
        for (var face : shape.faces()) close(face.normal().length(),1,"法线必须单位化后才可用于方块距离");
    }
    private static void bounds(BuildingModelShape shape, Vec3 size) { close(shape.boundsMin(),size.scale(-.5),"图元下界居中且尺寸正确"); close(shape.boundsMax(),size.scale(.5),"图元上界居中且尺寸正确"); }
    private static BuildingModelShape shape(String name) { return BuildingModelShape.create(name,new JsonObject(),SIZE); }
    private static BuildingModelShape.Face face(BuildingModelShape shape, String id) { return shape.faces().stream().filter(face -> face.id().equals(id)).findFirst().orElseThrow(); }
    private static Set<String> ids(BuildingModelShape shape) { return shape.faces().stream().map(BuildingModelShape.Face::id).collect(java.util.stream.Collectors.toSet()); }
    private static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
    private static void rejected(Runnable action, String message) { try { action.run(); } catch (IllegalArgumentException expected) { return; } throw new AssertionError(message); }
    private static void close(Vec3 actual, Vec3 expected, String message) { check(actual.distanceTo(expected) < 1e-7,message+": "+actual+" != "+expected); }
    private static void close(double actual, double expected, String message) { check(Math.abs(actual-expected) < 1e-7,message+": "+actual+" != "+expected); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
