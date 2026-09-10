package org.maiwithu.maicraft.core.pathing.goal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.phys.Vec3;

/**
 * 分多次少量读取周围地形，记录能站的表面、相邻支撑、可见性和材质。稀疏取样用于挑候选，不证明整个平台连续或路线能走通。
 */
public final class RegionalTerrain {
    public interface View {
        Vec3 surfaceBelow(Vec3 point, int depth);
        boolean known(Vec3 point);
        boolean visible(Vec3 from, Vec3 to);
        default String material(Vec3 surface) { return "unknown"; }
    }
    public record Surface(Vec3 point, int supportSamples, boolean visible, String material) {
        public Surface(Vec3 point,int supportSamples,boolean visible) { this(point,supportSamples,visible,"unknown"); }
        // “平台”是几何上的候选落脚面：中心及四邻采样中至少三处同高有支撑，不是游戏里的专用方块类型。
        public boolean platform() { return supportSamples >= 3; }
    }
    private static final int RADIUS=12, STRIDE=4, DEPTH=24;
    private static final List<Vec3> OFFSETS=offsets();
    private static final List<Vec3> OVERVIEW_OFFSETS=overviewOffsets();
    private final Vec3 origin;
    private final int radius,depth;
    private final List<Vec3> samples;
    private final List<Surface> surfaces = new ArrayList<>();
    private int column, unknown;
    public RegionalTerrain(Vec3 origin) { this(origin,RADIUS,DEPTH,OFFSETS); }
    private RegionalTerrain(Vec3 origin,int radius,int depth,List<Vec3> samples) {
        this.origin=origin; this.radius=radius; this.depth=depth; this.samples=samples;
    }
    // 概览把取样扩大到一百二十八格、向下二百五十六格；远处采得更疏。
    public static RegionalTerrain overview(Vec3 origin) { return new RegionalTerrain(origin,128,256,OVERVIEW_OFFSETS); }
    // 向下碰到表面后再检查身体与禁入区域；当前 known 使用 hasChunkAt，在原版客户端不能识别未加载区块。
    public static View observed(net.minecraft.client.player.LocalPlayer player) {
        var level=player.clientLevel;
        return new View() {
            private final Map<Vec3,String> materials=new java.util.HashMap<>();
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
                if(probe.destination()==null) return null;
                Vec3 surface=probe.destination().landingPoint();
                materials.put(surface,net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(hit.getBlockPos()).getBlock()).toString());
                return surface;
            }
            public boolean visible(Vec3 from, Vec3 to) {
                return level.clip(new net.minecraft.world.level.ClipContext(from,to,
                        net.minecraft.world.level.ClipContext.Block.COLLIDER,
                        net.minecraft.world.level.ClipContext.Fluid.NONE,player)).getType()==net.minecraft.world.phys.HitResult.Type.MISS;
            }
            public String material(Vec3 surface) { return materials.getOrDefault(surface,"unknown"); }
        };
    }
    public Vec3 origin() { return origin; }
    public boolean complete() { return column==samples.size(); }
    public List<Surface> surfaces() { return List.copyOf(surfaces); }
    // 每次最多处理指定数量的列，并在约一毫秒后让出；单列读取本身不会被中途打断。
    public void advance(View view, int budget) {
        // 把地形采样分摊到多次调用，并限制本次耗时。没加载或没采到的区域保持未知，不能算成空地。
        long end=System.nanoTime()+1_000_000;
        for(int n=0;n<budget && !complete() && (n==0 || System.nanoTime()<end);n++) {
            int index=column++;
            Vec3 top=origin.add(samples.get(index)).add(0,2,0);
            if(!view.known(top)) { unknown++; continue; }
            Vec3 floor=view.surfaceBelow(top,depth);
            if(floor==null || floor.y>top.y || top.y-floor.y>depth) continue;
            // 中心落脚点已经通过碰撞/空间检查，再探测四邻是否有近似同高的支撑面，过滤孤立尖点。
            int supports=1;
            for(int[] offset:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
                Vec3 beside=view.surfaceBelow(floor.add(offset[0],.15,offset[1]),2);
                if(beside!=null && Math.abs(beside.y-floor.y)<.1) supports++;
            }
            surfaces.add(new Surface(floor,supports,view.visible(origin.add(0,1.6,0),floor.add(0,.5,0)),view.material(floor)));
        }
    }
    private static List<Vec3> offsets() {
        var points=new ArrayList<Vec3>();
        for(int x=-RADIUS;x<=RADIUS;x+=STRIDE) for(int z=-RADIUS;z<=RADIUS;z+=STRIDE) points.add(new Vec3(x,0,z));
        points.sort(java.util.Comparator.comparingDouble(Vec3::lengthSqr)); return List.copyOf(points);
    }
    // 近、中、远取样交替安排，避免近处还没看完就完全没有远处信息。
    private static List<Vec3> overviewOffsets() {
        var medium=new ArrayList<Vec3>(); var far=new ArrayList<Vec3>();
        for(int x=-32;x<=32;x+=8) for(int z=-32;z<=32;z+=8) medium.add(new Vec3(x,0,z));
        for(int x=-128;x<=128;x+=32) for(int z=-128;z<=128;z+=32)
            if(x*x+z*z<=128*128) far.add(new Vec3(x,0,z));
        medium.sort(java.util.Comparator.comparingDouble(Vec3::lengthSqr));
        far.sort(java.util.Comparator.comparingDouble(Vec3::lengthSqr));
        var merged=new java.util.LinkedHashSet<Vec3>();
        for(int i=0;i<Math.max(OFFSETS.size(),Math.max(medium.size(),far.size()));i++) {
            if(i<OFFSETS.size()) merged.add(OFFSETS.get(i));
            if(i<far.size()) merged.add(far.get(i));
            if(i<medium.size()) merged.add(medium.get(i));
        }
        return List.copyOf(merged);
    }
    /** Bounds summarize samples, not continuous free space or a promise that a route exists. */
    // 按方位、高度段和材质分组，最多展示十二组；先留不同材质的平台候选，再补较近的组。
    public Map<String,Object> summary() {
        var groups=new LinkedHashMap<String,List<Surface>>();
        for(var surface:surfaces) {
            Vec3 delta=surface.point().subtract(origin);
            String sector=Math.hypot(delta.x,delta.z)<3 ? "below" : Math.abs(delta.x)>Math.abs(delta.z)
                    ? delta.x<0 ? "west" : "east" : delta.z<0 ? "north" : "south";
            groups.computeIfAbsent(sector+":"+(int)Math.floor(delta.y/4)+":"+surface.material(),ignored->new ArrayList<>()).add(surface);
        }
        var regions=new ArrayList<Map<String,Object>>();
        var ordered=groups.entrySet().stream().sorted(java.util.Comparator.comparingDouble(entry -> entry.getValue().stream()
                .mapToDouble(s->s.point().distanceToSqr(origin)).min().orElseThrow())).toList();
        var selected=new java.util.LinkedHashSet<Map.Entry<String,List<Surface>>>();
        var materials=new java.util.HashSet<String>();
        for(var entry:ordered) if(entry.getValue().stream().anyMatch(Surface::platform)
                && materials.add(entry.getValue().getFirst().material()) && selected.size()<12) selected.add(entry);
        for(var entry:ordered) if(selected.size()<12) selected.add(entry);
        selected.forEach(entry -> {
            var samples=entry.getValue();
            var data=new LinkedHashMap<String,Object>();
            data.put("direction",entry.getKey().split(":")[0]);
            data.put("surface_material",samples.getFirst().material());
            data.put("relative_height",List.of(samples.stream().mapToDouble(s->s.point().y-origin.y).min().orElseThrow(),
                    samples.stream().mapToDouble(s->s.point().y-origin.y).max().orElseThrow()));
            data.put("horizontal_distance",List.of(samples.stream().mapToDouble(s->s.point().subtract(origin).horizontalDistance()).min().orElseThrow(),
                    samples.stream().mapToDouble(s->s.point().subtract(origin).horizontalDistance()).max().orElseThrow()));
            data.put("surface_samples",samples.size());
            data.put("approximate_xz_span",List.of(
                    Math.floor(samples.stream().mapToDouble(s->s.point().x-origin.x).min().orElseThrow()/STRIDE)*STRIDE,
                    Math.ceil(samples.stream().mapToDouble(s->s.point().x-origin.x).max().orElseThrow()/STRIDE)*STRIDE,
                    Math.floor(samples.stream().mapToDouble(s->s.point().z-origin.z).min().orElseThrow()/STRIDE)*STRIDE,
                    Math.ceil(samples.stream().mapToDouble(s->s.point().z-origin.z).max().orElseThrow()/STRIDE)*STRIDE));
            data.put("broad_support_samples",samples.stream().filter(Surface::platform).count());
            data.put("visible_samples",samples.stream().filter(Surface::visible).count());
            data.put("evidence","sampled_loaded_geometry; continuity and route unverified");
            regions.add(data);
        });
        return Map.of("radius",radius,"depth",depth,"sample_strides",radius>RADIUS ? List.of(4,8,32) : List.of(4),
                "sampled_columns",column,"total_columns",samples.size(),"unloaded_columns",unknown,
                "complete",complete(),"regions",regions,"omitted_regions",Math.max(0,groups.size()-regions.size()),
                "unseen","sparse loaded-world samples; coverage and material do not prove a route or full platform extent");
    }
}
