// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.network;

import com.google.gson.JsonObject;
import static org.maiwithu.maicraft.network.ProtocolFixture.check;
import static org.maiwithu.maicraft.network.ProtocolFixture.field;

public final class ProtocolLifecycleTest {
    public static void main(String[] args) {
        negotiation();
        worldAndConnection();
        closeAndExpiry();
        receiptCapacity();
        rateAndCancellation();
        System.out.println("ProtocolLifecycleTest passed");
    }

    private static void negotiation() {
        var fixture = new ProtocolFixture();
        JsonObject hello = fixture.hello("next-scope");
        field(fixture.send(hello), "clientNonce", "next-scope");
        field(fixture.send(hello), "sessionId", fixture.send(hello).get("sessionId").getAsString());
        hello.addProperty("bootstrap", 99);
        field(fixture.send(hello), "kind", "welcome");
        field(fixture.send(hello), "code", "unsupported_bootstrap");
        hello = fixture.hello("unsupported-feature");
        hello.getAsJsonObject("features").addProperty("machine.configure", 0);
        check(!fixture.send(hello).getAsJsonObject("features").has("machine.configure"), "Do not advertise an incompatible version");
        fixture.disabled = true;
        fixture.welcome = fixture.send(fixture.hello("policy"));
        field(fixture.welcome.getAsJsonObject("features").getAsJsonObject("machine.configure"), "enabled", "false");
        field(fixture.send(fixture.request("denied")), "code", "authorization_denied");
    }

    private static void worldAndConnection() {
        var fixture = new ProtocolFixture();
        JsonObject original = fixture.send(fixture.request("applied"));
        fixture.dimension = "minecraft:the_nether";
        field(fixture.send(fixture.request("wrong-world")), "code", "dimension_changed");
        check(fixture.send(fixture.packet("query", "applied")).equals(original), "Old dimension receipts remain queryable");
        fixture.dispatcher.invalidateWorld();
        fixture.dimension = "minecraft:overworld";
        field(fixture.send(fixture.request("returned")), "code", "session_closed");
        var otherConnection = new ProtocolFixture();
        field(otherConnection.send(fixture.packet("query", "applied")), "effect", "unknown");
        field(otherConnection.send(fixture.request("other-peer")), "code", "session_expired");
        check(otherConnection.mutations == 0, "Another connection cannot reuse a session");
        check(fixture.mutations == 1, "World change must prevent further effects");
    }

    private static void closeAndExpiry() {
        var fixture = new ProtocolFixture();
        JsonObject original = fixture.send(fixture.request("first"));
        field(fixture.send(fixture.packet("close", null)), "allowed", "false");
        field(fixture.send(fixture.request("closed")), "code", "session_closed");
        check(fixture.send(fixture.packet("query", "first")).equals(original), "Close must retain receipts");
        fixture.tick += ProtocolSession.RETENTION_TICKS + 1;
        field(fixture.send(fixture.packet("query", "first")), "effect", "unknown");
        field(fixture.send(fixture.request("first")), "code", "session_expired");
        check(fixture.mutations == 1, "Expired sessions cannot execute a late packet");
    }

    private static void receiptCapacity() {
        var fixture = new ProtocolFixture();
        JsonObject original = fixture.send(fixture.request("original"));
        for (int i = 1; i < RequestLedger.MAX_REQUESTS; i++) {
            fixture.tick++;
            field(fixture.send(fixture.request("fill-" + i)), "status", "succeeded");
        }
        fixture.tick++;
        field(fixture.send(fixture.request("overflow")), "code", "receipt_capacity");
        check(fixture.send(fixture.request("original")).equals(original), "Capacity must never evict original identity");
        field(fixture.send(fixture.packet("cancel", "overflow")), "effect", "not_applied");
        field(fixture.send(fixture.request("overflow")), "effect", "not_applied");
        check(fixture.mutations == RequestLedger.MAX_REQUESTS, "Capacity rejection or cancellation executed a request");
    }

    private static void rateAndCancellation() {
        var fixture = new ProtocolFixture();
        fixture.tick++;
        JsonObject first = fixture.send(fixture.request("first"));
        for (int i = 1; i < ServerProtocolDispatcher.MAX_REQUESTS_PER_TICK; i++)
            field(fixture.send(fixture.request("fill-" + i)), "status", "succeeded");
        field(fixture.send(fixture.request("limited")), "code", "rate_limited");
        check(fixture.send(fixture.request("first")).equals(first), "Rate limiting must preserve an applied receipt");
        field(fixture.send(fixture.packet("cancel", "after-budget")), "code", "cancelled");
        fixture.tick++;
        field(fixture.send(fixture.request("limited")), "code", "rate_limited");
        field(fixture.send(fixture.request("after-budget")), "code", "cancelled");
        check(fixture.mutations == ServerProtocolDispatcher.MAX_REQUESTS_PER_TICK, "Budget must not permit delayed replay");
    }
}
