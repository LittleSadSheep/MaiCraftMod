package org.maiwithu.maicraft.core.pathing.goal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.phys.Vec3;

/** Incremental coarse surface samples, shared by travel decisions and the downward overview. */
public final class RegionalTerrain {
    public interface View {
        Vec3 surfaceBelow(Vec3 point, int depth);
        boolean known(Vec3 point);
        boolean visible(Vec3 from, Vec3 to);
    }
    public record Surface(Vec3 point, int supportSamples, boolean visible) {
        public boolean platform() { return supportSamples >= 3; }
    }
    private static final int RADIUS=12, STRIDE=4, DEPTH=24;
    private final Vec3 origin;
    private final List<Surface> surfaces = new ArrayList<>();
    private int column, unknown;
    public RegionalTerrain(Vec3 origin) { this.origin=origin; }
    public static View observed(net.minecraft.client.player.LocalPlayer player) {
        var level=player.clientLevel;
        return new View() {
            public boolean known(Vec3 point) {
                return point.y>=level.getMinBuildHeight() && point.y<level.getMaxBuildHeight()
                        && level.hasChunkAt(net.minecraft.core.BlockPos.containing(point));
            }
            public Vec3 surfaceBelow(Vec3 point, int depth) {
                if(!known(point)) return null;
                var hit=level.clip(new net.minecraft.world.level.ClipContext(point,point.add(0,-depth,0),
                        net.minecraft.world.level.ClipContext.Block.COLLIDER,
                        net.minecraft.world.level.ClipContext.Fluid.NONE,player));
                if(hit.getType()!=net.minecraft.world.phys.HitResult.Type.BLOCK) return null;
                var feet=org.maiwithu.maicraft.core.pathing.util.BlockHelper.playerFeet(level,point.x,hit.getLocation().y,point.z);
                var probe=org.maiwithu.maicraft.core.pathing.transport.TransportLanding.inspect(level,level::hasChunkAt,
                        feet,player.getBbWidth(),player.getBbHeight(),
                        org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext.forbiddenBodyCells());
                return probe.destination()==null ? null : probe.destination().landingPoint();
            }
            public boolean visible(Vec3 from, Vec3 to) {
                return level.clip(new net.minecraft.world.level.ClipContext(from,to,
                        net.minecraft.world.level.ClipContext.Block.COLLIDER,
                        net.minecraft.world.level.ClipContext.Fluid.NONE,player)).getType()==net.minecraft.world.phys.HitResult.Type.MISS;
            }
        };
    }
    public Vec3 origin() { return origin; }
    public boolean complete() { return column==49; }
    public List<Surface> surfaces() { return List.copyOf(surfaces); }
    public void advance(View view, int budget) {
        long end=System.nanoTime()+1_000_000;
        for(int n=0;n<budget && !complete() && (n==0 || System.nanoTime()<end);n++) {
            int index=column++;
            Vec3 top=origin.add((index%7)*STRIDE-RADIUS,2,(index/7)*STRIDE-RADIUS);
            if(!view.known(top)) { unknown++; continue; }
            Vec3 floor=view.surfaceBelow(top,DEPTH);
            if(floor==null || floor.y>top.y || top.y-floor.y>DEPTH) continue;
            int supports=1;
            for(int[] offset:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
                Vec3 beside=view.surfaceBelow(floor.add(offset[0],.15,offset[1]),2);
                if(beside!=null && Math.abs(beside.y-floor.y)<.1) supports++;
            }
            surfaces.add(new Surface(floor,supports,view.visible(origin.add(0,1.6,0),floor.add(0,.5,0))));
        }
    }
    /** Bounds summarize samples, not continuous free space or a promise that a route exists. */
    public Map<String,Object> summary() {
        var groups=new LinkedHashMap<String,List<Surface>>();
        for(var surface:surfaces) {
            Vec3 delta=surface.point().subtract(origin);
            String sector=Math.hypot(delta.x,delta.z)<3 ? "below" : Math.abs(delta.x)>Math.abs(delta.z)
                    ? delta.x<0 ? "west" : "east" : delta.z<0 ? "north" : "south";
            groups.computeIfAbsent(sector+":"+(int)Math.floor(delta.y/4),ignored->new ArrayList<>()).add(surface);
        }
        var regions=new ArrayList<Map<String,Object>>();
        groups.entrySet().stream().sorted(java.util.Comparator.comparingDouble(entry -> entry.getValue().stream()
                .mapToDouble(s->s.point().distanceToSqr(origin)).min().orElseThrow())).limit(6).forEach(entry -> {
            var samples=entry.getValue();
            var data=new LinkedHashMap<String,Object>();
            data.put("direction",entry.getKey().split(":")[0]);
            data.put("relative_height",List.of(samples.stream().mapToDouble(s->s.point().y-origin.y).min().orElseThrow(),
                    samples.stream().mapToDouble(s->s.point().y-origin.y).max().orElseThrow()));
            data.put("horizontal_distance",List.of(samples.stream().mapToDouble(s->s.point().subtract(origin).horizontalDistance()).min().orElseThrow(),
                    samples.stream().mapToDouble(s->s.point().subtract(origin).horizontalDistance()).max().orElseThrow()));
            data.put("surface_samples",samples.size());
            data.put("broad_support_samples",samples.stream().filter(Surface::platform).count());
            data.put("visible_samples",samples.stream().filter(Surface::visible).count());
            data.put("evidence","sampled_loaded_geometry; continuity and route unverified");
            regions.add(data);
        });
        return Map.of("radius",RADIUS,"depth",DEPTH,"sample_stride",STRIDE,"sampled_columns",column,
                "unloaded_columns",unknown,"complete",complete(),"regions",regions,
                "unseen","outside sampled columns or depth remains unknown; absence is not no_path");
    }
}
