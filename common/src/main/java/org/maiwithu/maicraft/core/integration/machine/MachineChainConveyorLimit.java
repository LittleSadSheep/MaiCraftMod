// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import org.maiwithu.maicraft.core.blueprint.ConstructionOwnership;
import org.maiwithu.maicraft.core.integration.machine.catalog.ClientMachineCatalog;
import org.maiwithu.maicraft.core.integration.machine.catalog.MachineBlueprint;
import org.maiwithu.maicraft.intent.IntentRuntime;

/** 用户明确要求每台机器最多一台锁链传动轮；跨施工任务累计，保留原生拆改与失败后的已完成效果。 */
public final class MachineChainConveyorLimit {
    public static final String BLOCK = "create:chain_conveyor";
    public static final int MAXIMUM = 1;
    public record Scope(String key, MachineBlueprint blueprint) {}
    public record Check(int existing, int resulting, int additions, int unloaded, boolean allowed) {
        public Map<String, Object> facts() {
            return Map.of("maximum", MAXIMUM, "existing_or_declared", existing, "resulting", resulting,
                    "new_wheels", additions, "unloaded_recorded_wheels", unloaded, "allowed", allowed);
        }
        public void requireAllowed() {
            if (!allowed) throw new IllegalArgumentException("machine_chain_conveyor_limit: " + facts());
        }
    }
    private MachineChainConveyorLimit() {}

    public static Scope bind(LocalPlayer player, String task, String label, BlockPos at) {
        return bind(player, task, label, at, false);
    }
    public static Scope bind(LocalPlayer player, String task, String label, BlockPos at, boolean wholeBlueprint) {
        String requested = reference(task);
        var blueprint = ClientMachineCatalog.blueprint(player, requested == null ? label : requested, at).orElse(null);
        // 新建的另一台具名机器不继承相邻机器的额度；只有接线接口允许反查所属整机范围。
        if (blueprint == null && !wholeBlueprint) blueprint = ClientMachineCatalog.containing(player, at).orElse(null);
        String name = blueprint == null ? label : blueprint.label();
        BlockPos anchor = blueprint == null ? at : new BlockPos(blueprint.anchor().x(), blueprint.anchor().y(), blueprint.anchor().z());
        String key = player.level().dimension().location() + "|" + anchor.asLong() + "|" + (name == null ? "" : name.toLowerCase(Locale.ROOT));
        var scope = new Scope(key, blueprint);
        // 先迁移能由原任务精确追溯的旧接线，再绑定本任务，避免重启和失败续作重置轮数。
        for (var row : ConstructionOwnership.placements(player)) if (row.machine() == null) {
            String oldReference = reference(row.task());
            boolean same = blueprint != null && (blueprint.id().equals(oldReference) || blueprint.label().equalsIgnoreCase(oldReference));
            if (same || blueprint != null && inside(blueprint, row.position()))
                ConstructionOwnership.bindMachineTask(player, row.task(), key);
        }
        ConstructionOwnership.bindMachineTask(player, task, key);
        return scope;
    }

    private static String reference(String task) {
        if (task == null || !task.startsWith("intent-") || task.length() < 43) return null;
        try {
            var record = IntentRuntime.get().task(UUID.fromString(task.substring(7, 43)));
            return record == null || record.goal().target() == null ? null : record.goal().target().label();
        } catch (IllegalArgumentException unavailable) { return null; }
    }

    private static boolean inside(MachineBlueprint blueprint, BlockPos at) {
        var low = blueprint.captureMin(); var high = blueprint.captureMax();
        return low != null && at.getX() >= low.x() && at.getX() <= high.x() && at.getY() >= low.y()
                && at.getY() <= high.y() && at.getZ() >= low.z() && at.getZ() <= high.z();
    }

    public static Check check(LocalPlayer player, Scope scope, Map<BlockPos, String> changes) {
        Set<BlockPos> recorded = new LinkedHashSet<>(), wheels = new LinkedHashSet<>();
        if (scope.blueprint() != null) {
            // 旧蓝图未被补丁覆盖的轮仍属于整机目标；自动接线在蓝图范围外放下的轮由归属记录补齐。
            ClientMachineCatalog.blueprintPlan(scope.blueprint()).preview().forEach((at, state) -> {
                recorded.add(at);
                if (BLOCK.equals(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString())) wheels.add(at);
            });
        }
        var owned = ConstructionOwnership.placements(player);
        for (var row : owned) if (scope.key().equals(row.machine()) && BLOCK.equals(row.block())) recorded.add(row.position());
        int unloaded = 0;
        for (BlockPos at : recorded) {
            if (player.level().isLoaded(at)) {
                if (BLOCK.equals(BuiltInRegistries.BLOCK.getKey(player.level().getBlockState(at).getBlock()).toString())) wheels.add(at);
            } else if (wheels.contains(at) || owned.stream().anyMatch(row -> row.position().equals(at) && BLOCK.equals(row.block()))) {
                // 卸载不是已经拆除的证据；记录未知项并保留额度，等回到工地再由真实方块状态消除。
                wheels.add(at); unloaded++;
            }
        }
        int existing = wheels.size(), additions = 0;
        for (var entry : changes.entrySet()) {
            if (BLOCK.equals(entry.getValue())) { if (wheels.add(entry.getKey())) additions++; }
            else wheels.remove(entry.getKey());
        }
        // 历史机器已经超额时仍允许拆轮和不增加轮的修理；新布置必须在补丁合并后满足整机上限。
        return new Check(existing, wheels.size(), additions, unloaded, additions == 0 || wheels.size() <= MAXIMUM);
    }

    public static Map<BlockPos, String> changes(MachineConstructionPlan plan) {
        var changes = new LinkedHashMap<BlockPos, String>();
        plan.preview().forEach((at, state) -> changes.put(at, BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString()));
        return changes;
    }
}
