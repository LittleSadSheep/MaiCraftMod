// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import java.util.Map;
import java.util.Set;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import org.maiwithu.maicraft.core.scan.TargetIndex;
import org.maiwithu.maicraft.core.task.enchant.EnchantParameters;
import org.maiwithu.maicraft.task.TaskResult;

/** 附魔台目标只来自当前维度的已加载事实；准备物品、选择报价与花费确认交给同一项附魔任务。 */
final class EnchantAbilityAdapter {
    static final String ABILITY = "maicraft:enchant";
    private EnchantAbilityAdapter() {}

    static void validate(Goal goal) {
        EnchantParameters.parse(goal.parameters());
        var target = goal.target();
        if (target != null && !Set.of("coordinates","landmark","nearest").contains(target.kind()))
            throw new IllegalArgumentException("enchant requires coordinates, a landmark or the nearest loaded enchanting table");
        if (target != null && "coordinates".equals(target.kind()) && target.position() == null)
            throw new IllegalArgumentException("enchant coordinates require a position");
        if (target != null && "landmark".equals(target.kind()) && (target.label() == null || target.label().isBlank()))
            throw new IllegalArgumentException("enchant landmark requires an existing label");
    }

    static IntentAction adapt(Goal goal, LocalPlayer player, IntentRuntime runtime) {
        validate(goal); var options = EnchantParameters.parse(goal.parameters()); var target = goal.target();
        BlockPos table;
        if (target == null || "nearest".equals(target.kind())) {
            var blocks = Set.of(Blocks.ENCHANTING_TABLE);
            // 复用有界的区块索引，扫描没完成就等待；不会凭空生成台子或为找台子修改地形。
            TargetIndex.register(player.clientLevel, blocks);
            TargetIndex.Result found;
            try { found = TargetIndex.query(player.clientLevel, player.blockPosition(), blocks, 1, (options.searchRadius()+15)/16, 128); }
            finally { TargetIndex.unregister(player.clientLevel, blocks); }
            if (!found.complete()) return IntentAction.Pending.INSTANCE;
            if (found.hits().isEmpty() || found.hits().getFirst().distSqr(player.blockPosition()) > (double) options.searchRadius()*options.searchRadius())
                return unavailable("No existing enchanting table was observed within the requested loaded search radius.");
            table = found.hits().getFirst();
        } else {
            Goal.WorldPosition position = target.position();
            if ("landmark".equals(target.kind())) {
                var landmark = runtime.landmark(target.label());
                if (landmark == null) return unavailable("The enchanting table landmark is not known.");
                position = landmark.position();
            }
            if (position == null || position.dimension() != null && !position.dimension().equals(player.level().dimension().location().toString()))
                return unavailable("The enchanting table must be in the current dimension.");
            table = new BlockPos(position.x(),position.y(),position.z());
        }
        if (!player.level().isLoaded(table) || !player.level().getBlockState(table).is(Blocks.ENCHANTING_TABLE))
            return unavailable("The selected position is not an observed loaded enchanting table.");
        // 旧能力只转换执行请求，保留父Goal原文和旧消费编号；新旧入口共同使用同一附魔机制工厂与已验收执行器。
        var production = new com.google.gson.JsonObject(); production.addProperty("schema_version", 2);
        production.addProperty("process", org.maiwithu.maicraft.core.integration.machine.process.MinecraftEnchantProcessAdapter.ID);
        production.add("parameters", options.executionParameters());
        return new IntentAction.Native(MachineProductionIntent.createTask("enchant-" + java.util.UUID.randomUUID(),
                player.level().getGameTime() + 10L * 60 * 20, player, table, player.level().dimension().location().toString(),
                production, null, java.util.List.of(), org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator.MaterialPolicy.INVENTORY_ONLY));
    }

    private static IntentAction unavailable(String detail) {
        return new IntentAction.Report(TaskResult.fail(detail, Map.of("failure_code","enchant_table_unavailable",
                "enchantment_submitted",false,"mechanical_retry_allowed",true)),null);
    }
}
