// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;

/** 单个响应共享的资源页预算；在添加任意数据组件载荷前先做限制。 */
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
        int size = resource.toString().getBytes(StandardCharsets.UTF_8).length;
        if (size > 24_000) {
            // 保留完整身份哈希，同时避免数据组件载荷过大而阻塞后续所有页面。
            resource = resource.deepCopy();
            resource.remove("identity");
            resource.addProperty("identity_details", "omitted_payload_limit");
            size = resource.toString().getBytes(StandardCharsets.UTF_8).length;
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
