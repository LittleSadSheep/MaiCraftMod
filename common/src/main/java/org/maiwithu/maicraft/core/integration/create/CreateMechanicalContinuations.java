// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create;

import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 短期保存部分已确认的接线进度与栏位准备信息，供同请求、身体和维度续接；最多三十二份，三十分钟过期，取用一次就移除。
 */
final class CreateMechanicalContinuations {
    private static final int MAX_RECEIPTS = 32;
    private static final long TTL_NANOS = Duration.ofMinutes(30).toNanos();
    private static final Map<UUID, Entry> ENTRIES = new LinkedHashMap<>();

    record Entry(
            UUID token,
            CreateMechanicalPower.Request request,
            CreateMechanicalPlan plan,
            int confirmedCells,
            String prefixHash,
            long bodyEpoch,
            String dimension,
            CreateMechanicalStager.Snapshot staging,
            long expiresAtNanos) {}

    private CreateMechanicalContinuations() {}

    static synchronized Entry issue(
            CreateMechanicalPower.Request request,
            CreateMechanicalPlan plan,
            int confirmedCells,
            long bodyEpoch,
            String dimension,
            CreateMechanicalStager.Snapshot staging) {
        if (confirmedCells <= 0 || confirmedCells >= plan.cells().size()) return null;
        purgeExpired();
        while (ENTRIES.size() >= MAX_RECEIPTS) {
            Iterator<UUID> iterator = ENTRIES.keySet().iterator();
            if (!iterator.hasNext()) break;
            iterator.next();
            iterator.remove();
        }
        UUID token = UUID.randomUUID();
        Entry entry = new Entry(token, request, plan, confirmedCells,
                plan.prefixHash(confirmedCells), bodyEpoch, dimension,
                staging,
                System.nanoTime() + TTL_NANOS);
        ENTRIES.put(token, entry);
        return entry;
    }

    /** A continuation is consumed before execution so two callers cannot replay the same prefix. */
    static synchronized Entry take(UUID token) {
        purgeExpired();
        return ENTRIES.remove(token);
    }

    static synchronized void discard(UUID token) {
        ENTRIES.remove(token);
    }

    private static void purgeExpired() {
        long now = System.nanoTime();
        ENTRIES.values().removeIf(entry -> now - entry.expiresAtNanos() >= 0);
    }
}
