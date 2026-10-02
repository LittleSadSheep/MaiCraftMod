// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.container;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.maiwithu.maicraft.core.inventory.StockEvidence;

/** 每个存档独立保存开箱记忆；数量是上次真实菜单观察，只决定复访顺序，不保证现在仍有货。 */
public final class ContainerMemory {
    public record Entry(String dimension, Map<BlockPos, String> blocks, Map<ResourceLocation, Long> items,
                        long observedTick, long observedEpochMillis) {
        public Entry { blocks = Map.copyOf(blocks); items = Map.copyOf(items); }
        public String id() { return identity(dimension, blocks); }
        public int rank(List<ResourceLocation> requested) {
            return requested.stream().anyMatch(item -> items.getOrDefault(item, 0L) > 0) ? 0 : 2;
        }
        public Map<String, Object> receipt() {
            BlockPos at = first(blocks);
            Map<String, Long> contents = new LinkedHashMap<>();
            items.forEach((item, count) -> contents.put(item.toString(), count));
            return Map.of("container_memory_id", id(), "label", "箱子 " + at.getX() + "," + at.getY() + "," + at.getZ(),
                    "dimension", dimension, "coordinates", List.of(at.getX(), at.getY(), at.getZ()),
                    "last_observed_items", Map.copyOf(contents), "observed_at_tick", observedTick,
                    "observed_at_epoch_ms", observedEpochMillis, "stock_status", "historical_menu_observation");
        }
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Runnable changed;

    public ContainerMemory(Runnable changed) { this.changed = changed; }

    public Entry recall(String dimension, Map<BlockPos, String> blocks) {
        if (blocks.isEmpty()) return null;
        Entry entry = entries.get(identity(dimension, blocks));
        if (entry != null && entry.blocks().equals(blocks)) return entry;
        // 已看见箱体被拆换或单双箱连接改变时，作废这一处旧标识，下一次按未调查容器处理。
        if (removeOverlaps(dimension, blocks)) changed.run();
        return null;
    }

    public void observe(String dimension, Map<BlockPos, String> blocks, StockEvidence.Snapshot stock) {
        if (blocks.isEmpty()) return;
        Map<ResourceLocation, Long> contents = new LinkedHashMap<>();
        stock.stored().forEach((item, amount) -> { if (amount > 0) contents.put(item, amount); });
        // 开箱确认、拿走或放入后整份覆盖；空箱也保存，才能区分从未翻过与上次没有。
        Entry entry = new Entry(dimension, blocks, contents, stock.observedGameTick(), System.currentTimeMillis());
        removeOverlaps(dimension, blocks); entries.put(entry.id(), entry); changed.run();
    }

    private boolean removeOverlaps(String dimension, Map<BlockPos, String> blocks) {
        return entries.values().removeIf(entry -> entry.dimension().equals(dimension)
                && entry.blocks().keySet().stream().anyMatch(blocks::containsKey));
    }

    public JsonArray snapshot() {
        JsonArray rows = new JsonArray();
        for (Entry entry : entries.values()) {
            JsonObject row = new JsonObject(); row.addProperty("dimension", entry.dimension());
            row.addProperty("observed_tick", entry.observedTick()); row.addProperty("observed_epoch_ms", entry.observedEpochMillis());
            JsonArray blocks = new JsonArray();
            entry.blocks().forEach((at, block) -> {
                JsonObject cell = new JsonObject(); cell.addProperty("x", at.getX()); cell.addProperty("y", at.getY());
                cell.addProperty("z", at.getZ()); cell.addProperty("block_id", block); blocks.add(cell);
            });
            row.add("blocks", blocks); JsonObject items = new JsonObject();
            entry.items().forEach((item, count) -> items.addProperty(item.toString(), count)); row.add("items", items); rows.add(row);
        }
        return rows;
    }

    public void restore(JsonArray rows) {
        Map<String, Entry> restored = new LinkedHashMap<>();
        if (rows != null) for (var value : rows) {
            JsonObject row = value.getAsJsonObject(); String dimension = ResourceLocation.parse(row.get("dimension").getAsString()).toString();
            Map<BlockPos, String> blocks = new LinkedHashMap<>();
            for (var block : row.getAsJsonArray("blocks")) {
                var cell = block.getAsJsonObject(); BlockPos at = new BlockPos(cell.get("x").getAsBigDecimal().intValueExact(),
                        cell.get("y").getAsBigDecimal().intValueExact(), cell.get("z").getAsBigDecimal().intValueExact());
                if (blocks.put(at, ResourceLocation.parse(cell.get("block_id").getAsString()).toString()) != null)
                    throw new IllegalArgumentException("duplicate remembered container cell");
            }
            if (blocks.isEmpty()) throw new IllegalArgumentException("remembered container has no cells");
            Map<ResourceLocation, Long> items = new LinkedHashMap<>();
            row.getAsJsonObject("items").entrySet().forEach(item -> {
                long amount = item.getValue().getAsBigDecimal().longValueExact();
                if (amount <= 0) throw new IllegalArgumentException("invalid remembered item amount");
                items.put(ResourceLocation.parse(item.getKey()), amount);
            });
            Entry entry = new Entry(dimension, blocks, items, row.get("observed_tick").getAsLong(), row.get("observed_epoch_ms").getAsLong());
            if (restored.put(entry.id(), entry) != null) throw new IllegalArgumentException("duplicate remembered container");
        }
        // 整份文件解析成功后才切换，旧格式没有箱子字段时恢复为空记忆。
        entries.clear(); entries.putAll(restored);
    }

    public void clear() { entries.clear(); }
    public static String state(int rank) { return switch (rank) { case 0 -> "remembered_present"; case 2 -> "remembered_absent"; default -> "unvisited"; }; }
    private static BlockPos first(Map<BlockPos, String> blocks) { return blocks.keySet().stream().min(BlockPos::compareTo).orElseThrow(); }
    private static String identity(String dimension, Map<BlockPos, String> blocks) { return dimension + "/container/" + first(blocks).asLong(); }
}
