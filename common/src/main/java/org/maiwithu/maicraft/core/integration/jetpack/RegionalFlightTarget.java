package org.maiwithu.maicraft.core.integration.jetpack;

import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.core.pathing.goal.RegionalGoal;
import org.maiwithu.maicraft.core.pathing.goal.RegionalTerrain;

/** Keeps one flight owner while successive observations turn a direction into a real platform. */
public final class RegionalFlightTarget implements MovingFlightTarget {
    private final RegionalGoal goal;
    private RegionalTerrain terrain;
    private final Map<BlockPos,Integer> visits=new HashMap<>();
    private final java.util.Set<BlockPos> rejected=new HashSet<>();
    private Vec3 point, position;
    private Vec3 segmentStart;
    private List<Vec3> candidates=List.of();
    private int candidateIndex, legs, stableTicks;
    private long observedTick;
    private boolean landing, contacted, exhausted;
    private boolean settling;
    public RegionalFlightTarget(RegionalGoal goal) { this.goal=goal; position=goal.origin(); }
    public boolean update(LocalPlayerContext ctx) {
        return advance(ctx.player().position(),ctx.player().getDeltaMovement(),ctx.player().onGround(),
                JetpackRoute.observed(ctx),RegionalTerrain.observed(ctx.player()),JetpackNativeAdapter.inspect(ctx),ctx.tickRevision());
    }
    boolean advance(Vec3 current, Vec3 velocity, boolean grounded, JetpackRoute.Space space,
                    RegionalTerrain.View view, JetpackNativeAdapter.Snapshot power, long tick) {
        position=current;
        if(exhausted || !settling && current.distanceTo(goal.origin())>goal.radius()+2) return false;
        if(terrain==null || terrain.complete() && (terrain.origin().distanceToSqr(current)>16 || tick-observedTick>40)) {
            terrain=new RegionalTerrain(current); observedTick=tick;
        }
        terrain.advance(view,4);
        if(landing && !JetpackRoute.supportsLanding(space,point)) { rejected.add(BlockPos.containing(point)); landing=false; point=null; }
        if(!landing) {
            Vec3 platform=terrain.surfaces().stream().filter(RegionalTerrain.Surface::platform)
                    .filter(s->(settling || goal.matches(s.point())) && !rejected.contains(BlockPos.containing(s.point())))
                    .filter(s->JetpackRoute.supportsLanding(space,s.point()))
                    .min(Comparator.comparingDouble(s->s.point().distanceToSqr(current)-s.supportSamples()*2))
                    .map(RegionalTerrain.Surface::point).orElse(null);
            if(platform!=null) { point=platform; landing=true; candidates=List.of(); }
        }
        contacted=landing && grounded && current.distanceTo(point)<.85;
        stableTicks=contacted && Math.abs(velocity.y)<.1 ? stableTicks+1 : 0;
        if(landing) return true;
        if(point!=null && (arrived(current,point) || passed(segmentStart,point,current) || !JetpackRoute.flightClear(space,current,point,power))) {
            visits.merge(cell(point),1,Integer::sum); point=null; candidates=List.of();
        }
        if(point!=null) return true;
        if(legs>=(settling ? 256 : 128)) { exhausted=true; return false; }
        if(candidates.isEmpty()) {
            var next=new ArrayList<Vec3>();
            for(int length:new int[]{6,3}) for(int x=-1;x<=1;x++) for(int y=-1;y<=1;y++) for(int z=-1;z<=1;z++) {
                if(x==0 && y==0 && z==0) continue;
                Vec3 target=current.add(new Vec3(x,y,z).normalize().scale(length));
                if((settling || goal.contains(target)) && visits.getOrDefault(cell(target),0)<2) next.add(target);
            }
            next.sort(Comparator.comparingDouble(p->score(current,p)));
            candidates=next; candidateIndex=0;
        }
        long end=System.nanoTime()+1_000_000;
        for(int n=0;n<4 && candidateIndex<candidates.size() && (n==0 || System.nanoTime()<end);n++) {
            Vec3 candidate=candidates.get(candidateIndex++);
            if(JetpackRoute.flightClear(space,current,candidate,power)) {
                segmentStart=current; point=candidate; legs++; candidates=List.of(); return true;
            }
        }
        if(candidateIndex==candidates.size()) { exhausted=true; return false; }
        return true;
    }
    private double score(Vec3 current,Vec3 target) {
        return -target.subtract(current).dot(settling ? new Vec3(0,-1,0) : goal.direction())*4 + target.distanceTo(goal.origin())*.03
                + visits.getOrDefault(cell(target),0)*30;
    }
    private static BlockPos cell(Vec3 point) { return BlockPos.containing(point.scale(1D/3)); }
    // These are observation neighborhoods; native platform contact owns final arrival.
    static boolean arrived(Vec3 current,Vec3 target) { return current.distanceToSqr(target)<2.25; }
    static boolean passed(Vec3 start,Vec3 target,Vec3 current) {
        if(start==null) return false;
        Vec3 direction=target.subtract(start).normalize(), beyond=current.subtract(target);
        double forward=beyond.dot(direction);
        return forward>=0 && beyond.subtract(direction.scale(forward)).lengthSqr()<1;
    }
    public Vec3 point() { return point==null ? position : point; }
    public Vec3 velocity() { return Vec3.ZERO; }
    public boolean contact() { return contacted; }
    public boolean touchdown() { return stableTicks>=3; }
    public boolean landingSelected() { return landing; }
    public boolean ready() { return point!=null; }
    public boolean supportsFastDescent() { return true; }
    public boolean seekLandingOnStop() {
        if(!settling) {
            settling=true; exhausted=false;
            if(!landing) { point=null; candidates=List.of(); terrain=null; }
        }
        return true;
    }
    public boolean nextLanding() {
        if(!landing) return false;
        rejected.add(BlockPos.containing(point)); landing=false; point=null; return true;
    }
    public JetpackRoute.Space space(LocalPlayerContext context,LongSet forbidden) { return JetpackRoute.observed(context,forbidden); }
    public Map<String,Object> diagnostics() {
        return Map.of("kind","regional_platform_discovery","direction",goal.direction().toString(),"radius",goal.radius(),
                "landing_selected",landing,"observation_legs",legs,"stable_touchdown",touchdown(),"exhausted",exhausted,"seeking_exit",settling,
                "terrain",terrain==null ? Map.of() : terrain.summary());
    }
}
