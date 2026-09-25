// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.maiwithu.maicraft.core.integration.machine.assembly.MachineInstallation;

/** 按固定蓝图顺序读取实际方块；比较只提供信息，不生成放置、拆除、导航或施工准入条件。 */
public final class MachineBlueprintDiff {
    public static final List<String> COUNTERS = List.of("matched", "missing", "wrong_block", "wrong_state", "unexpected", "part_mismatch", "unknown");
    private record Target(BlockPos position, BlockState desired, String partItem, String side, BiPredicate<Level, BlockState> matches) {}
    private final BlockPos anchor;
    private final List<Target> targets;

    public MachineBlueprintDiff(MachineConstructionPlan plan) {
        anchor = plan.anchor(); var cells = new LinkedHashMap<BlockPos, Target>();
        for (var target : plan.blocks()) cells.put(target.pos(), new Target(target.pos(), target.desiredState(), null, null,
                (world, actual) -> target.matches(actual)));
        // 传送带等原生安装按最终结构比较，不能拿开工时的准备轴与完成的带子对比。
        for (var installation : plan.installations()) installation.targets().forEach((at, state) ->
                cells.put(at, new Target(at, state, null, null, (world, actual) -> actual.equals(state))));
        var ordered = new ArrayList<>(cells.values());
        for (var part : plan.parts()) ordered.add(new Target(part.position(), null, part.spec().itemId(),
                part.spec().side() == null ? "center" : part.spec().side().getSerializedName(),
                (world, actual) -> MachineInstallation.matches(world, part.position(), part.spec())));
        ordered.sort(Comparator.comparing(Target::position).thenComparing(target -> target.side() == null ? "" : target.side()));
        targets = List.copyOf(ordered);
    }
    public int size() { return targets.size(); }

    // 每页只读指定的一段蓝图，调用方可在施工期间分页查看；没加载的格子保留未知，不按空气计算。
    public JsonObject page(Level world, String dimension, int offset, int limit) {
        if (offset < 0 || limit < 1) throw new IllegalArgumentException("invalid_blueprint_diff_page");
        int start = Math.min(offset, targets.size()), end = (int) Math.min(targets.size(), (long) start + limit);
        Map<String, Integer> counts = new LinkedHashMap<>(); COUNTERS.forEach(key -> counts.put(key, 0));
        var differences = new JsonArray(); boolean sameDimension = world.dimension().location().toString().equals(dimension);
        for (int index = start; index < end; index++) {
            Target target = targets.get(index); BlockState actual = null; String status, reason = "";
            if (!sameDimension || !world.isLoaded(target.position())) {
                status = "unknown"; reason = sameDimension ? "chunk_not_loaded" : "different_dimension";
            } else {
                try {
                    actual = world.getBlockState(target.position());
                    status = target.matches().test(world, actual) ? "matched"
                            : target.partItem() != null ? "part_mismatch"
                            : target.desired().isAir() ? "unexpected"
                            : actual.isAir() ? "missing"
                            : actual.getBlock() != target.desired().getBlock() ? "wrong_block" : "wrong_state";
                } catch (RuntimeException | LinkageError unavailable) {
                    status = "unknown"; reason = "observation_unavailable:" + unavailable.getClass().getSimpleName();
                }
            }
            counts.merge(status, 1, Integer::sum);
            if (status.equals("matched")) continue;
            var row = new JsonObject(); row.addProperty("target_index", index); row.addProperty("status", status);
            row.add("offset", offset(target.position().subtract(anchor)));
            if (target.partItem() == null) row.add("expected", state(target.desired()));
            else { var expected = new JsonObject(); expected.addProperty("part_item_id", target.partItem()); expected.addProperty("side", target.side()); row.add("expected", expected); }
            row.add("actual", actual == null ? JsonNull.INSTANCE : state(actual));
            if (!reason.isEmpty()) row.addProperty("reason", reason); differences.add(row);
        }
        var result = new JsonObject(); result.addProperty("schema", "machine_blueprint_diff.v1");
        result.addProperty("scope", "declared_block_states_and_native_parts");
        result.addProperty("extra_block_scope", "explicit_air_targets_only");
        result.addProperty("production_verified", false); result.addProperty("world_mutated", false);
        result.addProperty("observed_at_tick", world.getGameTime()); result.addProperty("total_targets", targets.size());
        result.addProperty("offset", start); result.addProperty("examined", end - start); counts.forEach(result::addProperty);
        result.add("differences", differences); result.addProperty("has_more", end < targets.size());
        if (end < targets.size()) result.addProperty("next_offset", end);
        boolean complete = start == 0 && end == targets.size() && counts.get("unknown") == 0;
        result.addProperty("comparison_complete", complete);
        result.add("structure_matches_blueprint", complete ? new JsonPrimitive(counts.get("matched") == targets.size()) : JsonNull.INSTANCE);
        return result;
    }
    private static JsonArray offset(BlockPos at) { var value = new JsonArray(); value.add(at.getX()); value.add(at.getY()); value.add(at.getZ()); return value; }
    private static JsonObject state(BlockState value) {
        var result = new JsonObject(); result.addProperty("block_id", BuiltInRegistries.BLOCK.getKey(value.getBlock()).toString());
        var properties = new JsonObject(); value.getValues().forEach((property, setting) -> properties.addProperty(property.getName(), name(property, setting)));
        result.add("properties", properties); return result;
    }
    @SuppressWarnings({"rawtypes", "unchecked"}) private static String name(Property property, Comparable value) { return property.getName(value); }
}
