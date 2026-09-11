// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.network;

import com.google.gson.JsonObject;
import static org.maiwithu.maicraft.network.ProtocolFixture.check;
import static org.maiwithu.maicraft.network.ProtocolFixture.field;

public final class ProtocolBoundaryTest {
    public static void main(String[] args) {
        rejectJson("{\"kind\":\"hello\",\"kind\":\"request\"}");
        rejectJson("{\"a\":1} {\"b\":2}");
        rejectJson("{'kind':'hello'}");
        rejectJson("{\"a\":" + "[".repeat(26) + "0" + "]".repeat(26) + "}");
        rejectJson("{\"a\":\"" + "x".repeat(ProtocolJson.MAX_ENVELOPE_CHARS) + "\"}");
        rejectJson("{\"a\":[" + "0,".repeat(8193) + "0]}");
        directionalLimits();
        retainedLargeReceipts();
        malformedFields();
        invalidOutputNumber();
        transportIsolation();
        sessionCapacity();
        System.out.println("ProtocolBoundaryTest passed");
    }

    private static void rejectJson(String text) {
        try { ProtocolJson.decode(text); }
        catch (RuntimeException expected) { return; }
        throw new AssertionError("Malformed JSON was accepted");
    }

    private static void invalidOutputNumber() {
        JsonObject value = new JsonObject();
        value.addProperty("nan", Double.NaN);
        try { ProtocolJson.encode(value); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("NaN must not produce an unreadable authoritative receipt");
    }

    private static void directionalLimits() {
        JsonObject snapshot = new JsonObject();
        snapshot.addProperty("nativeSnapshot", "x".repeat(56000));
        check(ProtocolJson.decode(ProtocolJson.encode(snapshot)).equals(snapshot), "Paged server snapshots fit response budget");
        try { ProtocolJson.encodeRequest(snapshot); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("A large response must not be accepted as a serverbound request");
    }

    private static void retainedLargeReceipts() {
        JsonObject snapshot = new JsonObject();
        JsonObject body = new JsonObject();
        body.addProperty("nativeSnapshot", "temperature:100;pressure:20;".repeat(2000));
        snapshot.add("result", body);
        RequestLedger ledger = new RequestLedger();
        for (int i = 0; i < RequestLedger.MAX_REQUESTS; i++) {
            check(!ledger.full(true), "Large read snapshots must not exhaust memory after a few samples");
            ledger.store("snapshot-" + i, "same-content", snapshot, true);
        }
        check(ledger.full(), "Read tombstones still honor identity capacity");
        field(ledger.lookup("snapshot-0"), "code", "receipt_unavailable");
        check(ledger.matches("snapshot-0", "same-content"), "Expiring read content must keep its fingerprint");
        check(ledger.lookup("snapshot-511").equals(snapshot), "Recent read result must remain exact");
    }

    private static void malformedFields() {
        var fixture = new ProtocolFixture();
        var request = fixture.request("fraction");
        request.addProperty("version", 1.5);
        field(fixture.send(request), "effect", "not_applied");
        field(fixture.send(request), "code", "invalid_request");
        request = fixture.request("overflow");
        request.addProperty("version", "9999999999999999999999999");
        field(fixture.send(request), "code", "invalid_request");
        request = fixture.request("missing-body");
        request.remove("body");
        field(fixture.send(request), "code", "invalid_request");
        check(fixture.mutations == 0, "Malformed request reached a handler");
        JsonObject hello = fixture.hello("negative");
        hello.addProperty("controlGeneration", -1);
        field(fixture.send(hello), "code", "invalid_envelope");
    }

    private static void transportIsolation() {
        int[] sends = {0};
        int[] disconnected = {0};
        ClientProtocolBridge.installTransport(new ClientProtocolBridge.Transport() {
            @Override public boolean available() { return false; }
            @Override public void send(JsonObject envelope) { sends[0]++; }
        });
        ClientProtocolBridge.installListener(new ClientProtocolBridge.Listener() {
            @Override public void connected() {}
            @Override public void received(JsonObject envelope) { throw new AssertionError("Invalid data reached listener"); }
            @Override public void disconnected() { disconnected[0]++; }
        });
        check(!ClientProtocolBridge.send(new JsonObject()) && sends[0] == 0, "Absent server must not receive bootstrap packets");
        ClientProtocolBridge.receive("{bad}");
        check(disconnected[0] == 1, "Malformed server data must invalidate the client session");
    }

    private static void sessionCapacity() {
        var fixture = new ProtocolFixture();
        JsonObject first = fixture.send(fixture.request("original"));
        JsonObject originalQuery = fixture.packet("query", "original");
        for (int i = 1; i < ServerProtocolDispatcher.MAX_SESSIONS; i++) {
            fixture.tick++;
            fixture.welcome = fixture.send(fixture.hello("scope-" + i));
            field(fixture.welcome, "status", "succeeded");
            field(fixture.send(fixture.request("mutation-" + i)), "status", "succeeded");
        }
        fixture.tick++;
        field(fixture.send(fixture.hello("overflow")), "code", "session_capacity");
        check(fixture.send(originalQuery).equals(first), "Session pressure must retain original receipts");
        fixture.tick += ProtocolSession.RETENTION_TICKS + 1;
        field(fixture.send(fixture.hello("after-retention")), "status", "succeeded");
        field(fixture.send(originalQuery), "effect", "unknown");
    }
}
