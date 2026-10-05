// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.sleep;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.client.actor.NativeConfirmation;
import org.maiwithu.maicraft.client.chat.ChatMonitor;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.data.WorldTimeSemantics;
import org.maiwithu.maicraft.core.Constants;
import java.util.LinkedHashMap;
import java.util.Map;
import org.maiwithu.maicraft.core.task.ActualViewConvergenceGate;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.act.Interaction;
import org.maiwithu.maicraft.entity.InputDriver;
import org.maiwithu.maicraft.task.TaskState;

/** 到床边后瞄准并原生右键；公开 sleep 确认躺下即结束，自动夜间休息另设 waitUntilAwake 等自然醒。 */
public final class SleepCompanionTask extends AbstractCompanionTask<SleepTaskRecord> {
    /** 原生点击与就近检查共用的床交互半径（眼位到床中心的直线距离）；接近闸口必须与本闸同判。 */
    public static final double INTERACTION_REACH = 4.5;
    private NativeActionReceipt receipt;
    private boolean enteredSleep;
    private long wakeObservedAt = -1;
    private boolean morningObserved;
    /** 床点击提交那刻的日指数；确认窗读到白天时用来识别“入睡本身把整夜跳过了”的后验事实。 */
    private long clickDayIndex = Long.MIN_VALUE;
    private boolean nightSkippedBySleep;
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
            if (player.getEyePosition().distanceTo(Vec3.atCenterOf(r.bed)) > INTERACTION_REACH) {
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
            clickDayIndex = WorldTimeSemantics.dayIndex(context.level());
            return TaskState.RUNNING;
        }
        // 点击已发出后只等待确认，不每刻重复右键；未确认可能是白天、有怪、床被占用等。
        receipt = context.actions().poll(context, receipt);
        if (!receipt.terminal()) return TaskState.RUNNING;
        if (receipt.status() == NativeActionReceipt.Status.CONFIRMED_APPLIED) {
            enteredSleep = true;
            return r.waitUntilAwake ? TaskState.RUNNING : TaskState.SUCCESS;
        }
        // 确认窗读到“没躺下”还有一种可能：点击被服务器接受、入睡当场把整夜跳到清晨，
        // 窗口随后读到的只有醒后的白天（122 实机：day 17→18、醒在床格，终态却按拒绝收场）。
        // 确认窗最长 40 刻，日指数在这段窗口内前进只可能来自入睡本身——按世界实物判成功，
        // 回执与实物不再相反；对账事实进 result data，调用方可复核。
        if (clickDayIndex != Long.MIN_VALUE && !player.isSleeping()
                && player.getEyePosition().distanceTo(Vec3.atCenterOf(r.bed)) <= INTERACTION_REACH
                && WorldTimeSemantics.dayIndex(player.level()) > clickDayIndex) {
            enteredSleep = true;
            morningObserved = !WorldTimeSemantics.canAttemptSleep(player.level());
            nightSkippedBySleep = true;
            return TaskState.SUCCESS;
        }
        // 原版的拒绝原因（床太远/有怪/已占用）只走动作栏提示；最近 5 秒内出现过就引用原文，
        // 不再把真实原因笼统归入三选一猜测。
        String overlay = ChatMonitor.latestOverlay(5_000_000_000L);
        fail("sleep interaction was rejected or not confirmed (daytime, danger, or occupied bed)"
                + (overlay == null ? "" : "; server said: " + overlay)
                + ": " + receipt.detail(), FailureType.UNKNOWN);
        return TaskState.FAILED;
    }
    /** 睡眠结束或取消时结清自己的床点击；不会用新的点击或移动强行叫醒玩家。 */
    @Override protected void cleanup() {
        if (receipt != null && !receipt.terminal()) ClientRuntime.actor().activeContext().filter(context -> context.player() == player)
                .ifPresent(context -> context.actions().retireOneShotForTaskBoundary(context, receipt, "sleep task ended"));
        receipt = null; aimConvergence.reset(); super.cleanup();
    }
    @Override protected String successMessage() {
        if (nightSkippedBySleep) return "slept through the night: the day advanced past the bed click and the player woke at the bed";
        return r.waitUntilAwake ? "slept and observed a natural morning wake-up" : "sleeping in bed";
    }

    /** 面板行动行的一句话汇报；阶段来自真实睡眠状态与右键确认进度。 */
    @Override
    public String describeCurrentAction() {
        if (player.isSleeping()) return "正在睡觉";
        if (enteredSleep && r.waitUntilAwake) return "正在等待天亮";
        if (receipt != null) return "正在等待入睡确认";
        return "正在对准床准备入睡";
    }
    @Override protected Map<String, Object> resultData() {
        var data = new LinkedHashMap<String, Object>();
        data.put("entered_sleep", enteredSleep);
        data.put("wait_until_awake", r.waitUntilAwake);
        data.put("wake_observed", wakeObservedAt >= 0);
        data.put("morning_observed", morningObserved);
        if (nightSkippedBySleep) data.put("night_skipped_by_sleep", true);
        return Map.copyOf(data);
    }
    @Override protected String cancelledMessage() { return "sleep interrupted"; }
}
