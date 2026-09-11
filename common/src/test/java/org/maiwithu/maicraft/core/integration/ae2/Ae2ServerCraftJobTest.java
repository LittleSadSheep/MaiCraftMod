// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.ae2;

import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.List;
import org.maiwithu.maicraft.client.server.ClientOperation;
import org.maiwithu.maicraft.client.server.ClientRequestReceipt;
import org.maiwithu.maicraft.client.server.ClientRequestRouter;
import org.maiwithu.maicraft.network.ServerFeature;
import org.maiwithu.maicraft.network.ServerProtocolDispatcher;

/** Native job lifecycle contract: planning, one start, status polling, and uncertainty/cancellation. */
public final class Ae2ServerCraftJobTest {
    public static void main(String[] args) {
        var success = new Harness(false);
        var result = success.run(180);
        check(result.state() == Ae2ServerSupply.State.SUCCEEDED && success.plans == 1 && success.starts == 1
                && success.statusReads >= 2, "repeated ready status never resubmits native CPU work");
        var unknown = new Harness(true);
        check(unknown.run(180).state() == Ae2ServerSupply.State.UNCERTAIN && unknown.starts == 1,
                "unknown native submission stays pinned and is never retried through status or another backend");
        var cancelled = new Harness(false);
        cancelled.step(0);
        cancelled.step(1);
        cancelled.job.cancel();
        cancelled.run(80);
        check(cancelled.starts == 0 && cancelled.cancels == 1, "cancellation before approval prevents a queued job start");
        System.out.println("Ae2ServerCraftJobTest: passed");
    }

    private static final class Harness implements Ae2ServerCraftJob.Port, ServerProtocolDispatcher.Peer {
        final ArrayDeque<JsonObject> replies = new ArrayDeque<>();
        final ArrayDeque<Runnable> callbacks = new ArrayDeque<>();
        final ServerProtocolDispatcher server = new ServerProtocolDispatcher();
        final ClientRequestRouter router;
        final Ae2ServerCraftJob job;
        final boolean uncertain;
        long tick;
        int plans, starts, statusReads, cancels;

        Harness(boolean uncertain) {
            this.uncertain = uncertain;
            router = new ClientRequestRouter(() -> true, envelope -> {
                replies.add(server.receive(envelope, this));
                return true;
            }, () -> {}, callbacks::add, (receipt, send) -> { send.run(); return true; });
            for (var feature : features()) router.register(new ClientOperation(feature.operationId(), 1, feature.mutating(), null));
            router.bind(1, 1, dimension(), 1, true, 0);
            pump();
            job = new Ae2ServerCraftJob(new JsonObject(), "opaque-resource", "native-network", 8, this);
        }

        Ae2ServerSupply.Progress run(int ticks) {
            Ae2ServerSupply.Progress result = null;
            long from = tick;
            for (long next = from; next <= from + ticks; next++) {
                result = step(next);
                if (result.state() == Ae2ServerSupply.State.SUCCEEDED) break;
            }
            return result;
        }

        Ae2ServerSupply.Progress step(long tick) {
            this.tick = tick;
            var result = job.tick(tick);
            router.observe(tick);
            router.dispatch(true);
            pump();
            return result;
        }

        void pump() {
            for (int budget = 0; budget < 30 && !replies.isEmpty(); budget++) router.receive(replies.remove(), 1);
            while (!callbacks.isEmpty()) callbacks.remove().run();
        }
        public ClientRequestReceipt submit(String operation, JsonObject arguments, boolean mutating) {
            return router.submit(operation, arguments, mutating);
        }
        public void cancel(java.util.UUID request) { router.cancel(request); }
        public boolean mayCancel() { return true; }
        public String dimension() { return "minecraft:overworld"; }
        public long tick() { return tick; }
        public boolean mayMutate() { return true; }
        public List<ServerFeature> features() {
            return List.of(feature("plan", true), feature("start", true), feature("status", false), feature("cancel", true));
        }
        private ServerFeature feature(String suffix, boolean mutating) {
            return new ServerFeature("inventory.ae2_craft_" + suffix, 1, mutating, true, new JsonObject());
        }
        public JsonObject execute(String operation, JsonObject body) {
            String state;
            if (operation.endsWith("_plan")) { plans++; state = "planning"; }
            else if (operation.endsWith("_start")) { starts++; state = uncertain ? "uncertain" : "running"; }
            else if (operation.endsWith("_cancel")) { cancels++; state = "cancelled"; }
            else { statusReads++; state = statusReads <= 2 ? "ready" : "completed"; }
            var result = new JsonObject();
            result.addProperty("schema", "maicraft.ae2_craft_job.v1");
            result.addProperty("job_id", "owned-native-job");
            result.addProperty("resource_id", "opaque-resource");
            result.addProperty("membership", "native-network");
            result.addProperty("amount", 8);
            result.addProperty("status", state);
            if (starts > 0 && !uncertain) result.addProperty("native_job_id", "native-cpu-link");
            return result;
        }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
