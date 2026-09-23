// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** 即使调用方替换了集合，工作线程也必须保留原始注册表快照。 */
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
