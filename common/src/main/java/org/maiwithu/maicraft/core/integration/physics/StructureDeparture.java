package org.maiwithu.maicraft.core.integration.physics;

import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Pose;
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
    private boolean stopping, grounded, moved, crouching;
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
                    && posture(player,boxes,at)!=StructureExitPath.Posture.BLOCKED)
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
        return ship != null && quiet(ship.pose(), ship.lastPose(), feet);
    }
    static boolean quiet(StructurePose current, StructurePose previous, Vec3 feet) {
        if (current == null || previous == null) return false;
        // 拆轮维修时机身可能倾斜但脚下已稳定；倾角不能阻止搜索出口，实际台阶、落差和低顶仍由完整碰撞路径核验。
        // 保留脚位在两次姿态间的位移检查，旋转中的长翼尖也不能仅因质心未移动就被当成静止甲板。
        Vec3 local = current.toStorage(feet);
        return current.toWorld(local).distanceTo(previous.toWorld(local)) < .02;
    }
    private record Geometry(List<AABB> boxes,List<AABB> forbidden) {}
    // 潜行途中仍使用固定的站立尺寸检查是否已可起身，不能把当前较矮的身体误当成站立净空。
    private static StructureExitPath.Posture posture(LocalPlayer player,Geometry boxes,Vec3 destination) {
        return StructureExitPath.posture(boxes.boxes(),boxes.forbidden(),player.position(),destination,player.getBbWidth()+.04,
                player.getDimensions(Pose.STANDING).height()+.04,player.getDimensions(Pose.CROUCHING).height()+.04);
    }
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
        // 潜行经过低顶时速度较慢，给已验证的五格内出口十二秒，仍保留明确的退出超时。
        if (grounded && (stopping || ctx.tickRevision()-started > 240)) {
            InputDriver.halt(player);
            return result = Result.failed("structure_exit_stopped","walking departure did not reach ground",moved,false);
        }
        if (grounded && contact.supportedBy(structureId)) {
            var boxes = geometry(ctx,forbidden);
            var posture=boxes==null?StructureExitPath.Posture.BLOCKED:posture(player,boxes,destination);
            if (!quiet(SableStructureBridge.find(ctx.level(),structureId),player.position()) || boxes == null
                    || posture==StructureExitPath.Posture.BLOCKED) {
                InputDriver.halt(player);
                return result = Result.failed("structure_exit_changed","observed deck corridor changed",moved,false);
            }
            // 离开座面前先真实按下潜行，身体降到原生蹲姿后再走；甲板净空恢复就松开，避免潜行护边阻止离艇。
            crouching=posture==StructureExitPath.Posture.CROUCHING;
            if(crouching&&player.getBbHeight()>player.getDimensions(Pose.CROUCHING).height()+.01) {
                ctx.body().applyMovement(new BodyControlPort.Movement(0,0,false,true,false),ctx.tickRevision());
                return Result.running("crouching_under_cabin_roof");
            }
        }
        // 按真实移动方向步行，落点附近松开前进等待落地，不改玩家位置或载具碰撞关系。
        if (player.position().subtract(destination).horizontalDistance() < .18) InputDriver.halt(player);
        else {
            // 转头有平滑延迟，前进和横移按当刻真实朝向重新投影，不能先沿旧视线走出已验证的离车通道。
            InputDriver.lookAt(player,destination.add(0,player.getEyeHeight(),0));
            Vec3 delta = destination.subtract(player.position());
            ctx.body().applySteering(yaw -> toward(delta,yaw,crouching),player.getYRot(),ctx.tickRevision()); moved = true;
        }
        return Result.running("walking_off_structure");
    }
    @Override public void requestStop() { stopping = true; }
    @Override public void abandon() { result = Result.failed("structure_exit_abandoned","body control transferred",moved,moved); }
    @Override public boolean safeToInterrupt() { return result != null || grounded; }
    @Override public boolean livenessActive() { return result == null; }
    @Override public String phase() { return "walking_off_structure"; }
    @Override public Map<String,Object> diagnostics() { return Map.of("structure_id",structureId.toString(),
            "landing",List.of(destination.x,destination.y,destination.z),"grounded",grounded,"movement_submitted",moved,"crouching",crouching); }
    static BodyControlPort.Movement toward(Vec3 delta,float yaw) {
        return toward(delta,yaw,false);
    }
    static BodyControlPort.Movement toward(Vec3 delta,float yaw,boolean crouching) {
        double length = delta.horizontalDistance(), angle = Math.toRadians(yaw);
        if (length < .01) return BodyControlPort.Movement.STOPPED;
        double x = delta.x / length * .45, z = delta.z / length * .45;
        return new BodyControlPort.Movement((float)(-x*Math.sin(angle)+z*Math.cos(angle)),
                (float)(x*Math.cos(angle)+z*Math.sin(angle)),false,crouching,false);
    }
}
