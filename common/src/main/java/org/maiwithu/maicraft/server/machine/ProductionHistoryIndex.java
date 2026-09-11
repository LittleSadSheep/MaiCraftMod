// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Connection-owned endpoint retention with separate quotas for unobserved traffic. */
final class ProductionHistoryIndex {
    static final int RETAINED_ENDPOINTS = 64;
    static final int ENDPOINTS_PER_OWNER = 32;
    static final int RETAINED_OWNERS = 64;
    static final int RETAINED_EVENTS = 512;
    static final int RETAINED_CHARS = 262_144;
    static final int COLD_ENDPOINTS = 128;
    static final int COLD_EVENTS = 128;
    static final int COLD_CHARS = 32_768;
    private static final int RETIRED_ENDPOINTS = 2048;
    private record Owner(WeakReference<Object> identity, Set<String> endpoints) {}
    private final Map<String, ProducerEventHistory> histories = new HashMap<>();
    private final LinkedHashSet<String> cold = new LinkedHashSet<>();
    private final LinkedHashMap<String, Long> retired = new LinkedHashMap<>();
    private final Map<String, Integer> references = new HashMap<>();
    private final ArrayList<Owner> owners = new ArrayList<>();
    private long untrackedThrough;

    boolean retain(Object identity, Set<String> endpoints) {
        releaseCollectedOwners();
        Owner owner = owners.stream().filter(value -> value.identity().get() == identity).findFirst().orElse(null);
        Set<String> combined = new HashSet<>(owner == null ? Set.of() : owner.endpoints());
        combined.addAll(endpoints);
        long added = endpoints.stream().filter(key -> !references.containsKey(key)).count();
        if (combined.size() > ENDPOINTS_PER_OWNER || references.size() + added > RETAINED_ENDPOINTS
                || owner == null && owners.size() >= RETAINED_OWNERS) return false;
        if (owner == null) { owner = new Owner(new WeakReference<>(identity), new HashSet<>()); owners.add(owner); }
        for (String key : endpoints) {
            if (!owner.endpoints().add(key)) continue;
            references.merge(key, 1, Integer::sum);
            history(key); cold.remove(key);
        }
        return true;
    }

    int release(Object identity, Set<String> endpoints) {
        int released = 0;
        for (Owner owner : owners) {
            if (owner.identity().get() != identity) continue;
            for (String key : endpoints) if (owner.endpoints().remove(key)) { unreference(key); released++; }
        }
        owners.removeIf(owner -> owner.endpoints().isEmpty());
        trimCold();
        return released;
    }

    void releaseOwner(Object identity) {
        Set<String> keys = new HashSet<>();
        for (Owner owner : owners) if (owner.identity().get() == identity) keys.addAll(owner.endpoints());
        release(identity, keys);
    }

    void append(Set<String> endpoints, ProductionJournalEvent event, long sequence) {
        if ((sequence & 255) == 0) releaseCollectedOwners();
        for (String key : endpoints) {
            ProducerEventHistory history = history(key);
            boolean retained = references.containsKey(key);
            history.append(event, sequence, retained);
            if (!retained) { cold.remove(key); cold.add(key); }
        }
        trimCold();
    }

    ProducerEventHistory get(String key) {
        ProducerEventHistory present = histories.get(key);
        if (present != null) return present;
        Long lost = retired.get(key);
        return new ProducerEventHistory(lost == null ? untrackedThrough : lost,
                lost == null ? "history_untracked" : "endpoint_history_evicted");
    }

    boolean retained(Object identity, Set<String> endpoints) {
        return owners.stream().anyMatch(owner -> owner.identity().get() == identity && owner.endpoints().containsAll(endpoints));
    }

    int retainedEndpoints() { return references.size(); }
    int historyCount() { return histories.size(); }
    int retiredCount() { return retired.size(); }
    long storedChars() { return histories.values().stream().mapToLong(value -> value.chars).sum(); }

    private ProducerEventHistory history(String key) {
        ProducerEventHistory present = histories.get(key);
        if (present != null) return present;
        Long lost = retired.remove(key);
        ProducerEventHistory created = new ProducerEventHistory(lost == null ? untrackedThrough : lost,
                lost == null ? "history_untracked" : "endpoint_history_evicted");
        histories.put(key, created);
        return created;
    }

    private void unreference(String key) {
        int count = references.getOrDefault(key, 0);
        if (count > 1) { references.put(key, count - 1); return; }
        references.remove(key);
        ProducerEventHistory history = histories.get(key);
        if (history != null) history.trim(false);
        cold.remove(key); cold.add(key);
    }

    private void releaseCollectedOwners() {
        var iterator = owners.iterator();
        while (iterator.hasNext()) {
            Owner owner = iterator.next();
            if (owner.identity().get() != null) continue;
            for (String key : owner.endpoints()) unreference(key);
            iterator.remove();
        }
        trimCold();
    }

    private void trimCold() {
        while (cold.size() > COLD_ENDPOINTS) {
            String key = cold.iterator().next(); cold.remove(key);
            ProducerEventHistory history = histories.remove(key);
            if (history == null || history.latest == 0) continue;
            retired.remove(key); retired.put(key, history.latest);
        }
        while (retired.size() > RETIRED_ENDPOINTS) {
            var first = retired.entrySet().iterator();
            untrackedThrough = Math.max(untrackedThrough, first.next().getValue()); first.remove();
        }
    }
}
