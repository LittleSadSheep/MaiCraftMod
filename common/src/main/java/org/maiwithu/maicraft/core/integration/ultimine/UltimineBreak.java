// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.ultimine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.act.BlockDigger;
import org.maiwithu.maicraft.entity.InputDriver;

/** 对准指定面 → 原生预览 → 持键挖触发块 → 松键 → 复查整批；每次只拥有一个真实破坏事务。 */
public final class UltimineBreak implements AutoCloseable {
    public enum Status { RUNNING, COMPLETE, NO_SHOT, FAILED }
    public record Result(Status status, Map<BlockPos, BlockState> removed, Map<String, Object> evidence, boolean uncertain) {}
    private final LocalPlayer player;
    private final BlockPos origin;
    private final BlockDigger digger;
    private final UltimineControl control;
    private final Predicate<BlockPos> allowed, preserve;
    private final UltimineSession.Mode mode;
    private UltimineBatch batch;
    private boolean armed, attempted, originConfirmed, finishing, uncertain, closed;
    private long settleUntil;
    private Status outcome = Status.COMPLETE;
    private String reason = "preparing_native_selection";
    private Result result;

    public UltimineBreak(LocalPlayer player, BlockPos origin, Direction face, UltimineSession.Mode mode,
                         Predicate<BlockPos> allowed, Predicate<BlockPos> preserve) {
        this(player, origin, face, mode, allowed, preserve, new UltimineSession(mode));
    }
    /** 可替换的原生边界用于回放服务端分包；生产入口始终使用真实 FTB 会话。 */
    public UltimineBreak(LocalPlayer player, BlockPos origin, Direction face, UltimineSession.Mode mode,
                         Predicate<BlockPos> allowed, Predicate<BlockPos> preserve, UltimineControl control) {
        this.player = player; this.origin = origin.immutable(); this.mode = mode;
        this.allowed = allowed; this.preserve = preserve; this.control = control;
        digger = new BlockDigger(player); digger.requiredFace(face); digger.beforeBreak(this::prepare);
    }

    public Result tick() {
        if (result != null) return result;
        InputDriver.halt(player);
        if (finishing) return settle();
        var context = ClientRuntime.requireContext(player);
        BlockState state = read(origin);
        if (state == null) return interrupt("ultimine_origin_unloaded");
        BlockDigger.DigResult dig;
        if (state.isAir()) {
            // 触发块消失后只能轮询原交易；再点一次会误击通道后方另一块。
            dig = digger.settleGone(true);
            if (dig == BlockDigger.DigResult.BROKE_TARGET) return confirmed();
        } else dig = null;
        if (armed && digger.hasPendingBreak()) {
            var keep = control.tickInFlight(context);
            if (!keep.ready()) return interrupt(keep.code());
        }
        if (dig == null) dig = digger.digTargetStep(origin);
        if (dig == BlockDigger.DigResult.BROKE_TARGET) return confirmed();
        if (dig == BlockDigger.DigResult.NO_SHOT) {
            outcome = Status.NO_SHOT; finishing = true; settleUntil = player.level().getGameTime();
            digger.cancel(); return settle();
        }
        if (finishing) return settle();
        return running();
    }

    private boolean prepare(BlockHitResult hit) {
        // 命中面由原生射线产生；授权只检查实际原生选区，不从区块索引自行扩展矿脉。
        var decision = control.prepareSelection(ClientRuntime.requireContext(player), hit, allowed, preserve);
        reason = decision.code();
        switch (decision.status()) {
            case READY, SINGLE_BLOCK -> {
                armed = decision.ready();
                List<BlockPos> selection = armed ? decision.completeSelection() : List.of(origin);
                batch = new UltimineBatch(origin, selection, this::read);
                attempted = true; return true;
            }
            case ABORT, BLOCKED -> {
                outcome = Status.FAILED; finishing = true; settleUntil = player.level().getGameTime(); return false;
            }
            default -> { return false; }
        }
    }

    private Result confirmed() {
        originConfirmed = true; finishing = true;
        // FTB 会按耐久、饥饿等原生条件提前停止；留一秒接收副目标分包后报告剩余格，不能假定整脉全清。
        settleUntil = player.level().getGameTime() + (armed ? 20 : 0);
        return settle();
    }
    private Result settle() {
        var release = control.finish(ClientRuntime.requireContext(player));
        if (release.status() == UltimineSession.Status.WAITING) return running();
        if (release.status() == UltimineSession.Status.ABORT || release.status() == UltimineSession.Status.BLOCKED) {
            outcome = Status.FAILED; reason = release.code(); uncertain |= attempted && !originConfirmed;
        }
        var observation = observe();
        if (originConfirmed && !observation.allRemoved() && player.level().getGameTime() < settleUntil) return running();
        uncertain |= observation.unknown() > 0;
        result = new Result(outcome, observation.removed(), evidence(observation), uncertain);
        close(); return result;
    }
    private Result interrupt(String code) {
        reason = code; outcome = Status.FAILED; uncertain |= attempted && !originConfirmed;
        finishing = true; settleUntil = player.level().getGameTime();
        digger.cancel(); control.close(); return settle();
    }
    private BlockState read(BlockPos at) { return player.level().isLoaded(at) ? player.level().getBlockState(at) : null; }
    private UltimineBatch.Observation observe() {
        return batch == null ? new UltimineBatch.Observation(Map.of(), List.of(), 0, 0) : batch.observe(this::read, originConfirmed);
    }
    private Result running() { return new Result(Status.RUNNING, Map.of(), Map.of(), false); }
    private Map<String, Object> evidence(UltimineBatch.Observation observation) {
        var data = new LinkedHashMap<String, Object>();
        data.put("mode", mode.name().toLowerCase(Locale.ROOT)); data.put("origin", UltimineBatch.position(origin));
        data.put("native_chain_started", armed && attempted); data.put("origin_break_confirmed", originConfirmed);
        data.put("reason", reason); data.put("selected_cells", batch == null ? 0 : batch.size());
        data.put("observed_removed", observation.removed().size()); data.put("remaining", observation.remaining());
        data.put("unknown", observation.unknown()); data.put("cells", observation.cells());
        data.put("hold", control.holdEvidence()); data.put("outcome_uncertain", uncertain);
        return Map.copyOf(data);
    }
    /** 暂停或取消也留下完整现场；不把尚未确认的触发块与副目标记成已完成采集。 */
    public Map<String, Object> interruptedEvidence() {
        uncertain |= attempted && !originConfirmed; reason = "ultimine_task_interrupted";
        return evidence(observe());
    }
    public BlockPos origin() { return origin; }
    public boolean inFlight() { return attempted && result == null; }
    @Override public void close() {
        if (closed) return;
        closed = true; control.close(); digger.cancel();
    }
}
