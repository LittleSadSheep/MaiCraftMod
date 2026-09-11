// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.server;

import com.google.gson.JsonObject;
import static org.maiwithu.maicraft.client.server.ClientRequestReceipt.*;
import static org.maiwithu.maicraft.client.server.ServerRouterTestHarness.check;

public final class ClientRequestReconciliationTest {
    public static void main(String[] args) {
        lostPacketAndUnknownReceiptAreNeverReplayed();
        disconnectedMutationBlocksReconnectedFallback();
        throwingSenderRemainsUncertain();
        staleIdentitiesCannotSettleMutation();
        protocolSuccessPreservesBusinessOutcome();
        System.out.println("ClientRequestReconciliationTest: passed");
    }

    private static void lostPacketAndUnknownReceiptAreNeverReplayed() {
        var h = new ServerRouterTestHarness(true);
        h.welcome("test.write");
        var receipt = h.submit("test.write");
        h.advance(1);
        h.advance(22);
        h.router.receive(h.reply(receipt, "unknown", "unknown", "request_not_found"), 1);
        h.advance(150);
        h.advance(350);
        check(receipt.snapshot().status() == Status.UNKNOWN && h.count("request") == 1 && h.count("query") >= 2,
                "dropped requests and dropped replies reconcile by identity without resubmission");
        check(h.local.submissions == 0, "query not-found is not proof that a mutation cannot arrive later");
        h.router.receive(h.reply(receipt, "succeeded", "applied", ""), 1);
        h.router.receive(h.reply(receipt, "pending", "unknown", ""), 1);
        check(receipt.snapshot().status() == Status.SUCCEEDED, "late authoritative result resolves uncertainty monotonically");
    }

    private static void disconnectedMutationBlocksReconnectedFallback() {
        var h = new ServerRouterTestHarness(true);
        h.welcome("test.write");
        var receipt = h.submit("test.write");
        h.advance(1);
        JsonObject late = h.reply(receipt, "succeeded", "applied", "");
        h.router.disconnect();
        h.available = false;
        h.router.bind(2, 2, "minecraft:overworld", 2, true, 2);
        var next = h.submit("test.other");
        var read = h.submit("test.read");
        h.advance(3);
        check(next.snapshot().status() == Status.QUEUED && receipt.snapshot().retired(),
                "unknown old mutation fences new client mutations across reconnects");
        check(read.snapshot().status() == Status.SUCCEEDED && h.local.submissions == 1,
                "read-only observations remain available while mutation outcome is unresolved");
        h.router.receive(late, 2);
        check(receipt.snapshot().effect() == Effect.UNKNOWN, "a new connection cannot impersonate an old receipt");
        h.router.receive(late, 1);
        check(receipt.snapshot().effect() == Effect.APPLIED && receipt.snapshot().retired(),
                "an already-received old authoritative result remains historical and resolves its fence");
        h.advance(4);
        check(next.snapshot().status() == Status.SUCCEEDED && h.count("request") == 1,
                "fresh separately requested operations may proceed only after the old effect is known");
    }

    private static void throwingSenderRemainsUncertain() {
        var h = new ServerRouterTestHarness(true);
        h.welcome("test.write");
        h.throwAfterSend = true;
        var receipt = h.submit("test.write");
        h.advance(1);
        h.advance(200);
        check(receipt.snapshot().code().equals("transport_send_uncertain")
                && receipt.snapshot().effect() == Effect.UNKNOWN && h.count("request") == 1 && h.local.submissions == 0,
                "an exception after enqueueing a packet cannot assert no effect or invoke native fallback");
    }

    private static void staleIdentitiesCannotSettleMutation() {
        var h = new ServerRouterTestHarness(true);
        h.welcome("test.write");
        var receipt = h.submit("test.write");
        h.advance(1);
        for (String field : new String[]{"sessionId", "dimension", "operationId", "requestId"}) {
            var malformed = h.reply(receipt, "succeeded", "applied", "");
            malformed.addProperty(field, "another_identity");
            h.router.receive(malformed, 1);
        }
        check(receipt.snapshot().status() == Status.PENDING, "foreign request/session/world/operation receipts are ignored");
        h.router.bind(1, 2, "minecraft:the_nether", 2, true, 2);
        long requests = h.count("request");
        h.advance(30);
        check(h.count("request") == requests && h.last("query").get("dimension").getAsString().equals("minecraft:overworld"),
                "same-connection world replacement only queries the old dimension's request identity");
    }

    private static void protocolSuccessPreservesBusinessOutcome() {
        var h = new ServerRouterTestHarness(true);
        h.welcome("test.write");
        var receipt = h.submit("test.write");
        h.advance(1);
        var reply = h.reply(receipt, "succeeded", "applied", "");
        var result = new JsonObject();
        result.addProperty("status", "partial");
        result.addProperty("moved", 3);
        result.addProperty("requested", 8);
        result.addProperty("effect", "unknown");
        reply.add("result", result);
        h.router.receive(reply, 1);
        check(receipt.snapshot().status() == Status.SUCCEEDED && receipt.snapshot().unresolvedMutation()
                && receipt.snapshot().result().get("moved").getAsInt() == 3,
                "dispatch success neither upgrades partial business results nor clears explicit effect uncertainty");
        var frozen = receipt.snapshot().result();
        frozen.addProperty("moved", 8);
        check(receipt.snapshot().result().get("moved").getAsInt() == 3, "callers cannot mutate retained evidence");
    }
}
