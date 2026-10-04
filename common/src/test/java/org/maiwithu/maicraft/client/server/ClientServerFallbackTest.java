// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.server;

import com.google.gson.JsonObject;
import static org.maiwithu.maicraft.client.server.ClientRequestReceipt.*;
import static org.maiwithu.maicraft.client.server.ServerRouterTestHarness.check;

public final class ClientServerFallbackTest {
    public static void main(String[] args) {
        noServerCannotUseNativeClient();
        partialSupportSelectsPerOperation();
        registrationAndConditionsDiffer();
        policyRejectionNeverFallsBack();
        explicitUnsupportedMayFallBackOnce();
        lostHandshakeDoesNotSendMutations();
        delayedChannelCanConfirmServer();
        confirmedServerDoesNotAuthorizeAnotherConnection();
        malformedWelcomeCannotConfirmServer();
        System.out.println("ClientServerFallbackTest: passed");
    }

    private static void noServerCannotUseNativeClient() {
        // 未装服务端时，读世界和写玩家的本地实现都保持排队；十秒后交给运行时断线。
        var h = new ServerRouterTestHarness(false);
        var receipt = h.submit("test.write");
        var read = h.submit("test.read");
        h.advance(199);
        check(!h.router.serverConfirmationExpired(), "allow channel registration and handshake to arrive");
        h.advance(200);
        check(h.sent.isEmpty() && h.local.submissions == 0, "missing server cannot execute either backend");
        check(receipt.snapshot().status() == Status.QUEUED && read.snapshot().status() == Status.QUEUED,
                "world reads and player mutations remain unsubmitted");
        check(h.router.serverConfirmationExpired() && !h.router.nativeFallbackAllowed("test.write"),
                "missing server expires and cannot use legacy native fallback");
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
        // 服务器确认已安装但没提供此项操作时，仍可使用符合原生条件的客户端实现。
        var h = new ServerRouterTestHarness(true);
        h.welcome();
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
        // 只声明通道却不完成握手，不能在协商超时后借客户端降级开始施工。
        var h = new ServerRouterTestHarness(true);
        var receipt = h.submit("test.write");
        h.advance(1);
        check(receipt.snapshot().status() == Status.QUEUED && h.count("request") == 0,
                "no mutation precedes version and capability negotiation");
        h.advance(101);
        check(h.count("hello") == 1 && h.count("request") == 0 && h.local.submissions == 0,
                "a lost welcome cannot fall back to native client operations");
        h.router.receive(h.welcomeEnvelope("test.write"), 1);
        h.advance(200);
        check(receipt.snapshot().backend() == Backend.UNSELECTED && h.router.serverConfirmationExpired(),
                "a welcome after negotiation timeout cannot revive the unconfirmed connection");
    }

    private static void delayedChannelCanConfirmServer() {
        // Fabric 入服后才收到通道声明时继续握手；服务端无需实现每一项客户端能力。
        var h = new ServerRouterTestHarness(false);
        h.advance(20);
        h.available = true;
        h.advance(21);
        h.welcome();
        check(h.router.serverConfirmed(), "valid delayed handshake confirms server installation");
        var local = h.submit("test.write");
        h.advance(22);
        check(local.snapshot().status() == Status.SUCCEEDED && h.local.submissions == 1,
                "confirmed server permits supported local operations");
    }

    private static void confirmedServerDoesNotAuthorizeAnotherConnection() {
        // 同服换维度继续认安装证明；换服和断线必须清掉旧证明，旧欢迎包不能授权新服务器。
        var h = new ServerRouterTestHarness(true);
        var old = h.welcomeEnvelope();
        h.welcome();
        h.router.bind(1, 2, "minecraft:the_nether", 2, false, 1);
        check(h.router.serverConfirmed(), "dimension changes keep the current server confirmation");
        h.router.bind(2, 3, "minecraft:overworld", 3, true, 2);
        h.router.receive(old, 1);
        h.router.receive(old, 2);
        check(!h.router.serverConfirmed(), "neither old connection nor old nonce authorizes another server");
        h.router.receive(h.welcomeEnvelope(), 2);
        check(h.router.serverConfirmed(), "new connection needs its own valid welcome");
        h.router.disconnect();
        check(!h.router.serverConfirmed(), "disconnect revokes the installation proof");
    }

    private static void malformedWelcomeCannotConfirmServer() {
        // 缺少会话身份的伪欢迎包不能开放本地动作，验证失败后也不保留部分确认。
        var h = new ServerRouterTestHarness(true);
        var malformed = h.welcomeEnvelope();
        malformed.remove("sessionId");
        h.router.receive(malformed, 1);
        h.advance(200);
        check(!h.router.serverConfirmed() && h.router.serverConfirmationExpired(),
                "malformed server response never confirms installation");
    }
}
