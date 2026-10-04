// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import java.util.Map;
import java.util.Set;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import org.maiwithu.maicraft.core.scan.TargetIndex;
import org.maiwithu.maicraft.core.task.stonecutter.StonecuttingParameters;
import org.maiwithu.maicraft.task.TaskResult;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.UUID;
import org.maiwithu.maicraft.core.integration.machine.process.MinecraftStonecuttingProcessAdapter;
import org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator;

/** 从当前维度的已加载事实选切石机；随身原料按请求装入，配方选择、整批加工和库存核对交给同一任务。 */
final class StonecutAbilityAdapter {
    static final String ABILITY = "maicraft:stonecut";
    /** 切石机常在基地范围内；已加载世界内的小半径即可，不提供跨区块搜索。 */
    private static final int SEARCH_RADIUS = 16;
    private StonecutAbilityAdapter() {}

    static void validate(Goal goal) {
        StonecuttingParameters.parse(goal.parameters());
        var target = goal.target();
        if (target != null && !Set.of("coordinates", "landmark", "nearest").contains(target.kind()))
            throw new IllegalArgumentException("stonecut requires coordinates, a landmark or the nearest loaded stonecutter");
        if (target != null && "coordinates".equals(target.kind()) && target.position() == null)
            throw new IllegalArgumentException("stonecut coordinates require a position");
        if (target != null && "landmark".equals(target.kind()) && (target.label() == null || target.label().isBlank()))
            throw new IllegalArgumentException("stonecut landmark requires an existing label");
    }

    static IntentAction adapt(Goal goal, LocalPlayer player, IntentRuntime runtime) {
        validate(goal);
        var options = StonecuttingParameters.parse(goal.parameters());
        var target = goal.target();
        BlockPos station;
        if (target == null || "nearest".equals(target.kind())) {
            var blocks = Set.of(Blocks.STONECUTTER);
            // 复用有界的区块索引，扫描没完成就等待；不会凭空生成切石机或为找切石机修改地形。
            TargetIndex.register(player.clientLevel, blocks);
            TargetIndex.Result found;
            try { found = TargetIndex.query(player.clientLevel, player.blockPosition(), blocks, 1, (SEARCH_RADIUS + 15) / 16, 128); }
            finally { TargetIndex.unregister(player.clientLevel, blocks); }
            if (!found.complete()) return IntentAction.Pending.INSTANCE;
            if (found.hits().isEmpty() || found.hits().getFirst().distSqr(player.blockPosition()) > (double) SEARCH_RADIUS * SEARCH_RADIUS)
                return unavailable("No existing stonecutter was observed within the loaded search radius.");
            station = found.hits().getFirst();
        } else {
            Goal.WorldPosition position = target.position();
            if ("landmark".equals(target.kind())) {
                var landmark = runtime.landmark(target.label());
                if (landmark == null) return unavailable("The stonecutter landmark is not known.");
                position = landmark.position();
            }
            if (position == null || position.dimension() != null && !position.dimension().equals(player.level().dimension().location().toString()))
                return unavailable("The stonecutter must be in the current dimension.");
            station = new BlockPos(position.x(), position.y(), position.z());
        }
        if (!player.level().isLoaded(station) || !player.level().getBlockState(station).is(Blocks.STONECUTTER))
            return unavailable("The selected position is not an observed loaded stonecutter.");
        // 能力入口只转换执行请求，与 operate_machine 的 run_production 共用同一原生工序工厂与已验收执行器。
        var production = new JsonObject(); production.addProperty("schema_version", 2);
        production.addProperty("process", MinecraftStonecuttingProcessAdapter.ID);
        production.add("parameters", options.executionParameters());
        return new IntentAction.Native(MachineProductionIntent.createTask("stonecut-" + UUID.randomUUID(),
                player.level().getGameTime() + 10L * 60 * 20, player, station, player.level().dimension().location().toString(),
                production, null, List.of(), SemanticMaterialSupplyCoordinator.MaterialPolicy.INVENTORY_ONLY));
    }

    private static IntentAction unavailable(String detail) {
        return new IntentAction.Report(TaskResult.fail(detail, Map.of("failure_code", "stonecutter_station_unavailable",
                "stonecutting_submitted", false, "mechanical_retry_allowed", true)), null);
    }
}
