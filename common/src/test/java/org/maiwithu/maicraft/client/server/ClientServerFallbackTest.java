// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.server;

import com.google.gson.JsonObject;
import static org.maiwithu.maicraft.client.server.ClientRequestReceipt.*;
import static org.maiwithu.maicraft.client.server.ServerRouterTestHarness.check;

public final class ClientServerFallbackTest {
    public static void main(String[] args) {
        noServerUsesNativeClient();
        partialSupportSelectsPerOperation();
        registrationAndConditionsDiffer();
        policyRejectionNeverFallsBack();
        explicitUnsupportedMayFallBackOnce();
        lostHandshakeDoesNotSendMutations();
        System.out.println("ClientServerFallbackTest: passed");
    }

    private static void noServerUsesNativeClient() {
        var h = new ServerRouterTestHarness(false);
        var receipt = h.submit("test.write");
        h.advance(1);
        check(h.sent.isEmpty(), "a server without the optional channel receives no custom payloads");
        check(receipt.snapshot().backend() == Backend.CLIENT && receipt.snapshot().status() == Status.SUCCEEDED,
                "a supported equivalent native backend works without server installation");
        check(h.local.submissions == 1, "native operation submitted exactly once");
    }

    private static void partialSupportSelectsPerOperation() {
        var h = new ServerRouterTestHarness(true);
        h.welcome("test.write");
        var remote = h.submit("test.write");
        h.advance(1);
        h.router.receive(h.reply(remote, "succeeded", "applied", ""), 1);
        var local = h.submit("test.other");
        h.advance(2);
        check(remote.snapshot().backend() == Backend.SERVER && local.snapshot().backend() == Backend.CLIENT,
                "server support for one operation does not override another operation's client backend");
        check(h.count("request") == 1 && h.local.submissions == 1, "one submission per selected backend");
    }

    private static void registrationAndConditionsDiffer() {
        var h = new ServerRouterTestHarness(false);
        h.local.supported = false;
        check(!h.router.supported("test.write"), "registered but absent native APIs are unsupported");
        h.local.supported = true;
        h.local.ready = false;
        JsonObject local = h.router.capabilityReport().getAsJsonObject("operations").getAsJsonObject("test.write");
        check(local.get("registered").getAsBoolean() && local.get("supported").getAsBoolean()
                && !local.get("available").getAsBoolean(), "current reach conditions differ from support");
        var rejected = h.submit("test.write");
        h.advance(1);
        check(rejected.snapshot().code().equals("terminal_out_of_reach") && h.local.submissions == 0,
                "unmet native conditions never dispatch an operation");
        var server = new ServerRouterTestHarness(true);
        server.welcome("test.write");
        check(server.router.capabilityReport().getAsJsonObject("operations").getAsJsonObject("test.write")
                .get("available").isJsonNull(), "server registration cannot assert unknown execution preconditions");
    }

    private static void policyRejectionNeverFallsBack() {
        var h = new ServerRouterTestHarness(true);
        var welcome = h.welcomeEnvelope("test.write");
        welcome.getAsJsonObject("features").getAsJsonObject("test.write").addProperty("enabled", false);
        h.router.receive(welcome, 1);
        h.acknowledgeControl();
        var disabled = h.submit("test.write");
        h.advance(1);
        check(disabled.snapshot().code().equals("server_policy_denied") && h.count("request") == 0
                && h.local.submissions == 0, "disabled server policy cannot be bypassed through a local backend");
        var permitted = new ServerRouterTestHarness(true);
        permitted.welcome("test.write");
        var denied = permitted.submit("test.write");
        permitted.advance(1);
        permitted.router.receive(permitted.reply(denied, "rejected", "not_applied", "permission_denied"), 1);
        permitted.advance(2);
        check(denied.snapshot().status() == Status.REJECTED && permitted.local.submissions == 0,
                "explicit no-effect permission rejection never permits fallback");
    }

    private static void explicitUnsupportedMayFallBackOnce() {
        var h = new ServerRouterTestHarness(true);
        h.welcome("test.write");
        var receipt = h.submit("test.write");
        h.advance(1);
        var rejected = h.reply(receipt, "rejected", "not_applied", "unsupported_operation");
        h.router.receive(rejected, 1);
        h.advance(2);
        h.router.receive(rejected, 1);
        h.advance(3);
        check(h.count("request") == 1 && h.local.submissions == 1 && receipt.snapshot().backend() == Backend.CLIENT,
                "definite unsupported result can route to an equivalent backend once; duplicates cannot replay it");
    }

    private static void lostHandshakeDoesNotSendMutations() {
        var h = new ServerRouterTestHarness(true);
        var receipt = h.submit("test.write");
        h.advance(1);
        check(receipt.snapshot().status() == Status.QUEUED && h.count("request") == 0,
                "no mutation precedes version and capability negotiation");
        h.advance(101);
        check(h.count("hello") == 1 && h.count("request") == 0 && h.local.submissions == 1,
                "a lost welcome permits only a never-submitted equivalent native operation");
        h.router.receive(h.welcomeEnvelope("test.write"), 1);
        check(receipt.snapshot().backend() == Backend.CLIENT && h.count("request") == 0,
                "a late welcome cannot reselect a backend for an already executed operation");
    }
}
