// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.network;

import com.google.gson.JsonObject;
import static org.maiwithu.maicraft.network.ProtocolFixture.check;
import static org.maiwithu.maicraft.network.ProtocolFixture.field;

public final class ProtocolMutationTest {
    public static void main(String[] args) {
        duplicateAndConflict();
        uncertainAndRejected();
        cancellation();
        generationRevocation();
        boundedReceipt();
        receiptMetadataFitsBound();
        System.out.println("ProtocolMutationTest passed");
    }

    private static void duplicateAndConflict() {
        var fixture = new ProtocolFixture();
        var request = fixture.request("once");
        request.getAsJsonObject("body").addProperty("a", 1);
        request.getAsJsonObject("body").addProperty("b", 2);
        JsonObject original = fixture.send(request);
        field(original, "effect", "applied");
        JsonObject reordered = new JsonObject();
        reordered.addProperty("b", 2);
        reordered.addProperty("a", 1);
        request.add("body", reordered);
        check(original.equals(fixture.send(request)), "JSON key ordering must preserve identity");
        check(fixture.mutations == 1, "Duplicate must not mutate twice");
        request.getAsJsonObject("body").addProperty("b", 3);
        field(fixture.send(request), "code", "request_conflict");
        field(fixture.send(request), "effect", "unknown");
        check(fixture.mutations == 1, "Conflicting reuse must not execute");
        original.getAsJsonObject("result").addProperty("count", -1);
        field(fixture.send(fixture.packet("query", "once")).getAsJsonObject("result"), "count", "1");
    }

    private static void uncertainAndRejected() {
        var fixture = new ProtocolFixture();
        fixture.failure = "native";
        var request = fixture.request("native-failure");
        JsonObject receipt = fixture.send(request);
        field(receipt, "status", "failed");
        field(receipt, "effect", "unknown");
        fixture.failure = "";
        check(fixture.send(request).equals(receipt), "Native failures cannot be retried under the same ID");
        check(fixture.mutations == 1, "Unknown native mutation was repeated");
        fixture.failure = "preflight";
        JsonObject rejected = fixture.send(fixture.request("preflight"));
        field(rejected, "code", "out_of_reach");
        field(rejected, "effect", "not_applied");
        check(fixture.mutations == 1, "Preflight rejection unexpectedly mutated");
        fixture.failure = "";
        check(fixture.send(fixture.request("preflight")).equals(rejected), "Rejected identity must stay rejected");
    }

    private static void cancellation() {
        var fixture = new ProtocolFixture();
        field(fixture.send(fixture.packet("cancel", "late")), "code", "cancelled");
        field(fixture.send(fixture.request("late")), "effect", "not_applied");
        check(fixture.mutations == 0, "Cancel-before-request must reserve a tombstone");
        JsonObject applied = fixture.send(fixture.request("already"));
        check(fixture.send(fixture.packet("cancel", "already")).equals(applied), "Cancel cannot retract completed effects");
        field(fixture.send(fixture.packet("query", "missing")), "effect", "unknown");
    }

    private static void generationRevocation() {
        var fixture = new ProtocolFixture();
        JsonObject control = fixture.packet("control", null);
        control.addProperty("controlGeneration", 1);
        control.addProperty("allowed", false);
        field(fixture.send(control), "status", "succeeded");
        field(fixture.send(fixture.request("stale")), "code", "control_revoked");
        control.addProperty("allowed", true);
        field(fixture.send(control), "code", "stale_control");
        control.addProperty("controlGeneration", 2);
        field(fixture.send(control), "status", "succeeded");
        field(fixture.send(control), "status", "succeeded");
        var fresh = fixture.request("fresh");
        fresh.addProperty("controlGeneration", 2);
        field(fixture.send(fresh), "effect", "applied");
        fixture.allowed = false;
        var blocked = fixture.request("dead");
        blocked.addProperty("controlGeneration", 2);
        field(fixture.send(blocked), "code", "control_revoked");
        var read = fixture.request("read");
        read.addProperty("operationId", "machine.snapshot");
        field(fixture.send(read), "status", "succeeded");
        check(fixture.mutations == 1, "Only the permitted current-generation mutation may run");
    }

    private static void boundedReceipt() {
        var fixture = new ProtocolFixture();
        fixture.failure = "large";
        JsonObject receipt = fixture.send(fixture.request("large"));
        field(receipt, "effect", "unknown");
        field(receipt, "status", "failed");
        check(ProtocolJson.encode(receipt).length() < 8192, "Oversized native result must become a bounded receipt");
        fixture.failure = "";
        check(fixture.send(fixture.request("large")).equals(receipt), "Lost result cannot permit replay");
        check(fixture.mutations == 1, "Oversized result caused a repeated mutation");
    }

    private static void receiptMetadataFitsBound() {
        for (int size = 65000; size <= 65500; size += 25) {
            var fixture = new ProtocolFixture();
            fixture.failure = "edge";
            JsonObject request = fixture.request("edge-" + size);
            request.getAsJsonObject("body").addProperty("size", size);
            JsonObject receipt = fixture.send(request);
            check(ProtocolJson.encode(receipt).length() <= ProtocolJson.MAX_ENVELOPE_CHARS, "Budget metadata made the receipt too large");
            check(!receipt.get("effect").getAsString().equals("not_applied"), "Receipt serialization failure hid a native effect");
            check(fixture.mutations == 1, "Boundary case did not invoke the native operation");
        }
    }
}
