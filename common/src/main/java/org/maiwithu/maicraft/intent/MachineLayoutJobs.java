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

/** 把耗时的机器布局计算放到后台；后台只读复制好的方块与物品规则，不直接读取会变化的游戏世界。 */
final class MachineLayoutJobs {
    private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(2), runnable -> {
                Thread thread = new Thread(runnable, "MaiCraft-machine-layout"); thread.setDaemon(true); return thread;
            });
    private static final Map<String, Future<SemanticMachineLayout.Result>> JOBS = new LinkedHashMap<>();
    private static WeakReference<Object> world = new WeakReference<>(null);
    private static SemanticMachineLayout.Registry registry;
    private MachineLayoutJobs() {}

    /** 返回 null 表示还在计算，下个游戏刻再来问；相同设计可以复用已算好的结果。 */
    static SemanticMachineLayout.Result poll(LocalPlayer player, JsonObject design) {
        // 换世界时取消旧世界的计算，并重新抄一份当前物品／方块规则，防止把旧注册信息带过来。
        if (world.get() != player.level()) {
            JOBS.values().forEach(future -> future.cancel(true)); JOBS.clear(); EXECUTOR.purge();
            world = new WeakReference<>(player.level()); registry = snapshotRegistry();
        }
        String key = design.toString();
        Future<SemanticMachineLayout.Result> future = JOBS.get(key);
        if (future == null) {
            // 同时最多缓存两份布局计算；新请求超出时取消最早那份，给新设计让位。
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

    /** 在游戏线程上复制方块属性及可选值，后台只拿字符串和不可修改的集合。 */
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
        // 再复制一层嵌套集合，调用者后来改原列表也不会让正在后台算的规则突然变化。
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
