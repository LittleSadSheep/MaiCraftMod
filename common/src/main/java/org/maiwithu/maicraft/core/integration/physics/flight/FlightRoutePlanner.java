package org.maiwithu.maicraft.core.integration.physics.flight;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.integration.physics.SableStructureBridge;

/** 远程目标边飞边观察；每次局部规划使用真实机体、有限转弯航迹和已加载地形，落点另做完整跑道检查。 */
public final class FlightRoutePlanner {
    private final FlightEnvelope envelope;
    private final Vec3 destination;
    private final double requestedAltitude;
    private FlightLandingSite landing;
    private FlightGuidance guidance;
    private long planned=Long.MIN_VALUE,landingSearched=Long.MIN_VALUE;
    private double clearance=Double.NaN;
    private Map<String,Object> evidence=Map.of();
    public FlightRoutePlanner(FlightEnvelope envelope,Vec3 destination,double cruiseAltitude) {
        this.envelope=envelope;this.destination=destination;requestedAltitude=cruiseAltitude;
    }
    public FlightGuidance tick(ClientLevel world,SableStructureBridge.Structure ship,FlightSample sample,FlightFeedbackController.Phase phase) {
        if(guidance!=null&&sample.tick()-planned<5)return guidance;
        planned=sample.tick();AABB bounds=ship.worldBounds();
        if(bounds==null)throw new IllegalStateException("aircraft bounds are unavailable");
        if(Double.isNaN(clearance))clearance=Math.max(1,sample.position().y-bounds.minY+1);
        if(sample.contact()==FlightSample.Contact.GROUNDED) {
            BlockPos at=BlockPos.containing(sample.position());
            if(world.getChunkSource().hasChunk(at.getX()>>4,at.getZ()>>4))
                clearance=Math.max(.25,sample.position().y-world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,at.getX(),at.getZ()));
        }
        double cruise=Math.max(requestedAltitude,landing==null?requestedAltitude:landing.touchdown().y+16);
        double width=Math.max(bounds.getXsize(),bounds.getZsize())+2;
        double length=envelope.kind()==FlightEnvelope.Kind.FIXED_WING?Math.max(48,envelope.cruiseSpeed()*4):width+4;
        var probe=FlightWorldProbe.capture(world,ship);
        if(landing==null&&(landingSearched==Long.MIN_VALUE||sample.tick()-landingSearched>=40)) {
            landingSearched=sample.tick();
            landing=FlightLandingSite.find(world,probe,destination,clearance,width,length,sample.heading(),sample.tick());
        } else if(landing!=null&&landing.touchdown().subtract(sample.position()).horizontalDistance()<96) {
            var fresh=FlightLandingSite.inspect(world,probe,landing.touchdown(),clearance,width,length,landing.heading(),sample.tick());
            if(fresh!=null)landing=fresh;
            else if(sample.tick()-landing.observedTick()>40)landing=null;
        }
        // 未找到落点时先飞向目的地区域取得观察；有了场地再绕到进近起点，不能在尚未加载的远方假造跑道。
        Vec3 approach=landing==null?new Vec3(destination.x,cruise,destination.z):landing.approach(cruise,envelope);
        Vec3 touchdown=landing==null?destination:landing.touchdown();
        boolean finalApproach=phase==FlightFeedbackController.Phase.DESCENT||phase==FlightFeedbackController.Phase.FLARE;
        Vec3 target=finalApproach?touchdown:approach;
        double wanted=FlightSample.heading(target.subtract(sample.position()));
        double seconds=Math.max(3,Math.min(6,envelope.cruiseSpeed()/4));
        var departure=FlightPathProbe.trace(probe,sample,bounds,sample.heading(),0,Math.min(5,seconds),envelope);
        // 固定翼检查前方滑跑空间，飞艇检查垂直离地与减速余量，不要求先有一条水平跑道。
        var departureState=envelope.kind()==FlightEnvelope.Kind.AIRSHIP
                ?FlightPathProbe.verticalDeparture(probe,bounds,sample.contact()==FlightSample.Contact.GROUNDED):departure.state();
        boolean departureClear=departureState==FlightPathProbe.Space.CLEAR;
        var departureEvidence=probe.lastObservation();
        FlightPathProbe.Result best=null;double bestCost=Double.POSITIVE_INFINITY;
        var trials=new ArrayList<Map<String,Object>>();
        double[] headings={0,.26,-.26,.52,-.52,1.05,-1.05,Math.PI};
        double glide=Math.min(cruise,touchdown.y+touchdown.subtract(sample.position()).horizontalDistance()*Math.tan(envelope.approachPitch()));
        double wantedVertical=Math.clamp(((finalApproach?glide:cruise)-sample.position().y)*.25,-envelope.descentRate(),envelope.climbRate());
        var direct=FlightPathProbe.trace(probe,sample,bounds,finalApproach&&landing!=null?landing.heading():wanted,wantedVertical,seconds,envelope);
        for(double offset:headings)for(double vertical:new double[]{wantedVertical,0,envelope.climbRate(),-envelope.descentRate()}) {
            double heading=FlightSample.wrap(wanted+offset);
            var result=FlightPathProbe.trace(probe,sample,bounds,heading,vertical,seconds,envelope);
            trials.add(Map.of("heading",heading,"vertical_speed",vertical,"state",result.state().name()));
            if(result.state()!=FlightPathProbe.Space.CLEAR)continue;
            Vec3 end=result.path().getLast();double cost=end.distanceTo(target)+Math.abs(offset)*8+Math.abs(vertical-wantedVertical)*2;
            if(cost<bestCost){best=result;bestCost=cost;}
        }
        Vec3 waypoint=best==null?sample.position().add(FlightSample.forward(sample.heading(),0).scale(32)):best.path().getLast();
        boolean known=best!=null||trials.stream().noneMatch(t->t.get("state").equals("UNKNOWN"));
        guidance=new FlightGuidance(waypoint,approach,touchdown,landing==null?sample.heading():landing.heading(),cruise,
                departureClear,known,best!=null&&(!finalApproach||direct.state()==FlightPathProbe.Space.CLEAR),
                landing!=null,best==null?sample.position().y:waypoint.y);
        evidence=Map.of("block_reads",probe.blockReads(),"candidate_routes",List.copyOf(trials),"selected_path",best==null?List.of():best.path(),
                "landing_site",landing==null?Map.of():Map.of("touchdown",landing.touchdown(),"heading",landing.heading(),"observed_tick",landing.observedTick()),
                "ground_clearance",clearance,"cruise_altitude",cruise,"departure_state",departureState.name(),
                "departure_observation",departureEvidence,"aircraft_bounds",bounds);
        return guidance;
    }
    public Map<String,Object> evidence(){return evidence;}
}
