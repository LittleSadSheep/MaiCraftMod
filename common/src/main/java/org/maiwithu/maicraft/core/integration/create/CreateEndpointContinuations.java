// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** One-use opaque receipts for a paused first-person endpoint evidence frontier. */
final class CreateEndpointContinuations {
    private static final Map<UUID, Entry> ENTRIES = new LinkedHashMap<>();

    record Entry(
            UUID token,
            CreateMechanicalPower.Request request,
            CreateProgressiveSurvey survey,
            long bodyEpoch,
            String dimension) {}

    private CreateEndpointContinuations() {}

    static synchronized Entry issue(
            CreateMechanicalPower.Request request,
            CreateProgressiveSurvey survey,
            long bodyEpoch,
            String dimension) {
        survey.pause();
        UUID token = UUID.randomUUID();
        Entry entry = new Entry(token, request, survey, bodyEpoch, dimension);
        ENTRIES.put(token, entry);
        return entry;
    }

    static synchronized Entry take(UUID token) {
        return ENTRIES.remove(token);
    }

    static synchronized void discard(UUID token) {
        Entry entry = ENTRIES.remove(token);
        if (entry != null) entry.survey().stop();
    }
}
