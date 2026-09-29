// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.chain;

import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.AABB;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.client.runtime.GameplayAttentionMonitor;
import org.maiwithu.maicraft.core.Constants;
import org.maiwithu.maicraft.core.data.WorldTimeSemantics;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.scan.TargetIndex;
import org.maiwithu.maicraft.core.task.base.LandmarkProtection;
import org.maiwithu.maicraft.core.task.sleep.NightRestTask;
import org.maiwithu.maicraft.core.task.sleep.NightRestRouteProbe;
import org.maiwithu.maicraft.core.pathing.baritone.EmbeddedBaritoneRuntime;
import org.maiwithu.maicraft.core.task.build.BuildEdgeMotion;
import org.maiwithu.maicraft.intent.IntentRuntime;
import org.maiwithu.maicraft.intent.IntentTaskRecord;
import org.maiwithu.maicraft.task.CompanionTickDispatcher;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.TaskState;
import org.maiwithu.maicraft.task.reflex.Reflex;

/** 夜间普通工作在安全间隙自动休息；先让防摔、换气、自卫处理危险，不抢占菜单事务或明确的夜间活动。 */
public final class NightRestChain implements Task, Reflex {
    public static final String ID = "night_rest";
    private final Set<Block> beds = BuiltInRegistries.BLOCK.stream().filter(block -> block instanceof BedBlock).collect(Collectors.toUnmodifiableSet());
    private final Set<BlockPos> attempted = new HashSet<>();
    private final Set<BlockPos> unreachable = new HashSet<>();
    private NightRestRouteProbe routeProbe;
    private BlockPos probeOrigin;
    private NightRestTask rest;
    private NightRestTask.Record record;
    private BlockPos candidate;
    private boolean registered;
    private long retryAt, night = Long.MIN_VALUE;
    private long preemptedAt = -1;
    private float initialHealth;

    @Override public boolean canRun(LocalPlayer player) {
        if (rest != null) return true;
        long now = player.level().getGameTime();
        if (now < retryAt || !player.isAlive() || player.getAbilities().instabuild || !player.onGround()
                || player.isPassenger() || player.isInWater() || player.isUsingItem() || player.isSleeping()
                || !ordinaryWork(CompanionTickDispatcher.current()) || !ClientRuntime.actor().settledForRoutinePause()) return false;
        var context = ClientRuntime.requireContext(player);
        if (context.minecraft().screen != null || player.containerMenu != player.inventoryMenu
                || !WorldTimeSemantics.canAttemptSleep(player.level()) || !BedBlock.canSetSpawn(player.level())) return false;
        // 临边保持和低顶挪位属于未完成的身体操作；等角色回到完整且可站立的落脚面后再考虑普通休息。
        if (!BuildEdgeMotion.canStandAt(player, NavigationSafetyContext.forbiddenBodyCells(),
                pos -> !NavigationSafetyContext.forbidsBody(pos))) return false;
        long day = WorldTimeSemantics.dayIndex(player.level());
        if (night != day) { attempted.clear(); unreachable.clear(); routeProbe = null; night = day; }
        if (!player.blockPosition().equals(probeOrigin)) { unreachable.clear(); routeProbe = null; probeOrigin = player.blockPosition().immutable(); }
        var protection = protection(player);
        if (!protection.problems().isEmpty()) return false;
        if (!registered) { TargetIndex.register(player.clientLevel, beds); registered = true; }
        return protection.run(() -> {
            var excluded = new HashSet<>(attempted); excluded.addAll(unreachable);
            var found = TargetIndex.query(player.clientLevel, player.blockPosition(), beds, 64, 2, 256, excluded);
            for (BlockPos at : found.hits()) if (usable(player, at)) {
                if (routeProbe == null || !routeProbe.matches(player, at)) {
                    try { routeProbe = new NightRestRouteProbe(player, at, EmbeddedBaritoneRuntime.physicalObstacles()); }
                    catch (RuntimeException | LinkageError unavailable) { unreachable.add(at.immutable()); routeProbe = null; return false; }
                }
                if (!routeProbe.advance()) return false;
                if (routeProbe.connected()) { candidate = at.immutable(); return true; }
                unreachable.add(at.immutable()); routeProbe = null; return false;
            }
            if (found.complete()) { retryAt = now + 200; unreachable.clear(); routeProbe = null; }
            return false;
        });
    }
    static boolean ordinaryWork(TaskRecord task) {
        if (task == null) return true;
        if (!(task instanceof IntentTaskRecord intent) || intent.paused() || intent.stepIndex() >= intent.steps().size()) return false;
        // 等待特定时间、战斗、钓鱼、跟随等目标需要自己的时机，不由日常休息悄悄跳过整夜。
        return switch (intent.steps().get(intent.stepIndex()).ability()) {
            case "maicraft:build_machine", "maicraft:build", "maicraft:acquire_items", "maicraft:craft", "maicraft:cook" -> true;
            default -> false;
        };
    }
    static boolean usable(LocalPlayer player, BlockPos at) {
        if (!player.level().isLoaded(at) || at.distSqr(player.blockPosition()) > 32 * 32
                || NavigationSafetyContext.protectsUse(at)) return false;
        var state = player.level().getBlockState(at);
        if (!(state.getBlock() instanceof BedBlock) || state.getValue(BedBlock.PART) != BedPart.HEAD
                || state.getValue(BedBlock.OCCUPIED)) return false;
        BlockPos foot = at.relative(state.getValue(BedBlock.FACING).getOpposite());
        return player.level().isLoaded(foot) && player.level().getBlockState(foot).is(state.getBlock())
                && !NavigationSafetyContext.protectsUse(foot)
                && player.level().getEntitiesOfClass(Monster.class, new AABB(at).inflate(8, 5, 8), Monster::isAlive).isEmpty();
    }
    private static LandmarkProtection protection(LocalPlayer player) {
        var active = CompanionTickDispatcher.current();
        var labels = new LinkedHashSet<String>();
        if (active instanceof IntentTaskRecord intent && intent.stepIndex() < intent.steps().size()) {
            var step = intent.steps().get(intent.stepIndex()); labels.addAll(step.inheritedProtectionLabels());
            var explicit = step.parameters().get("protected_labels");
            if (explicit != null && explicit.isJsonArray()) explicit.getAsJsonArray().forEach(label -> labels.add(label.getAsString()));
        }
        return LandmarkProtection.resolve(List.copyOf(labels), IntentRuntime.get().landmarks(), player.level().dimension().location().toString());
    }
    @Override public TaskState tick(LocalPlayer player) {
        long now = player.level().getGameTime();
        // 自卫、换气等本能占用身体期间夜休没有执行；恢复时先补回这段时间，再检查是否还能返回原工位。
        if (record != null && preemptedAt >= 0) {
            long paused = Math.max(0, now - preemptedAt);
            long deadline = record.getDeadlineGameTime();
            record.extendDeadlineTo(deadline > Long.MAX_VALUE - paused ? Long.MAX_VALUE : deadline + paused);
            preemptedAt = -1;
        }
        if (rest == null) {
            if (candidate == null || !usable(player, candidate)) { retryAt = now + 200; return TaskState.RUNNING; }
            attempted.add(candidate); initialHealth = player.getHealth();
            record = new NightRestTask.Record("night-rest-" + now, now + 1800, player.blockPosition(), candidate);
            rest = new NightRestTask(player, record); rest.start(player);
            GameplayAttentionMonitor.reflexStarted(ID, "nighttime and a usable nearby bed were observed", "rest until morning and return to work",
                    "no item consumption expected", "native bed use can update the normal respawn point");
            Constants.LOG.info("[maicraft-rest] 夜间休息开始，结束后返回原工位");
        }
        var protection = protection(player);
        TaskState state;
        if (!protection.problems().isEmpty()) state = TaskState.FAILED;
        else if (now >= record.getDeadlineGameTime()) state = TaskState.TIMEOUT;
        else state = protection.run(() -> rest.tick(player));
        if (state.isTerminal()) finish(player, state);
        return TaskState.RUNNING;
    }
    private void finish(LocalPlayer player, TaskState state) {
        var result = rest.result(state); rest = null; record = null; candidate = null; retryAt = player.level().getGameTime() + 1200;
        // 取消、死亡或正常收尾后丢弃旧抢占时间，下一次夜休不能获得上一趟的执行预算。
        preemptedAt = -1;
        Constants.LOG.info("[maicraft-rest] 收尾 {} {}", state, result.message());
        GameplayAttentionMonitor.reflexFinished(ID, result.message(), Boolean.TRUE.equals(result.data().get("slept_until_morning")) ? 1 : 0,
                "no items consumed by night rest", "observed health change=" + (player.getHealth() - initialHealth));
    }
    @Override public void stop(LocalPlayer player, StopReason reason) {
        if (rest != null) {
            // 连续多次暂停只记第一次失去身体的时刻，防止重复暂停缩短实际等待区间。
            if (reason == StopReason.PREEMPTED && preemptedAt < 0) preemptedAt = player.level().getGameTime();
            rest.stop(player, reason);
            if (reason != StopReason.PREEMPTED) finish(player, TaskState.CANCELLED);
        }
        if (reason != StopReason.PREEMPTED && registered) { TargetIndex.unregister(player.clientLevel, beds); registered = false; }
    }
    @Override public String name() { return "NightRestChain"; }
    @Override public String id() { return ID; }
    @Override public String describe() { return "普通工作夜间有安全床可用时自动休息，醒后返回原工位；紧急自救优先。"; }
}
