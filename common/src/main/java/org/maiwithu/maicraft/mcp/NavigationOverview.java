package org.maiwithu.maicraft.mcp;

import java.lang.ref.WeakReference;
import com.google.gson.JsonObject;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.core.pathing.goal.RegionalTerrain;
import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** One asynchronous terrain thumbnail; client ticks do the work without blocking the game thread. */
final class NavigationOverview {
    private WeakReference<LocalPlayer> body=new WeakReference<>(null);
    private RegionalTerrain scan;
    private long observedTick;
    private long requestedAt;
    private final List<CompletableFuture<Void>> pending=new ArrayList<>();
    CompletionStage<Void> prepare(LocalPlayer player) {
        long tick=player.level().getGameTime();
        if(body.get()!=player) finish(new IllegalStateException("terrain observation body changed"));
        if(pending.isEmpty() && (body.get()!=player || scan==null || scan.origin().distanceToSqr(player.position())>16
                || scan.complete() && tick-observedTick>40)) {
            body=new WeakReference<>(player); scan=RegionalTerrain.overview(player.position()); observedTick=tick;
        }
        if(scan.complete()) return CompletableFuture.completedFuture(null);
        if(pending.isEmpty()) requestedAt=System.nanoTime();
        var request=new CompletableFuture<Void>(); pending.add(request); return request;
    }
    void tick(LocalPlayer player) {
        if(pending.isEmpty()) return;
        if(player==null || body.get()!=player) { finish(new IllegalStateException("terrain observation world closed")); return; }
        advance(RegionalTerrain.observed(player));
    }
    void advance(RegionalTerrain.View view) {
        try {
            scan.advance(view,8);
            if(scan.complete() || System.nanoTime()-requestedAt>=5_000_000_000L) finish(null);
        } catch(RuntimeException failed) { finish(failed); }
    }
    private void finish(Throwable failure) {
        var requests=List.copyOf(pending); pending.clear();
        for(var request:requests) {
            if(failure==null) request.complete(null); else request.completeExceptionally(failure);
        }
    }
    JsonObject describe(LocalPlayer player) {
        long tick=player.level().getGameTime();
        if(scan==null || body.get()!=player) {
            body=new WeakReference<>(player); scan=RegionalTerrain.overview(player.position()); observedTick=tick;
        }
        var gson=new Gson();
        var result=gson.toJsonTree(scan.summary()).getAsJsonObject();
        result.addProperty("age_ticks",Math.max(0,tick-observedTick));
        result.addProperty("reference","relative to the observation origin; coarse samples, not a route");
        var offset=scan.origin().subtract(player.position());
        result.add("observation_origin_offset",gson.toJsonTree(List.of(offset.x,offset.y,offset.z)));
        return result;
    }
}
