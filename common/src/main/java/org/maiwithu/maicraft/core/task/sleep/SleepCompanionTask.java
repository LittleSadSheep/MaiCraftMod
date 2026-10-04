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
import org.maiwithu.maicraft.core.data.WorldTimeSemantics;
import org.maiwithu.maicraft.core.Constants;
import java.util.Map;
import org.maiwithu.maicraft.core.task.ActualViewConvergenceGate;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.act.Interaction;
import org.maiwithu.maicraft.entity.InputDriver;
import org.maiwithu.maicraft.task.TaskState;

/** 到床边后瞄准并原生右键；公开 sleep 确认躺下即结束，自动夜间休息另设 waitUntilAwake 等自然醒。 */
public final class SleepCompanionTask extends AbstractCompanionTask<SleepTaskRecord> {
    private NativeActionReceipt receipt;
    private boolean enteredSleep;
    private long wakeObservedAt = -1;
    private boolean morningObserved;
    private static final int WAKE_SYNC_TICKS = 200;
    private final ActualViewConvergenceGate aimConvergence = new ActualViewConvergenceGate();
    public SleepCompanionTask(LocalPlayer player, SleepTaskRecord record) { super(player, record); }
    // 只确认上床的任务可以结束，但原生睡眠还在继续；床上界面保留到自然醒或玩家主动离床。
    @Override public boolean keepsGuiOnCompletion() { return player.isSleeping(); }
    @Override protected TaskState onTick() {
        // 自动休息持有身体直到自然醒；只躺下就恢复施工会让角色在床上继续发动作。
        if (player.isSleeping()) {
            enteredSleep = true;
            if (receipt != null && !receipt.terminal()) {
                var observed = ClientRuntime.requireContext(player); receipt = observed.actions().poll(observed, receipt);
            }
            return r.waitUntilAwake ? TaskState.RUNNING : TaskState.SUCCESS;
        }
        if (enteredSleep && r.waitUntilAwake) {
            // 醒来实体包与世界时间/天气包不保证同刻到达；先留同步窗口，不能在第一帧仍显示夜晚时宣判睡眠失败。
            long now = player.level().getGameTime();
            if (wakeObservedAt < 0) {
                wakeObservedAt = now;
                Constants.LOG.info("[maicraft-rest] 观察到醒来，等待时间同步 game_time={} day_time={} thunder={}",
                        now, player.level().getDayTime(), player.level().isThundering());
            }
            if (!WorldTimeSemantics.canAttemptSleep(player.level())) { morningObserved = true; return TaskState.SUCCESS; }
            if (now - wakeObservedAt < WAKE_SYNC_TICKS) return TaskState.RUNNING;
            fail("woke from sleep, but morning or cleared weather was not confirmed within the synchronization window", FailureType.UNKNOWN);
            return TaskState.FAILED;
        }
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
        if (receipt.status() == NativeActionReceipt.Status.CONFIRMED_APPLIED) {
            enteredSleep = true;
            return r.waitUntilAwake ? TaskState.RUNNING : TaskState.SUCCESS;
        }
        fail("sleep interaction was rejected or not confirmed (daytime, danger, or occupied bed): "
                + receipt.detail(), FailureType.UNKNOWN);
        return TaskState.FAILED;
    }
    /** 睡眠结束或取消时结清自己的床点击；不会用新的点击或移动强行叫醒玩家。 */
    @Override protected void cleanup() {
        if (receipt != null && !receipt.terminal()) ClientRuntime.actor().activeContext().filter(context -> context.player() == player)
                .ifPresent(context -> context.actions().retireOneShotForTaskBoundary(context, receipt, "sleep task ended"));
        receipt = null; aimConvergence.reset(); super.cleanup();
    }
    @Override protected String successMessage() { return r.waitUntilAwake ? "slept and observed a natural morning wake-up" : "sleeping in bed"; }
    @Override protected Map<String, Object> resultData() {
        return Map.of("entered_sleep", enteredSleep, "wait_until_awake", r.waitUntilAwake,
                "wake_observed", wakeObservedAt >= 0, "morning_observed", morningObserved);
    }
    @Override protected String cancelledMessage() { return "sleep interrupted"; }
}
