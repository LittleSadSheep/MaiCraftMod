package org.maiwithu.maicraft.core.task.physics;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.core.integration.jetpack.JetpackRoute;
import org.maiwithu.maicraft.core.integration.physics.SableStructureBridge;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.pathing.transport.TransportLanding;

/** 施工移位只选择有地面支撑、身体无碰撞且看得见点击面的真实落点，抵达后还要重新瞄准。 */
final class StructureEditApproach {
    private StructureEditApproach() {}
    static StructureWorksiteSearch.Probe probe(LocalPlayerContext ctx,SableStructureBridge.Structure ship,
            BlockPos target,boolean placing,List<StructureEditTarget.Click> faces,JetpackRoute.Space space,BlockPos feet) {
        var player=ctx.player();
        var dimensions=player.getDimensions(Pose.STANDING);
        var landing=TransportLanding.inspect(ctx.level(),ctx.level()::isLoaded,feet,
                dimensions.width()+.16,dimensions.height()+.08,NavigationSafetyContext.forbiddenBodyCells());
        if(landing.destination()==null) return new StructureWorksiteSearch.Probe(null,landing.unloaded(),landing.unknown());
        Vec3 at=landing.destination().landingPoint();
        var body=body(at,dimensions.width()+.16,dimensions.height()+.08);
        // 导航经过站立姿态，所以除当前身体的原生碰撞检查外，还为起身保留净空。
        if(placing&&!StructureEditTarget.outsidePlacement(ship.pose(),target,body)
                ||!space.clear(at,at)||!SableStructureBridge.clearBody(ctx.level(),body))
            return new StructureWorksiteSearch.Probe(null,false,false);
        var click=StructureEditTarget.visible(player,at.add(0,eyeHeight(ctx,placing),0),faces);
        return new StructureWorksiteSearch.Probe(click==null?null:new StructureWorksiteSearch.Site(landing.destination(),click),false,false);
    }
    static double eyeHeight(LocalPlayerContext ctx,boolean placing) {
        // 结构放置始终潜行，候选位置必须按潜行眼高看见外侧面，避免抵达后才发现被遮挡。
        return ctx.player().getEyeHeight(placing?Pose.CROUCHING:Pose.STANDING);
    }
    static StructureEditTarget.Click current(LocalPlayerContext ctx,SableStructureBridge.Structure ship,
            BlockPos target,boolean placing,List<StructureEditTarget.Click> faces) {
        var p=ctx.player();
        if(placing&&!StructureEditTarget.outsidePlacement(ship.pose(),target,p.getBoundingBox())) return null;
        return StructureEditTarget.visible(p,p.position().add(0,eyeHeight(ctx,placing),0),faces);
    }
    private static AABB body(Vec3 feet,double width,double height) {
        return new AABB(feet.x-width/2,feet.y+.001,feet.z-width/2,feet.x+width/2,feet.y+height,feet.z+width/2);
    }
    static Map<String,Object> gaze(LocalPlayerContext ctx,List<StructureEditTarget.Click> faces) {
        var p=ctx.player();Vec3 eye=p.getEyePosition();
        var hit=ctx.level().clip(new ClipContext(eye,eye.add(p.getViewVector(1).scale(p.blockInteractionRange())),
                ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,p));
        var out=new LinkedHashMap<String,Object>();
        out.put("feet_world",vector(p.position()));out.put("eye_world",vector(eye));
        out.put("sneaking",p.isShiftKeyDown());out.put("on_ground",p.onGround());out.put("reach",p.blockInteractionRange());
        out.put("expected_faces",faces.stream().map(c->Map.of("support_storage",List.of(c.support().getX(),c.support().getY(),c.support().getZ()),
                "face",c.face().getName(),"aim_world",vector(c.world()))).toList());
        // 记录实际视线碰到的方块和面，而非只保留过滤后的 null，让模型区分顶面遮挡、超距和转头未完成。
        out.put("actual_hit_type",hit.getType().name());
        if(hit.getType()==HitResult.Type.BLOCK) {
            out.put("actual_block",List.of(hit.getBlockPos().getX(),hit.getBlockPos().getY(),hit.getBlockPos().getZ()));
            out.put("actual_face",hit.getDirection().getName());out.put("actual_hit_location",vector(hit.getLocation()));
        }
        return out;
    }
    static List<Double> vector(Vec3 p) { return List.of(p.x,p.y,p.z); }
}
