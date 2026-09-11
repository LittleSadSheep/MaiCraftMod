// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;

/** Loss watermarks belong to one endpoint, never to unrelated world traffic. */
final class ProducerEventHistory {
    final ArrayDeque<ProductionJournalEvent> events = new ArrayDeque<>();
    final Map<String, Long> losses = new LinkedHashMap<>();
    long latest;
    int chars;

    ProducerEventHistory(long lostThrough, String reason) {
        if (lostThrough > 0) lost(lostThrough, reason);
    }

    void append(ProductionJournalEvent event, long sequence, boolean retained) {
        latest = sequence;
        if (event == null) { lost(sequence, "oversized_event"); return; }
        events.addLast(event); chars += event.encoded().length();
        trim(retained);
    }

    void trim(boolean retained) {
        int countLimit = retained ? ProductionHistoryIndex.RETAINED_EVENTS : ProductionHistoryIndex.COLD_EVENTS;
        int charLimit = retained ? ProductionHistoryIndex.RETAINED_CHARS : ProductionHistoryIndex.COLD_CHARS;
        while (events.size() > countLimit || chars > charLimit) {
            ProductionJournalEvent evicted = events.removeFirst();
            chars -= evicted.encoded().length();
            lost(evicted.sequence(), "endpoint_budget_exceeded");
        }
    }

    void lost(long sequence, String reason) {
        losses.merge(reason, sequence, Math::max);
        latest = Math.max(latest, sequence);
    }

    long lossThrough() { return losses.values().stream().mapToLong(Long::longValue).max().orElse(0); }
}
