package org.maiwithu.maicraft.core.pathing;

import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.pathing.goal.RegionalGoal;
import org.maiwithu.maicraft.core.pathing.goal.RegionalTerrain;

// 用给定高度和材质的简化地图，检查区域方向、分次取样、未知列记录，以及概览保留远处不同材质的表面。
public final class RegionalTerrainTest {
    public static void main(String[] args) {
        Vec3 origin=new Vec3(.5,100,.5);
        var goal=new RegionalGoal(origin,RegionalGoal.direction("down",73),64);
        check(!goal.matches(origin) && goal.matches(origin.add(5,-10,0)),"a lower region excludes the departure floor");
        check(!goal.matches(origin.add(0,-70,0)),"direction does not expand the requested scope");
        check(RegionalGoal.direction("forward",90).x<-.99,"relative direction is anchored to initial heading");
        var scan=new RegionalTerrain(origin);
        var view=new RegionalTerrain.View() {
            public boolean known(Vec3 point) { return point.x<10; }
            public Vec3 surfaceBelow(Vec3 point,int depth) {
                double height=point.x<0 ? 92 : 50;
                return point.y>=height && point.y-height<=depth ? new Vec3(point.x,height,point.z) : null;
            }
            public boolean visible(Vec3 from,Vec3 to) { return to.z<0; }
        };
        scan.advance(view,1);
        check((int)scan.summary().get("sampled_columns")==1 && !scan.complete(),"observation yields after its tick budget");
        for(int tick=0;tick<100 && !scan.complete();tick++) scan.advance(view,4);
        check(scan.complete() && !scan.surfaces().isEmpty(),"loaded lower surfaces are discovered incrementally");
        check(scan.surfaces().stream().allMatch(s->s.point().y==92 && s.platform()),"distant unobserved floors are not invented");
        check((int)scan.summary().get("unloaded_columns")>0,"unknown columns remain explicit");
        check(((java.util.List<?>)scan.summary().get("regions")).size()<=6,"the overview stays bounded");
        var wide=RegionalTerrain.overview(origin);
        var distant=new RegionalTerrain.View() {
            public boolean known(Vec3 point) { return point.x<110; }
            public Vec3 surfaceBelow(Vec3 point,int depth) {
                return point.y>=-80 && point.y+80<=depth ? new Vec3(point.x,-80,point.z) : null;
            }
            public boolean visible(Vec3 from,Vec3 to) { return true; }
            public String material(Vec3 point) { return point.x>=90 ? "minecraft:smooth_stone" : "minecraft:grass_block"; }
        };
        for(int tick=0;tick<300 && !wide.complete();tick++) wide.advance(distant,8);
        check(wide.complete() && (int)wide.summary().get("radius")==128 && (int)wide.summary().get("depth")==256,
                "large terrain overview completes across bounded observation slices");
        check(wide.surfaces().stream().anyMatch(s->s.point().x>=90 && s.material().equals("minecraft:smooth_stone")),
                "far platforms and deep terrain are sampled beyond the former local radius");
        check(wide.summary().get("regions").toString().contains("minecraft:smooth_stone"),
                "near grass samples must not crowd a distant different platform material out of the summary");
        check((int)wide.summary().get("unloaded_columns")>0,"wide observation still distinguishes unloaded columns");
        System.out.println("RegionalTerrainTest: passed");
    }
    private static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }
}
