// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.runtime;

import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import org.maiwithu.maicraft.client.server.ClientRequestReceipt;
import org.maiwithu.maicraft.client.server.ServerAssistClient;

/** One operation in flight for a production task. A response never authorizes replaying an unknown effect. */
final class ProductionRequestSlot {
    private ClientRequestReceipt receipt;
    private JsonObject arguments;
    private String operation;
    private boolean mutating;
    private Map<String, Object> last = Map.of();

    JsonObject call(String operation, JsonObject arguments, boolean mutating) {
        if (receipt == null) {
            if (!ServerAssistClient.supported(operation))
                throw new IllegalArgumentException("production_capability_unavailable: " + operation);
            this.operation = operation; this.arguments = arguments.deepCopy(); this.mutating = mutating;
            receipt = ServerAssistClient.submit(operation, arguments, mutating);
            return null;
        }
        if (!this.operation.equals(operation) || !this.arguments.equals(arguments) || this.mutating != mutating)
            throw new IllegalStateException("A production request changed before its receipt was consumed");
        ClientRequestReceipt.Snapshot state = receipt.snapshot();
        capture(state);
        if (state.status() == ClientRequestReceipt.Status.QUEUED || state.status() == ClientRequestReceipt.Status.PENDING)
            return null;
        if (state.unresolvedMutation()) {
            ServerAssistClient.query(state.requestId());
            throw new IllegalStateException("production_effect_uncertain: inspect request " + state.requestId()
                    + " before any retry; " + state.message());
        }
        if (state.status() != ClientRequestReceipt.Status.SUCCEEDED) {
            throw new IllegalArgumentException("production_request_" + state.code() + ": " + state.message());
        }
        JsonObject result = state.result();
        result.addProperty("maicraft_request_id", state.requestId().toString());
        receipt = null; this.arguments = null; this.operation = null;
        return result;
    }

    boolean pending() { return receipt != null; }

    void cancel() {
        if (receipt != null) {
            ServerAssistClient.cancel(receipt.id());
            capture(receipt.snapshot());
            receipt = null;
        }
    }

    Map<String, Object> report() {
        if (receipt != null) capture(receipt.snapshot());
        return last;
    }

    private void capture(ClientRequestReceipt.Snapshot state) {
        var result = new LinkedHashMap<String, Object>();
        result.put("request_id", state.requestId().toString());
        result.put("operation", state.operationId());
        result.put("backend", state.backend().name().toLowerCase(java.util.Locale.ROOT));
        result.put("status", state.status().name().toLowerCase(java.util.Locale.ROOT));
        result.put("effect", state.effect().name().toLowerCase(java.util.Locale.ROOT));
        result.put("outcome_uncertain", state.unresolvedMutation());
        result.put("mechanical_retry_allowed", false);
        result.put("server_tick", state.serverTick());
        result.put("code", state.code()); result.put("message", state.message());
        result.put("result", state.result());
        last = Map.copyOf(result);
    }
}
