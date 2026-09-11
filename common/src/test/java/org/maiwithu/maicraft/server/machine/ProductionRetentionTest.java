// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.maiwithu.maicraft.network.ProtocolJson;

/** Unrelated factory traffic must not erase a long-running authorized production observation. */
public final class ProductionRetentionTest {
    public static void main(String[] args) {
        unrelatedFactoriesCannotStarveRetainedEndpoints();
        ownLossRemainsVisibleAndLateRetentionDoesNotHideIt();
        allTransferParticipantsShareOneOrderedEvent();
        oversizedEventsAreLocalLossesAndCannotStallCursors();
        pagesFitTheWireAndAlwaysAdvance();
        retentionIsBoundedAndConnectionOwned();
        serializedBytesAndSharedOwnersAreBounded();
        extractionMarkersOrderSameTickWithoutInventingEvents();
        System.out.println("Production retention isolation, long-supply, real-gap, oversize and ownership regressions passed");
    }

    private static void unrelatedFactoriesCannotStarveRetainedEndpoints() {
        ProductionEventJournal journal = new ProductionEventJournal("test:world");
        Object connection = new Object();
        check(journal.retain(connection, Set.of("press", "idle")), "Initial watch was rejected");
        String scope = journal.page(0, Set.of("press"), 1, null).get("scope").getAsString();
        journal.append(event("press", 2));
        for (int i = 0; i < 20_000; i++) journal.append(event("unrelated_" + i, 3 + i));
        journal.append(event("press", 30_000));
        JsonObject active = journal.page(0, Set.of("press"), 10_000_000, scope);
        check(!active.get("gap").getAsBoolean() && active.getAsJsonArray("events").size() == 2,
                "Unrelated factory churn erased the selected producer during long supply");
        JsonObject idle = journal.page(0, Set.of("idle"), 10_000_000, scope);
        check(!idle.get("gap").getAsBoolean() && idle.getAsJsonArray("events").isEmpty(),
                "An idle retained producer acquired a false global gap");
        check(idle.get("next_sequence").getAsLong() == journal.latestSequence(), "Unrelated sequences stalled an empty page");
        check(idle.get("first_available_sequence").getAsLong() == 1, "An idle endpoint inherited an unrelated global coverage floor");
        check(journal.retention(connection, Set.of("press", "idle")).get("retained").getAsBoolean(), "Long supply expired the watch");
        check(journal.histories().historyCount() <= ProductionHistoryIndex.COLD_ENDPOINTS + 2, "Cold endpoint histories grew without a bound");
        check(journal.histories().retiredCount() <= 2048, "Eviction metadata grew without a bound");
    }

    private static void ownLossRemainsVisibleAndLateRetentionDoesNotHideIt() {
        ProductionEventJournal journal = new ProductionEventJournal("test:world");
        Object connection = new Object(); journal.retain(connection, Set.of("press", "quiet"));
        for (int i = 0; i < 600; i++) journal.append(event("press", i));
        JsonObject page = journal.page(0, Set.of("press"), 700, null);
        check(page.get("gap").getAsBoolean() && page.get("incomplete").getAsBoolean(), "Real selected endpoint loss disappeared");
        JsonObject detail = page.getAsJsonArray("gap_details").get(0).getAsJsonObject();
        check(detail.get("producer").getAsString().equals("press"), "Gap was attributed to an unrelated endpoint");
        long lost = detail.get("lost_through").getAsLong();
        check(!journal.page(lost, Set.of("press"), 700, null).get("gap").getAsBoolean(), "Consumed history was still treated as missing");
        check(!journal.page(0, Set.of("quiet"), 700, null).get("gap").getAsBoolean(), "A busy neighbor's quota loss poisoned the quiet watch");
        check(journal.page(0, Set.of("quiet"), 700, "different-world").get("gap").getAsBoolean(), "World reset was accepted");
        check(journal.page(900, Set.of("quiet"), 700, null).get("gap").getAsBoolean(), "Future/reset cursor was accepted");
        ProductionEventJournal late = new ProductionEventJournal("test:late");
        for (int i = 0; i < 160; i++) late.append(event("already_busy", i));
        late.retain(connection, Set.of("already_busy"));
        check(late.page(0, Set.of("already_busy"), 170, null).get("gap").getAsBoolean(), "New retention washed away prior known loss");
    }

    private static void allTransferParticipantsShareOneOrderedEvent() {
        ProductionEventJournal journal = new ProductionEventJournal("test:world");
        Object connection = new Object(); journal.retain(connection, Set.of("source", "sink"));
        JsonObject transfer = event("transporter", 1);
        transfer.addProperty("source", "source"); transfer.addProperty("destination", "sink");
        journal.append(transfer);
        check(journal.page(0, Set.of("source"), 2, null).getAsJsonArray("events").size() == 1, "Actual source could not observe its transfer");
        check(journal.page(0, Set.of("sink"), 2, null).getAsJsonArray("events").size() == 1, "Actual destination could not observe its transfer");
        check(journal.page(0, Set.of("source", "sink", "transporter"), 2, null).getAsJsonArray("events").size() == 1,
                "Shared endpoint views duplicated the same native event");
        for (int i = 0; i < 600; i++) {
            JsonObject other = event("other_source", 3 + i); other.addProperty("destination", "sink"); journal.append(other);
        }
        check(!journal.page(0, Set.of("source"), 700, null).get("gap").getAsBoolean(), "Destination churn erased the independent source history");
        check(journal.page(0, Set.of("sink"), 700, null).get("gap").getAsBoolean(), "Destination's own relevant history loss was hidden");
    }

    private static void oversizedEventsAreLocalLossesAndCannotStallCursors() {
        ProductionEventJournal journal = new ProductionEventJournal("test:world");
        Object connection = new Object(); journal.retain(connection, Set.of("normal", "oversize"));
        journal.append(event("normal", 1));
        JsonObject huge = event("oversize", 2); huge.addProperty("payload", "x".repeat(30_000)); journal.append(huge);
        JsonObject complex = event("oversize", 3); JsonArray values = new JsonArray();
        for (int i = 0; i < 4200; i++) values.add(0);
        complex.add("values", values); journal.append(complex);
        JsonObject deep = event("oversize", 4), cursor = deep;
        for (int i = 0; i < 25; i++) { JsonObject nested = new JsonObject(); cursor.add("nested", nested); cursor = nested; }
        journal.append(deep);
        journal.append(event("normal", 5));
        JsonObject normal = journal.page(0, Set.of("normal"), 6, null);
        check(!normal.get("gap").getAsBoolean() && normal.getAsJsonArray("events").size() == 2, "An oversized event cleared unrelated history");
        JsonObject missing = journal.page(0, Set.of("oversize"), 6, null);
        check(missing.get("gap").getAsBoolean() && missing.get("incomplete").getAsBoolean(), "Dropped native payload was presented as complete");
        check(missing.getAsJsonArray("events").isEmpty() && !missing.get("truncated").getAsBoolean()
                && missing.get("next_sequence").getAsLong() == 5, "Oversize-only page stalled at a nonadvancing cursor");
    }

    private static void pagesFitTheWireAndAlwaysAdvance() {
        ProductionEventJournal journal = new ProductionEventJournal("test:world");
        Object connection = new Object(); journal.retain(connection, Set.of("large", "complex"));
        for (int i = 0; i < 3; i++) {
            JsonObject event = event("large", i); event.addProperty("payload", "x".repeat(23_000)); journal.append(event);
        }
        for (int i = 0; i < 3; i++) {
            JsonObject event = event("complex", 4 + i); JsonArray data = new JsonArray();
            for (int n = 0; n < 3600; n++) data.add(n % 10);
            event.add("values", data); journal.append(event);
        }
        long after = 0; int observed = 0;
        for (int pages = 0; pages < 10 && after < journal.latestSequence(); pages++) {
            JsonObject page = journal.page(after, Set.of("large", "complex"), 10, null);
            check(!page.get("gap").getAsBoolean(), "A valid bounded event was rejected");
            JsonObject envelope = new JsonObject(); envelope.addProperty("kind", "receipt"); envelope.add("result", page);
            ProtocolJson.decode(ProtocolJson.encode(envelope));
            long next = page.get("next_sequence").getAsLong();
            check(next > after && (!page.get("truncated").getAsBoolean() || !page.getAsJsonArray("events").isEmpty()),
                    "A valid event could never fit and the page did not advance");
            observed += page.getAsJsonArray("events").size(); after = next;
        }
        check(after == 6 && observed == 6, "Wire paging lost or duplicated an event");
    }

    private static void retentionIsBoundedAndConnectionOwned() {
        ProductionEventJournal journal = new ProductionEventJournal("test:world");
        Object first = new Object(), second = new Object(), stranger = new Object();
        Set<String> left = names("left_", 32), right = names("right_", 32);
        check(journal.retain(first, left) && journal.retain(second, right), "Declared watch capacity was unavailable");
        check(!journal.retain(first, Set.of("overflow")), "Per-connection reservation grew without a bound");
        check(!journal.retain(stranger, Set.of("overflow")) && journal.histories().retainedEndpoints() == 64, "Global watch quota overflowed or partially reserved");
        check(journal.release(stranger, left, null).get("released_endpoints").getAsInt() == 0, "Another connection released this watch");
        check(journal.release(first, left, "old-scope").get("status").getAsString().equals("scope_changed"), "Stale cleanup released a new world watch");
        check(journal.retain(stranger, Set.of("left_0")), "Sharing an existing retained endpoint used another global slot");
        journal.disconnected(first);
        check(journal.retention(stranger, Set.of("left_0")).get("retained").getAsBoolean(), "Disconnect released another connection's shared watch");
        check(journal.histories().retainedEndpoints() == 33, "Disconnected owner kept its endpoint reservations");
        journal.disconnected(second); journal.disconnected(stranger);
        check(journal.histories().retainedEndpoints() == 0, "All disconnected watches did not release capacity");
        check(journal.histories().storedChars() <= (long) ProductionHistoryIndex.RETAINED_ENDPOINTS * ProductionHistoryIndex.RETAINED_CHARS
                + (long) ProductionHistoryIndex.COLD_ENDPOINTS * ProductionHistoryIndex.COLD_CHARS, "Serialized retention bytes exceeded the finite budget");
    }

    private static Set<String> names(String prefix, int count) { return IntStream.range(0, count).mapToObj(i -> prefix + i).collect(Collectors.toSet()); }
    private static void extractionMarkersOrderSameTickWithoutInventingEvents() {
        ProductionEventJournal journal = new ProductionEventJournal("test:world");
        Object connection = new Object(); journal.retain(connection, Set.of("source", "sink"));
        var old = journal.markExtraction(100);
        long baseline = journal.latestSequence();
        var fresh = journal.markExtraction(100);
        JsonObject transfer = event("source", 100); transfer.addProperty("destination", "sink"); journal.append(transfer);
        JsonObject page = journal.page(baseline, Set.of("source", "sink"), 100, fresh.scope());
        long delivery = page.getAsJsonArray("events").get(0).getAsJsonObject().get("sequence").getAsLong();
        check(old.tick() == fresh.tick() && old.sequence() <= baseline && fresh.sequence() > baseline && fresh.sequence() < delivery,
                "Same-tick ordering could not distinguish pre-baseline in-flight material");
        check(page.getAsJsonArray("events").size() == 1 && !page.get("gap").getAsBoolean(), "Ordering markers became fabricated output events or false gaps");
    }
    private static void serializedBytesAndSharedOwnersAreBounded() {
        ProductionEventJournal journal = new ProductionEventJournal("test:world");
        java.util.List<Object> owners = new java.util.ArrayList<>();
        for (int i = 0; i < 64; i++) {
            Object owner = new Object(); owners.add(owner);
            check(journal.retain(owner, Set.of("shared")), "Sharing exceeded capacity before the declared owner limit");
        }
        check(!journal.retain(new Object(), Set.of("shared")), "Shared endpoint owners grew without a bound");
        for (int i = 0; i < 20; i++) {
            JsonObject event = event("shared", i); event.addProperty("payload", "x".repeat(23_000)); journal.append(event);
        }
        check(journal.histories().storedChars() <= ProductionHistoryIndex.RETAINED_CHARS, "Per-endpoint serialized byte budget overflowed");
        check(journal.page(0, Set.of("shared"), 30, null).get("gap").getAsBoolean(), "Byte-budget loss was hidden");
        for (Object owner : owners) journal.disconnected(owner);
        check(journal.histories().retainedEndpoints() == 0, "Shared owner cleanup leaked the reservation");
    }
    private static JsonObject event(String producer, long tick) {
        JsonObject event = new JsonObject(); event.addProperty("producer", producer); event.addProperty("tick", tick); return event;
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
