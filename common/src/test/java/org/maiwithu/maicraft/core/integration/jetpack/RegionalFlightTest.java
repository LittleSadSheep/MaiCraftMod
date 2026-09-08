package org.maiwithu.maicraft.core.integration.jetpack;

import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.pathing.goal.RegionalGoal;
import org.maiwithu.maicraft.core.pathing.goal.RegionalTerrain;

/** Observation replay: no floor at departure, local air legs, then actual supported touchdown. */
public final class RegionalFlightTest {
    private static final JetpackNativeAdapter.Snapshot POWER=new JetpackNativeAdapter.Snapshot(
            true,"fixture","create_jetpack:netherite_jetpack",true,true,900,17000,.016,.32,.6,-.03,.08);
    public static void main(String[] args) {
        Vec3 origin=new Vec3(.5,80,.5);
        double[] floor={0};
        var space=new JetpackRoute.Space() {
            public boolean clear(Vec3 from,Vec3 to) { return to.y>=floor[0]; }
            public Vec3 landingBelow(Vec3 point) { return point.y>=floor[0] ? new Vec3(point.x,floor[0],point.z) : null; }
        };
        var view=new RegionalTerrain.View() {
            public boolean known(Vec3 point) { return true; }
            public Vec3 surfaceBelow(Vec3 point,int depth) {
                return point.y>=floor[0] && point.y-floor[0]<=depth ? new Vec3(point.x,floor[0],point.z) : null;
            }
            public boolean visible(Vec3 from,Vec3 to) { return true; }
        };
        var target=new RegionalFlightTarget(new RegionalGoal(origin,new Vec3(0,-1,0),100));
        check(target.advance(origin,Vec3.ZERO,true,space,view,POWER,0),"unknown destination permits bounded discovery");
        check(target.ready() && !target.landingSelected() && target.point().y<origin.y,"descend before the floor enters observation");
        var search=new JetpackRoute.Search(origin,target.point(),POWER,0,false);
        for(int i=0;i<10 && !search.done();i++) search.advance(space,16,10_000_000);
        check(search.result()!=null && search.result().points().getLast().equals(target.point()),"an air segment needs no fictitious support");
        var exact=new JetpackRoute.Search(origin,target.point(),POWER);
        exact.advance(space,16,10_000_000);
        check(exact.done() && exact.result()==null,"ordinary landing flights still require actual support");
        Vec3 current=origin;
        for(int tick=1;tick<120 && !target.landingSelected();tick++) {
            if(target.ready()) current=target.point();
            check(target.advance(current,Vec3.ZERO,false,space,view,POWER,tick),"discovery continues through air waypoints");
            check(!target.touchdown(),"an air waypoint never completes the journey");
        }
        check(target.landingSelected() && target.point().y==0,"fresh observations bind a real lower platform");
        floor[0]=-10;
        check(target.advance(current,Vec3.ZERO,false,space,view,POWER,130) && target.point().y!=0,
                "removed support returns to discovery instead of landing at a stale coordinate");
        for(int tick=131;tick<260 && !target.landingSelected();tick++) {
            if(target.ready()) current=target.point();
            check(target.advance(current,Vec3.ZERO,false,space,view,POWER,tick),"continue observing changed terrain");
        }
        check(target.landingSelected() && target.point().y==-10,"replacement platform is observed, not guessed");
        for(int tick=260;tick<263;tick++) target.advance(target.point(),Vec3.ZERO,true,space,view,POWER,tick);
        check(target.touchdown(),"only stable supported arrival completes a regional flight");
        var edge=new JetpackRoute.Space() {
            public boolean clear(Vec3 from,Vec3 to) {
                for(int i=0;i<=20;i++) {
                    Vec3 point=from.lerp(to,i/20D);
                    if(point.y<origin.y && point.x<4) return false;
                }
                return true;
            }
            public Vec3 landingBelow(Vec3 point) { return null; }
        };
        var departure=new RegionalFlightTarget(new RegionalGoal(origin,new Vec3(0,-1,0),64));
        for(int tick=0;tick<40 && !departure.ready();tick++)
            check(departure.advance(origin,Vec3.ZERO,true,edge,view,POWER,tick),"search around the departure platform edge");
        check(departure.ready() && (departure.point().y>=origin.y || departure.point().x>=4),
                "the descent direction cannot tunnel through the current platform");
        var sealed=new JetpackRoute.Space() {
            public boolean clear(Vec3 from,Vec3 to) { return from.equals(to); }
            public Vec3 landingBelow(Vec3 point) { return null; }
        };
        Vec3 oldPoint=departure.point();
        departure.advance(origin,Vec3.ZERO,true,sealed,view,POWER,41);
        check(!departure.ready() || !departure.point().equals(oldPoint),"changed local obstacles retire the stale air segment");
        floor[0]=0;
        Vec3 above=new Vec3(.5,20,.5);
        var cancelled=new RegionalFlightTarget(new RegionalGoal(above,new Vec3(0,1,0),64));
        cancelled.advance(above,Vec3.ZERO,false,space,view,POWER,0);
        check(!cancelled.landingSelected(),"an upward goal does not bind the lower floor");
        cancelled.seekLandingOnStop();
        for(int tick=1;tick<20 && !cancelled.landingSelected();tick++) cancelled.advance(above,Vec3.ZERO,false,space,view,POWER,tick);
        check(cancelled.landingSelected() && cancelled.point().y==0,"cancellation discovers an available exit without continuing the upward intent");
        System.out.println("RegionalFlightTest: passed");
    }
    private static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }
}
