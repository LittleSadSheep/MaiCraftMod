// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.supply;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.core.act.BlockDigger;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.pathing.util.BlockHelper;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord.Source;
import org.maiwithu.maicraft.core.task.base.LandmarkProtection;
import org.maiwithu.maicraft.core.task.build.BuildTemporarySupportMaterials.SupplyNeed;
import org.maiwithu.maicraft.core.task.mine.MineBlockTaskRecord;
import org.maiwithu.maicraft.intent.IntentRuntime;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.TaskState;

/** 临时支撑先用随身余料和无线现货；确实没有时只采工位边能直接够到的普通土石，不递归合成工具或远行采矿。 */
final class BuildSupportSupply {
    private final List<SupplyNeed> options;
    private final SemanticBuildSupplyTaskRecord owner;
    private final BlockPos origin;
    private final String dimension;
    private final long deadline;
    private final List<Source> sources;
    private final Set<BlockPos> attempted = new HashSet<>();
    private final List<Map<String, Object>> attempts = new ArrayList<>();
    private Task child;
    private TaskRecord record;
    private int stockAt, serial;
    private SupplyNeed fulfilled;
    private boolean uncertain;
    private String failure;

    BuildSupportSupply(LocalPlayer player, SemanticBuildSupplyTaskRecord owner, List<SupplyNeed> options) {
        this.owner = owner; this.options = List.copyOf(options);
        origin = player.blockPosition().immutable(); dimension = player.level().dimension().location().toString();
        deadline = player.level().getGameTime() + 1200;
        // 临时支撑严格遵守仅用背包；普通仓储授权在这里收窄成无线现货，不开放网络合成和箱子远行。
        sources = owner.materialPolicy == SemanticMaterialSupplyCoordinator.MaterialPolicy.INVENTORY_ONLY
                ? List.of(Source.INVENTORY) : SemanticMaterialSupplyCoordinator.resolveSources(owner.materialPolicy, owner.allowedSources);
    }

    TaskState tick(LocalPlayer player, Function<Task, TaskState> runner) {
        if (failure != null) return TaskState.FAILED;
        if (!dimension.equals(player.level().dimension().location().toString()) || !nearOrigin(player.blockPosition())
                || player.level().getGameTime() >= deadline) {
            failure = "temporary support supply exceeded its fixed local work area or time budget";
            cancel(player); return TaskState.FAILED;
        }
        var protection = LandmarkProtection.resolve(owner.protectedLabels, IntentRuntime.get().landmarks(), dimension);
        if (!protection.problems().isEmpty()) {
            failure = "temporary support protection could not be resolved"; cancel(player); return TaskState.FAILED;
        }
        return protection.run(() -> advance(player, runner));
    }

    private TaskState advance(LocalPlayer player, Function<Task, TaskState> runner) {
        // 先结清已提交的取物或挖掘，再检查库存；中途出现足量物品也不能把未结束的原生动作丢掉。
        if (child != null) {
            TaskState terminal = runner.apply(child);
            if (terminal == null) return TaskState.RUNNING;
            var result = child.result(terminal);
            Map<String, Object> data = result == null || result.data() == null ? Map.of() : result.data();
            uncertain |= Boolean.TRUE.equals(data.get("outcome_uncertain")) || Boolean.TRUE.equals(data.get("world_change_uncertain"));
            attempts.add(Map.of("source", record instanceof MineBlockTaskRecord ? "local_harvest" : "wireless_stock",
                    "terminal_state", terminal.name(), "data", data));
            boolean harvestFailed = record instanceof MineBlockTaskRecord
                    && (terminal != TaskState.SUCCESS || result == null || !result.success());
            child = null; record = null;
            if (uncertain || harvestFailed || terminal == TaskState.CANCELLED || terminal == TaskState.TIMEOUT) {
                failure = "temporary support operation did not settle successfully"; return TaskState.FAILED;
            }
        }
        fulfilled = options.stream().filter(need -> carried(player, need.item()) >= need.requiredFinalCount()).findFirst().orElse(null);
        if (fulfilled != null) return TaskState.SUCCESS;
        // 每种材料单独核对足量库存，不能把一块泥土加一块圆石误算为两格单材质支撑链。
        if ((sources.contains(Source.WIRELESS) || sources.contains(Source.STORAGE)) && stockAt < options.size()) {
            var need = options.get(stockAt++);
            start(player, new SemanticAcquireTaskRecord(id(), deadline, List.of(BuiltInRegistries.ITEM.getKey(need.item())),
                    need.requiredFinalCount(), List.of(Source.INVENTORY, Source.WIRELESS), false,
                    SemanticAcquireTaskRecord.SourceHint.empty(), owner.protectedLabels, 4));
            return TaskState.RUNNING;
        }
        // 只有全部可用现货都查过，才尝试已加载、可直接点击且有现成工具的普通材料；搜索中心始终冻结。
        if (sources.contains(Source.MINE) && player.onGround()) {
            var digger = new BlockDigger(player);
            for (BlockPos cell : BlockPos.betweenClosed(origin.offset(-4, -1, -4), origin.offset(4, 2, 4))) {
                if (!localSourceAllowed(player, cell) || digger.reachableHit(cell) == null) continue;
                BlockState state = player.level().getBlockState(cell);
                Set<Item> output = localOutputs(state).stream().filter(item -> options.stream().anyMatch(need -> need.item() == item))
                        .collect(Collectors.toUnmodifiableSet());
                if (output.isEmpty() || state.requiresCorrectToolForDrops() && !MineBlockTaskRecord.hasEfficientTool(player, Set.of(state.getBlock()))) continue;
                attempted.add(cell.immutable());
                start(player, new MineBlockTaskRecord(id(), deadline, Set.of(state.getBlock()), 1, "nearby temporary support",
                        output, state.requiresCorrectToolForDrops()).onlyAt(cell, state));
                return TaskState.RUNNING;
            }
        }
        failure = "no sufficient carried or wireless support stock, and no easy visible local source";
        return TaskState.FAILED;
    }

    // 不挖脚下、保护格、未知块或会造成塌落的地方；邻近地表材料还须下方有完整承托，避免为垫块开深坑。
    private boolean localSourceAllowed(LocalPlayer player, BlockPos cell) {
        var level = player.level();
        // 简单取土不能挖开水坝或熔岩边界；邻格尚未加载也无法证明拆除后仍安全。
        for (Direction side : Direction.values()) {
            BlockPos neighbor = cell.relative(side);
            if (!level.isLoaded(neighbor) || !level.getFluidState(neighbor).isEmpty()) return false;
        }
        return nearOrigin(cell) && !attempted.contains(cell) && !cell.equals(player.blockPosition().below())
                && level.isLoaded(cell) && level.isLoaded(cell.below()) && !NavigationSafetyContext.protectsMutation(cell)
                && !BlockHelper.shouldAvoidBreaking(level, cell) && !BlockHelper.breakReleasesFallingBlock(level, cell)
                && !localOutputs(level.getBlockState(cell)).isEmpty()
                && (cell.getY() >= origin.getY() || level.getBlockState(cell.below()).isCollisionShapeFullBlock(level, cell.below()));
    }

    static Set<Item> localOutputs(BlockState state) {
        // 普通石头可能因现成工具的精准采集掉石头；只把实际背包产物计数，不额外制作或更换附魔工具。
        if (state.is(Blocks.DIRT) || state.is(Blocks.GRASS_BLOCK)) return Set.of(Items.DIRT);
        if (state.is(Blocks.COBBLESTONE)) return Set.of(Items.COBBLESTONE);
        if (state.is(Blocks.STONE)) return Set.of(Items.COBBLESTONE, Items.STONE);
        if (state.is(Blocks.NETHERRACK)) return Set.of(Items.NETHERRACK);
        return Set.of();
    }
    boolean nearOrigin(BlockPos cell) {
        return Math.abs(cell.getX() - origin.getX()) <= 6 && Math.abs(cell.getZ() - origin.getZ()) <= 6
                && cell.getY() >= origin.getY() - 1 && cell.getY() <= origin.getY() + 2;
    }
    private static int carried(LocalPlayer player, Item item) {
        return SemanticMaterialSupplyCoordinator.inventoryCount(player, List.of(BuiltInRegistries.ITEM.getKey(item)));
    }
    private void start(LocalPlayer player, TaskRecord next) { record = next; child = TaskFactory.create(player, next); }
    private String id() { return owner.getToolCallId() + "-local-support-" + (++serial); }
    SupplyNeed fulfilled() { return fulfilled; }
    long deadline() { return deadline; }
    Map<String, Object> receipt() {
        return Map.of("purpose", "temporary_support", "attempts", List.copyOf(attempts), "outcome_uncertain", uncertain,
                "failure_code", failure == null ? "" : "local_support_unavailable", "message", failure == null ? "local support supply" : failure,
                "local_horizontal_limit", 6, "goal_satisfied", fulfilled != null);
    }
    boolean mustSettle() { return child != null && child.mustSettleBeforeSatisfiedCancellation(); }
    void cancel(LocalPlayer player) {
        if (child == null) return;
        child.stop(player, Task.StopReason.REPLACED);
        var stopped = child.result(TaskState.CANCELLED);
        uncertain |= stopped == null || stopped.data() == null || Boolean.TRUE.equals(stopped.data().get("outcome_uncertain"))
                || Boolean.TRUE.equals(stopped.data().get("world_change_uncertain"));
        child = null; record = null;
    }
}
