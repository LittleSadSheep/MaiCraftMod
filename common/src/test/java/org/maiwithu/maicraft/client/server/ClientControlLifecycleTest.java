// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.server;

import static org.maiwithu.maicraft.client.server.ClientRequestReceipt.*;
import static org.maiwithu.maicraft.client.server.ServerRouterTestHarness.check;

public final class ClientControlLifecycleTest {
    public static void main(String[] args) throws Exception {
        queuedCancellationNeverExecutes();
        sentCancellationReconcilesEffects();
        takeoverInvalidatesQueuedGeneration();
        lostControlAcknowledgementIsRetriedSafely();
        worldNonceRejectsOldWelcome();
        callbacksRunOnTheClientThread();
        dispatchBudgetSpansObservationAndTaskPhase();
        System.out.println("ClientControlLifecycleTest: passed");
    }

    private static void queuedCancellationNeverExecutes() {
        var h = new ServerRouterTestHarness(false);
        var queued = h.submit("test.write");
        h.router.cancel(queued.id());
        h.advance(1);
        check(queued.snapshot().status() == Status.CANCELLED && queued.snapshot().effect() == Effect.NOT_APPLIED
                && h.local.submissions == 0 && h.sent.isEmpty(), "cancelled queued native work never executes");
    }

    private static void sentCancellationReconcilesEffects() {
        var h = new ServerRouterTestHarness(true);
        h.welcome("test.write");
        var sent = h.submit("test.write");
        h.advance(1);
        h.router.cancel(sent.id());
        check(h.count("cancel") == 1 && sent.snapshot().retired() && sent.snapshot().effect() == Effect.UNKNOWN,
                "cancellation is a request to the server, not an assertion of rollback");
        h.router.receive(h.reply(sent, "succeeded", "applied", ""), 1);
        h.advance(2);
        check(sent.snapshot().status() == Status.SUCCEEDED && sent.snapshot().retired()
                && h.count("request") == 1 && h.local.submissions == 0,
                "already executed effects remain visible after their owner cancels");
    }

    private static void takeoverInvalidatesQueuedGeneration() {
        var h = new ServerRouterTestHarness(true);
        h.welcome("test.write");
        var queued = h.submit("test.write");
        h.router.control(2, false);
        h.advance(1);
        check(queued.snapshot().status() == Status.CANCELLED && h.count("request") == 0,
                "human takeover discards work created under an old control generation");
        h.router.control(3, true);
        var fresh = h.submit("test.write");
        h.advance(2);
        check(fresh.snapshot().status() == Status.QUEUED, "a new generation waits for the server's lease acknowledgement");
        h.acknowledgeControl();
        h.advance(3);
        check(h.count("request") == 1 && fresh.snapshot().status() == Status.PENDING,
                "only fresh acknowledged generation work is submitted");
    }

    private static void lostControlAcknowledgementIsRetriedSafely() {
        var h = new ServerRouterTestHarness(true);
        h.router.receive(h.welcomeEnvelope("test.write"), 1);
        long generation = h.last("control").get("controlGeneration").getAsLong();
        var queued = h.submit("test.write");
        h.advance(21);
        check(h.count("control") == 2 && h.last("control").get("controlGeneration").getAsLong() == generation
                && h.count("request") == 0, "only an idempotent control lease is retransmitted after a lost ack");
        h.acknowledgeControl();
        h.advance(22);
        check(queued.snapshot().status() == Status.PENDING, "recovered control acknowledgement enables the original unsent request");
    }

    private static void worldNonceRejectsOldWelcome() {
        var h = new ServerRouterTestHarness(true);
        var stale = h.welcomeEnvelope("test.write");
        h.router.bind(1, 2, "minecraft:overworld", 2, true, 1);
        h.router.receive(stale, 1);
        check(h.router.capabilityReport().get("state").getAsString().equals("negotiating")
                && h.count("control") == 0, "a stale same-dimension welcome cannot bind a replacement player/world");
    }

    private static void callbacksRunOnTheClientThread() throws Exception {
        var h = new ServerRouterTestHarness(false);
        h.local.async = true;
        var receipt = h.submit("test.write");
        int[] updates = {0};
        receipt.onUpdate(snapshot -> {
            check(Thread.currentThread() == h.client, "receipt observer escaped the client thread");
            updates[0]++;
        });
        h.advance(1);
        h.router.cancel(receipt.id());
        int before = updates[0];
        Thread network = new Thread(() -> h.local.completion.accept(
                new Result(Status.SUCCEEDED, Effect.APPLIED, null, "", "late native confirmation")));
        network.start();
        network.join();
        check(updates[0] == before && receipt.snapshot().effect() == Effect.UNKNOWN,
                "background completion only enqueues evidence without touching client state");
        h.drain();
        check(receipt.snapshot().retired() && receipt.snapshot().effect() == Effect.APPLIED
                && h.local.cancellations == 1 && updates[0] > before,
                "retired asynchronous native work still reconciles on the client thread");
    }

    private static void dispatchBudgetSpansObservationAndTaskPhase() {
        var h = new ServerRouterTestHarness(true);
        h.welcome("test.read");
        for (int i = 0; i < 12; i++) h.submit("test.read");
        h.router.observe(1);
        h.router.dispatch(false);
        h.router.dispatch(true);
        check(h.count("request") == 8, "read and task dispatch share one advertised per-tick request limit");
        h.advance(2);
        check(h.count("request") == 12, "remaining unsent requests wait for a later tick");
    }
}
