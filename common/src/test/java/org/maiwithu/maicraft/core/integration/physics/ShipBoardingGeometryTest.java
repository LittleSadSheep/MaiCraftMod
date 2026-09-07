package org.maiwithu.maicraft.core.integration.physics;

import java.util.UUID;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;

public final class ShipBoardingGeometryTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        World world = new World();
        BlockPos origin = new BlockPos(20_000_000,64,20_000_000);
        for (int x=0;x<5;x++) for (int z=0;z<5;z++) world.blocks.put(origin.offset(x,0,z),Blocks.OAK_PLANKS.defaultBlockState());
        AABB bounds = new AABB(Vec3.atLowerCornerOf(origin),Vec3.atLowerCornerOf(origin.offset(5,1,5)));
        var pose = new StructurePose(new Vec3(10,100,20),0,0,0,1,Vec3.atLowerCornerOf(origin),new Vec3(1,1,1));
        var sample = StructureDeckGeometry.sample(world,p->true,pose,bounds,origin,bounds.getCenter(),.76,1.88);
        var selected = ShipLandingTarget.choose(sample.surfaces(),bounds.getCenter(),new Vec3(0,90,20));
        check(selected != null && selected.block().equals(origin.offset(2,0,2)), "a broad deck should choose an interior face, not its nearest rim");
        var yaw = new Quaterniond().rotateY(Math.PI/2);
        var moved = new StructurePose(new Vec3(14,102,23),yaw.x,yaw.y,yaw.z,yaw.w,pose.pivot(),new Vec3(1,1,1));
        var tracked = StructureDeckGeometry.probe(world,p->true,moved,bounds,selected,.76,1.88);
        check(tracked != null && tracked.storage().equals(selected.storage())
                        && tracked.world().distanceTo(moved.toWorld(selected.storage())) < 1e-8,
                "translation and rotation must follow the retained local deck face");
        world.blocks.put(selected.block().above(2),Blocks.STONE.defaultBlockState());
        check(StructureDeckGeometry.probe(world,p->true,moved,new AABB(Vec3.atLowerCornerOf(origin),Vec3.atLowerCornerOf(origin.offset(5,4,5))),selected,.76,1.88)==null,
                "new head obstruction must invalidate the live deck");
        world.blocks.remove(selected.block().above(2)); world.blocks.remove(selected.block());
        check(StructureDeckGeometry.probe(world,p->true,moved,bounds,selected,.76,1.88)==null,"removed support cannot retain an old landing promise");
        check(StructureDeckGeometry.probe(world,p->false,moved,bounds,selected,.76,1.88)==null,"unloaded support cannot be accepted");
        NativePlayer player = new NativePlayer(); UUID id=player.ship.id;
        check(SableStructureBridge.contact(player).supportedBy(id),"native below-contact and matching tracking UUID prove support");
        player.info.verticalCollisionBelow=false;
        check(!SableStructureBridge.contact(player).supportedBy(id),"side contact or an old tracking UUID is not boarding");
        player.info.verticalCollisionBelow=true; player.info.trackingSubLevel=new NativeShip();
        check(!SableStructureBridge.contact(player).supportedBy(id),"standing on a different ship cannot satisfy the requested UUID");
        check(!SableStructureBridge.contact(new Object()).known(),"absent native API remains unknown");
        var dwell = new ShipLandingTarget.SupportDwell();
        for (int tick=1;tick<=8;tick++) dwell.observe(tick,true,Vec3.ZERO);
        check(dwell.ticks==8,"consecutive stable local contact must settle");
        dwell.observe(8,true,Vec3.ZERO); check(dwell.ticks==8,"repeated reads in one tick cannot invent settlement");
        dwell.observe(10,true,Vec3.ZERO); check(dwell.ticks==1,"an unobserved tick must restart contact verification");
        dwell.observe(11,false,Vec3.ZERO); check(dwell.ticks==0,"lost support must immediately clear confirmation");
        dwell.observe(12,true,Vec3.ZERO); dwell.observe(13,true,new Vec3(1,0,0));
        check(dwell.ticks==0,"sliding along the deck is not stable boarding");
        System.out.println("ShipBoardingGeometryTest: passed");
    }
    public static final class NativeShip { final UUID id=UUID.randomUUID(); public UUID getUniqueId(){return id;} }
    public static final class NativeInfo { public boolean verticalCollisionBelow=true; public NativeShip trackingSubLevel; }
    public static final class NativePlayer {
        final NativeShip ship=new NativeShip(); final NativeInfo info=new NativeInfo();
        NativePlayer(){info.trackingSubLevel=ship;}
        public NativeShip sable$getTrackingSubLevel(){return ship;}
        public NativeInfo sable$getCollisionInfo(){return info;}
    }
    private static final class World implements BlockGetter {
        final Map<BlockPos,BlockState> blocks=new HashMap<>();
        public BlockState getBlockState(BlockPos p){return blocks.getOrDefault(p,Blocks.AIR.defaultBlockState());}
        public BlockEntity getBlockEntity(BlockPos p){return null;}
        public FluidState getFluidState(BlockPos p){return getBlockState(p).getFluidState();}
        public int getHeight(){return 384;} public int getMinBuildHeight(){return -64;}
    }
    private static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);}
}
