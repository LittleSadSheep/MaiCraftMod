// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.ae2;

import com.google.gson.JsonObject;
import org.maiwithu.maicraft.client.server.ClientRequestReceipt;
import org.maiwithu.maicraft.client.server.ServerAssistClient;

/** Native CPU planning and submission are distinct one-shot requests; status never resubmits a job. */
final class Ae2ServerCraftJob {
    interface Port {
        ClientRequestReceipt submit(String operation, JsonObject arguments, boolean mutating);
        void cancel(java.util.UUID request);
        boolean mayCancel();
    }
    private static final Port NATIVE = new Port() {
        public ClientRequestReceipt submit(String operation, JsonObject arguments, boolean mutating) {
            return operation.equals("inventory.ae2_craft_cancel")
                    ? ServerAssistClient.submit(operation, arguments, mutating, null)
                    : ServerAssistClient.submit(operation, arguments, mutating);
        }
        public void cancel(java.util.UUID request) { ServerAssistClient.cancel(request); }
        public boolean mayCancel() { return ServerAssistClient.nativeFallbackAllowed("inventory.ae2_craft_cancel"); }
    };
    private final JsonObject target;
    private final String resource;
    private final String membership;
    private final int amount;
    private ClientRequestReceipt pending;
    private long pendingTick;
    private long nextStatusTick;
    private String jobId;
    private String status = "new";
    private boolean startRequested;
    private boolean started;
    private boolean planningRequested;
    private boolean cancelled;
    private final Port port;

    Ae2ServerCraftJob(JsonObject target, String resource, String membership, int amount) {
        this(target, resource, membership, amount, NATIVE);
    }

    Ae2ServerCraftJob(JsonObject target, String resource, String membership, int amount, Port port) {
        this.target = target.deepCopy();
        this.resource = resource;
        this.membership = membership;
        this.amount = Math.min(4096, amount);
        this.port = port;
    }

    static boolean available() {
        return ServerAssistClient.serverSupported("inventory.ae2_craft_plan")
                && ServerAssistClient.serverSupported("inventory.ae2_craft_start")
                && ServerAssistClient.serverSupported("inventory.ae2_craft_status")
                && ServerAssistClient.serverSupported("inventory.ae2_craft_cancel");
    }

    Ae2ServerSupply.Progress tick(long tick) {
        if (cancelled) return finish(startRequested ? Ae2ServerSupply.State.UNCERTAIN : Ae2ServerSupply.State.FAILED, "craft_job_cancelled");
        if (pending != null) {
            var receipt = pending.snapshot();
            if (!receipt.settled()) {
                if (tick - pendingTick > 200) return finish(Ae2ServerSupply.State.UNCERTAIN, "craft_request_timeout");
                return running();
            }
            if (receipt.effect() == ClientRequestReceipt.Effect.UNKNOWN || receipt.retired())
                return finish(Ae2ServerSupply.State.UNCERTAIN, "craft_receipt_unknown");
            if (receipt.status() != ClientRequestReceipt.Status.SUCCEEDED)
                return finish(Ae2ServerSupply.State.FAILED, receipt.code());
            JsonObject result = receipt.result();
            if (!validResult(result, jobId, resource, membership, amount))
                return finish(Ae2ServerSupply.State.UNCERTAIN, "craft_job_identity_changed");
            jobId = result.get("job_id").getAsString();
            status = result.get("status").getAsString();
            started |= result.has("native_job_id");
            pending = null;
            nextStatusTick = tick + 20;
        }
        if (status.equals("new")) {
            JsonObject body = target.deepCopy();
            body.addProperty("resource_id", resource);
            body.addProperty("amount", amount);
            planningRequested = true;
            submit("inventory.ae2_craft_plan", body, true, tick);
        } else if (status.equals("ready") && !startRequested) {
            startRequested = true;
            submit("inventory.ae2_craft_start", jobBody(), true, tick);
        } else if (status.equals("completed")) return finish(Ae2ServerSupply.State.SUCCEEDED, "native_crafting_completed");
        else if (status.equals("uncertain")) return finish(Ae2ServerSupply.State.UNCERTAIN, "native_crafting_uncertain");
        else if (status.equals("missing_materials") || status.equals("rejected") || status.equals("failed") || status.equals("cancelled"))
            return finish(Ae2ServerSupply.State.FAILED, "native_crafting_" + status);
        else if (pending == null && tick >= nextStatusTick)
            submit("inventory.ae2_craft_status", jobBody(), false, tick);
        return running();
    }

    static boolean validResult(JsonObject result, String job, String resource, String membership, int amount) {
        try {
            return result.get("schema").getAsString().equals("maicraft.ae2_craft_job.v1")
                    && !result.get("job_id").getAsString().isBlank()
                    && (job == null || job.equals(result.get("job_id").getAsString()))
                    && resource.equals(result.get("resource_id").getAsString())
                    && membership.equals(result.get("membership").getAsString())
                    && amount == result.get("amount").getAsBigDecimal().intValueExact()
                    && java.util.Set.of("planning", "ready", "missing_materials", "running", "completed",
                            "cancelled", "rejected", "failed", "uncertain").contains(result.get("status").getAsString());
        } catch (RuntimeException malformed) { return false; }
    }

    void cancel() {
        if (cancelled) return;
        cancelled = true;
        if (jobId == null && pending != null) {
            var receipt = pending.snapshot();
            if (receipt.status() == ClientRequestReceipt.Status.SUCCEEDED
                    && validResult(receipt.result(), null, resource, membership, amount)) {
                jobId = receipt.result().get("job_id").getAsString();
                status = receipt.result().get("status").getAsString();
            }
        }
        if (pending != null) port.cancel(pending.id());
        if (jobId != null && !status.equals("completed") && !status.equals("cancelled")
                && port.mayCancel()) {
            // Cleanup has no task owner: retiring the original task must not delete its queued cancellation.
            JsonObject body = jobBody();
            if (body.has("container_id")) {
                var minecraft = net.minecraft.client.Minecraft.getInstance();
                if (minecraft == null || minecraft.player == null
                        || minecraft.player.containerMenu.containerId != body.get("container_id").getAsInt()
                        || !org.maiwithu.maicraft.client.actor.MenuVisibility.matches(minecraft, minecraft.player.containerMenu))
                    body.remove("container_id");
            }
            port.submit("inventory.ae2_craft_cancel", body, true);
        }
    }

    boolean effectStarted() { return startRequested; }
    boolean started() { return started; }
    boolean planned() { return planningRequested; }
    String resource() { return resource; }
    int amount() { return amount; }
    private void submit(String operation, JsonObject body, boolean mutating, long tick) {
        pending = port.submit(operation, body, mutating);
        pendingTick = tick;
    }
    private JsonObject jobBody() { var body = target.deepCopy(); body.addProperty("job_id", jobId); return body; }
    private Ae2ServerSupply.Progress running() {
        return new Ae2ServerSupply.Progress(Ae2ServerSupply.State.RUNNING, "native_crafting_" + status, "waiting for native AE2 CPU job " + jobId);
    }
    private Ae2ServerSupply.Progress finish(Ae2ServerSupply.State state, String code) {
        return new Ae2ServerSupply.Progress(state, code, "AE2 job " + jobId + ": " + status + "; player delivery is verified separately");
    }
}
