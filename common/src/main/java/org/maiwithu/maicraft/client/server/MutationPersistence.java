// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.server;

import com.google.gson.JsonObject;

/** Write-ahead safety fence. Persistence failures must prevent new mutation dispatch. */
public interface MutationPersistence {
    void beforeSubmission(ClientRequestReceipt receipt);
    void afterObservation(ClientRequestReceipt receipt);
    boolean unresolved();
    JsonObject report();

    MutationPersistence NONE = new MutationPersistence() {
        public void beforeSubmission(ClientRequestReceipt receipt) {}
        public void afterObservation(ClientRequestReceipt receipt) {}
        public boolean unresolved() { return false; }
        public JsonObject report() { return new JsonObject(); }
    };
}
