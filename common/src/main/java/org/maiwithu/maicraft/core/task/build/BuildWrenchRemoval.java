// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;
import java.util.List;
import java.util.function.BooleanSupplier;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.NativeConfirmation;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.PlayerInv;
import org.maiwithu.maicraft.core.act.FirstPersonInteractionTargeting;
import org.maiwithu.maicraft.core.act.Interaction;
import org.maiwithu.maicraft.core.task.ActualViewConvergenceGate;
import org.maiwithu.maicraft.core.task.FirstPersonActionGate;
import org.maiwithu.maicraft.entity.InputDriver;
import org.maiwithu.maicraft.server.machine.NativeApi;

/** 对已获准拆除的格子拿扳手、蹲下、原生右键一次，再等服务端确认；观察物品回收但不伪造掉落。 */
public final class BuildWrenchRemoval {
    public enum Status { RUNNING, REMOVED, FAILED }
    private static final String WRENCHABLE = "com.simibubi.create.content.equipment.wrench.IWrenchable";
    private final LocalPlayer player;
    private final BlockPos target;
    private final BlockState before;
    private final Item wrench, recoveredItem;
    private final BooleanSupplier permitted;
    private final FirstPersonActionGate selection = new FirstPersonActionGate();
    private final ActualViewConvergenceGate aim = new ActualViewConvergenceGate();
    private NativeActionReceipt receipt;
    private Status status = Status.RUNNING;
    private String reason = "preparing_carried_wrench";
    private final Object level;
    private long started = -1;
    private long confirmedAt = -1;
    private int beforeCount, observedGain;

    public static BuildWrenchRemoval carried(LocalPlayer player, BlockPos target, BooleanSupplier permitted) {
        Item wrench = BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse("create:wrench")).orElse(Items.AIR);
        return available(player, target) ? new BuildWrenchRemoval(player, target, wrench, permitted) : null;
    }
    static boolean available(LocalPlayer player, BlockPos target) {
        Item wrench = BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse("create:wrench")).orElse(Items.AIR);
        return wrench != Items.AIR && PlayerInv.findSlot(player.getInventory(), wrench) >= 0 && supported(player.level().getBlockState(target));
    }
    static boolean supported(BlockState state) {
        try {
            if (NativeApi.is(state.getBlock(), WRENCHABLE))
                // 只接受原生默认整块拆卸；包覆轴等覆写版本会先剥壳，不能把那种状态变化误报成整块已拆。
                return defaultRemoval(state.getBlock().getClass(), NativeApi.type(WRENCHABLE));
            return state.is(TagKey.create(Registries.BLOCK, ResourceLocation.parse("create:wrench_pickup")));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { return false; }
    }
    public static boolean defaultRemoval(Class<?> block, Class<?> contract) throws NoSuchMethodException {
        return block.getMethod("onSneakWrenched", BlockState.class, UseOnContext.class).getDeclaringClass() == contract;
    }
    public BuildWrenchRemoval(LocalPlayer player, BlockPos target, Item wrench, BooleanSupplier permitted) {
        this.player = player; this.target = target.immutable(); this.wrench = wrench; this.permitted = permitted;
        level = player.level(); before = player.level().getBlockState(target); recoveredItem = before.getBlock().asItem();
    }
    public Status tick() {
        if (status != Status.RUNNING) return status;
        var context = ClientRuntime.requireContext(player);
        if (receipt != null) {
            // 已发出的拆卸只读结算，不因背包先收到返还物或目标先显示空气而再按一次右键。
            receipt = context.actions().poll(context, receipt);
            if (!receipt.terminal()) return status;
            observedGain = Math.max(0, PlayerInv.count(player.getInventory(), recoveredItem) - beforeCount);
            if (receipt.status() == NativeActionReceipt.Status.CONFIRMED_APPLIED && player.level().isLoaded(target)
                    && player.level().getBlockState(target).isAir()) {
                // 方块更新可能先于背包返还包；短暂等实际库存，不把服务端拆除确认当作物品已经进包。
                if (confirmedAt < 0) confirmedAt = context.tickRevision();
                if (!player.isCreative() && recoveredItem != Items.AIR && observedGain == 0 && context.tickRevision() - confirmedAt < 20) return status;
                reason = "native_wrench_removal_confirmed"; InputDriver.halt(player); return status = Status.REMOVED;
            }
            return fail("native_wrench_removal_" + receipt.status().name().toLowerCase(Locale.ROOT));
        }
        if (started < 0) started = context.tickRevision();
        if (context.tickRevision() - started > 200) return fail("wrench_preparation_timeout");
        if (player.level() != level || !player.level().isLoaded(target) || !player.level().getBlockState(target).equals(before)
                || !permitted.getAsBoolean()) return fail("wrench_target_or_permission_changed");
        var selected = selection.select(player, PlayerInv.findSlot(player.getInventory(), wrench));
        if (selected == FirstPersonActionGate.Status.FAILED) return fail(selection.failure());
        if (selected != FirstPersonActionGate.Status.READY) { reason = "equipping_carried_wrench"; return status; }
        InputDriver.halt(player); InputDriver.sneak(player, true);
        if (!player.isShiftKeyDown() || player.getPose() != Pose.CROUCHING) { reason = "waiting_for_native_crouch"; return status; }
        BlockHitResult visible = FirstPersonInteractionTargeting.visibleBlockHit(player.level(), player,
                player.getEyePosition(), target, player.blockInteractionRange());
        if (visible == null) return fail("wrench_target_occluded");
        InputDriver.lookAt(player, visible.getLocation());
        if (!aim.ready(player, visible.getLocation().subtract(player.getEyePosition()))) { reason = "aiming_wrench_at_target"; return status; }
        var ray = Interaction.nativeRaytrace(player, player.blockInteractionRange());
        if (!(ray instanceof BlockHitResult hit) || !hit.getBlockPos().equals(target)) { reason = "waiting_for_native_target_ray"; return status; }
        if (!context.mutationAvailable()) return status;
        beforeCount = PlayerInv.count(player.getInventory(), recoveredItem);
        var expected = NativeConfirmation.blockBecomesAir(target, before);
        var confirmed = new NativeConfirmation() {
            public Verdict observe(LocalPlayerContext current) { return expected.observe(current); }
            public boolean requiresBlockAcknowledgement() { return true; }
        };
        receipt = context.actions().useBlock(context, InteractionHand.MAIN_HAND, hit, confirmed, 60);
        reason = "awaiting_native_wrench_removal"; return status;
    }
    public boolean pending() { return receipt != null && !receipt.terminal() || selection.pending(); }
    /** 潜行后失去射线且从未发出右键时，允许调用方改回普通工具拆同一块；未知点击不能走此回退。 */
    public boolean canFallbackToMining() {
        return status == Status.FAILED && receipt == null && !selection.pending()
                && reason.equals("wrench_target_occluded") && player.level() == level
                && player.level().isLoaded(target) && player.level().getBlockState(target).equals(before);
    }
    public boolean uncertain() { return receipt != null && receipt.status() != NativeActionReceipt.Status.CONFIRMED_APPLIED; }
    public String reason() { return reason; }
    public void pause() { aim.reset(); started = -1; }
    public void stop() {
        var context = ClientRuntime.actor().activeContext().filter(value -> value.player() == player && value.isCurrent());
        if (receipt != null && !receipt.terminal() && context.isPresent())
            receipt = context.get().actions().retireOneShotForTaskBoundary(context.get(), receipt, "wrench removal stopped");
        selection.reset();
    }
    private Status fail(String failure) { reason = failure; InputDriver.halt(player); return status = Status.FAILED; }
    public Map<String, Object> evidence() {
        var data = new LinkedHashMap<String, Object>(); data.put("method", "create_wrench_sneak_use"); data.put("reason", reason);
        data.put("target", List.of(target.getX(), target.getY(), target.getZ()));
        data.put("native_use_submitted", receipt != null); data.put("block_removal_verified", status == Status.REMOVED);
        data.put("recovered_item", BuiltInRegistries.ITEM.getKey(recoveredItem).toString()); data.put("inventory_gain_observed", observedGain);
        data.put("recovery_scope", "primary_item_inventory_delta_during_native_removal"); data.put("outcome_uncertain", uncertain());
        return Map.copyOf(data);
    }
}
