package org.maiwithu.maicraft.core.pathing.baritone.landing;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.function.BiFunction;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.integration.ae2.Ae2ResourceSupply;
import org.maiwithu.maicraft.core.task.craft.CraftCompanionTask;
import org.maiwithu.maicraft.core.task.craft.CraftTaskRecord;
import org.maiwithu.maicraft.task.TaskState;

/** Reachable neighboring water, dry boat fallback and in-place boat preparation use production selectors. */
public final class AirRescueChoiceTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var f=new WaterLandingReplayTest.Fixture(false);
        var instance=field(Minecraft.class,"instance"); Object previous=instance.get(null);
        field(Minecraft.class,"gameThread").set(f.minecraft,Thread.currentThread()); instance.set(null,f.minecraft);
        try { neighboringWater(); changedLanding(); boatAndCraft(); }
        finally { instance.set(null,previous); }
        System.out.println("AirRescueChoiceTest: passed");
    }
    private static void neighboringWater() throws Exception {
        var f=new WaterLandingReplayTest.Fixture(false); f.position(12,-.08,false); f.player.setYRot(73);
        f.world.scene.blocks.put(BlockPos.ZERO.below(),trapdoor());
        var rescue=EmergencyLanding.find(f.context);
        check(rescue!=null && rescue.plan().kind()==LandingAssistPlan.Kind.WATER && !rescue.plan().feet().equals(BlockPos.ZERO),
                "an unsuitable hatch chooses a reachable neighboring water landing");
        EmergencyLanding.tick(f.context,rescue);
        double angle=Math.toRadians(f.player.getYRot());
        double x=-f.steering.forward()*Math.sin(angle)+f.steering.strafe()*Math.cos(angle);
        double z=f.steering.forward()*Math.cos(angle)+f.steering.strafe()*Math.sin(angle);
        var target=Vec3.atBottomCenterOf(rescue.plan().feet()).subtract(f.player.position());
        check(x*target.x+z*target.z>0 && Math.abs(f.player.getYRot()-73)<.001,
                "WASD corrects the landing without spinning the camera heading");
        f.position(2,-1.8,false);
        check(!AirLandingControl.reachable(f.player,new BlockPos(3,0,0)),"a last-moment controller cannot invent three blocks of horizontal travel");
    }
    private static void boatAndCraft() throws Exception {
        var f=new WaterLandingReplayTest.Fixture(false); f.position(12,-.08,false);
        for(int x=-5;x<=5;x++) for(int z=-5;z<=5;z++) f.world.scene.blocks.put(new BlockPos(x,-1,z),trapdoor());
        f.player.inventory.setItem(0,ItemStack.EMPTY); f.player.inventory.setItem(3,new ItemStack(Items.SPRUCE_BOAT));
        var rescue=EmergencyLanding.find(f.context);
        check(rescue!=null && rescue.plan().kind()==LandingAssistPlan.Kind.BOAT,"when reachable water sites are unavailable, a dry boat support is selected");
        for(int tick=0;tick<5;tick++) { f.time++; rescue.tick(f.context); }
        check(!rescue.failed() && f.player.getMainHandItem().is(Items.SPRUCE_BOAT) && f.uses==0,
                "a carried boat of any supported wood is staged early without placing it out of reach");
        for(int x=-5;x<=5;x++) for(int z=-5;z<=5;z++) f.world.scene.blocks.put(new BlockPos(x,-1,z),
                trapdoor().setValue(BlockStateProperties.WATERLOGGED,true));
        check(LandingBoatRescue.plan(f.world,BlockPos.ZERO,.6,1.8)!=null,
                "a boat can rest above contained water when the trapdoor collision surface remains dry");

        f=new WaterLandingReplayTest.Fixture(false); f.position(12,-.08,false); f.player.inventory.setItem(0,ItemStack.EMPTY);
        var plan=LandingBoatRescue.plan(f.world,BlockPos.ZERO,.6,1.8);
        rescue=LandingAssistSession.automatic(java.util.List.of(plan),true);
        var boat=field(LandingAssistSession.class,"boat").get(rescue);
        var supply=(LandingMaterialSupply)field(LandingBoatRescue.class,"supply").get(boat);
        int[] starts={0},ticks={0};
        var transaction=(Ae2ResourceSupply.Session)Proxy.newProxyInstance(Ae2ResourceSupply.Session.class.getClassLoader(),
                new Class<?>[]{Ae2ResourceSupply.Session.class},(proxy,method,values)->switch(method.getName()) {
                    case "tick" -> { ticks[0]++; yield Optional.empty(); }
                    case "phase" -> "awaiting_native_craft_plan";
                    default -> throw new AssertionError(method.getName());
                });
        BiFunction<LocalPlayer,Ae2ResourceSupply.Request,Ae2ResourceSupply.Session> begin=(player,request)-> {
            starts[0]++;
            check(request.allowCrafting() && request.totalCount()==1
                    && request.acceptedItemIds().contains(ResourceLocation.parse("minecraft:spruce_boat")),
                    "boat supply permits one native AE craft and supports alternative boat recipes");
            return transaction;
        };
        field(LandingMaterialSupply.class,"begin").set(supply,begin);
        f.time++; rescue.tick(f.context);
        check(starts[0]==1 && ticks[0]==1 && f.uses==0,"missing boat starts native supply/crafting before the final placement window");

        var craft=(CraftCompanionTask)f.memory.allocateInstance(CraftCompanionTask.class);
        field(CraftCompanionTask.class,"r").set(craft,new CraftTaskRecord("rescue",100,
                ResourceLocation.parse("minecraft:oak_boat"),1,null).inPlace());
        var prepare=CraftCompanionTask.class.getDeclaredMethod("prepareSurface"); prepare.setAccessible(true);
        check(prepare.invoke(craft)==TaskState.FAILED && field(CraftCompanionTask.class,"nav").get(craft)==null,
                "an in-place emergency craft cannot hijack air steering to walk toward a workbench");
    }
    private static void changedLanding() throws Exception {
        var f=new WaterLandingReplayTest.Fixture(false); f.position(12,-.08,false);
        var rescue=EmergencyLanding.find(f.context);
        f.time++; EmergencyLanding.tick(f.context,rescue);
        Object episode=rescue.diagnostics().get("rescue_episode");
        f.world.scene.blocks.put(BlockPos.ZERO.below(),trapdoor());
        f.time++; EmergencyLanding.tick(f.context,rescue);
        check(rescue.plan().kind()==LandingAssistPlan.Kind.WATER && !rescue.plan().feet().equals(BlockPos.ZERO)
                        && rescue.diagnostics().get("landing_changes").equals(1)
                        && rescue.diagnostics().get("rescue_episode").equals(episode) && f.uses==0,
                "changed native support redirects the same fall before any placement, without creating a second rescue episode");
    }
    private static net.minecraft.world.level.block.state.BlockState trapdoor() {
        return Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(BlockStateProperties.HALF,Half.TOP);
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
