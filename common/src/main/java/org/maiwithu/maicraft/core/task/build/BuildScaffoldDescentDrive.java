// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.act.BlockDigger;
import org.maiwithu.maicraft.core.pathing.calc.NavGoal;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.pathing.util.BlockHelper;
import org.maiwithu.maicraft.entity.InputDriver;

/** 对齐柱心 -> 验证整柱退路 -> 原生拆一格 -> 自然落稳；每格后交回清理队列，先收身边能碰到的支撑。 */
final class BuildScaffoldDescentDrive {
    enum Status { RUNNING, STEP_DONE, UNAVAILABLE, FAILED }
    private final LocalPlayer player;
    private final BlockPos first;
    private final Map<BlockPos, BlockState> owned;
    private final Predicate<BlockPos> permitted;
    private final LongSet forbidden;
    private final PlayerNav.ContextProvider walking;
    private final Consumer<BlockPos> confirmed;
    private final BlockDigger digger;
    private BuildEdgeMotion alignment;
    private BuildScaffoldDescent proof;
    private PlayerNav exitNav;
    private boolean aligned, acknowledged, exiting, exitArrived, interruptedBreak;
    private int ticks;
    private Status status = Status.RUNNING;
    private String reason = "aligning_owned_column";

    BuildScaffoldDescentDrive(LocalPlayer player, BlockPos first, Map<BlockPos, BlockState> owned,
            Predicate<BlockPos> permitted, LongSet forbidden, PlayerNav.ContextProvider walking, Consumer<BlockPos> confirmed) {
        this.player = player; this.first = first.immutable(); this.owned = Map.copyOf(owned);
        this.permitted = permitted; this.forbidden = forbidden; this.walking = walking; this.confirmed = confirmed;
        digger = new BlockDigger(player);
        alignment = BuildEdgeMotion.alignAt(Vec3.atBottomCenterOf(first.above()), forbidden,
                at -> !walking.embeddedForbiddenBodyCells().contains(at.asLong()));
    }

    Status tick() {
        if (status != Status.RUNNING) return status;
        if (++ticks > 300) return finish(Status.FAILED, "scaffold_descent_drive_timeout");
        if (!aligned) {
            var result = alignment.tick(player);
            if (result == BuildEdgeMotion.Status.FAILED) return finish(Status.UNAVAILABLE, alignment.failure());
            if (result != BuildEdgeMotion.Status.ARRIVED) return status;
            alignment.release(player); alignment = null; aligned = true; return status;
        }
        if (proof == null) {
            proof = BuildScaffoldDescent.inspect(player, first, owned, permitted, forbidden);
            if (!proof.accepted()) return finish(Status.UNAVAILABLE, proof.reason());
        }
        if (exiting) return exitTick();
        InputDriver.halt(player);
        var step = proof.step();
        if (!acknowledged) {
            BlockDigger.DigResult result;
            if (player.level().getBlockState(step.block()).isAir()) result = digger.settleGone(true);
            else {
                if (!proof.beforeBreak()) return finish(Status.FAILED, proof.reason());
                result = digger.digTargetStep(step.block());
            }
            if (result == BlockDigger.DigResult.NO_SHOT || result == BlockDigger.DigResult.BROKE_OCCLUDER)
                return finish(Status.FAILED, "scaffold_descent_native_break_unconfirmed");
            if (result == BlockDigger.DigResult.BROKE_TARGET) {
                // 真实破坏确认和身体落地是两件事；先精确记账一次，再等身体自然落稳。
                acknowledged = true; confirmed.accept(step.block());
            }
        }
        var observed = proof.observe(acknowledged, player.level().getGameTime());
        if (observed == BuildScaffoldDescent.Status.REJECTED) return finish(Status.FAILED, proof.reason());
        if (observed == BuildScaffoldDescent.Status.LANDED) {
            if (proof.steps().size() > 1) return finish(Status.STEP_DONE, "one_owned_support_removed_and_landed");
            proof.advance(); exiting = true;
        }
        return status;
    }

    private Status exitTick() {
        // 最后一格拆完后实际走上旁边连片地面，避免只在证明里离开柱底就宣布完成。
        Vec3 exit = proof.exit();
        var observed = proof.observe(false, player.level().getGameTime());
        if (observed == BuildScaffoldDescent.Status.REJECTED) return finish(Status.FAILED, proof.reason());
        if (observed == BuildScaffoldDescent.Status.COMPLETE) return finish(Status.STEP_DONE, proof.reason());
        if (!exitArrived) {
            if (exitNav == null) exitNav = PlayerNav.toGoal(player,
                    () -> NavGoal.exact(BlockHelper.playerFeet(player.level(), exit.x, exit.y, exit.z)),
                    BuildStanceNavigation.PRECISE_WALK, () -> player.onGround() && player.position().distanceToSqr(exit) < .01, walking).walkingOnly();
            var result = exitNav.tick();
            if (result == PlayerNav.Status.FAILED) return finish(Status.FAILED, exitNav.failReason());
            if (result != PlayerNav.Status.ARRIVED) return status;
            exitNav.stop(); exitNav = null; exitArrived = true;
            alignment = BuildEdgeMotion.alignAt(exit, forbidden, at -> !walking.embeddedForbiddenBodyCells().contains(at.asLong()));
        }
        if (alignment != null) {
            var result = alignment.tick(player);
            if (result == BuildEdgeMotion.Status.FAILED) return finish(Status.FAILED, alignment.failure());
            if (result == BuildEdgeMotion.Status.ARRIVED) { alignment.release(player); alignment = null; }
        }
        return status;
    }

    private Status finish(Status next, String detail) { status = next; reason = detail; stop(); return status; }
    void stop() {
        // 中断不能带着旧身体控制租约继续下拆；已发生的确认由调用方保留，未完成阶段重新观察后再恢复。
        if (!acknowledged && first.equals(digger.current())) {
            // 暂停边界也可能刚收到服务器确认；只结算已有回执，绝不把空气本身当作本次挖掘成功。
            if (player.level().isLoaded(first) && player.level().getBlockState(first).isAir()
                    && org.maiwithu.maicraft.client.runtime.ClientRuntime.actor().activeContext()
                    .filter(context -> context.player() == player && context.isCurrent()).isPresent()
                    && digger.settleGone(true) == BlockDigger.DigResult.BROKE_TARGET) {
                acknowledged = true; confirmed.accept(first);
            }
            interruptedBreak = !acknowledged;
        }
        if (status == Status.RUNNING) { status = Status.FAILED; reason = "scaffold_descent_interrupted_reobserve_required"; }
        if (alignment != null) alignment.stop(player); if (exitNav != null) exitNav.stop(); digger.cancel(); InputDriver.halt(player);
    }
    Map<String, Object> evidence() { return Map.of("state", status.name(), "reason", reason, "confirmed_removal", acknowledged, "interrupted_break_uncertain", interruptedBreak,
            "proof", proof == null ? Map.of() : proof.evidence()); }
    String reason() { return reason; }
}
