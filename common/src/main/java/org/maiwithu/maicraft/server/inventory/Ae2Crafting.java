// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.inventory;

import com.google.gson.JsonObject;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Future;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import org.maiwithu.maicraft.server.machine.NativeApi;
import org.maiwithu.maicraft.server.machine.ServerAccess;

/** Native AE2 planning and standalone CPU jobs, with player-bound tokens and no submission replay. */
public final class Ae2Crafting {
    static final String PLAN = "appeng.api.networking.crafting.ICraftingPlan";
    static final String LINK = "appeng.api.networking.crafting.ICraftingLink";
    private static final Map<UUID, Ae2CraftJob> JOBS = new LinkedHashMap<>();
    private Ae2Crafting() {}

    public static JsonObject plan(ServerPlayer player, JsonObject body) {
        BlockPos position = ServerAccess.position(body.getAsJsonObject("position"));
        Direction side = ServerAccess.side(body);
        String resourceId = ServerAccess.text(body, "resource_id");
        int amount = ServerAccess.integer(body, "amount", 1, 4096);
        Ae2Access access = Ae2Access.terminal(player, position, side, true);
        expireFinished();
        if (JOBS.size() >= 128 || JOBS.values().stream().filter(job -> job.owner.get() == player && !finished(job)).count() >= 8) {
            throw ServerAccess.denied("crafting_job_limit", "Release completed plans before creating more jobs");
        }
        Object key = Ae2Keys.find(access, player, resourceId, true);
        Object service = Ae2Keys.crafting(access);
        if (!NativeApi.truth(NativeApi.call(service, Ae2Keys.CRAFTING, "isCraftable", key))) {
            throw ServerAccess.denied("not_craftable", "No actual installed pattern produces this key");
        }
        Class<?> requesterApi = NativeApi.type("appeng.api.networking.crafting.ICraftingSimulationRequester");
        Object requester = Proxy.newProxyInstance(requesterApi.getClassLoader(), new Class<?>[]{requesterApi},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getActionSource" -> access.actionSource();
                    case "getGridNode" -> access.node();
                    case "toString" -> "MaiCraft player crafting requester";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        Future<?> future = (Future<?>) NativeApi.call(service, Ae2Keys.CRAFTING, "beginCraftingCalculation",
                player.serverLevel(), requester, key, (long) amount,
                NativeApi.enumValue("appeng.api.networking.crafting.CalculationStrategy", "REPORT_MISSING_ITEMS"));
        Ae2CraftJob job = new Ae2CraftJob(player, position, side, access.membership(), resourceId, amount, future);
        JOBS.put(job.id, job);
        return Ae2CraftingStatus.describe(player, job);
    }

    public static JsonObject status(ServerPlayer player, JsonObject body) {
        Ae2CraftJob job = owned(player, body);
        ServerAccess.check(player, job.position, false);
        return Ae2CraftingStatus.describe(player, job);
    }

    public static JsonObject start(ServerPlayer player, JsonObject body) {
        Ae2CraftJob job = owned(player, body);
        Ae2Access access = sameTerminal(player, job);
        Ae2CraftingStatus.refresh(job);
        if (job.submissionStarted) return Ae2CraftingStatus.describe(player, job);
        if (!job.status.equals("ready")) throw ServerAccess.denied("plan_not_ready", "Crafting plan is not executable: " + job.status);
        // Pin before calling AE2: any exception after this point can never trigger a second native submission.
        job.submissionStarted = true;
        job.status = "uncertain";
        Object submission = NativeApi.call(Ae2Keys.crafting(access), Ae2Keys.CRAFTING, "submitJob",
                job.plan, null, null, true, access.actionSource());
        String api = "appeng.api.networking.crafting.ICraftingSubmitResult";
        if (NativeApi.truth(NativeApi.call(submission, api, "successful"))) {
            job.link = NativeApi.call(submission, api, "link");
            job.status = job.link == null ? "uncertain" : "running";
        } else {
            job.status = "rejected";
            job.error = String.valueOf(NativeApi.call(submission, api, "errorCode"));
        }
        return Ae2CraftingStatus.describe(player, job);
    }

    public static JsonObject cancel(ServerPlayer player, JsonObject body) {
        Ae2CraftJob job = owned(player, body);
        sameTerminal(player, job);
        if (job.link != null) {
            NativeApi.call(job.link, LINK, "cancel");
            job.status = NativeApi.truth(NativeApi.call(job.link, LINK, "isCanceled")) ? "cancelled" : "uncertain";
        } else if (!job.submissionStarted) {
            job.calculation.cancel(true);
            job.status = "cancelled";
        }
        return Ae2CraftingStatus.describe(player, job);
    }

    private static Ae2CraftJob owned(ServerPlayer player, JsonObject body) {
        UUID id;
        try { id = UUID.fromString(ServerAccess.text(body, "job_id")); }
        catch (IllegalArgumentException invalid) { throw ServerAccess.denied("invalid_argument", "Invalid crafting job token"); }
        Ae2CraftJob job = JOBS.get(id);
        if (job == null || job.owner.get() != player) throw ServerAccess.denied("unknown_job", "Job is not owned by this connection");
        if (!job.dimension.equals(player.serverLevel().dimension().location().toString())) {
            throw ServerAccess.denied("dimension_changed", "Job belongs to another dimension");
        }
        return job;
    }

    private static Ae2Access sameTerminal(ServerPlayer player, Ae2CraftJob job) {
        Ae2Access access = Ae2Access.terminal(player, job.position, job.side, true);
        if (!job.membership.equals(access.membership())) throw ServerAccess.denied("network_changed", "The planned AE2 network changed");
        return access;
    }

    private static void expireFinished() {
        long now = System.nanoTime();
        for (Ae2CraftJob job : JOBS.values()) {
            try { Ae2CraftingStatus.refresh(job); } catch (RuntimeException ignored) { /* Keep uncertain native effects pinned. */ }
        }
        JOBS.values().removeIf(job -> {
            boolean old = now - job.createdNanos > java.util.concurrent.TimeUnit.HOURS.toNanos(1);
            if (!old) return false;
            if (!job.submissionStarted) { job.calculation.cancel(true); return true; }
            return job.status.equals("completed") || job.status.equals("cancelled") || job.status.equals("rejected");
        });
        if (JOBS.size() >= 128) {
            var iterator = JOBS.values().iterator();
            while (iterator.hasNext() && JOBS.size() >= 128) if (finished(iterator.next())) iterator.remove();
        }
    }

    private static boolean finished(Ae2CraftJob job) {
        return job.status.equals("completed") || job.status.equals("cancelled") || job.status.equals("rejected")
                || job.status.equals("failed") || job.status.equals("missing_materials");
    }
}
