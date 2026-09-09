package org.maiwithu.maicraft.core.task.sleep;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.client.actor.NativeConfirmation;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.task.ActualViewConvergenceGate;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.act.Interaction;
import org.maiwithu.maicraft.entity.InputDriver;
import org.maiwithu.maicraft.task.TaskState;

/** 已经走到床边后，在这里对准床、右键，并等游戏确认玩家真的躺下。 */
public final class SleepCompanionTask extends AbstractCompanionTask<SleepTaskRecord> {
    private NativeActionReceipt receipt;
    private final ActualViewConvergenceGate aimConvergence = new ActualViewConvergenceGate();
    public SleepCompanionTask(LocalPlayer player, SleepTaskRecord record) { super(player, record); }
    @Override protected TaskState onTick() {
        // 已经躺下就完成；“睡觉”任务只负责躺下，不等到第二天天亮。
        if (player.isSleeping()) return TaskState.SUCCESS;
        var context = ClientRuntime.requireContext(player);
        if (receipt == null) {
            // 每次准备点击都按原版床的维度规则检查；规划通过后仍可能换维度，爆炸不能靠事后确认补救。
            if (!BedBlock.canSetSpawn(context.level())) {
                fail("beds explode in this dimension; sleeping here is unsafe", FailureType.HAZARD);
                return TaskState.FAILED;
            }
            // 还没点击时先检查床是否仍在已加载区域、是否伸手够得着，避免对失效位置操作。
            if (!context.level().isLoaded(r.bed)) {
                fail("bed is outside loaded client terrain", FailureType.TARGET_LOST);
                return TaskState.FAILED;
            }
            if (player.getEyePosition().distanceTo(Vec3.atCenterOf(r.bed)) > 4.5) {
                fail("bed is outside interaction reach", FailureType.OUT_OF_REACH);
                return TaskState.FAILED;
            }
            Vec3 aim = Vec3.atCenterOf(r.bed);
            InputDriver.lookAt(player, aim);
            // 先让玩家真正转头看向床；提出转头要求并不代表画面已经转到位。
            if (!aimConvergence.ready(player, aim.subtract(player.getEyePosition()))) {
                return TaskState.RUNNING;
            }
            HitResult aimed = Interaction.nativeRaytrace(player, 4.5);
            // 看向床中心的途中可能被墙挡住；实际视线必须落到床头或相邻的床尾。
            if (!(aimed instanceof BlockHitResult hit)
                    || !(context.level().getBlockState(hit.getBlockPos()).getBlock()
                            instanceof BedBlock)
                    || hit.getBlockPos().distManhattan(r.bed) > 1) {
                fail("bed is occluded from the current stance", FailureType.OCCLUDED);
                return TaskState.FAILED;
            }
            receipt = context.actions().useBlock(context, InteractionHand.MAIN_HAND, hit,
                    c -> c.player().isSleeping() ? NativeConfirmation.Verdict.APPLIED
                            : NativeConfirmation.Verdict.PENDING, 40);
            return TaskState.RUNNING;
        }
        // 点击已发出后只等待确认，不每刻重复右键；未确认可能是白天、有怪、床被占用等。
        receipt = context.actions().poll(context, receipt);
        if (!receipt.terminal()) return TaskState.RUNNING;
        if (receipt.status() == NativeActionReceipt.Status.CONFIRMED_APPLIED) return TaskState.SUCCESS;
        fail("sleep interaction was rejected or not confirmed (daytime, danger, or occupied bed): "
                + receipt.detail(), FailureType.UNKNOWN);
        return TaskState.FAILED;
    }
    /** 丢掉任务自己的点击和瞄准记录；当前没有退役动作端口里的待确认点击，也不会主动叫醒已睡着的玩家。 */
    @Override protected void cleanup() { receipt = null; aimConvergence.reset(); }
    @Override protected String successMessage() { return "sleeping in bed"; }
    @Override protected String cancelledMessage() { return "sleep interrupted"; }
}
