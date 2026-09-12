// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine.watch;

import com.google.gson.JsonObject;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.maiwithu.maicraft.server.machine.ProductionEventJournal;
import org.maiwithu.maicraft.server.machine.ServerAccess;
import org.maiwithu.maicraft.server.machine.ServerProductionEvents;

/** Connection/level-scoped read leases. Background ticks never transfer items, configure machines or load chunks. */
public final class MachineWatchService {
    public static final int MAX_JOBS = 16, MAX_JOBS_PER_PLAYER = 4, JOBS_PER_TICK = 2;
    private static final long REGISTRATION_TICKS = 6000, HISTORY_TICKS = 6000;
    private static final Map<MinecraftServer,Store> SERVERS = new IdentityHashMap<>();
    private static final class Store { final Map<UUID,Job> jobs = new LinkedHashMap<>(); int next; }
    private static final class Job {
        final UUID id; final ServerPlayer owner; final Object connection; final ServerLevel level; final WatchGoal goal;
        final WatchProgress progress; final long created; final Map<BlockPos,WatchNativeAccess.Endpoint> endpoints = new LinkedHashMap<>();
        final Map<String,String> recipes = new LinkedHashMap<>(); final Set<String> targetProcesses = new java.util.HashSet<>();
        ProductionEventJournal journal; Set<String> retained = Set.of(); long armedAt = -1, nextPoll, previousComplete = -1, stoppedAt = -1;
        Job(UUID id, ServerPlayer owner, WatchGoal goal, long tick) {
            this.id = id; this.owner = owner; connection = connection(owner); level = owner.serverLevel(); this.goal = goal;
            created = tick; progress = new WatchProgress(goal);
        }
        void release() { if (journal != null && !retained.isEmpty()) { journal.release(this,retained,progress.scope()); retained = Set.of(); } }
        JsonObject report() {
            JsonObject result = progress.report(); result.addProperty("schema","maicraft.machine_watch.v1"); result.addProperty("job_id",id.toString());
            result.addProperty("dimension",goal.dimension()); result.addProperty("authorized_positions",endpoints.size());
            result.addProperty("required_positions",goal.positions().size()); result.addProperty("registration_complete",endpoints.size() == goal.positions().size());
            result.addProperty("monitor_active",progress.armed() && !progress.stopped());
            result.addProperty("background_read_only",true); result.addProperty("forces_chunks",false); result.addProperty("max_duration_ticks",goal.maximumTicks());
            result.addProperty("history_retention","up_to_6000_ticks_or_capacity");
            return result;
        }
    }
    private MachineWatchService() {}

    public static JsonObject execute(ServerPlayer player, JsonObject body) {
        MinecraftServer server = player.getServer(); requireThread(server);
        String action = ServerAccess.text(body,"action");
        WatchGoal.keys(body, action.equals("register") ? new String[]{"action","job_id","goal","authorize_positions"} : new String[]{"action","job_id"});
        UUID id = UUID.fromString(ServerAccess.text(body,"job_id"));
        Store store = SERVERS.computeIfAbsent(server,ignored -> new Store());
        Job job = store.jobs.get(id);
        if (job != null && (job.owner != player || job.connection != connection(player) || job.level != player.serverLevel()))
            throw ServerAccess.denied("watch_not_found","No monitor in this player connection and world");
        if (action.equals("register")) return register(store,id,job,player,body,serverTick(server));
        if (job == null) throw ServerAccess.denied("watch_not_found","No monitor in this player connection and world");
        if (action.equals("cancel")) { job.progress.cancel(); job.release(); if (job.stoppedAt < 0) job.stoppedAt = serverTick(server); }
        else if (!action.equals("status")) throw ServerAccess.denied("invalid_argument","Unknown monitor action");
        return job.report();
    }

    private static JsonObject register(Store store, UUID id, Job job, ServerPlayer player, JsonObject body, long now) {
        WatchGoal goal = WatchGoal.parse(body.getAsJsonObject("goal"));
        if (!goal.dimension().equals(player.serverLevel().dimension().location().toString())) throw WatchGoal.invalid("Monitor dimension differs from player");
        if (job == null) {
            makeRoom(store,connection(player));
            job = new Job(id,player,goal,now); store.jobs.put(id,job);
        } else if (!job.goal.specification().equals(goal.specification())) throw ServerAccess.denied("watch_conflict","Existing monitor goal is immutable");
        var positions = body.getAsJsonArray("authorize_positions");
        if (positions == null || positions.isEmpty() || positions.size() > 4) throw WatchGoal.invalid("Authorize one to four nearby positions");
        Set<BlockPos> requested = new java.util.LinkedHashSet<>();
        for (var raw : positions) {
            BlockPos position = ServerAccess.position(raw.getAsJsonObject());
            if (!goal.positions().contains(position) || !requested.add(position)) throw WatchGoal.invalid("Authorization position is not a distinct goal endpoint");
        }
        if (job.progress.armed() || job.progress.stopped()) return job.report();
        Map<BlockPos,WatchNativeAccess.Endpoint> observed = new LinkedHashMap<>();
        for (BlockPos position : requested) if (!job.endpoints.containsKey(position)) observed.put(position,WatchNativeAccess.authorize(player,position));
        job.endpoints.putAll(observed);
        for (var process : goal.processes()) if (!job.recipes.containsKey(process.id()) && job.endpoints.containsKey(process.position())
                && job.endpoints.containsKey(process.outputPosition())) {
            var recipe = WatchNativeAccess.recipe(player,goal,process); job.recipes.put(process.id(),recipe.id());
            if (recipe.targetOutput()) job.targetProcesses.add(process.id());
        }
        if (job.endpoints.size() == goal.positions().size()) arm(job,now);
        return job.report();
    }
    private static void makeRoom(Store store, Object connection) {
        while (store.jobs.size() >= MAX_JOBS || store.jobs.values().stream().filter(job -> job.connection == connection).count() >= MAX_JOBS_PER_PLAYER) {
            boolean ownLimit = store.jobs.values().stream().filter(job -> job.connection == connection).count() >= MAX_JOBS_PER_PLAYER;
            Job old = store.jobs.values().stream().filter(job -> job.progress.stopped() && (!ownLimit || job.connection == connection))
                    .min(java.util.Comparator.comparingLong(job -> job.created)).orElse(null);
            if (old == null) throw ServerAccess.denied("watch_limit","Cancel or expire an existing monitor first");
            old.release(); store.jobs.remove(old.id);
        }
    }

    private static void arm(Job job, long now) {
        if (job.progress.armed() || job.progress.stopped()) return;
        if (job.recipes.size() != job.goal.processes().size() || job.targetProcesses.isEmpty()) {
            job.progress.attention("native_recipe_target_unverified",true); return;
        }
        String unavailable = WatchNativeAccess.available(job.owner,job.level,job.endpoints);
        if (unavailable != null) { unavailable(job,unavailable); return; }
        long stock = WatchNativeAccess.sink(job.owner,job.goal);
        job.journal = ServerProductionEvents.trustedJournal(job.level);
        Set<String> endpoints = job.goal.positions().stream().map(ServerProductionEvents::key).collect(java.util.stream.Collectors.toSet());
        if (!job.journal.retain(job,endpoints)) { job.progress.attention("native_history_retention_limit",true); return; }
        job.retained = endpoints;
        long sequence = job.journal.latestSequence();
        JsonObject baseline = job.journal.page(sequence,endpoints,job.level.getGameTime(),null);
        job.progress.arm(baseline.get("scope").getAsString(),sequence,job.level.getGameTime(),stock,job.recipes);
        job.armedAt = now; job.nextPoll = now; job.previousComplete = now;
    }

    public static void tick(MinecraftServer server) {
        requireThread(server); Store store = SERVERS.get(server); if (store == null || store.jobs.isEmpty()) return;
        long now = serverTick(server); var jobs = java.util.List.copyOf(store.jobs.values());
        int budget = JOBS_PER_TICK;
        for (int visited = 0; visited < jobs.size() && budget > 0; visited++) {
            Job job = jobs.get(Math.floorMod(store.next++,jobs.size()));
            if (job.progress.stopped()) {
                job.release(); if (job.stoppedAt < 0) job.stoppedAt = now;
                if (now-job.stoppedAt >= HISTORY_TICKS) store.jobs.remove(job.id);
                continue;
            }
            if (job.owner.hasDisconnected() || job.connection != connection(job.owner) || job.owner.serverLevel() != job.level
                    || !job.owner.isAlive() || job.owner.isSpectator()) { job.progress.attention("owner_lifecycle_changed",true); job.release(); continue; }
            if (!org.maiwithu.maicraft.network.ServerOperationRegistry.allowed(job.owner,"machine.watch")) {
                job.progress.attention("server_policy_changed",true); job.release(); continue;
            }
            if (now - (job.armedAt < 0 ? job.created : job.armedAt) > (job.armedAt < 0 ? REGISTRATION_TICKS : job.goal.maximumTicks())) {
                job.progress.attention(job.armedAt < 0 ? "registration_expired" : "monitor_expired",true); job.release(); continue;
            }
            if (now < job.nextPoll) continue;
            budget--; job.nextPoll = now+20;
            try {
                if (!job.progress.armed()) { if (job.endpoints.size() == job.goal.positions().size()) arm(job,now); continue; }
                String unavailable = WatchNativeAccess.available(job.owner,job.level,job.endpoints);
                if (unavailable != null) { unavailable(job,unavailable); continue; }
                job.progress.accept(job.journal.page(job.progress.cursor(),job.retained,job.level.getGameTime(),job.progress.scope()));
                if (job.progress.stopped()) { job.release(); continue; }
                long stock = WatchNativeAccess.sink(job.owner,job.goal);
                job.progress.observedSink(stock,job.level.getGameTime(),job.previousComplete < 0 ? 0 : now-job.previousComplete);
                job.previousComplete = now;
                if (!job.progress.caughtUp()) job.nextPoll = now+1;
                if (job.progress.stopped()) job.release();
            } catch (RuntimeException | LinkageError unreadable) { job.progress.attention("native_observation_unavailable",true); job.release(); }
        }
    }
    private static void unavailable(Job job, String reason) {
        job.previousComplete = -1;
        if (reason.equals("waiting_loaded")) job.progress.waitingLoaded(); else { job.progress.attention(reason,true); job.release(); }
    }
    public static void disconnected(ServerPlayer player) {
        requireThread(player.getServer());
        for (Store store : SERVERS.values()) store.jobs.values().removeIf(job -> {
            if (job.connection != connection(player) && job.owner != player) return false; job.release(); return true;
        });
    }
    public static void stopped(MinecraftServer server) {
        requireThread(server);
        Store store = SERVERS.remove(server); if (store != null) store.jobs.values().forEach(Job::release);
    }
    private static Object connection(ServerPlayer player) { return player.connection == null ? player : player.connection; }
    private static long serverTick(MinecraftServer server) { return Integer.toUnsignedLong(server.getTickCount()); }
    private static void requireThread(MinecraftServer server) { if (server == null || !server.isSameThread()) throw WatchGoal.invalid("Monitor requires the server thread"); }
}
