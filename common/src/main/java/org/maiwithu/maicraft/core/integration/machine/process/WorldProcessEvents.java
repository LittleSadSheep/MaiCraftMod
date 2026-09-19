// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.process;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.client.server.ClientRequestReceipt;
import org.maiwithu.maicraft.client.server.ServerAssistClient;
import org.maiwithu.maicraft.core.integration.machine.runtime.ProductionEventCursor;

/** 世界内加工复用现有生产日志；投料前固定证据模式并登记完整区域，运行中缺页或失去后端时不能静默降低证明标准。 */
final class WorldProcessEvents {
    private static final String OPERATION = "machine.production_events";
    private final List<List<BlockPos>> groups = new ArrayList<>();
    private final ProductionEventCursor cursor;
    private final String dimension;
    final boolean nativeEvents;
    private ClientRequestReceipt pending;
    private final java.util.Set<Integer> requestedGroups = new java.util.LinkedHashSet<>();
    private int group, retained;
    private boolean baselineDone, closed;

    WorldProcessEvents(List<BlockPos> positions, String dimension) {
        this.dimension = dimension; nativeEvents = available();
        for (int start = 0; start < positions.size(); start += 4)
            groups.add(List.copyOf(positions.subList(start, Math.min(positions.size(), start + 4))));
        cursor = new ProductionEventCursor(groups.size());
    }

    static boolean available() {
        if (!ServerAssistClient.serverSupported(OPERATION)) return false;
        JsonObject report = ServerAssistClient.capabilityReport();
        JsonObject operations = report.getAsJsonObject("server_operations");
        if (operations == null || !operations.has(OPERATION)) return false;
        JsonObject limits = operations.getAsJsonObject(OPERATION).getAsJsonObject("limits");
        return limits != null && limits.has("world_transform_events") && limits.get("world_transform_events").getAsBoolean();
    }

    boolean baseline() {
        if (!nativeEvents) { baselineDone = true; return true; }
        if (baselineDone) return true;
        JsonObject page = request(group, group == 0);
        if (page == null) return false;
        if (!page.has("retention") || !page.getAsJsonObject("retention").get("retained").getAsBoolean())
            throw new IllegalStateException("world_process_event_region_not_retained");
        if (group == 0) {
            cursor.baseline(page);
            if (!cursor.scope().startsWith(dimension + ":")) throw new IllegalStateException("world_process_event_world_changed");
        } else cursor.baselineGroup(page);
        retained = ++group;
        if (group == groups.size()) { group = 0; baselineDone = true; }
        return baselineDone;
    }

    List<JsonObject> poll() {
        if (!nativeEvents) return List.of();
        if (!baselineDone) throw new IllegalStateException("world_process_event_baseline_missing");
        JsonObject page = request(group, false); if (page == null) return List.of();
        var batch = cursor.page(group++, page);
        if (batch == null) return List.of();
        group = 0; return batch.events();
    }

    private JsonObject request(int index, boolean baseline) {
        if (closed || !available()) throw new IllegalStateException("world_process_native_event_support_lost");
        if (pending == null) {
            JsonObject body = body(index); if (baseline) body.addProperty("baseline", true);
            requestedGroups.add(index);
            pending = ServerAssistClient.submit(OPERATION, body, false); return null;
        }
        var observed = pending.snapshot(); if (!observed.settled()) return null;
        pending = null;
        if (observed.status() != ClientRequestReceipt.Status.SUCCEEDED || observed.backend() != ClientRequestReceipt.Backend.SERVER)
            throw new IllegalStateException("world_process_native_event_read_failed: " + observed.code());
        return observed.result();
    }

    private JsonObject body(int index) {
        JsonObject body = new JsonObject(); JsonArray points = new JsonArray();
        for (BlockPos pos : groups.get(index)) {
            JsonObject point = new JsonObject(); point.addProperty("x", pos.getX()); point.addProperty("y", pos.getY()); point.addProperty("z", pos.getZ()); points.add(point);
        }
        body.add("positions", points);
        if (cursor.scope() != null) { body.addProperty("scope", cursor.scope()); body.addProperty("after_sequence", cursor.sequence()); }
        return body;
    }

    void close() {
        if (closed) return; closed = true;
        if (!nativeEvents) return;
        // 结束只释放本连接已经登记的只读观察，不关闭机器，也不取消正在进行的原生转化。
        // 基线请求可能已经在服务端登记、但回包还没到；连同这些已请求分组一起释放，避免取消时泄漏保留额度。
        // 此时父任务即将结束；释放请求不绑定该任务，否则下一刻所有权清理会在发出前取消它。
        for (int i : requestedGroups) try {
            JsonObject body = body(i); body.addProperty("release_watch", true); ServerAssistClient.submit(OPERATION, body, false, null);
        } catch (RuntimeException ignored) { }
    }
}
