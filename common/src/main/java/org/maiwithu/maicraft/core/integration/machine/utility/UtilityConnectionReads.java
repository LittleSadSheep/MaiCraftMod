// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.utility;

import com.google.gson.JsonObject;
import org.maiwithu.maicraft.client.server.ClientRequestReceipt;
import org.maiwithu.maicraft.client.server.ServerAssistClient;
import org.maiwithu.maicraft.client.server.ServerSessionRuntime;

/** Read-only requests remain immutable until their response is consumed. */
final class UtilityConnectionReads {
    private ClientRequestReceipt receipt;
    private String operation;
    private JsonObject body;
    private boolean renewed;
    JsonObject call(String operation, JsonObject body) {
        if (receipt == null) {
            if (!ServerAssistClient.serverSupported(operation)) {
                if (renewing(operation)) return null;
                throw new IllegalArgumentException("utility_server_capability_required: " + operation);
            }
            this.operation = operation; this.body = body.deepCopy();
            receipt = ServerAssistClient.submit(operation, body, false);
            return null;
        }
        if (!this.operation.equals(operation) || !this.body.equals(body))
            throw new IllegalStateException("utility_read_changed_while_pending");
        var result = receipt.snapshot();
        if (result.status() == ClientRequestReceipt.Status.QUEUED || result.status() == ClientRequestReceipt.Status.PENDING) return null;
        if (!renewed && result.code().equals("session_expired") && ServerAssistClient.takeExpiredReadForRefresh(result.requestId())) {
            renewed = true; receipt = null; return null;
        }
        if (result.status() != ClientRequestReceipt.Status.SUCCEEDED)
            throw new IllegalArgumentException("utility_read_" + result.code() + ": " + result.message());
        receipt = null; renewed = false;
        return result.result();
    }
    static boolean renewing(String operation) {
        return ServerSessionRuntime.installed() && ServerAssistClient.renegotiating(operation);
    }
    static boolean requireServer(String operation, String failureCode, Runnable pause) {
        if (ServerAssistClient.serverSupported(operation)) return true;
        if (!renewing(operation)) throw new IllegalArgumentException(failureCode);
        pause.run();
        return false;
    }
    boolean pending() { return receipt != null; }
    void cancel() { if (receipt != null) ServerAssistClient.cancel(receipt.id()); receipt = null; }
}
