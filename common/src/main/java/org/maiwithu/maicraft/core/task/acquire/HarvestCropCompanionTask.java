// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.acquire;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.NativeConfirmation;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.PlayerInv;
import org.maiwithu.maicraft.core.act.Interaction;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.pathing.goal.GoalCompiler;
import org.maiwithu.maicraft.core.scan.TargetIndex;
import org.maiwithu.maicraft.core.task.ActualViewConvergenceGate;
import org.maiwithu.maicraft.core.task.FirstPersonActionGate;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.task.mine.MineBlockTaskRecord;
import org.maiwithu.maicraft.core.task.mine.MineCompanionTask;
import org.maiwithu.maicraft.entity.InputDriver;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskState;

/** 查成熟农田 -> 定点原生采收及拾取 -> 留一份种苗补种；不会把未成熟田块或地面当普通矿脉扫掉。 */
public final class HarvestCropCompanionTask extends AbstractCompanionTask<HarvestCropTaskRecord> {
    record Crop(CropBlock block, Item output, Item seed) {}
    private static final List<Crop> CROPS = List.of(
            new Crop((CropBlock) Blocks.CARROTS, Items.CARROT, Items.CARROT),
            new Crop((CropBlock) Blocks.POTATOES, Items.POTATO, Items.POTATO),
            new Crop((CropBlock) Blocks.BEETROOTS, Items.BEETROOT, Items.BEETROOT_SEEDS),
            new Crop((CropBlock) Blocks.WHEAT, Items.WHEAT, Items.WHEAT_SEEDS));
    private final Set<Block> blocks;
    private final Set<BlockPos> excluded = new HashSet<>();
    private final FirstPersonActionGate selection = new FirstPersonActionGate();
    private final ActualViewConvergenceGate aiming = new ActualViewConvergenceGate();
    private Task harvest;
    private Crop crop;
    private BlockPos at;
    private NativeActionReceipt planting;
    private int harvested, replanted, seedBefore;
    private boolean registered, uncertain, satisfiedStop, failedHarvest;

    public HarvestCropCompanionTask(LocalPlayer player, HarvestCropTaskRecord record) {
        super(player, record);
        blocks = CROPS.stream().filter(c -> r.items.contains(c.output())).map(c -> (Block) c.block()).collect(Collectors.toUnmodifiableSet());
    }
    @Override protected void onStart() { TargetIndex.register(player.clientLevel, blocks); registered = true; }
    public static boolean supports(Item item) { return CROPS.stream().anyMatch(crop -> crop.output() == item); }
    /** 掉落物刚进背包时仍保有补种责任；父获取任务不能因为总数已够就提前取消。 */
    @Override public boolean mustSettleBeforeSatisfiedCancellation() { return harvest != null || at != null; }
    @Override public void requestSatisfiedSettlement() { satisfiedStop = true; }
    static Crop mature(BlockState state) {
        return CROPS.stream().filter(crop -> state.is(crop.block()) && crop.block().isMaxAge(state)).findFirst().orElse(null);
    }
    static boolean permitted(LocalPlayer player, BlockPos at, BlockPos origin, int radius) {
        return player.level().isLoaded(at) && at.distSqr(origin) <= (double) radius * radius
                && !NavigationSafetyContext.protectsMutation(at) && !NavigationSafetyContext.protectsUse(at.below())
                && player.level().isLoaded(at.below()) && player.level().getBlockState(at.below()).is(Blocks.FARMLAND)
                && mature(player.level().getBlockState(at)) != null;
    }
    @Override protected TaskState onTick() {
        if (harvest != null) {
            TaskState state = runChild(harvest); if (state == null) return TaskState.RUNNING;
            var result = harvest.result(state); harvest = null;
            if (state != TaskState.SUCCESS || result == null || !result.success()) {
                uncertain = state == TaskState.TIMEOUT || state == TaskState.CANCELLED
                        || result != null && Boolean.TRUE.equals(result.data().get("outcome_uncertain"));
                // 已确认破坏但拾取失败，也要先尝试用真实种苗补上自己的田格，不能带着洞口换下一种来源。
                if (result != null && result.data().get("confirmed_source_breaks") instanceof Number count && count.intValue() > 0) {
                    harvested++; failedHarvest = true; return TaskState.RUNNING;
                }
                return stop("crop_harvest_not_confirmed", FailureType.UNKNOWN);
            }
            harvested++; return TaskState.RUNNING;
        }
        if (at != null) return plant();
        if (failedHarvest) return stop("crop_drop_collection_incomplete", FailureType.UNKNOWN);
        if (satisfiedStop) return TaskState.SUCCESS;
        if (r.items.stream().mapToInt(item -> PlayerInv.count(player.getInventory(), item)).sum() >= r.count) return TaskState.SUCCESS;
        if (harvested >= 64 || excluded.size() >= 512) return stop("crop_harvest_budget_exhausted", FailureType.NO_MATERIAL);
        var found = TargetIndex.query(player.clientLevel, r.origin, blocks, 64, (r.radius + 15) / 16, 256, excluded);
        // 排除未成熟和受保护的候选后继续索引下一批，不能因最近一排尚未成熟就断言整片村田没有收成。
        boolean rejected = false;
        for (BlockPos pos : found.hits()) {
            if (!permitted(player, pos, r.origin, r.radius)) { rejected |= excluded.add(pos.immutable()); continue; }
            at = pos.immutable(); crop = mature(player.level().getBlockState(at)); excluded.add(at);
            var record = new MineBlockTaskRecord(r.getToolCallId() + "/crop-" + harvested, r.getDeadlineGameTime(),
                    Set.of(crop.block()), 1, "mature crop", crop.seed() == crop.output() ? Set.of(crop.output())
                            : Set.of(crop.output(), crop.seed()), false).onlyAt(at, player.level().getBlockState(at));
            harvest = new MineCompanionTask(player, record); return TaskState.RUNNING;
        }
        if (!found.complete() || rejected) return TaskState.RUNNING;
        return stop("no_loaded_mature_crop_in_scope", FailureType.NO_MATERIAL);
    }
    private TaskState plant() {
        var context = ClientRuntime.requireContext(player);
        if (planting != null) {
            planting = context.actions().poll(context, planting);
            if (!planting.terminal()) return TaskState.RUNNING;
            uncertain |= planting.status() == NativeActionReceipt.Status.UNCERTAIN;
            if (planting.status() != NativeActionReceipt.Status.CONFIRMED_APPLIED
                    || PlayerInv.count(player.getInventory(), crop.seed()) != seedBefore - 1)
                return stop("crop_replant_unconfirmed", FailureType.UNKNOWN);
            replanted++; planting = null; at = null; crop = null; selection.reset(); aiming.reset(); stopNav();
            return TaskState.RUNNING;
        }
        // 采收以后先补种，补种失败会带着真实进度结束，绝不继续拆下一格或把那份种苗吃掉。
        if (!context.level().isLoaded(at) || !context.level().getBlockState(at).isAir()
                || !context.level().getBlockState(at.below()).is(Blocks.FARMLAND)
                || NavigationSafetyContext.protectsMutation(at) || NavigationSafetyContext.protectsUse(at.below()))
            return stop("crop_replant_site_changed", FailureType.TARGET_LOST);
        if (PlayerInv.count(player.getInventory(), crop.seed()) < 1) return stop("crop_replant_seed_missing", FailureType.NO_MATERIAL);
        Vec3 point = new Vec3(at.getX() + .5, at.getY() - .0625, at.getZ() + .5);
        if (player.getEyePosition().distanceTo(point) > 4.25) {
            if (nav == null) nav = PlayerNav.to(player, () -> GoalCompiler.interact(at.below()), .8,
                    () -> player.getEyePosition().distanceTo(point) <= 4.25).walkingOnly();
            if (nav.tick() == PlayerNav.Status.FAILED) return stop("crop_replant_unreachable", FailureType.NO_PATH);
            return TaskState.RUNNING;
        }
        stopNav(); InputDriver.halt(player);
        var selected = selection.select(player, PlayerInv.findSlot(player.getInventory(), crop.seed()));
        if (selected == FirstPersonActionGate.Status.RUNNING) return TaskState.RUNNING;
        if (selected == FirstPersonActionGate.Status.FAILED) return stop("crop_seed_selection_failed", FailureType.UNKNOWN);
        InputDriver.lookAt(player, point);
        if (!aiming.ready(player, point.subtract(player.getEyePosition()))) return TaskState.RUNNING;
        if (!(Interaction.nativeRaytrace(player, 4.5) instanceof BlockHitResult hit)
                || !hit.getBlockPos().equals(at.below()) || hit.getDirection() != Direction.UP)
            return stop("crop_replant_surface_occluded", FailureType.OCCLUDED);
        seedBefore = PlayerInv.count(player.getInventory(), crop.seed());
        planting = context.actions().useBlock(context, InteractionHand.MAIN_HAND, hit, new NativeConfirmation() {
            public Verdict observe(LocalPlayerContext observed) {
                if (!observed.level().isLoaded(at)) return Verdict.PENDING;
                var live = observed.level().getBlockState(at);
                return live.is(crop.block()) ? Verdict.APPLIED : live.isAir() ? Verdict.PENDING : Verdict.DIVERGED;
            }
            public boolean requiresBlockAcknowledgement() { return true; }
        }, 40);
        return TaskState.RUNNING;
    }
    private TaskState stop(String code, FailureType type) { fail(code, type); return TaskState.FAILED; }
    @Override public void stop(LocalPlayer companion, StopReason reason) {
        if (harvest != null) harvest.stop(companion, reason);
        super.stop(companion, reason); InputDriver.halt(companion);
    }
    @Override protected void cleanup() {
        if (harvest != null) { harvest.stop(player, StopReason.REPLACED); harvest.result(TaskState.CANCELLED); harvest = null; }
        if (planting != null) {
            uncertain |= !planting.terminal();
            ClientRuntime.actor().activeContext().filter(context -> context.player() == player).ifPresent(context ->
                    context.actions().retireOneShotForTaskBoundary(context, planting, "crop replant task ended; inspect the field before retrying"));
            planting = null;
        }
        if (registered) { TargetIndex.unregister(player.clientLevel, blocks); registered = false; }
        selection.reset(); aiming.reset(); super.cleanup();
    }
    /** 面板行动行的一句话汇报；作物名来自采收目标方块，挖矿子任务仍在时由一线先说话。 */
    @Override public String describeCurrentAction() {
        if (harvest != null) {
            String deeper = harvest.describeCurrentAction();
            return deeper != null ? deeper : "正在采收作物";
        }
        if (at != null) return "正在补种 " + (crop == null ? "作物" : crop.block().getName().getString());
        return "正在寻找成熟作物";
    }

    @Override protected Map<String, Object> resultData() {
        return Map.of("harvested_crops", harvested, "replanted_crops", replanted,
                "replant_pending", at != null && harvested > replanted, "outcome_uncertain", uncertain, "search_radius", r.radius);
    }
    @Override protected String successMessage() { return "harvested mature crops and confirmed replanting before retaining the produce"; }
}
