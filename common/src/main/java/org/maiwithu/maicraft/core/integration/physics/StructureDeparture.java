package org.maiwithu.maicraft.core.integration.physics;

import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.BodyControlPort;
import org.maiwithu.maicraft.core.integration.jetpack.JetpackRoute;
import org.maiwithu.maicraft.core.pathing.transport.TransportLanding;
import org.maiwithu.maicraft.core.pathing.transport.TransportSession;
import org.maiwithu.maicraft.entity.InputDriver;
import org.maiwithu.maicraft.core.integration.create.ContraptionObstacles;

/** 原生下座后，从已观察的静止甲板走到近旁地面，再把总行程交还普通寻路。 */
public final class StructureDeparture implements TransportSession {
    private static Map<String,Object> observation=Map.of();
    /** 只读保留最近一次出口搜索依据；不为诊断发出移动，也不把无候选误报为已离艇。 */
    public static Map<String,Object> diagnosticState(){return observation;}
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
        var report=new LinkedHashMap<String,Object>();observation=report;
        report.put("observed_tick",ctx.tickRevision());report.put("position",List.of(player.getX(),player.getY(),player.getZ()));
        report.put("native_contact",contact);report.put("on_ground",player.onGround());report.put("passenger",player.isPassenger());
        report.put("phase","checking_native_support");
        if (!player.onGround() || player.isPassenger() || contact.trackingId() == null
                || !contact.supportedBy(contact.trackingId())) return null;
        var ship = SableStructureBridge.find(ctx.level(), contact.trackingId());
        report.put("phase","checking_stationary_deck");
        if (!quiet(ship, player.position())) return null;
        Geometry boxes = geometry(ctx,forbidden);
        report.put("phase","reading_collision_geometry");
        if (boxes == null) return null;
        var space = JetpackRoute.observed(ctx, forbidden);
        var candidates = new ArrayList<Vec3>();
        int supportedLandings=0,clearLandings=0;
        BlockPos origin = player.blockPosition();
        for (int x=-4; x<=4; x++) for (int z=-4; z<=4; z++) for (int y=-3; y<=0; y++) {
            var landing = TransportLanding.inspect(ctx.level(), ctx.level()::isLoaded, origin.offset(x,y,z),
                    player.getBbWidth()+.08, player.getBbHeight()+.04, forbidden).destination();
            if (landing == null)continue;
            supportedLandings++;
            if(!space.clear(landing.landingPoint(), landing.landingPoint()))continue;
            clearLandings++;
            Vec3 at = landing.landingPoint();
            if (at.subtract(player.position()).horizontalDistance() >= .4
                    && StructureExitPath.clear(boxes.boxes(),boxes.forbidden(),player.position(),at,player.getBbWidth()+.04,player.getBbHeight()+.04))
                candidates.add(at);
        }
        // 低顶座舱无法离开时，直接交付实际搜索阶段、候选计数和完整碰撞快照，便于区分地面缺失与路径被挡。
        report.put("phase",candidates.isEmpty()?"no_deck_exit":"exit_selected");
        report.put("supported_landings",supportedLandings);report.put("clear_landings",clearLandings);report.put("exit_candidates",candidates.size());
        if(candidates.isEmpty()) {
            report.put("body_dimensions",List.of(player.getBbWidth()+.04,player.getBbHeight()+.04));
            report.put("collision_boxes",boxes.boxes().stream().map(b->List.of(b.minX,b.minY,b.minZ,b.maxX,b.maxY,b.maxZ)).toList());
            report.put("forbidden_boxes",boxes.forbidden().stream().map(b->List.of(b.minX,b.minY,b.minZ,b.maxX,b.maxY,b.maxZ)).toList());
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
        // 离开低甲板时也合入成型桨叶，不能在切换到普通地面寻路之前先穿过运动装置。
        var physical = PhysicalObstacleSnapshot.capture(ctx.level(), ctx.player().position())
                .plus(ContraptionObstacles.capture(ctx.level(),ctx.player().position()));
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
        else {
            // 转头有平滑延迟，前进和横移按当刻真实朝向重新投影，不能先沿旧视线走出已验证的离车通道。
            InputDriver.lookAt(player,destination.add(0,player.getEyeHeight(),0));
            Vec3 delta = destination.subtract(player.position());
            ctx.body().applySteering(yaw -> toward(delta,yaw),player.getYRot(),ctx.tickRevision()); moved = true;
        }
        return Result.running("walking_off_structure");
    }
    @Override public void requestStop() { stopping = true; }
    @Override public void abandon() { result = Result.failed("structure_exit_abandoned","body control transferred",moved,moved); }
    @Override public boolean safeToInterrupt() { return result != null || grounded; }
    @Override public boolean livenessActive() { return result == null; }
    @Override public String phase() { return "walking_off_structure"; }
    @Override public Map<String,Object> diagnostics() { return Map.of("structure_id",structureId.toString(),
            "landing",List.of(destination.x,destination.y,destination.z),"grounded",grounded,"movement_submitted",moved); }
    static BodyControlPort.Movement toward(Vec3 delta,float yaw) {
        double length = delta.horizontalDistance(), angle = Math.toRadians(yaw);
        if (length < .01) return BodyControlPort.Movement.STOPPED;
        double x = delta.x / length * .45, z = delta.z / length * .45;
        return new BodyControlPort.Movement((float)(-x*Math.sin(angle)+z*Math.cos(angle)),
                (float)(x*Math.cos(angle)+z*Math.sin(angle)),false,false,false);
    }
}
