// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** One response-wide resource page, bounded before adding arbitrary component payloads. */
public final class SnapshotBudget {
    private final int offset;
    private final int limit;
    private int seen;
    private int emitted;
    private int bytes;
    private boolean truncated;
    private boolean pageFull;
    private int nextOffset;

    public SnapshotBudget(int offset, int limit) { this.offset = offset; this.limit = limit; this.nextOffset = offset; }

    public void add(JsonArray destination, JsonObject resource) {
        int index = seen++;
        if (index < offset) return;
        if (pageFull) { truncated = true; return; }
        int size = resource.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (size > 24_000) {
            // Retain the full identity hash without allowing a component payload to stall every future page.
            resource = resource.deepCopy();
            resource.remove("identity");
            resource.addProperty("identity_details", "omitted_payload_limit");
            size = resource.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            truncated = true;
        }
        if (emitted >= limit || bytes + size > 24_000) { truncated = true; pageFull = true; return; }
        destination.add(resource);
        emitted++;
        bytes += size;
        nextOffset = index + 1;
    }

    public void truncate() { truncated = true; }
    public boolean truncated() { return truncated; }
    public int nextOffset() { return nextOffset; }
    public int seen() { return seen; }
}
