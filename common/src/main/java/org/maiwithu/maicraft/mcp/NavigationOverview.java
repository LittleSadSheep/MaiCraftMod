package org.maiwithu.maicraft.mcp;

import java.lang.ref.WeakReference;
import com.google.gson.JsonObject;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.core.pathing.goal.RegionalTerrain;

/** On-demand coarse terrain thumbnail; each request advances one bounded observation slice. */
final class NavigationOverview {
    private WeakReference<LocalPlayer> body=new WeakReference<>(null);
    private RegionalTerrain scan;
    private long observedTick;
    JsonObject describe(LocalPlayer player) {
        long tick=player.level().getGameTime();
        if(body.get()!=player || scan==null || scan.origin().distanceToSqr(player.position())>16
                || scan.complete() && tick-observedTick>40) {
            body=new WeakReference<>(player); scan=new RegionalTerrain(player.position()); observedTick=tick;
        }
        scan.advance(RegionalTerrain.observed(player),49);
        var gson=new com.google.gson.Gson();
        var result=gson.toJsonTree(scan.summary()).getAsJsonObject();
        result.addProperty("age_ticks",Math.max(0,tick-observedTick));
        result.addProperty("reference","relative to the observation origin; coarse samples, not a route");
        var offset=scan.origin().subtract(player.position());
        result.add("observation_origin_offset",gson.toJsonTree(java.util.List.of(offset.x,offset.y,offset.z)));
        return result;
    }
}
