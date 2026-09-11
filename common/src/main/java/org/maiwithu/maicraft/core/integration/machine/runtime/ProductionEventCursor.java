// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.runtime;

import com.google.gson.JsonObject;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;

/** All position groups must cover an event before the shared world cursor advances past it. */
public final class ProductionEventCursor {
    public record Batch(long sequence, long tick, List<JsonObject> events, boolean complete) {}
    private final long[] next;
    private final TreeMap<Long, JsonObject> events = new TreeMap<>();
    private String scope;
    private long after, tick, roundTick = Long.MAX_VALUE;
    private int bufferedChars;
    private boolean complete = true;

    public ProductionEventCursor(int groups) {
        if (groups < 1) throw new IllegalArgumentException("No production observation groups");
        next = new long[groups]; Arrays.fill(next, -1);
    }

    public void baseline(JsonObject page) {
        if (scope != null) throw new IllegalStateException("Production baseline already fixed");
        if (page.get("gap").getAsBoolean()) throw invalid("baseline_gap");
        scope = page.get("scope").getAsString();
        if (scope.isBlank()) throw invalid("missing_world_scope");
        after = number(page, "next_sequence"); tick = number(page, "tick");
        if (after < 0 || tick < 0) throw invalid("invalid_baseline");
    }

    /** Later watch groups join the first group's cursor, without advancing past unconsumed early events. */
    public void baselineGroup(JsonObject page) {
        if (scope == null || !scope.equals(page.get("scope").getAsString()) || page.get("gap").getAsBoolean())
            throw invalid("baseline_group_gap_or_scope_changed");
        long next = number(page, "next_sequence"), latest = number(page, "latest_sequence");
        if (next < after || next > latest || number(page, "tick") < tick) throw invalid("baseline_group_cursor_changed");
    }

    public Batch page(int group, JsonObject page) {
        if (scope == null || group < 0 || group >= next.length || next[group] >= 0) throw invalid("unexpected_group");
        if (!scope.equals(page.get("scope").getAsString()) || page.get("gap").getAsBoolean()) throw invalid("journal_gap_or_scope_changed");
        long cursor = number(page, "next_sequence"), latest = number(page, "latest_sequence"), at = number(page, "tick");
        if (cursor < after || cursor > latest || at < tick) throw invalid("server_cursor_moved_backwards");
        if (!page.has("events") || !page.get("events").isJsonArray()) throw invalid("missing_events");
        for (var raw : page.getAsJsonArray("events")) {
            JsonObject event = raw.getAsJsonObject(); long sequence = number(event, "sequence");
            if (sequence <= after || sequence > cursor || number(event, "tick") > at
                    || !scope.equals(event.get("scope").getAsString())) throw invalid("event_outside_page_scope");
            JsonObject previous = events.putIfAbsent(sequence, event.deepCopy());
            if (previous != null && !previous.equals(event)) throw invalid("conflicting_event_identity");
            if (previous == null) bufferedChars = Math.addExact(bufferedChars, event.toString().length());
            if (events.size() > 4096 || bufferedChars > 2_097_152) throw invalid("event_round_budget_exceeded");
        }
        next[group] = cursor; roundTick = Math.min(roundTick, at);
        complete &= !page.get("truncated").getAsBoolean();
        for (long value : next) if (value < 0) return null;
        long watermark = Arrays.stream(next).min().orElseThrow();
        List<JsonObject> accepted = events.headMap(watermark, true).values().stream().map(JsonObject::deepCopy).toList();
        // Later events are queried again from the safe watermark, including those seen by only one group.
        boolean caughtUp = complete;
        after = watermark; tick = roundTick; events.clear(); bufferedChars = 0; complete = true;
        Arrays.fill(next, -1); roundTick = Long.MAX_VALUE;
        return new Batch(after, tick, accepted, caughtUp);
    }

    public String scope() { return scope; }
    public long sequence() { return after; }
    public long tick() { return tick; }
    static long number(JsonObject value, String field) { return value.get(field).getAsBigDecimal().longValueExact(); }
    private static IllegalStateException invalid(String reason) { return new IllegalStateException("production_" + reason); }
}
