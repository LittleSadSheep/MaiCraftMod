// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.ponder;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;

/**
 * 管理最多八个教程回放，分客户端更新推进；世界、语言、资源或注册故事板变化时清掉旧结果，缓存不足先淘汰已结束的回放。
 */
public final class PonderReplayRuntime {
    private record Job(Object entry, PonderReplaySession session) {}
    private static final Map<String, Job> JOBS = new LinkedHashMap<>(16, .75f, true);
    private static Object resources, level;
    private static String language;
    private static ReflectivePonderAccess.Api activeApi;
    private PonderReplayRuntime() {}

    public static synchronized PonderReplaySession request(PonderAccess.Entry entry, ReflectivePonderAccess.Api api) throws ReflectiveOperationException {
        PonderBlueprintStore.configure(PonderReplayRuntime::refreshEnvironment, PonderReplayRuntime::makeRoom);
        refreshEnvironment(); activeApi = api; Job job = JOBS.get(entry.key());
        if (job != null && job.entry() != entry.nativeEntry()) { invalidate(); job = null; }
        if (job == null) {
            if (JOBS.size() >= 8 && !evictCompleted(entry.key()))
                throw new IllegalStateException("Eight Ponder scenes are still being extracted; wait for a running replay to finish");
            job = new Job(entry.nativeEntry(), PonderNativeReplay.create(entry, api)); JOBS.put(entry.key(), job);
        }
        return job.session();
    }

    /** Call once per client tick. A native callback is indivisible; the budget is checked between simulation ticks. */
    // 四毫秒预算在每个演示刻之间检查；单个回调和完整快照仍可能超过剩余时间，它不是强制中断时限。
    public static synchronized void tick() {
        if (JOBS.values().stream().noneMatch(job -> job.session().status().equals("running"))) return;
        refreshEnvironment(); long deadline = System.nanoTime() + 4_000_000;
        for (Job job : java.util.List.copyOf(JOBS.values())) {
            job.session().advance(64, deadline);
            if (System.nanoTime() >= deadline) break;
        }
    }

    // 读取结果前和回放途中都检查环境；资源管理器即使还是同一对象，注册的重载通知也会清掉旧快照。
    public static synchronized void refreshEnvironment() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) return;
        Object currentResources = minecraft.getResourceManager();
        String currentLanguage = minecraft.getLanguageManager().getSelected();
        if (resources != currentResources || level != minecraft.level || !java.util.Objects.equals(language, currentLanguage)) {
            invalidate();
            if (resources != currentResources && currentResources instanceof ReloadableResourceManager reloadable)
                reloadable.registerReloadListener((ResourceManagerReloadListener) manager -> invalidate());
            resources = currentResources; level = minecraft.level; language = currentLanguage;
        }
        if (!JOBS.isEmpty() && activeApi != null) {
            try {
                Object registry = activeApi.index().getMethod("getSceneAccess").invoke(null);
                var registered = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Object, Boolean>());
                for (Object row : (Iterable<?>) activeApi.sceneAccess().getMethod("getRegisteredEntries").invoke(registry))
                    registered.add(((Map.Entry<?, ?>) row).getValue());
                if (JOBS.values().stream().anyMatch(job -> !registered.contains(job.entry()))) invalidate();
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { invalidate(); }
        }
    }

    /** Also available to integrations that rebuild Ponder's registry without a resource reload. */
    public static synchronized void invalidate() { JOBS.clear(); PonderBlueprintStore.clear(); }

    static synchronized void makeRoom(String sceneKey, int bytes) {
        while (!PonderBlueprintStore.fits(bytes) && evictCompleted(sceneKey)) { /* Evict whole sessions so replay links can be regenerated. */ }
    }

    private static boolean evictCompleted(String except) {
        var iterator = JOBS.entrySet().iterator();
        while (iterator.hasNext()) {
            var job = iterator.next();
            if (job.getKey().equals(except) || job.getValue().session().status().equals("running")) continue;
            PonderBlueprintStore.removeScene(job.getKey()); iterator.remove(); return true;
        }
        return false;
    }
}
