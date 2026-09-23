// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** 按顺序分配全局游标，独立保留各端点历史，并记录各自的缺口。 */
public final class ProductionEventJournal {
    public record OrderingMarker(String scope, long sequence, long tick) {}
    private final String scope;
    private final ProductionHistoryIndex histories = new ProductionHistoryIndex();
    private long sequence;

    public ProductionEventJournal(String dimension) { scope = dimension + ":" + UUID.randomUUID(); }
    public long latestSequence() { return sequence; }
    /** 只记录实际成功提取的顺序；不会伪造配方或转移事件。 */
    public OrderingMarker markExtraction(long tick) { return new OrderingMarker(scope, ++sequence, tick); }
    public boolean validCursor(long after, String expectedScope) {
        return after >= 0 && after <= sequence && (expectedScope == null || scope.equals(expectedScope));
    }

    public boolean retain(Object connection, Set<String> endpoints) {
        if (connection == null || endpoints.isEmpty()) throw new IllegalArgumentException("A connection and endpoints are required");
        return histories.retain(connection, Set.copyOf(endpoints));
    }

    public void disconnected(Object connection) { histories.releaseOwner(connection); }

    public JsonObject release(Object connection, Set<String> endpoints, String expectedScope) {
        boolean matches = expectedScope == null || scope.equals(expectedScope);
        int count = matches ? histories.release(connection, endpoints) : 0;
        JsonObject result = new JsonObject();
        result.addProperty("schema", "maicraft.production_watch_release.v1");
        result.addProperty("status", !matches ? "scope_changed" : count > 0 ? "released" : "no_change");
        result.addProperty("effect", count > 0 ? "metadata_only" : "not_applied");
        result.addProperty("released_endpoints", count);
        return result;
    }

    public void append(JsonObject event) {
        Set<String> endpoints = ProductionJournalEvent.subjects(event);
        long index = ++sequence;
        histories.append(endpoints, ProductionJournalEvent.encode(event, scope, index), index);
    }

    public JsonObject page(long after, Set<String> producers, long tick, String expectedScope) {
        JsonObject result = new JsonObject();
        result.addProperty("schema", "maicraft.production_events.v1");
        result.addProperty("scope", scope);
        result.addProperty("tick", tick);
        result.addProperty("latest_sequence", sequence);
        boolean gap = !validCursor(after, expectedScope);
        JsonArray gaps = new JsonArray();
        TreeMap<Long, ProductionJournalEvent> available = new TreeMap<>();
        long first = sequence + 1;
        long relevantLossThrough = 0;
        for (String producer : producers) {
            ProducerEventHistory history = histories.get(producer);
            relevantLossThrough = Math.max(relevantLossThrough, history.lossThrough());
            if (history.lossThrough() > after) {
                gap = true;
                JsonObject missing = new JsonObject();
                missing.addProperty("producer", producer); missing.addProperty("lost_through", history.lossThrough());
                JsonArray reasons = new JsonArray();
                history.losses.forEach((reason, lost) -> { if (lost > after) reasons.add(reason); });
                missing.add("reasons", reasons); gaps.add(missing);
            }
            if (!history.events.isEmpty()) first = Math.min(first, history.events.getFirst().sequence());
            for (ProductionJournalEvent event : history.events) if (event.sequence() > after) available.put(event.sequence(), event);
        }
        result.addProperty("first_available_sequence", relevantLossThrough + 1);
        result.addProperty("first_retained_sequence", first);
        result.addProperty("gap", gap); result.addProperty("incomplete", gap); result.add("gap_details", gaps);
        JsonArray events = new JsonArray();
        result.add("events", events);
        int length = 0, nodes = 0;
        long next = after;
        boolean truncated = false;
        for (ProductionJournalEvent event : available.values()) {
            // 每个已接受事件都能单独放入；截断页面时仍会推进到某个实际事件之后。
            if (events.size() >= 64 || length + event.encoded().length() > 48_000 || nodes + event.nodes() > 7000) {
                truncated = true; break;
            }
            events.add(event.json()); length += event.encoded().length(); nodes += event.nodes(); next = event.sequence();
        }
        result.addProperty("truncated", truncated);
        result.addProperty("next_sequence", truncated ? next : sequence);
        result.addProperty("delivery_verified", false);
        return result;
    }

    public JsonObject retention(Object connection, Set<String> endpoints) {
        JsonObject result = new JsonObject();
        result.addProperty("retained", histories.retained(connection, endpoints));
        result.addProperty("until", "release_or_connection_end");
        result.addProperty("event_limit_per_endpoint", ProductionHistoryIndex.RETAINED_EVENTS);
        result.addProperty("char_limit_per_endpoint", ProductionHistoryIndex.RETAINED_CHARS);
        result.addProperty("endpoint_limit_per_connection", ProductionHistoryIndex.ENDPOINTS_PER_OWNER);
        result.addProperty("retained_endpoint_limit", ProductionHistoryIndex.RETAINED_ENDPOINTS);
        result.addProperty("connection_limit", ProductionHistoryIndex.RETAINED_OWNERS);
        return result;
    }

    ProductionHistoryIndex histories() { return histories; }
}
