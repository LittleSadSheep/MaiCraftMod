// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** A worker must retain its exact registry snapshot even if the caller replaces its collections. */
public final class MachineLayoutJobsTest {
    public static void main(String[] args) {
        Set<String> directions = new HashSet<>(Set.of("north", "south"));
        Map<String, Set<String>> properties = new HashMap<>(Map.of("facing", directions));
        Map<String, Map<String, Set<String>>> blocks = new HashMap<>(Map.of("mod:machine", properties));
        Set<String> items = new HashSet<>(Set.of("mod:part"));
        var registry = MachineLayoutJobs.frozenRegistry(blocks, items);
        directions.clear(); properties.clear(); blocks.clear(); items.clear();
        if (!registry.blockExists("mod:machine") || !registry.itemExists("mod:part")
                || !registry.supportsState("mod:machine", Map.of("facing", "north"))
                || registry.supportsState("mod:machine", Map.of("facing", "east"))
                || registry.supportsState("mod:missing", Map.of())) throw new AssertionError("mutable or permissive registry snapshot");
        System.out.println("MachineLayoutJobsTest: passed");
    }
}
