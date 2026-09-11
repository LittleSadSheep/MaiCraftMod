// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.server;

import java.nio.file.Files;
import java.nio.file.Path;
import static org.maiwithu.maicraft.client.server.ClientRequestReceipt.*;
import static org.maiwithu.maicraft.client.server.ServerRouterTestHarness.check;

public final class MutationJournalTest {
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("maicraft-mutation-journal-");
        try {
            restartRetainsUnknownIdentity(directory);
            corruptAndUnwritableJournalFailClosed(directory);
            interruptedAtomicWriteRetainsFence(directory);
        } finally {
            try (var files = Files.list(directory)) {
                for (Path file : files.toList()) Files.deleteIfExists(file);
            }
            Files.delete(directory);
        }
        System.out.println("MutationJournalTest: passed");
    }

    private static void restartRetainsUnknownIdentity(Path directory) throws Exception {
        Path path = directory.resolve("unresolved.json");
        var journal = new MutationJournal(path);
        journal.bind("remote:example", "player", "minecraft:overworld");
        var h = new ServerRouterTestHarness(true, journal);
        h.welcome("test.write");
        var request = h.submit("test.write");
        h.advance(1);
        var uncertain = h.reply(request, "succeeded", "applied", "");
        var business = new com.google.gson.JsonObject();
        business.addProperty("status", "uncertain");
        uncertain.add("result", business);
        h.router.receive(uncertain, 1);
        check(new MutationJournal(path).unresolved() && Files.readString(path).contains(request.id().toString()),
                "a dispatch success with unknown business effect remains durable across restart");
        var restarted = new ServerRouterTestHarness(false, new MutationJournal(path));
        var fresh = restarted.submit("test.write");
        restarted.advance(1);
        check(fresh.snapshot().status() == Status.QUEUED && restarted.local.submissions == 0,
                "process restart cannot turn an unresolved server mutation into a native retry");
        h.router.receive(h.reply(request, "succeeded", "applied", ""), 1);
        check(!new MutationJournal(path).unresolved(), "only a validated authoritative result removes the persistent fence");
    }

    private static void corruptAndUnwritableJournalFailClosed(Path directory) throws Exception {
        Path corrupt = directory.resolve("corrupt.json");
        Files.writeString(corrupt, "{truncated");
        var h = new ServerRouterTestHarness(false, new MutationJournal(corrupt));
        h.submit("test.write");
        h.advance(1);
        check(h.local.submissions == 0, "corrupt journal is never treated as an empty request history");
        Path blocker = directory.resolve("regular-file");
        Files.writeString(blocker, "not a directory");
        var unavailable = new ServerRouterTestHarness(false, new MutationJournal(blocker.resolve("journal.json")));
        var rejected = unavailable.submit("test.write");
        unavailable.advance(1);
        check(rejected.snapshot().effect() == Effect.NOT_APPLIED && unavailable.local.submissions == 0,
                "persistence failure aborts before native submission");
    }

    private static void interruptedAtomicWriteRetainsFence(Path directory) throws Exception {
        Path path = directory.resolve("interrupted.json");
        Path pending = directory.resolve("interrupted.json.pending");
        Files.writeString(pending, "{\"schema\":1,\"unresolved\":[{\"request_id\":\"00000000-0000-0000-0000-000000000001\","
                + "\"server\":\"remote:example\",\"operation\":\"test.write\"}]}");
        check(new MutationJournal(path).unresolved(), "a crash before atomic rename preserves the pending write-ahead fence");
    }
}
