// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.runtime;

import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.maiwithu.maicraft.client.server.ClientOperation;
import org.maiwithu.maicraft.client.server.ClientRequestReceipt;
import org.maiwithu.maicraft.client.server.ClientRequestRouter;
import org.maiwithu.maicraft.network.ServerFeature;
import org.maiwithu.maicraft.network.ServerProtocolDispatcher;

/** Real client/server protocol with delayed welcome delivery; no Minecraft world or fabricated receipt states. */
public final class ProductionSessionRolloverTest {
    private static final String READ = "machine.snapshot", WRITE = "machine.configure";
    private ProductionSessionRolloverTest() {}

    public static void main(String[] args) {
        renewsAfter504RequestsWithoutFailingProduction();
        renewalDoesNotHideLifecycleOrCapabilityChanges();
        renewalRequiresUnchangedControlOwnership();
        uncertainMutationKeepsItsOriginalIdentity();
        System.out.println("ProductionSessionRolloverTest: delayed 504-request renewal, lifecycle rejection and unresolved mutation fencing passed");
    }

    private static void renewsAfter504RequestsWithoutFailingProduction() {
        var fixture = new Fixture();
        check(!fixture.router.renegotiating(READ), "Initial negotiation borrowed previous support");
        rejects(() -> fixture.slot.call(READ, new JsonObject(), false), "production_capability_unavailable");
        fixture.welcome(); fixture.fill(504); fixture.renew();
        check(!fixture.router.supported(READ) && fixture.router.renegotiating(READ), "Renewal must be pending, not ready");
        for (int wait = 0; wait < 5; wait++) {
            check(fixture.slot.call(READ, new JsonObject(), false) == null && !fixture.slot.pending(),
                    "Production failed or submitted a read before its new welcome");
            check(fixture.slot.call(WRITE, new JsonObject(), true) == null && !fixture.slot.pending(),
                    "Renewal fabricated a mutation receipt");
            fixture.advance(false);
        }
        rejects(() -> fixture.slot.call("missing.operation", new JsonObject(), false), "production_capability_unavailable");
        check(fixture.requests.size() == 504 && fixture.writes == 0, "Waiting dispatched unnegotiated work");
        fixture.welcome(); fixture.read();
        check(fixture.reads == 505 && fixture.hellos == 2 && !fixture.router.renegotiating(READ),
                "Fresh scope did not resume exactly one independent observation");
    }

    private static void renewalDoesNotHideLifecycleOrCapabilityChanges() {
        for (String change : List.of("disconnect", "binding", "channel", "unsupported", "timeout")) {
            var fixture = new Fixture(); fixture.welcome(); fixture.fill(504);
            if (change.equals("unsupported")) fixture.advertiseRead = false;
            fixture.renew();
            switch (change) {
                case "disconnect" -> fixture.router.disconnect();
                case "binding" -> fixture.router.bind(1, 2, "minecraft:overworld", 1, true, ++fixture.tick);
                case "channel" -> { fixture.available = false; fixture.router.observe(++fixture.tick); }
                case "unsupported" -> fixture.welcome();
                case "timeout" -> { fixture.tick += 100; fixture.router.observe(fixture.tick); }
                default -> throw new AssertionError(change);
            }
            check(!fixture.router.renegotiating(READ), "Stale renewal support survived " + change);
            rejects(() -> fixture.slot.call(READ, new JsonObject(), false), "production_capability_unavailable");
            check(fixture.requests.size() == 504, "Rejected lifecycle change submitted another observation");
        }
    }

    private static void uncertainMutationKeepsItsOriginalIdentity() {
        var fixture = new Fixture(); fixture.welcome(); fixture.fill(503);
        check(fixture.slot.call(WRITE, new JsonObject(), true) == null, "Mutation did not queue");
        fixture.advance(false); // The server performs it once; deliberately withhold its authoritative receipt.
        fixture.tick += 101; fixture.router.observe(fixture.tick);
        check(fixture.writes == 1 && fixture.hellos == 1 && !fixture.router.renegotiating(WRITE),
                "Uncertain mutation permitted scope rotation");
        rejects(() -> fixture.slot.call(WRITE, new JsonObject(), true), "production_effect_uncertain");
        check(fixture.requests.size() == 504 && fixture.slot.pending(), "Uncertain submission lost its original slot");
        fixture.welcome(); // This drains the original receipt and its read-only reconciliation, not a replay.
        check(fixture.slot.call(WRITE, new JsonObject(), true) != null && fixture.writes == 1,
                "Original receipt failed to settle or mutation was replayed");
    }

    private static void renewalRequiresUnchangedControlOwnership() {
        for (boolean allowed : new boolean[]{false, true}) {
            var fixture = new Fixture(); fixture.welcome(); fixture.fill(504); fixture.renew();
            fixture.router.control(allowed ? 2 : 1, allowed);
            check(!fixture.router.renegotiating(READ) && !fixture.router.renegotiating(WRITE),
                    "Renewal support survived control revocation or an authority generation change");
            rejects(() -> fixture.slot.call(READ, new JsonObject(), false), "production_capability_unavailable");
            fixture.welcome(); fixture.read();
            check(fixture.router.supported(READ) && !fixture.router.renegotiating(READ),
                    "Current welcome did not independently restore advertised read support");
            check(fixture.slot.call(WRITE, new JsonObject(), true) == null, "Current mutation did not queue");
            fixture.advance(true);
            if (allowed) {
                check(fixture.slot.call(WRITE, new JsonObject(), true) != null && fixture.writes == 1,
                        "A newly acknowledged authority could not submit its own mutation");
            } else {
                rejects(() -> fixture.slot.call(WRITE, new JsonObject(), true), "production_request_control_unavailable");
                check(fixture.writes == 0, "Revoked control dispatched a mutation after welcome");
            }
        }
    }

    static final class Fixture implements ProductionRequestSlot.Backend {
        final ArrayDeque<JsonObject> replies = new ArrayDeque<>();
        final Set<String> requests = new HashSet<>();
        final ServerProtocolDispatcher server = new ServerProtocolDispatcher();
        final ClientRequestRouter router;
        final ProductionRequestSlot slot;
        boolean available = true, advertiseRead = true;
        long tick, serverOffset;
        int reads, writes, hellos;

        Fixture() {
            ServerProtocolDispatcher.Peer peer = new ServerProtocolDispatcher.Peer() {
                public String dimension() { return "minecraft:overworld"; }
                public long tick() { return tick + serverOffset; }
                public boolean mayMutate() { return true; }
                public List<ServerFeature> features() {
                    var write = new ServerFeature(WRITE, 1, true, true, new JsonObject());
                    return advertiseRead ? List.of(new ServerFeature(READ, 1, false, true, new JsonObject()), write) : List.of(write);
                }
                public JsonObject execute(String operation, JsonObject body) {
                    if (operation.equals(READ)) reads++; else writes++;
                    JsonObject result = new JsonObject(); result.addProperty("complete", true); return result;
                }
            };
            router = new ClientRequestRouter(() -> available, envelope -> {
                String kind = envelope.get("kind").getAsString();
                if (kind.equals("hello")) hellos++;
                if (kind.equals("request")) check(requests.add(envelope.get("requestId").getAsString()), "Request identity replayed");
                replies.add(server.receive(envelope, peer)); return true;
            }, () -> {}, Runnable::run, (receipt, send) -> { send.run(); return true; });
            router.register(new ClientOperation(READ, 1, false, null));
            router.register(new ClientOperation(WRITE, 1, true, null));
            router.bind(1, 1, peer.dimension(), 1, true, tick);
            slot = new ProductionRequestSlot(this);
        }
        void fill(int count) { for (int i = 0; i < count; i++) read(); }
        void read() {
            check(slot.call(READ, new JsonObject(), false) == null, "Read did not queue");
            advance(true);
            check(slot.call(READ, new JsonObject(), false) != null, "Original native read did not settle");
        }
        void renew() {
            router.observe(++tick);
            check(router.renegotiating(READ) && hellos == 2, "504 observations did not initiate bounded scope renewal");
        }
        void advance(boolean deliver) {
            router.observe(++tick); router.dispatch(true); if (deliver) welcome();
        }
        void welcome() {
            for (int i = 0; i < 20 && !replies.isEmpty(); i++) router.receive(replies.remove(), 1);
            check(replies.isEmpty(), "Protocol replies exceeded the bounded drain");
        }
        public boolean supported(String operation) { return router.supported(operation); }
        public boolean renegotiating(String operation) { return router.renegotiating(operation); }
        public boolean takeExpiredReadForRefresh(UUID id) { return router.takeExpiredReadForRefresh(id); }
        public ClientRequestReceipt submit(String operation, JsonObject arguments, boolean mutating) { return router.submit(operation, arguments, mutating); }
        public void query(UUID id) { router.query(id); }
        public void cancel(UUID id) { router.cancel(id); }
    }

    private static void rejects(Runnable action, String code) {
        try { action.run(); throw new AssertionError("Expected " + code); }
        catch (IllegalArgumentException | IllegalStateException rejected) {
            check(rejected.getMessage().contains(code), "Unexpected rejection: " + rejected.getMessage());
        }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
