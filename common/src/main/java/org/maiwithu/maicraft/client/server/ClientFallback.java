// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.server;

import com.google.gson.JsonObject;
import java.util.UUID;
import java.util.function.Consumer;

/** An explicitly equivalent native client implementation of one operation/version. */
public interface ClientFallback {
    /** Registration alone is insufficient: the required client mod/API must actually exist. */
    boolean supported();

    /** Fresh execution conditions, such as a visible terminal, range or current materials. */
    Availability availability(JsonObject arguments);

    /** Called once on the client thread; completion may arrive on any thread. */
    void submit(UUID requestId, JsonObject arguments, Consumer<ClientRequestReceipt.Result> completion);

    /** Retire queued native work; already submitted effects must still report their actual result. */
    default void cancel(UUID requestId) {}

    record Availability(boolean available, String reason) {
        public Availability { reason = reason == null ? "" : reason; }
        public static Availability ready() { return new Availability(true, ""); }
        public static Availability unavailable(String reason) { return new Availability(false, reason); }
    }
}
