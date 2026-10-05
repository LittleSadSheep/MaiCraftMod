// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.suicide;

import java.util.List;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.BlockUseAcknowledgement;
import org.maiwithu.maicraft.client.actor.BodyControlPort;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.client.actor.NativeConfirmation;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.PlayerInv;
import org.maiwithu.maicraft.core.act.FirstPersonInteractionTargeting;
import org.maiwithu.maicraft.core.act.Interaction;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.task.ActualViewConvergenceGate;
import org.maiwithu.maicraft.core.task.FirstPersonActionGate;
import org.maiwithu.maicraft.entity.InputDriver;
import org.maiwithu.maicraft.task.TaskState;

/** 站在选定格里低头，用随身岩浆桶、打火石或火焰弹在脚下造一次原生危险；只等这次回执和真实危险格，不重复同一次操作。 */
final class SuicideSelfHazard {
    enum Kind {
        // 岩浆桶致死最快，排在点火前；打火石有耐久可反复点火，排在火焰弹前面。
        LAVA_BUCKET("lava_bucket", "lava bucket", List.of(Items.LAVA_BUCKET)),
        FIRE("fire", "flint and steel or fire charge", List.of(Items.FLINT_AND_STEEL, Items.FIRE_CHARGE));
        final String method, label;
        final List<Item> items;
        Kind(String method, String label, List<Item> items) { this.method = method; this.label = label; this.items = items; }

        static Kind of(String method) {
            for (Kind kind : values()) if (kind.method.equals(method)) return kind;
            return null;
        }

        // 已经提交的造危险操作没有出效果时登记这个键，之后不再换格反复尝试同一种方式。
        String rejectedKey() { return method + ":native_rejection"; }

        boolean present(Level level, BlockPos cell) {
            if (!level.isLoaded(cell)) return false;
            return this == FIRE ? level.getBlockState(cell).getBlock() instanceof BaseFireBlock : level.getFluidState(cell).is(FluidTags.LAVA);
        }

        int slot(Inventory inventory) {
            // 只找背包、快捷栏和副手里的物品；副手已握着就直接用副手，不为此挪动主手物品。
            for (Item item : items) {
                if (inventory.getItem(Inventory.SLOT_OFFHAND).is(item)) return Inventory.SLOT_OFFHAND;
                for (int index = 0; index < Inventory.INVENTORY_SIZE; index++) if (inventory.getItem(index).is(item)) return index;
            }
            return -1;
        }

        boolean holds(ItemStack stack) { return items.stream().anyMatch(stack::is); }
    }

    private final Kind kind;
    private final BlockPos cell;
    private final FirstPersonActionGate selection = new FirstPersonActionGate();
    private final ActualViewConvergenceGate view = new ActualViewConvergenceGate();
    private NativeActionReceipt receipt;
    private int slot = -1;
    private long started = -1;
    private String failure;

    SuicideSelfHazard(Kind kind, BlockPos cell) { this.kind = kind; this.cell = cell.immutable(); }

    TaskState tick(LocalPlayerContext context) {
        var player = context.player(); long now = context.level().getGameTime();
        // 操作期间站定不动：先选物品 -> 低头对准脚下支撑面顶部 -> 准星确认后右键一次 -> 等服务端确认真实结果。
        context.body().applyMovement(BodyControlPort.Movement.STOPPED, context.tickRevision());
        if (receipt != null) {
            InputDriver.lookAt(player, aim());
            receipt = context.actions().poll(context, receipt);
            if (!receipt.terminal()) return TaskState.RUNNING;
            return receipt.status() == NativeActionReceipt.Status.CONFIRMED_APPLIED
                    ? TaskState.SUCCESS : failed("native " + kind.method + " use was not confirmed: " + receipt.detail());
        }
        if (started < 0) started = now;
        if (now - started > 200) return failed("could not aim the " + kind.method + " item at the floor below the chosen cell");
        if (NavigationSafetyContext.protectsMutation(cell) || NavigationSafetyContext.protectsUse(cell.below()))
            return failed("the chosen cell or its floor is explicitly protected");
        if (!context.menus().ensureWorldVisible(context)) return TaskState.RUNNING;
        if (slot < 0 && (slot = kind.slot(player.getInventory())) < 0)
            return failed("no " + kind.label + " remains in the carried inventory");
        var ready = slot == Inventory.SLOT_OFFHAND ? FirstPersonActionGate.Status.READY : selection.select(context, player, slot);
        if (ready == FirstPersonActionGate.Status.FAILED) return failed(selection.failure());
        if (ready != FirstPersonActionGate.Status.READY) return TaskState.RUNNING;
        InteractionHand hand = slot == Inventory.SLOT_OFFHAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        if (!kind.holds(player.getItemInHand(hand))) return failed("the " + kind.method + " item changed before use");
        Vec3 aim = aim();
        InputDriver.lookAt(player, aim);
        if (!view.ready(player, aim.subtract(player.getEyePosition())) || !context.permitsNativeActions() || !context.mutationAvailable())
            return TaskState.RUNNING;
        return kind == Kind.FIRE ? ignite(context, hand) : pour(context, hand);
    }

    private TaskState ignite(LocalPlayerContext context, InteractionHand hand) {
        // 原生准星必须正好落在脚下支撑方块的顶面，火才会生在角色所站的格子里；没对上就继续转头等待，不盲点。
        var player = context.player();
        var ray = Interaction.nativeRaytrace(player, player.blockInteractionRange());
        if (!(ray instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK
                || !hit.getBlockPos().equals(cell.below()) || hit.getDirection() != Direction.UP) return TaskState.RUNNING;
        receipt = context.actions().useBlock(context, hand, hit, new NativeConfirmation() {
            @Override public boolean requiresBlockAcknowledgement() { return true; }
            @Override public Verdict observe(LocalPlayerContext current) {
                return kind.present(current.level(), cell) ? Verdict.APPLIED : Verdict.PENDING;
            }
        }, 80);
        return TaskState.RUNNING;
    }

    private TaskState pour(LocalPlayerContext context, InteractionHand hand) {
        // 满桶沿原生桶射线落在命中面外侧一格；低头命中脚下顶面时，岩浆正好倒进角色所站的格子。
        var player = context.player(); Vec3 eye = player.getEyePosition();
        var hit = FirstPersonInteractionTargeting.bucketRay(context.level(), player, eye,
                eye.add(player.getViewVector(1).scale(player.blockInteractionRange())), Items.LAVA_BUCKET);
        if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(cell.below()) || hit.getDirection() != Direction.UP
                || !FirstPersonInteractionTargeting.acceptsBucketHit(context.level(), cell, Items.LAVA_BUCKET, hit)) return TaskState.RUNNING;
        if (!(context.level() instanceof BlockUseAcknowledgement sequences)) return failed("native block acknowledgement hook is unavailable");
        var confirmation = new PourConfirmation(sequences, PlayerInv.count(player.getInventory(), Items.LAVA_BUCKET));
        receipt = context.actions().useItem(context, hand, confirmation, 80);
        confirmation.sequence = sequences.maicraft$currentBlockSequence();
        return TaskState.RUNNING;
    }

    /** 倒桶确认先等服务器承认这次使用序号，再看格里出现岩浆或岩浆桶确实少了一只；凝固或流走不能把已扣桶记成未知。 */
    private final class PourConfirmation implements NativeConfirmation {
        private final BlockUseAcknowledgement sequences;
        private final int before, buckets;
        private int sequence = -1;

        PourConfirmation(BlockUseAcknowledgement sequences, int buckets) {
            this.sequences = sequences; this.before = sequences.maicraft$currentBlockSequence(); this.buckets = buckets;
        }

        @Override public Verdict observe(LocalPlayerContext current) {
            // useItem 内部会先本地轮询一次，此时还没登记新序号；客户端预测出的岩浆不能提前当作服务器已接受。
            if (sequence <= before || sequences.maicraft$acknowledgedBlockSequence() < sequence) return Verdict.PENDING;
            return kind.present(current.level(), cell) || PlayerInv.count(current.player().getInventory(), Items.LAVA_BUCKET) < buckets
                    ? Verdict.APPLIED : Verdict.PENDING;
        }
    }

    // 视线落点是支撑面顶部中心，也就是选定格的底面中心；角色站在格内时射线全程留在这一列。
    private Vec3 aim() { return Vec3.atBottomCenterOf(cell); }
    private TaskState failed(String reason) { failure = reason; return TaskState.FAILED; }
    String failure() { return failure; }
    // 已按下右键却没拿到确认，说明原生交互拒绝或结果未知；调用方据此不再换格反复尝试。
    boolean submitted() { return receipt != null; }

    void close(LocalPlayer player) {
        // 收场只终止等待并保留未知结果，不能把已经发出的点火或倒桶描述成已撤销。
        if (receipt != null && !receipt.terminal()) {
            ClientRuntime.actor().activeContext().filter(context -> context.player() == player).ifPresent(context ->
                    context.actions().retireOneShotForTaskBoundary(context, receipt, "suicide self-made hazard ended"));
        }
        selection.reset();
    }
}
