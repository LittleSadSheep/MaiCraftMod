// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.ponder;

import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import org.maiwithu.maicraft.mcp.knowledge.KnowledgeLibrary;

/** Bounded immutable tutorial artifacts. Import only resolves previously returned, current resource URIs. */
public final class PonderBlueprintStore {
    public static final String PREFIX = "maicraft://knowledge/ponder/structure/";
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final Map<String, JsonObject> DOCUMENTS = new LinkedHashMap<>();
    private record Hooks(Runnable refresh, java.util.function.BiConsumer<String, Integer> reserve) {}
    private static volatile Hooks hooks = new Hooks(() -> {}, (scene, size) -> {});
    private static int bytes;
    private PonderBlueprintStore() {}

    public static String put(String sceneKey, JsonObject blueprint) {
        String encoded = blueprint.toString();
        String uri = PREFIX + sceneKey + "/" + KnowledgeLibrary.digest(encoded);
        synchronized (PonderBlueprintStore.class) { if (DOCUMENTS.containsKey(uri)) return uri; }
        int size = encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (size > MAX_BYTES) throw new IllegalStateException("One Ponder snapshot exceeds the 16 MiB evidence budget");
        hooks.reserve().accept(sceneKey, size);
        synchronized (PonderBlueprintStore.class) {
            if (DOCUMENTS.containsKey(uri)) return uri;
            if (!fits(size)) throw new IllegalStateException("Ponder active structure evidence exceeds the 16 MiB cache budget");
            DOCUMENTS.put(uri, blueprint.deepCopy()); bytes += size;
        }
        return uri;
    }

    public static synchronized JsonObject read(String uri) {
        JsonObject document = DOCUMENTS.get(uri);
        return document == null ? null : document.deepCopy();
    }

    /** Does not replay implicitly or interpret observed NBT as requested configuration. */
    public static JsonObject resolve(String uri) {
        hooks.refresh().run();
        JsonObject result = read(uri);
        if (result == null) throw new IllegalArgumentException("Unknown or stale Ponder blueprint URI; read its replay resource again");
        JsonObject evidence = result.getAsJsonObject("evidence");
        if (evidence == null || !evidence.get("projection_complete").getAsBoolean())
            throw new IllegalArgumentException("Ponder snapshot contains unresolved transforms or overlaps; inspect evidence and submit an edited blueprint");
        if (result.getAsJsonArray("blocks").isEmpty())
            throw new IllegalArgumentException("Ponder snapshot has no visible placeable blocks; choose another chapter");
        return result;
    }

    public static synchronized void clear() { DOCUMENTS.clear(); bytes = 0; }
    static void configure(Runnable environmentRefresh, java.util.function.BiConsumer<String, Integer> reserve) {
        hooks = new Hooks(environmentRefresh, reserve);
    }
    static synchronized boolean fits(int size) { return size >= 0 && size <= MAX_BYTES && bytes <= MAX_BYTES - size; }
    static synchronized void removeScene(String sceneKey) {
        var iterator = DOCUMENTS.entrySet().iterator();
        while (iterator.hasNext()) {
            var document = iterator.next();
            if (!document.getKey().startsWith(PREFIX + sceneKey + "/")) continue;
            bytes -= document.getValue().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length; iterator.remove();
        }
    }
}
