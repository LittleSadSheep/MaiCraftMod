// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.agent.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 只检查注册表，不调用工具；失败登记必须保留原实例、分类和顺序。 */
public final class ToolRegistryTest {
    public static void main(String[] args) {
        List<MaiCraftTool> before = ToolRegistry.all();
        String prefix = "registry_test_" + UUID.randomUUID().toString().replace("-", "");
        var original = new Stub(prefix + "a", MaiCraftTool.Residency.DEFERRED);
        var replacement = new Stub(original.name(), MaiCraftTool.Residency.RESIDENT);
        var next = new Stub(prefix + "b", MaiCraftTool.Residency.DEFERRED);
        try {
            ToolRegistry.register(original);
            ToolRegistry.register(next);
            List<MaiCraftTool> expected = new ArrayList<>(before);
            expected.add(original); expected.add(next);
            rejectedDuplicate(replacement);
            check(ToolRegistry.get(original.name()) == original && ToolRegistry.resolve(original.name()) == original,
                    "a duplicate rejected by register must not replace the existing lookup");
            check(ToolRegistry.all().equals(expected) && ToolRegistry.size() == before.size() + 2,
                    "a rejected registration must preserve membership and insertion order");
            check(ToolRegistry.deferred().contains(original) && !ToolRegistry.resident().contains(replacement),
                    "the rejected object's residency must not enter the registry");
            rejectedDuplicate(original);
            check(ToolRegistry.get(original.name()) == original, "rejecting the same object is also non-mutating");
            try {
                ToolRegistry.register(new Stub("invalid/name", MaiCraftTool.Residency.RESIDENT));
                throw new AssertionError("invalid tool name was accepted");
            } catch (IllegalArgumentException expectedFailure) { }
            check(ToolRegistry.all().equals(expected), "invalid names must leave all registered tools intact");
            check(ToolRegistry.remove(original.name()) == original, "explicit removal returns the original instance");
            ToolRegistry.register(replacement);
            check(ToolRegistry.get(original.name()) == replacement,
                    "a name becomes available again after explicit removal");
        } finally {
            ToolRegistry.remove(original.name()); ToolRegistry.remove(next.name());
        }
        check(ToolRegistry.all().equals(before), "the test must restore the preexisting registry");
        System.out.println("ToolRegistryTest: rejected duplicate names preserve the original registration");
    }

    private static void rejectedDuplicate(MaiCraftTool tool) {
        try {
            ToolRegistry.register(tool);
            throw new AssertionError("duplicate name was accepted");
        } catch (IllegalStateException expected) {
            check(expected.getMessage().contains(tool.name()), "duplicate diagnostics must identify the rejected name");
        }
    }

    private record Stub(String name, MaiCraftTool.Residency residency) implements MaiCraftTool {
        public String description() { return "registry fixture"; }
        public Map<String, Object> parameterSchema() { return Map.of(); }
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
