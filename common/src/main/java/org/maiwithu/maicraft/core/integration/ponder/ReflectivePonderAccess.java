// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.ponder;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Works with any plugin registered in the installed Ponder registry, without per-mod scene lists. */
public final class ReflectivePonderAccess implements PonderAccess {
    private static final String BASE = "net.createmod.ponder.";
    public record Api(Class<?> index, Class<?> sceneAccess, Class<?> story, Class<?> localization, Class<?> registry, Class<?> level) {}
    private interface Resolver { Api get() throws ReflectiveOperationException; }
    private static final class NotInstalled extends ReflectiveOperationException {}
    private final Resolver resolver;
    public ReflectivePonderAccess() { this(ReflectivePonderAccess.class.getClassLoader()); }
    public ReflectivePonderAccess(ClassLoader loader) { resolver = () -> load(loader); }
    public ReflectivePonderAccess(Api api) { resolver = () -> api; }

    private static Api load(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> index;
        try { index = Class.forName(BASE + "foundation.PonderIndex", true, loader); }
        catch (ClassNotFoundException absent) { throw new NotInstalled(); }
        return new Api(index, Class.forName(BASE + "api.registration.SceneRegistryAccess", true, loader),
                Class.forName(BASE + "api.registration.StoryBoardEntry", true, loader),
                Class.forName(BASE + "foundation.registration.PonderLocalization", true, loader),
                Class.forName(BASE + "foundation.registration.PonderSceneRegistry", true, loader),
                Class.forName(BASE + "api.level.PonderLevel", true, loader));
    }

    @Override public Snapshot snapshot() {
        try {
            Api api = resolver.get();
            Object registry = api.index().getMethod("getSceneAccess").invoke(null);
            Object entries = api.sceneAccess().getMethod("getRegisteredEntries").invoke(registry);
            if (!(entries instanceof Iterable<?> iterable)) return new Snapshot("api_unavailable", "Ponder entries are not iterable", List.of());
            List<Entry> result = new ArrayList<>(); Map<String, Integer> occurrences = new LinkedHashMap<>();
            int unreadable = 0;
            for (Object value : iterable) {
                try {
                    Object entry = ((Map.Entry<?, ?>) value).getValue();
                    String component = call(api, entry, "getComponent").toString(), schematic = call(api, entry, "getSchematicLocation").toString();
                    List<String> tags = new ArrayList<>();
                    for (Object tag : (Iterable<?>) call(api, entry, "getTags")) tags.add(tag.toString());
                    String identity = component + "\n" + schematic;
                    int occurrence = occurrences.merge(identity, 1, Integer::sum);
                    result.add(new Entry(key(identity + "\n" + occurrence), component, schematic, tags, entry));
                } catch (ReflectiveOperationException | RuntimeException | LinkageError invalidEntry) { unreadable++; }
            }
            return new Snapshot(unreadable > 0 ? "partial" : result.isEmpty() ? "registered_empty" : "available",
                    "Installed Ponder registry; no scenes compiled by discovery; unreadable entries=" + unreadable, result);
        } catch (NotInstalled absent) {
            return new Snapshot("not_installed", "Ponder is not installed", List.of());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return new Snapshot("api_unavailable", "Ponder registry cannot be read: " + reason(unavailable), List.of());
        }
    }

    @Override public PonderTranscript compile(Entry entry) {
        try {
            Api api = resolver.get(); Class<?> localizationType = api.localization();
            Object localization = localizationType.getConstructor().newInstance();
            // This is the same null-world compilation used by PonderLocalization.generateSceneLang.
            // Do not call SceneRegistryAccess.compile, scene.begin, scene.tick or any world instruction.
            Object scene = api.registry().getMethod("compileScene", localizationType, api.story(), api.level())
                    .invoke(null, localization, entry.nativeEntry(), null);
            Map<String, String> defaults = new LinkedHashMap<>();
            Object specific = localizationType.getField("specific").get(localization);
            for (var sceneEntry : ((Map<?, ?>) specific).entrySet()) {
                String[] id = sceneEntry.getKey().toString().split(":", 2);
                for (var text : ((Map<?, ?>) sceneEntry.getValue()).entrySet())
                    defaults.put(id[0] + ".ponder." + id[1] + "." + text.getKey(), String.valueOf(text.getValue()));
            }
            Object shared = api.index().getMethod("getLangAccess").invoke(null);
            for (var text : ((Map<?, ?>) shared.getClass().getField("shared").get(shared)).entrySet()) {
                String[] id = text.getKey().toString().split(":", 2);
                defaults.put(id[0] + ".ponder.shared." + id[1], String.valueOf(text.getValue()));
            }
            return PonderInstructionReader.read(scene, defaults);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            throw new IllegalStateException("Ponder narration compilation unavailable for " + entry.component() + ": " + reason(unavailable), unavailable);
        }
    }

    private static Object call(Api api, Object owner, String method) throws ReflectiveOperationException {
        return api.story().getMethod(method).invoke(owner);
    }
    private static String key(String value) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)), 0, 8); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String reason(Throwable failure) {
        if (failure instanceof InvocationTargetException invocation && invocation.getCause() != null) failure = invocation.getCause();
        return failure.getClass().getSimpleName() + (failure.getMessage() == null ? "" : ": " + failure.getMessage());
    }
}
