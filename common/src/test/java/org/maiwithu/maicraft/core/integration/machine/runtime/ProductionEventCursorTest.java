// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public final class ProductionEventCursorTest {
    public static void main(String[] args) {
        var cursor = new ProductionEventCursor(2); cursor.baseline(page(0, 0, false));
        check(cursor.page(0, page(10, 20, true, 8)) == null, "Cannot advance before all groups return");
        var first = cursor.page(1, page(5, 20, true, 3));
        check(first.sequence() == 5 && first.events().size() == 1 && sequence(first.events().getFirst()) == 3, "Minimum watermark must defer events above other groups' coverage");
        check(!first.complete(), "Truncated history cannot be presented as caught up");
        cursor.page(0, page(20, 20, false, 8, 12));
        var second = cursor.page(1, page(20, 20, false, 8));
        check(second.complete() && second.events().size() == 2 && sequence(second.events().getFirst()) == 8, "Deferred events must be recovered exactly once next round");
        var loss = new ProductionEventCursor(1); loss.baseline(page(0, 0, false));
        JsonObject gap = page(20, 20, false); gap.addProperty("gap", true);
        rejects(() -> loss.page(0, gap));
        JsonObject world = page(20, 20, false); world.addProperty("scope", "other-world");
        rejects(() -> loss.page(0, world));
        var conflict = new ProductionEventCursor(2); conflict.baseline(page(0, 0, false));
        conflict.page(0, page(10, 10, false, 3));
        JsonObject changed = page(10, 10, false, 3); changed.getAsJsonArray("events").get(0).getAsJsonObject().addProperty("producer", "different");
        rejects(() -> conflict.page(1, changed));
        System.out.println("ProductionEventCursorTest passed");
    }

    private static JsonObject page(long next, long latest, boolean truncated, long... sequence) {
        var page = new JsonObject(); page.addProperty("scope", "world-journal"); page.addProperty("gap", false);
        page.addProperty("next_sequence", next); page.addProperty("latest_sequence", latest); page.addProperty("tick", 100 + latest);
        page.addProperty("truncated", truncated); JsonArray events = new JsonArray();
        for (long value : sequence) { var event = new JsonObject(); event.addProperty("scope", "world-journal"); event.addProperty("sequence", value); event.addProperty("tick", 100 + value); events.add(event); }
        page.add("events", events); return page;
    }
    private static long sequence(JsonObject event) { return event.get("sequence").getAsLong(); }
    private static void rejects(Runnable task) { try { task.run(); } catch (IllegalStateException expected) { return; } throw new AssertionError("Invalid event continuity was accepted"); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
