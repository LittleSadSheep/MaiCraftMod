package org.maiwithu.maicraft.core.task.physics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.pathing.transport.TransportTargets;

/** 按离麦麦的距离逐刻检查地面施工站位；一次导航失败不会把其他可见站位一起判死。 */
final class StructureWorksiteSearch {
    record Site(TransportTargets.Destination landing,StructureEditTarget.Click click) {}
    record Probe(Site site,boolean unloaded,boolean unknown) {}
    private final List<BlockPos> cells;
    private final List<Map<String,Object>> attempts=new ArrayList<>();
    private int scanned,unloaded,unknown;

    StructureWorksiteSearch(Vec3 focus,Vec3 player,double reach,double eyeHeight) {
        // 眼点必须在触及球内；多留一格覆盖半砖的真实脚高，最终仍按原生落点和射线过滤。
        double radius=Math.min(8,Math.max(1,reach))+1;
        var low=BlockPos.containing(focus.x-radius,focus.y-eyeHeight-radius,focus.z-radius);
        var high=BlockPos.containing(focus.x+radius,focus.y-eyeHeight+radius+1,focus.z+radius);
        var ordered=new ArrayList<BlockPos>();
        for(BlockPos p:BlockPos.betweenClosed(low,high)) ordered.add(p.immutable());
        ordered.sort(Comparator.comparingDouble(p->Vec3.atBottomCenterOf(p).distanceToSqr(player)));
        cells=List.copyOf(ordered);
    }
    Site advance(Function<BlockPos,Probe> inspect) {
        // 每刻最多检查 24 个位置；找到后交回导航，失败再从下一个位置接着找，不重复走向同一格。
        int end=Math.min(cells.size(),scanned+24);
        while(scanned<end) {
            Probe probe=inspect.apply(cells.get(scanned++));
            if(probe.unloaded()) unloaded++;
            if(probe.unknown()) unknown++;
            if(probe.site()!=null) return probe.site();
        }
        return null;
    }
    boolean exhausted() { return scanned>=cells.size(); }
    void record(Map<String,Object> attempt) { attempts.add(Map.copyOf(attempt)); }
    Map<String,Object> diagnostics() {
        // 扫描有明确边界，不能把局部候选耗尽描述成整片世界都没有可行站位。
        return Map.of("scope","nearby_main_world_landings","scanned_cells",scanned,"total_cells",cells.size(),
                "unloaded_cells",unloaded,"unknown_cells",unknown,"scan_exhausted",exhausted(),"attempts",List.copyOf(attempts));
    }
}
