// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.process;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.core.task.base.NativeConsumptionTaskRecord;
import org.maiwithu.maicraft.task.TaskRecord;
import java.util.Collections;

/** 统一使用机器按原生机制分派；同一水中转化适配器处理安装配方表中的不同产物，不新增逐配方能力。 */
public final class NativeProcessRegistry {
    public static final String KNOWLEDGE_URI = "maicraft://knowledge/processes";
    private record Entry(NativeProcessAdapter adapter, String namespace) {}
    private static final Map<String, Entry> ENTRIES = entries();
    private NativeProcessRegistry() {}

    private static Map<String, Entry> entries() {
        var out = new LinkedHashMap<String, Entry>();
        register(out, new MinecraftEnchantProcessAdapter(), "enchant");
        register(out, new WorldTransformProcessAdapter(), "world-process");
        return Collections.unmodifiableMap(out);
    }
    private static void register(Map<String, Entry> entries, NativeProcessAdapter adapter, String namespace) {
        if (entries.putIfAbsent(adapter.id(), new Entry(adapter, namespace)) != null)
            throw new IllegalStateException("duplicate native process " + adapter.id());
    }
    public static NativeProcessAdapter adapter(String id) {
        Entry entry = ENTRIES.get(id);
        if (entry == null) throw new IllegalArgumentException("unsupported_native_process: " + id + "; inspect the site for available process contracts");
        return entry.adapter();
    }
    public static String consumptionNamespace(String id) { adapter(id); return ENTRIES.get(id).namespace(); }
    public static void validate(NativeProcessRequest request) { adapter(request.process()).validate(request.parameters()); }
    public static void requireAvailable(NativeProcessRequest request) {
        validate(request);
        if (!adapter(request.process()).available()) throw new IllegalArgumentException("native_process_unavailable: " + request.process());
    }
    public static TaskRecord createTask(String callId, long deadline, LocalPlayer player, BlockPos anchor, NativeProcessRequest request) {
        requireAvailable(request); var adapter = adapter(request.process()); BlockPos position = request.position(anchor);
        if (!adapter.matches(player, position)) throw new IllegalArgumentException("native_process_site_mismatch: " + request.process());
        TaskRecord task = adapter.createTask(callId, deadline, player, position, request.parameters());
        // 当前注册机制都包含一次不可盲重试的原生消费，必须交回能绑定同一持久屏障的任务单。
        if (!(task instanceof NativeConsumptionTaskRecord consumption)
                || !consumption.consumptionNamespace().equals(consumptionNamespace(request.process())))
            throw new IllegalStateException("native process task has no matching consumption boundary");
        return task;
    }
    public static JsonArray inspect(LocalPlayer player, BlockPos position) {
        JsonArray out = new JsonArray();
        for (var entry : ENTRIES.values()) {
            NativeProcessAdapter adapter = entry.adapter();
            if (!adapter.available() || !adapter.matches(player, position)) continue;
            JsonObject row = new JsonObject(); row.addProperty("process", adapter.id()); row.addProperty("available", true);
            row.add("contract", adapter.contract().deepCopy()); row.add("observation", adapter.inspect(player, position).deepCopy());
            row.addProperty("knowledge_uri", KNOWLEDGE_URI); out.add(row);
        }
        return out;
    }
    public static JsonArray contracts() {
        JsonArray out = new JsonArray();
        for (var entry : ENTRIES.values()) { JsonObject row = entry.adapter().contract().deepCopy(); row.addProperty("available", entry.adapter().available()); out.add(row); }
        return out;
    }
}
