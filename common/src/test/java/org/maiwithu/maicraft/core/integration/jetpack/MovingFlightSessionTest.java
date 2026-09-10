package org.maiwithu.maicraft.core.integration.jetpack;

import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.BodyControlPort;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.core.pathing.transport.TransportSession;
import sun.misc.Unsafe;

/**
 * 给移动平台设置位置、速度和接触状态，调用飞行会话的对应阶段，检查改目标、相对速度、站稳确认和模式恢复；不是整趟模组飞行。
 */
public final class MovingFlightSessionTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        Fixture f = new Fixture();
        var target = new Target();
        var session = session(target);
        var original = new JetpackRoute.Plan(List.of(Vec3.ZERO,new Vec3(3,10,0),new Vec3(3,8,0)),List.of(Vec3.ZERO),500);
        field(JetpackFlightSession.class,"route").set(session,original);
        phase(session,"FLY");
        invoke(session,"retarget",f.context);
        var changed = (JetpackRoute.Plan)field(JetpackFlightSession.class,"route").get(session);
        check(changed.points().getLast().equals(target.point()) && changed.points().get(1).y == 10,
                "moving deck must update the route endpoint and preserve approach clearance");
        check(changed.emergencyLandings().equals(original.emergencyLandings()),"retargeting must retain known exits");
        target.point=new Vec3(9,8,0); field(JetpackFlightSession.class,"target").set(session,target.point);
        target.clear=false; invoke(session,"retarget",f.context);
        check(phase(session).equals("REPLAN"),"a moving endpoint behind an obstacle needs a new corridor");

        target=new Target(); session=session(target); phase(session,"LAND");
        f.position(target.point, new Vec3(.1,-.1,0));
        target.velocity=new Vec3(.1,0,0);
        var steer=JetpackFlightSession.class.getDeclaredMethod("steer",LocalPlayerContext.class,Vec3.class,boolean.class);
        steer.setAccessible(true); steer.invoke(session,f.context,target.point,true);
        check(f.body.movement.forward()==0 && f.body.movement.strafe()==0 && !f.body.movement.sneaking(),
                "a body matching the deck speed must not brake against the ship or sneak in flight");

        field(JetpackFlightSession.class,"grounded").setBoolean(session,true);
        target.contact=true; invoke(session,"land",f.context);
        check(phase(session).equals("LAND"),"first native touch waits for stable boarding evidence");
        target.arrived=true; invoke(session,"land",f.context);
        check(phase(session).equals("RESTORE"),"confirmed native support may enter mode restoration");
        var success=(TransportSession.Result)invoke(session,"restore",f.context);
        check(success.state()==TransportSession.State.SUCCEEDED && f.body.released>0,
                "stable boarding completes through normal restore and control release");

        var wrong=session(new Target()); phase(wrong,"LAND");
        field(JetpackFlightSession.class,"grounded").setBoolean(wrong,true);
        invoke(wrong,"land",f.context);
        var failure=(TransportSession.Result)invoke(wrong,"restore",f.context);
        check(failure.state()==TransportSession.State.FAILED && failure.code().equals("jetpack_wrong_structure"),
                "nearby ordinary ground cannot masquerade as boarding the requested ship");
        var cancelled=session(target); phase(cancelled,"FLY");
        field(JetpackFlightSession.class,"effects").setBoolean(cancelled,true);
        cancelled.requestStop();
        check(!cancelled.safeToInterrupt(),"cancellation must retain airborne transport cleanup");
        field(JetpackFlightSession.class,"grounded").setBoolean(cancelled,true); phase(cancelled,"RESTORE");
        check(((TransportSession.Result)invoke(cancelled,"restore",f.context)).state()==TransportSession.State.FAILED,
                "a cancelled flight must not turn into a successful boarding receipt");
        var lost=session(target); phase(lost,"RESTORE");
        invoke(lost,"restore",f.context);
        check(phase(lost).equals("ACTIVE"),"losing the deck during mode restoration must rearm flight before trying to land again");
        System.out.println("MovingFlightSessionTest: passed");
    }
    // 直接注入设备状态与阶段，绕过外部模组读取；用于隔离会话判断，不能证明真实开关协议已接通。
    private static JetpackFlightSession session(Target target) throws Exception {
        var session=new JetpackFlightSession(target,LongSets.emptySet());
        field(JetpackFlightSession.class,"power").set(session,new JetpackNativeAdapter.Snapshot(true,"fixture","pack",true,true,
                900,17000,.016,.32,.6,-.03,.08));
        field(JetpackFlightSession.class,"originalActive").setBoolean(session,true);
        field(JetpackFlightSession.class,"originalHover").setBoolean(session,true);
        return session;
    }
    private static Object invoke(JetpackFlightSession session,String name,LocalPlayerContext ctx) throws Exception {
        var method=JetpackFlightSession.class.getDeclaredMethod(name,LocalPlayerContext.class); method.setAccessible(true);
        return method.invoke(session,ctx);
    }
    @SuppressWarnings({"unchecked","rawtypes"}) private static void phase(JetpackFlightSession session,String name) throws Exception {
        Field field=field(JetpackFlightSession.class,"phase"); field.set(session,Enum.valueOf((Class)field.getType(),name));
    }
    private static String phase(JetpackFlightSession s) throws Exception {return field(JetpackFlightSession.class,"phase").get(s).toString();}
    private static Field field(Class<?> owner,String name) throws Exception {
        for(Class<?> type=owner;type!=null;type=type.getSuperclass()) try {var f=type.getDeclaredField(name);f.setAccessible(true);return f;}
        catch(NoSuchFieldException ignored){} throw new NoSuchFieldException(name);
    }
    private static final class Target implements MovingFlightTarget {
        Vec3 point=new Vec3(4,8,0),velocity=Vec3.ZERO; boolean clear=true,contact,arrived;
        public boolean update(LocalPlayerContext ctx){return true;} public Vec3 point(){return point;} public Vec3 velocity(){return velocity;}
        public boolean contact(){return contact;} public boolean touchdown(){return arrived;}
        public Map<String,Object> diagnostics(){return Map.of();}
        public JetpackRoute.Space space(LocalPlayerContext c,LongSet f){return new JetpackRoute.Space(){
            public boolean clear(Vec3 a,Vec3 b){return clear;} public Vec3 landingBelow(Vec3 p){return point;}
        };}
    }
    private static final class Fixture {
        final TestPlayer player; final Body body=new Body(); final LocalPlayerContext context;
        Fixture() throws Exception {
            var singleton=field(Unsafe.class,"theUnsafe"); player=(TestPlayer)((Unsafe)singleton.get(null)).allocateInstance(TestPlayer.class);
            position(new Vec3(0,12,0),Vec3.ZERO);
            context=(LocalPlayerContext)Proxy.newProxyInstance(LocalPlayerContext.class.getClassLoader(),new Class<?>[]{LocalPlayerContext.class},
                    (p,m,a)->switch(m.getName()) {case "player"->player;case "body"->body;case "tickRevision"->1L;
                        default->throw new AssertionError("unexpected context access "+m.getName());});
        }
        void position(Vec3 p,Vec3 v)throws Exception{field(LocalPlayer.class,"position").set(player,p);field(LocalPlayer.class,"deltaMovement").set(player,v);}
    }
    private static final class TestPlayer extends LocalPlayer {
        private TestPlayer(){super(null,null,null,null,null,false,false);}
        public float getHealth(){return 20;} public float getAbsorptionAmount(){return 0;}
    }
    private static final class Body implements BodyControlPort {
        Movement movement=Movement.STOPPED; int released;
        public boolean automationOwnsControls(){return true;} public void applyMovement(Movement m,long t){movement=m;}
        public void requestLook(float y,float p,long t){} public void clearLook(){} public void releaseAll(){released++;movement=Movement.STOPPED;}
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
