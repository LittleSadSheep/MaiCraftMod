package org.maiwithu.maicraft.core.pathing.baritone.landing;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.DefaultNativeActionPort;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.NativeActionPort;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.client.actor.NativeConfirmation;

/** Controller replay with native boat entities/rays and explicit server spawn/passenger observations. */
public final class BoatCatchReplayTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        run(false);
        run(true);
        run(true,true);
        System.out.println("BoatCatchReplayTest: passed");
    }
    private static void run(boolean packetWindow) throws Exception {
        run(packetWindow,false);
    }
    private static void run(boolean packetWindow,boolean lateSpawn) throws Exception {
        var f=new WaterLandingReplayTest.Fixture(false);
        var item=packetWindow ? Items.SPRUCE_BOAT : Items.OAK_BOAT;
        f.player.inventory.setItem(0,new ItemStack(item));
        var spawn=packetWindow ? new Vec3(.72,0,.31) : new Vec3(.5,0,.5);
        var controller=new BoatLandingAssist(new BoatLandingSnapshot.Plan(new BlockPos(0,12,0),BlockPos.ZERO,
                item,null,spawn,true),false);
        Boat[] entity={null}; int[] placed={0},mounted={0},exited={0};
        var wire=new java.util.ArrayList<String>();
        var actions=(NativeActionPort)Proxy.newProxyInstance(NativeActionPort.class.getClassLoader(),new Class<?>[]{NativeActionPort.class},
                (proxy,method,values)->switch(method.getName()) {
                    case "useItem" -> {
                        check(BoatLandingGeometry.placeable(f.world,pos->true,f.player.getEyePosition(),spawn),"native boat ray reaches the actual support");
                        if(!packetWindow) { entity[0]=spawn(f,spawn); f.player.inventory.setItem(0,ItemStack.EMPTY); }
                        placed[0]++;
                        yield receipt(f,NativeActionReceipt.Kind.USE_ITEM,(NativeConfirmation)values[2],(Integer)values[3]);
                    }
                    case "interact" -> {
                        check(values[1]==entity[0] && !f.player.isPassenger(),"mount is bound to the newly observed native entity");
                        wire.add("interact");
                        if(!packetWindow) mount(f,entity[0]);
                        mounted[0]++;
                        yield receipt(f,NativeActionReceipt.Kind.INTERACT_ENTITY,(NativeConfirmation)values[3],(Integer)values[4]);
                    }
                    case "submitControlProtocol" -> {
                        ((Runnable)values[2]).run();
                        var exit=BoatLandingGeometry.exit(f.world,pos->true,spawn,entity[0].getBbWidth(),f.player.getBbWidth(),f.player.getBbHeight(),f.player.getYRot());
                        check(exit!=null,"native dismount geometry has grounded room");
                        field(Entity.class,"vehicle").set(f.player,null);
                        field(Entity.class,"passengers").set(entity[0],com.google.common.collect.ImmutableList.of());
                        f.position(0,0,true);
                        field(Entity.class,"position").set(f.player,exit);
                        field(Entity.class,"blockPosition").set(f.player,BlockPos.containing(exit));
                        field(Entity.class,"bb").set(f.player,new AABB(exit.x-.3,exit.y,exit.z-.3,exit.x+.3,exit.y+1.8,exit.z+.3));
                        exited[0]++;
                        yield receipt(f,NativeActionReceipt.Kind.MOD_PROTOCOL,(NativeConfirmation)values[3],(Integer)values[4]);
                    }
                    case "poll" -> f.receipts.poll((LocalPlayerContext)values[0],(NativeActionReceipt)values[1]);
                    default -> throw new AssertionError(method.getName());
                });
        var context=(LocalPlayerContext)Proxy.newProxyInstance(LocalPlayerContext.class.getClassLoader(),new Class<?>[]{LocalPlayerContext.class},
                (proxy,method,values)->method.getName().equals("actions") ? actions : method.invoke(f.context,values));
        f.position(2.7,packetWindow ? -2.3 : -.9,false); f.player.setYRot(52); f.player.setXRot(0); f.player.fallDistance=40;
        controller.tick(context);
        check(placed[0]==1 && !controller.failed(),"urgent aim submits one boat even in a short native opportunity");
        if(packetWindow) {
            if(lateSpawn) {
                f.position(0,0,true); f.time++;
                controller.beforePositionPacket(context);
                check(controller.failed() && mounted[0]==0,"missing spawn evidence cannot invent a catch before impact");
                entity[0]=spawn(f,spawn); f.time++;
                controller.beforePositionPacket(context);
                check(mounted[0]==0 && !controller.ready(),"a boat arriving after the ground report must not be credited as a clutch");
                return;
            }
            entity[0]=spawn(f,spawn); // Server entity packet arrives, inventory/vehicle packets follow later.
            field(Entity.class,"onGround").setBoolean(entity[0],false);
            f.position(.5625,0,true); f.time++;
            controller.beforePositionPacket(context);
            wire.add("ground_movement");
            check(wire.equals(List.of("interact","ground_movement")),"the catch must be sent before the ground movement report");
            check(controller.mountPending() && !controller.ready() && !f.player.isPassenger(),"submitting a catch is not a confirmed ride");
            check(!Boolean.TRUE.equals(controller.diagnostics().get("created_this_session")),"entity visibility alone does not authorize boat recovery");
            var rescue=new LandingBoatRescue(new LandingAssistPlan(LandingAssistPlan.Kind.BOAT,BlockPos.ZERO,BlockPos.ZERO,
                    BlockPos.ZERO.below(),net.minecraft.core.Direction.UP,false,spawn),true);
            field(LandingBoatRescue.class,"boat").set(rescue,controller);
            rescue.tick(context);
            check(!rescue.failed(),"client ground contact must not discard an already-submitted native catch");
            entity[0].setVariant(Boat.Type.SPRUCE);
            f.player.inventory.setItem(0,ItemStack.EMPTY); mount(f,entity[0]);
        } else { f.position(1.8,-.96,false); f.time++; controller.tick(context); }
        check(mounted[0]==1,"fresh server identity allows mounting without an extra smoothing/render wait");
        BoatLandingAssist.State result=BoatLandingAssist.State.RUNNING;
        for(int tick=0;tick<12 && result==BoatLandingAssist.State.RUNNING;tick++) {
            if(f.player.isPassenger()) { f.position(.5625,0,false); f.player.fallDistance=0; }
            f.time++; result=controller.tick(context);
        }
        check(result==BoatLandingAssist.State.SETTLED && placed[0]==1 && mounted[0]==1 && exited[0]==1
                && !f.player.isPassenger() && f.player.onGround(),"one native spawn, catch and supported dismount finishes the rescue");
        check(Boolean.TRUE.equals(controller.diagnostics().get("created_this_session")),"created boat ownership remains explicit");
        check(!packetWindow || Boolean.TRUE.equals(controller.diagnostics().get("mount_before_position_packet")),"packet-window catch remains explicit in diagnostics");
    }
    private static Boat spawn(WaterLandingReplayTest.Fixture f,Vec3 at) throws Exception {
        Boat boat=new Boat(f.world,at.x,at.y,at.z); field(Entity.class,"onGround").setBoolean(boat,true);
        boat.setDeltaMovement(Vec3.ZERO); f.world.observedEntities=List.of(boat); return boat;
    }
    private static void mount(WaterLandingReplayTest.Fixture f,Boat boat) throws Exception {
        field(Entity.class,"vehicle").set(f.player,boat);
        field(Entity.class,"passengers").set(boat,com.google.common.collect.ImmutableList.of(f.player));
        f.player.fallDistance=0;
    }
    private static NativeActionReceipt receipt(WaterLandingReplayTest.Fixture f,NativeActionReceipt.Kind kind,NativeConfirmation evidence,int timeout) throws Exception {
        var constructor=NativeActionReceipt.class.getDeclaredConstructors()[0]; constructor.setAccessible(true);
        var receipt=(NativeActionReceipt)constructor.newInstance(kind,f.context,timeout,evidence.stableTicksRequired(),evidence,null,null);
        field(DefaultNativeActionPort.class,"active").set(f.receipts,receipt); return receipt;
    }
    private static Field field(Class<?> type,String name) throws Exception {
        for(var owner=type;owner!=null;owner=owner.getSuperclass()) {
            try { var field=owner.getDeclaredField(name); field.setAccessible(true); return field; }
            catch(NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
    private static void check(boolean value,String message) { if(!value)throw new AssertionError(message); }
}
