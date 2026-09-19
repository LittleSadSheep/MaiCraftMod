// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.blueprint;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.maiwithu.maicraft.core.task.build.BuildTaskRecord;
import org.maiwithu.maicraft.core.build.BuildingBudgets;

/** 仅编码本项目原生确认过的临时支撑；恢复按记录逐格核对，绝不扫描泥土推断所有权。 */
public final class BuildProjectScaffolds {
    private BuildProjectScaffolds() {}

    public static JsonArray encode(Map<BlockPos, BlockState> scaffolds) {
        // 支撑账容量跟随配置，允许大型工程续建；这只放宽记录数量，不替角色批准额外搭建动作。
        if (scaffolds.size() > BuildingBudgets.current().maxScaffolds()) throw new IllegalArgumentException("too many saved project scaffolds");
        JsonArray rows = new JsonArray();
        scaffolds.entrySet().stream().sorted(Map.Entry.comparingByKey(java.util.Comparator.comparingLong(BlockPos::asLong)))
                .forEach(entry -> {
                    BlockPos at = entry.getKey(); BlockState state = entry.getValue(); requireState(state);
                    JsonObject row = new JsonObject(); row.addProperty("x", at.getX()); row.addProperty("y", at.getY()); row.addProperty("z", at.getZ());
                    row.addProperty("block_id", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
                    JsonObject properties = new JsonObject();
                    state.getValues().forEach((property, value) -> properties.addProperty(property.getName(), name(property, value)));
                    row.add("properties", properties); rows.add(row);
                });
        return rows;
    }

    public static Map<BlockPos, BlockState> decode(JsonArray rows) {
        // 恢复时使用相同数量预算，身份、完整状态和重复坐标仍逐条核验。
        if (rows == null || rows.size() > BuildingBudgets.current().maxScaffolds()) throw new IllegalArgumentException("invalid saved project scaffold count");
        Map<BlockPos, BlockState> result = new LinkedHashMap<>();
        for (var value : rows) {
            JsonObject row = value.getAsJsonObject();
            if (!row.keySet().equals(java.util.Set.of("x", "y", "z", "block_id", "properties")))
                throw new IllegalArgumentException("invalid saved scaffold fields");
            BlockPos at = new BlockPos(coordinate(row, "x"), coordinate(row, "y"), coordinate(row, "z"));
            ResourceLocation id = ResourceLocation.parse(row.get("block_id").getAsString());
            BlockState state = BuiltInRegistries.BLOCK.getOptional(id)
                    .orElseThrow(() -> new IllegalArgumentException("saved scaffold block is unavailable: " + id)).defaultBlockState();
            JsonObject properties = row.getAsJsonObject("properties");
            if (properties.size() != state.getProperties().size()) throw new IllegalArgumentException("saved scaffold state is incomplete");
            for (var entry : properties.entrySet()) {
                Property<?> property = state.getBlock().getStateDefinition().getProperty(entry.getKey());
                if (property == null) throw new IllegalArgumentException("unknown saved scaffold property");
                state = set(state, property, entry.getValue().getAsString());
            }
            requireState(state);
            if (result.put(at, state) != null) throw new IllegalArgumentException("duplicate saved scaffold position");
        }
        return Map.copyOf(result);
    }

    /** 先完成全部检查再交回可恢复清单；遇到未知或替换方块时不返回半份账，也不写空账覆盖证据。 */
    public static Map<BlockPos, BlockState> observed(Map<BlockPos, BlockState> saved, Level level, List<BuildTaskRecord.Target> targets) {
        var permanent = targets.stream().filter(target -> !target.desiredState().isAir()).map(BuildTaskRecord.Target::pos)
                .collect(java.util.stream.Collectors.toSet());
        Map<BlockPos, BlockState> result = new LinkedHashMap<>();
        for (var entry : saved.entrySet()) {
            BlockPos at = entry.getKey();
            if (level.isOutsideBuildHeight(at) || !level.isLoaded(at)) throw new IllegalArgumentException("saved scaffold is not observed: " + at);
            BlockState live = level.getBlockState(at);
            if (live.hasBlockEntity() || level.getBlockEntity(at) != null) throw new IllegalArgumentException("saved scaffold contains a block entity: " + at);
            // 原来确认的支撑已经变空气，只登记这项移除观察；其他不同方块不能当成已经清理。
            if (live.isAir()) continue;
            if (permanent.contains(at) || !entry.getValue().equals(live)) throw new IllegalArgumentException("saved scaffold changed or conflicts with a permanent target: " + at);
            result.put(at, live);
        }
        return Map.copyOf(result);
    }

    private static void requireState(BlockState state) {
        if (state == null || state.isAir() || state.hasBlockEntity() || !state.getFluidState().isEmpty())
            throw new IllegalArgumentException("saved scaffold must be a non-container dry block");
    }
    private static int coordinate(JsonObject row, String key) {
        try {
            int value = row.get(key).getAsBigDecimal().intValueExact();
            if (!key.equals("y") && Math.abs((long) value) > 30_000_000) throw new IllegalArgumentException("saved scaffold outside world bounds");
            return value;
        } catch (ArithmeticException invalid) { throw new IllegalArgumentException("saved scaffold coordinate must be an integer", invalid); }
    }
    private static <T extends Comparable<T>> BlockState set(BlockState state, Property<T> property, String value) {
        return state.setValue(property, property.getValue(value).orElseThrow(() -> new IllegalArgumentException("invalid saved scaffold property value")));
    }
    @SuppressWarnings({"rawtypes", "unchecked"}) private static String name(Property property, Comparable value) { return property.getName(value); }
}
