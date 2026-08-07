package org.maiwithu.maicraft.core.task.sleep;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.client.actor.NativeConfirmation;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.act.Interaction;
import org.maiwithu.maicraft.entity.InputDriver;
import org.maiwithu.maicraft.task.TaskState;

public final class SleepCompanionTask extends AbstractCompanionTask<SleepTaskRecord> {
    private NativeActionReceipt receipt;
    private int aimTicks;
    public SleepCompanionTask(LocalPlayer player, SleepTaskRecord record) { super(player, record); }
    @Override protected TaskState onTick() {
        if (player.isSleeping()) return TaskState.SUCCESS;
        var context = ClientRuntime.requireContext(player);
        if (receipt == null) {
            if (!context.level().isLoaded(r.bed)) {
                fail("bed is outside loaded client terrain", FailureType.TARGET_LOST);
                return TaskState.FAILED;
            }
            if (player.getEyePosition().distanceTo(Vec3.atCenterOf(r.bed)) > 4.5) {
                fail("bed is outside interaction reach", FailureType.OUT_OF_REACH);
                return TaskState.FAILED;
            }
            InputDriver.lookAt(player, Vec3.atCenterOf(r.bed));
            HitResult aimed = Interaction.nativeRaytrace(player, 4.5);
            if (!(aimed instanceof BlockHitResult hit)
                    || !(context.level().getBlockState(hit.getBlockPos()).getBlock()
                            instanceof net.minecraft.world.level.block.BedBlock)
                    || hit.getBlockPos().distManhattan(r.bed) > 1) {
                if (++aimTicks >= 5) {
                    fail("bed is occluded from the current stance", FailureType.OCCLUDED);
                    return TaskState.FAILED;
                }
                return TaskState.RUNNING;
            }
            receipt = context.actions().useBlock(context, InteractionHand.MAIN_HAND, hit,
                    c -> c.player().isSleeping() ? NativeConfirmation.Verdict.APPLIED
                            : NativeConfirmation.Verdict.PENDING, 40);
            return TaskState.RUNNING;
        }
        receipt = context.actions().poll(context, receipt);
        if (!receipt.terminal()) return TaskState.RUNNING;
        if (receipt.status() == NativeActionReceipt.Status.CONFIRMED_APPLIED) return TaskState.SUCCESS;
        fail("sleep interaction was rejected or not confirmed (daytime, danger, or occupied bed): "
                + receipt.detail(), FailureType.UNKNOWN);
        return TaskState.FAILED;
    }
    @Override protected void cleanup() { receipt = null; }
    @Override protected String successMessage() { return "sleeping in bed"; }
    @Override protected String cancelledMessage() { return "sleep interrupted"; }
}
