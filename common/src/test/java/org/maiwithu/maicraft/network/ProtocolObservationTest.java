// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.network;

import com.google.gson.JsonObject;
import static org.maiwithu.maicraft.network.ProtocolFixture.check;
import static org.maiwithu.maicraft.network.ProtocolFixture.field;

public final class ProtocolObservationTest {
    public static void main(String[] args) {
        var fixture = new ProtocolFixture();
        JsonObject mutation = fixture.send(fixture.request("retained-mutation"));
        JsonObject mutationQuery = fixture.packet("query", "retained-mutation");
        JsonObject oldReadRequest = null;
        int scopes = 1;
        for (int sample = 0; sample < 6000; sample++) {
            fixture.tick++;
            if (sample % 500 == 0) {
                fixture.send(fixture.packet("close", null));
                fixture.welcome = fixture.send(fixture.hello("observation-scope-" + sample));
                field(fixture.welcome, "status", "succeeded");
                scopes++;
            }
            JsonObject request = fixture.request("observation-" + sample);
            request.addProperty("operationId", "machine.snapshot");
            if (oldReadRequest == null) oldReadRequest = request.deepCopy();
            JsonObject receipt = fixture.send(request);
            field(receipt, "status", "succeeded");
            field(receipt, "effect", "not_applied");
            check(receipt.get("remainingRequests").getAsInt() >= 0, "Receipt budget must be nonnegative");
            // Keep a real mutation receipt alive while recycling many read-only scopes.
            if (sample % 100 == 0) check(fixture.send(mutationQuery).equals(mutation), "Mutation receipt was retired under read pressure");
        }
        check(scopes > ServerProtocolDispatcher.MAX_SESSIONS, "Long-run case must exceed the retained scope bound");
        field(fixture.send(oldReadRequest), "code", "session_expired");
        check(fixture.send(mutationQuery).equals(mutation), "Mutation receipt did not survive long-run reads");
        check(fixture.mutations == 1, "Observation lifecycle replayed a mutation");
        readExpiryDoesNotReplay();
        System.out.println("ProtocolObservationTest passed (6000 observations across 13 scopes)");
    }

    private static void readExpiryDoesNotReplay() {
        var fixture = new ProtocolFixture();
        JsonObject first = null;
        for (int i = 0; i <= RequestLedger.RECENT_READ_RESULTS; i++) {
            fixture.tick++;
            JsonObject request = fixture.request("read-" + i);
            request.addProperty("operationId", "machine.snapshot");
            if (first == null) first = request.deepCopy();
            field(fixture.send(request), "status", "succeeded");
        }
        field(fixture.send(first), "code", "receipt_unavailable");
        field(fixture.send(fixture.packet("query", "read-0")).getAsJsonObject("result"), "complete", "false");
    }
}
