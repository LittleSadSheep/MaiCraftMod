// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp;

import com.google.gson.JsonObject;

public final class SemanticAbilityAvailabilityTest {
    public static void main(String[] args) {
        var missing = new JsonObject();
        SemanticAbilityAvailability.describe(missing, "maicraft:connect_mechanical_power", false, true);
        check(missing.get("registered").getAsBoolean() && !missing.get("supported").getAsBoolean()
                && !missing.get("available").getAsBoolean(), "missing mod changes support without removing a registered contract");
        var alive = new JsonObject();
        SemanticAbilityAvailability.describe(alive, "maicraft:build_machine", true, true);
        check(alive.get("supported").getAsBoolean() && alive.get("available").isJsonNull(),
                "implemented ability cannot claim target, permissions and material preconditions are satisfied");
        var dead = new JsonObject();
        SemanticAbilityAvailability.describe(dead, "maicraft:build_machine", true, false);
        check(!dead.get("available").getAsBoolean(), "missing live body makes a mutating ability unavailable");
        var assistance = new JsonObject();
        assistance.add("operations", new JsonObject());
        SemanticAbilityAvailability.production(alive, "maicraft:build_machine", assistance);
        check(!alive.getAsJsonObject("production_enhancement").get("supported").getAsBoolean(),
                "ordinary construction registration does not imply server-backed production evidence");
        System.out.println("SemanticAbilityAvailabilityTest: passed");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
