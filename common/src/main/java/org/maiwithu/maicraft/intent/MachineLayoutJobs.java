// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonObject;
import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.properties.Property;
import org.maiwithu.maicraft.core.integration.machine.layout.SemanticMachineLayout;

/** Large pure layout searches never occupy the client tick or access live registry/world objects. */
final class MachineLayoutJobs {
    private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(2), runnable -> {
                Thread thread = new Thread(runnable, "MaiCraft-machine-layout"); thread.setDaemon(true); return thread;
            });
    private static final Map<String, Future<SemanticMachineLayout.Result>> JOBS = new LinkedHashMap<>();
    private static WeakReference<Object> world = new WeakReference<>(null);
    private static SemanticMachineLayout.Registry registry;
    private MachineLayoutJobs() {}

    /** Null means a client tick should yield. The bounded cache also retains design previews for build. */
    static SemanticMachineLayout.Result poll(LocalPlayer player, JsonObject design) {
        if (world.get() != player.level()) {
            JOBS.values().forEach(future -> future.cancel(true)); JOBS.clear(); EXECUTOR.purge();
            world = new WeakReference<>(player.level()); registry = snapshotRegistry();
        }
        String key = design.toString();
        Future<SemanticMachineLayout.Result> future = JOBS.get(key);
        if (future == null) {
            while (JOBS.size() >= 2) {
                String eldest = JOBS.keySet().iterator().next(); JOBS.remove(eldest).cancel(true);
            }
            EXECUTOR.purge();
            JsonObject frozen = design.deepCopy();
            SemanticMachineLayout.Registry facts = registry;
            future = EXECUTOR.submit(() -> SemanticMachineLayout.compile(frozen, SemanticMachineLayout.MAX_RADIUS, facts));
            JOBS.put(key, future);
            return null;
        }
        if (!future.isDone()) return null;
        try {
            var result = future.get();
            return new SemanticMachineLayout.Result(result.buildable(), result.blueprint().deepCopy(), result.report().deepCopy());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new IllegalArgumentException("machine layout polling interrupted", interrupted);
        } catch (ExecutionException failure) {
            JOBS.remove(key);
            throw new IllegalArgumentException("machine layout compilation failed: " + failure.getCause().getMessage(), failure.getCause());
        }
    }

    /** Only strings and immutable sets cross the worker boundary, never Minecraft callbacks. */
    static SemanticMachineLayout.Registry snapshotRegistry() {
        Map<String, Map<String, Set<String>>> blocks = new LinkedHashMap<>();
        BuiltInRegistries.BLOCK.forEach(block -> {
            Map<String, Set<String>> properties = new LinkedHashMap<>();
            block.getStateDefinition().getProperties().forEach(property -> properties.put(property.getName(), values(property)));
            blocks.put(BuiltInRegistries.BLOCK.getKey(block).toString(), Map.copyOf(properties));
        });
        Set<String> items = BuiltInRegistries.ITEM.keySet().stream().map(Object::toString).collect(Collectors.toUnmodifiableSet());
        return frozenRegistry(Map.copyOf(blocks), items);
    }

    static SemanticMachineLayout.Registry frozenRegistry(Map<String, Map<String, Set<String>>> blockProperties, Set<String> itemIds) {
        Map<String, Map<String, Set<String>>> frozen = new LinkedHashMap<>();
        blockProperties.forEach((id, properties) -> {
            Map<String, Set<String>> copy = new LinkedHashMap<>();
            properties.forEach((name, values) -> copy.put(name, Set.copyOf(values))); frozen.put(id, Map.copyOf(copy));
        });
        Map<String, Map<String, Set<String>>> blocks = Map.copyOf(frozen);
        Set<String> items = Set.copyOf(itemIds);
        return new SemanticMachineLayout.Registry() {
            public boolean blockExists(String id) { return blocks.containsKey(id); }
            public boolean itemExists(String id) { return items.contains(id); }
            public boolean supportsState(String id, Map<String, String> properties) {
                var supported = blocks.get(id);
                return supported != null && properties.entrySet().stream()
                        .allMatch(entry -> supported.getOrDefault(entry.getKey(), Set.of()).contains(entry.getValue()));
            }
        };
    }

    private static <T extends Comparable<T>> Set<String> values(Property<T> property) {
        return property.getPossibleValues().stream().map(property::getName).collect(Collectors.toUnmodifiableSet());
    }
}
