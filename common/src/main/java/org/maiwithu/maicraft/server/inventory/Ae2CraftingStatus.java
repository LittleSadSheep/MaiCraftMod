// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.inventory;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import net.minecraft.server.level.ServerPlayer;
import org.maiwithu.maicraft.server.machine.NativeApi;
import org.maiwithu.maicraft.server.machine.SnapshotBudget;

final class Ae2CraftingStatus {
    private Ae2CraftingStatus() {}

    static void refresh(Ae2CraftJob job) {
        if (job.status.equals("cancelled")) return;
        if (job.link != null) {
            Ae2NativeCraftingCompletion.State lifecycle = Ae2NativeCraftingCompletion.state(job.link);
            if (lifecycle == Ae2NativeCraftingCompletion.State.COMPLETED) {
                job.status = "completed"; job.error = null; job.completionProvenance = "native_CraftingLink.markDone";
            } else if (lifecycle == Ae2NativeCraftingCompletion.State.CANCELLED) {
                job.status = "cancelled"; job.error = null; job.completionProvenance = "native_CraftingLink.cancel";
            } else if (NativeApi.truth(NativeApi.call(job.link, Ae2Crafting.LINK, "isCanceled"))) {
                job.status = "cancelled"; job.error = null; job.completionProvenance = "native_link_cancelled";
            } else if (NativeApi.truth(NativeApi.call(job.link, Ae2Crafting.LINK, "isDone"))) {
                job.status = "completed"; job.error = null; job.completionProvenance = "native_link_done";
            } else if (job.cpuLogic != null) {
                Object current = NativeApi.call(job.cpuLogic, "appeng.crafting.execution.CraftingCpuLogic", "getLastLink");
                Object expected = NativeApi.call(job.link, Ae2Crafting.LINK, "getCraftingID");
                if (current == null || !expected.equals(NativeApi.call(current, Ae2Crafting.LINK, "getCraftingID"))) {
                    job.status = "uncertain"; job.error = "native_finish_not_observed";
                }
            }
            return;
        }
        if (job.submissionStarted || job.plan != null || !job.calculation.isDone()) return;
        try {
            job.plan = job.calculation.get(); // Non-blocking: done was established above, on the server thread.
            if (job.plan == null) { job.status = "failed"; job.error = "empty_plan"; return; }
            job.status = NativeApi.truth(NativeApi.call(job.plan, Ae2Crafting.PLAN, "simulation")) ? "missing_materials" : "ready";
        } catch (CancellationException cancelled) { job.status = "cancelled"; }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); job.status = "failed"; job.error = "interrupted"; }
        catch (ExecutionException failed) { job.status = "failed"; job.error = "calculation_failed"; }
    }

    static JsonObject describe(ServerPlayer player, Ae2CraftJob job) {
        refresh(job);
        JsonObject result = new JsonObject();
        result.addProperty("schema", "maicraft.ae2_craft_job.v1");
        result.addProperty("job_id", job.id.toString());
        result.addProperty("status", job.status);
        result.addProperty("resource_id", job.resourceId);
        result.addProperty("amount", job.amount);
        result.addProperty("membership", job.membership);
        result.addProperty("dimension", job.dimension);
        result.addProperty("tick", player.serverLevel().getGameTime());
        result.addProperty("submission_started", job.submissionStarted);
        result.addProperty("completion_provenance", job.completionProvenance);
        result.addProperty("error", job.error);
        result.addProperty("output_destination", "native_ae2_network_inventory");
        result.addProperty("player_delivery_verified", false);
        if (job.link != null) result.addProperty("native_job_id", NativeApi.call(job.link, Ae2Crafting.LINK, "getCraftingID").toString());
        if (job.plan != null) {
            result.addProperty("bytes", NativeApi.number(NativeApi.call(job.plan, Ae2Crafting.PLAN, "bytes")));
            result.addProperty("simulation", NativeApi.truth(NativeApi.call(job.plan, Ae2Crafting.PLAN, "simulation")));
            result.addProperty("multiple_paths", NativeApi.truth(NativeApi.call(job.plan, Ae2Crafting.PLAN, "multiplePaths")));
            SnapshotBudget budget = new SnapshotBudget(0, 96);
            for (String field : new String[]{"usedItems", "missingItems", "emittedItems"}) {
                JsonArray resources = new JsonArray();
                result.add(field, resources);
                int inspected = 0;
                for (Object raw : (Iterable<?>) NativeApi.call(job.plan, Ae2Crafting.PLAN, field)) {
                    if (++inspected > 256) { budget.truncate(); break; }
                    Map.Entry<?, ?> entry = (Map.Entry<?, ?>) raw;
                    Object key = entry.getKey();
                    if (!NativeApi.is(key, Ae2Access.ITEM)) {
                        JsonObject unknown = new JsonObject();
                        unknown.addProperty("resource_id", "unknown");
                        unknown.addProperty("native_id", NativeApi.call(key, "appeng.api.stacks.AEKey", "getId").toString());
                        unknown.addProperty("amount", ((Number) entry.getValue()).longValue());
                        unknown.addProperty("identity_complete", false);
                        budget.add(resources, unknown);
                    } else budget.add(resources, ResourceIdentity.resource(Ae2Keys.identity(player, key),
                            ((Number) entry.getValue()).longValue(), null, "items", "craft_plan:" + job.id, null, job.membership));
                }
            }
            result.addProperty("resources_truncated", budget.truncated());
        }
        return result;
    }
}
