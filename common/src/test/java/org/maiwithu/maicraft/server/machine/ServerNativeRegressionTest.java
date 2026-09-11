// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Set;
import org.maiwithu.maicraft.server.inventory.ResourceIdentity;

/** Server-only regressions for evidence identity, causal cursors and response bounds. */
public final class ServerNativeRegressionTest {
    public static void main(String[] args) {
        identitiesRetainComponents();
        eventsAreOrderedAndIsolated();
        historyGapsAreExplicit();
        pagesNeverSkipUnreportedResources();
        nativeFailureCannotBecomeMissingApi();
        configurationBooleansAreStrict();
        System.out.println("Server native identity, event, paging and reflection regressions passed");
    }

    private static void identitiesRetainComponents() {
        JsonObject first = JsonParser.parseString("{\"kind\":\"items\",\"id\":\"test:part\",\"components\":{\"b\":2,\"a\":1}}").getAsJsonObject();
        JsonObject reordered = JsonParser.parseString("{\"components\":{\"a\":1,\"b\":2},\"id\":\"test:part\",\"kind\":\"items\"}").getAsJsonObject();
        check(ResourceIdentity.key(first).equals(ResourceIdentity.key(reordered)), "Component map order changed identity");
        reordered.getAsJsonObject("components").addProperty("a", 3);
        check(!ResourceIdentity.key(first).equals(ResourceIdentity.key(reordered)), "Different component resources were merged");
    }

    private static void eventsAreOrderedAndIsolated() {
        ProductionEventJournal journal = new ProductionEventJournal("test:world");
        journal.append(event("a", 3));
        long baseline = journal.latestSequence();
        JsonObject event = event("b", 4); journal.append(event); event.addProperty("producer", "forged");
        journal.append(event("a", 5));
        JsonObject page = journal.page(baseline, Set.of("a", "b"), 6, null);
        JsonArray events = page.getAsJsonArray("events");
        check(events.size() == 2, "Baseline replayed old production or mutation altered history");
        check(events.get(0).getAsJsonObject().get("sequence").getAsLong() == 2, "Cross-machine stream lost ordering");
        check(journal.page(3, Set.of("a"), 7, null).getAsJsonArray("events").isEmpty(), "Duplicate cursor replayed production");
        check(journal.page(3, Set.of("a"), 7, "other").get("gap").getAsBoolean(), "Changed server scope was accepted");
        JsonObject transfer = event("other", 8); transfer.addProperty("destination", "sink"); journal.append(transfer);
        check(journal.page(3, Set.of("sink"), 9, null).getAsJsonArray("events").size() == 1, "Delivery endpoint could not observe its event");
    }

    private static void historyGapsAreExplicit() {
        ProductionEventJournal journal = new ProductionEventJournal("test:world");
        for (int i = 0; i < 1100; i++) journal.append(event("a", i));
        JsonObject page = journal.page(0, Set.of("a"), 1200, null);
        check(page.get("gap").getAsBoolean(), "Evicted native events were treated as a complete window");
        check(page.getAsJsonArray("events").size() == 64 && page.get("truncated").getAsBoolean(), "Event page was unbounded");
        long cursor = page.get("next_sequence").getAsLong();
        check(journal.page(cursor, Set.of("a"), 1200, null).getAsJsonArray("events")
                .get(0).getAsJsonObject().get("sequence").getAsLong() == cursor + 1, "Event paging lost a native output");
    }

    private static void pagesNeverSkipUnreportedResources() {
        SnapshotBudget budget = new SnapshotBudget(0, 3);
        JsonArray page = new JsonArray();
        JsonObject large = new JsonObject(); large.addProperty("payload", "x".repeat(14_000));
        budget.add(page, large); budget.add(page, large); budget.add(page, new JsonObject());
        check(page.size() == 1 && budget.nextOffset() == 1, "A smaller later entry skipped an unreported resource");
        SnapshotBudget oversize = new SnapshotBudget(0, 1);
        JsonObject identity = new JsonObject(); identity.addProperty("components", "x".repeat(30_000));
        JsonObject value = new JsonObject(); value.add("identity", identity); value.addProperty("resource_id", "opaque");
        JsonArray result = new JsonArray(); oversize.add(result, value);
        check(result.size() == 1 && oversize.nextOffset() == 1 && oversize.truncated(), "Large components permanently stalled paging");
    }

    public interface SampleApi { int read(); void mutate(); }
    public static final class Sample implements SampleApi {
        @Override public int read() { return 7; }
        @Override public void mutate() { throw new IllegalStateException("Native writer threw after starting"); }
    }

    private static void nativeFailureCannotBecomeMissingApi() {
        Sample sample = new Sample();
        check(NativeApi.number(NativeApi.call(sample, SampleApi.class.getName(), "read")) == 7, "Public interface call failed");
        try { NativeApi.call(sample, SampleApi.class.getName(), "mutate"); throw new AssertionError("Native failure disappeared"); }
        catch (NativeApi.NativeFailure expected) { check(expected.getCause() instanceof IllegalStateException, "Native cause was lost"); }
    }

    private static JsonObject event(String producer, long tick) {
        JsonObject event = new JsonObject(); event.addProperty("producer", producer); event.addProperty("tick", tick); return event;
    }
    private static void configurationBooleansAreStrict() {
        JsonObject body = new JsonObject(); body.addProperty("enabled", true);
        check(ServerAccess.bool(body, "enabled"), "Valid boolean was rejected");
        body.addProperty("enabled", "false");
        try { ServerAccess.bool(body, "enabled"); throw new AssertionError("Text silently changed native configuration"); }
        catch (org.maiwithu.maicraft.network.ServerOperationException expected) { /* Invalid input must not mutate. */ }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
