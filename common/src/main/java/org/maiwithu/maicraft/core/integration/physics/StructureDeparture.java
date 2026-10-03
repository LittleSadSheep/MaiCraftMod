package org.maiwithu.maicraft.core.integration.physics;

import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.core.integration.jetpack.JetpackRoute;
import org.maiwithu.maicraft.core.pathing.transport.TransportLanding;
import org.maiwithu.maicraft.core.pathing.transport.TransportSession;
import org.maiwithu.maicraft.entity.InputDriver;

/** 原生下座后，从已观察的静止甲板走到近旁地面，再把总行程交还普通寻路。 */
public final class StructureDeparture implements TransportSession {
    private final UUID structureId;
    private final Vec3 destination;
    private final LongSet forbidden;
    private long started = -1;
    private boolean stopping, grounded, moved;
    private Result result;
    private StructureDeparture(UUID id, Vec3 destination, LongSet forbidden) {
        this.structureId = id; this.destination = destination; this.forbidden = forbidden;
    }
    public Vec3 destination() { return destination; }

    public static StructureDeparture find(LocalPlayerContext ctx, BlockPos goal, LongSet forbidden) {
        var player = ctx.player(); var contact = SableStructureBridge.contact(player);
        if (!player.onGround() || player.isPassenger() || contact.trackingId() == null
                || !contact.supportedBy(contact.trackingId())) return null;
        var ship = SableStructureBridge.find(ctx.level(), contact.trackingId());
        if (!quiet(ship, player.position())) return null;
        Geometry boxes = geometry(ctx,forbidden);
        if (boxes == null) return null;
        var space = JetpackRoute.observed(ctx, forbidden);
        var candidates = new ArrayList<Vec3>();
        BlockPos origin = player.blockPosition();
        for (int x=-4; x<=4; x++) for (int z=-4; z<=4; z++) for (int y=-3; y<=0; y++) {
            var landing = TransportLanding.inspect(ctx.level(), ctx.level()::isLoaded, origin.offset(x,y,z),
                    player.getBbWidth()+.08, player.getBbHeight()+.04, forbidden).destination();
            if (landing == null || !space.clear(landing.landingPoint(), landing.landingPoint())) continue;
            Vec3 at = landing.landingPoint();
            if (at.subtract(player.position()).horizontalDistance() >= .4
                    && StructureExitPath.clear(boxes.boxes(),boxes.forbidden(),player.position(),at,player.getBbWidth()+.04,player.getBbHeight()+.04))
                candidates.add(at);
        }
        return candidates.stream().min(Comparator.comparingDouble(at -> at.distanceTo(player.position())
                        + .1 * at.distanceTo(Vec3.atCenterOf(goal))))
                .map(at -> new StructureDeparture(ship.id(), at, forbidden)).orElse(null);
    }

    private static boolean quiet(SableStructureBridge.Structure ship, Vec3 feet) {
        if (ship == null || ship.pose() == null || ship.lastPose() == null) return false;
        Vec3 local = ship.pose().toStorage(feet);
        return ship.pose().normalToWorld(new Vec3(0,1,0)).y > .98
                && ship.pose().toWorld(local).distanceTo(ship.lastPose().toWorld(local)) < .02;
    }
    private record Geometry(List<AABB> boxes,List<AABB> forbidden) {}
    private static Geometry geometry(LocalPlayerContext ctx,LongSet forbidden) {
        var physical = PhysicalObstacleSnapshot.capture(ctx.level(), ctx.player().position());
        if (physical.conservativeStructures() > 0) return null;
        var boxes = new ArrayList<>(physical.boxes());
        var excluded = new ArrayList<AABB>();
        BlockPos origin = ctx.player().blockPosition();
        for (BlockPos p : BlockPos.betweenClosed(origin.offset(-6,-4,-6),origin.offset(6,3,6))) {
            if (!ctx.level().isLoaded(p)) return null;
            var state = ctx.level().getBlockState(p);
            if (forbidden.contains(p.asLong()) || TransportLanding.unsafe(ctx.level(),p,state)) excluded.add(new AABB(p));
            for (var box : state.getCollisionShape(ctx.level(),p).toAabbs()) boxes.add(box.move(p));
        }
        return new Geometry(boxes,excluded);
    }
    @Override public Result tick(LocalPlayerContext ctx) {
        if (result != null) return result;
        if (started < 0) started = ctx.tickRevision();
        var player = ctx.player(); grounded = player.onGround();
        var contact = SableStructureBridge.contact(player);
        if (grounded && !contact.supportedBy(structureId)
                && player.position().distanceTo(destination) < .65) {
            InputDriver.halt(player);
            return result = stopping ? Result.failed("structure_exit_cancelled","stopped on observed ground",moved,false)
                    : Result.success("native walking left the stationary deck and reached observed ground");
        }
        // 空中保留已选落点；回到有支撑的位置后才能响应取消，避免在车沿上交接一半步行动作。
        if (grounded && (stopping || ctx.tickRevision()-started > 120)) {
            InputDriver.halt(player);
            return result = Result.failed("structure_exit_stopped","walking departure did not reach ground",moved,false);
        }
        if (grounded && contact.supportedBy(structureId)) {
            var boxes = geometry(ctx,forbidden);
            if (!quiet(SableStructureBridge.find(ctx.level(),structureId),player.position()) || boxes == null
                    || !StructureExitPath.clear(boxes.boxes(),boxes.forbidden(),player.position(),destination,player.getBbWidth()+.04,player.getBbHeight()+.04)) {
                InputDriver.halt(player);
                return result = Result.failed("structure_exit_changed","observed deck corridor changed",moved,false);
            }
        }
        // 按真实移动方向步行，落点附近松开前进等待落地，不改玩家位置或载具碰撞关系。
        if (player.position().subtract(destination).horizontalDistance() < .18) InputDriver.halt(player);
        else { InputDriver.stepToward(player,destination,false); moved = true; }
        return Result.running("walking_off_structure");
    }
    @Override public void requestStop() { stopping = true; }
    @Override public void abandon() { result = Result.failed("structure_exit_abandoned","body control transferred",moved,moved); }
    @Override public boolean safeToInterrupt() { return result != null || grounded; }
    @Override public boolean livenessActive() { return result == null; }
    @Override public String phase() { return "walking_off_structure"; }
    @Override public Map<String,Object> diagnostics() { return Map.of("structure_id",structureId.toString(),
            "landing",List.of(destination.x,destination.y,destination.z),"grounded",grounded,"movement_submitted",moved); }
}
