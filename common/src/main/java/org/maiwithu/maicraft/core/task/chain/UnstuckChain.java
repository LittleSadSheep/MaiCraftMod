package org.maiwithu.maicraft.core.task.chain;

import org.maiwithu.maicraft.task.reflex.Reflex;
import org.maiwithu.maicraft.entity.InputDriver;

import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskState;
import org.maiwithu.maicraft.core.task.survival.SurvivalDecisions;
import org.maiwithu.maicraft.core.task.survival.UnstuckDetector;
import org.maiwithu.maicraft.client.runtime.GameplayAttentionMonitor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Autonomous positional-recovery survival chain. Each tick it samples the body's
 * horizontal position and whether the body was trying to move (nonzero locomotion
 * input); when a full rolling window shows "kept pushing, never moved" — the
 * signature of a body wedged against geometry — it spikes above the LLM task and
 * drives a short break-out burst (turn to a new heading, walk forward, hop), then
 * drops back. Conservative by construction: an idle body (no locomotion input) is
 * never counted as stuck, so it will not wake during legitimate idle.
 *
 * <p>The break-out is a bounded, best-effort wander driven straight through
 * {@link InputDriver} (no nav, no dig plan) — rough but safe: it is capped at
 * {@link #WANDER_TICKS} and never travels far. The pure detection logic lives in
 * {@link UnstuckDetector} so it is unit-tested headless.
 */
public final class UnstuckChain implements Task, org.maiwithu.maicraft.task.reflex.Reflex {

    /** Rolling window length (ticks) and the disc radius (blocks) that counts as "not moving". */
    private static final int WINDOW = 40;
    private static final double MOVE_THRESHOLD = 0.75;
    /** Length of one break-out burst. */
    private static final int WANDER_TICKS = 30;

    private final UnstuckDetector detector = new UnstuckDetector(WINDOW, MOVE_THRESHOLD);
    private int wanderTicksLeft;
    private float wanderYaw;
    private boolean attentionActive;
    private double attentionStartX;
    private double attentionStartZ;

    @Override
    public boolean canRun(LocalPlayer companion) {
        Vec3 pos = companion.position();
        boolean tryingToMove = companion.zza != 0.0f || companion.xxa != 0.0f;
        detector.record(pos.x, pos.z, tryingToMove);

        if (wanderTicksLeft > 0) return true;   // finish the burst
        return detector.isStuck();
    }

    @Override
    public TaskState tick(LocalPlayer companion) {
        if (wanderTicksLeft <= 0) {
            // Begin a fresh break-out: pick a new heading (turn ~137° off current so
            // repeated attempts fan out) and clear the window so we re-evaluate after.
            wanderTicksLeft = WANDER_TICKS;
            wanderYaw = companion.getYRot() + 137.0f;
            detector.reset();
            attentionActive = true;
            attentionStartX = companion.getX();
            attentionStartZ = companion.getZ();
            GameplayAttentionMonitor.reflexStarted(
                    id(), "movement input produced too little displacement", "bounded emergency movement",
                    "no item consumption expected",
                    "short blind movement can enter nearby hazards");
        }
        driveWander(companion);
        if (--wanderTicksLeft <= 0) {
            InputDriver.halt(companion);
            finishAttention(companion, "bounded escape burst completed", WANDER_TICKS);
        }
        return TaskState.RUNNING;
    }

    @Override
    public void stop(LocalPlayer companion, StopReason why) {
        int actions = Math.max(0, WANDER_TICKS - wanderTicksLeft);
        finishAttention(companion,
                why == StopReason.PREEMPTED
                        ? "escape burst preempted by a higher safety reflex"
                        : "escape burst stopped before its result was confirmed",
                actions);
        InputDriver.halt(companion);
        org.maiwithu.maicraft.client.runtime.ClientRuntime.requireContext(companion).body().releaseAll();
        wanderTicksLeft = 0;
        detector.reset();
    }

    private void finishAttention(LocalPlayer companion, String outcome, int actions) {
        if (!attentionActive) return;
        double dx = companion.getX() - attentionStartX;
        double dz = companion.getZ() - attentionStartZ;
        double distance = Math.sqrt(dx * dx + dz * dz);
        GameplayAttentionMonitor.reflexFinished(
                id(), outcome, actions,
                "no item consumption observed",
                "horizontal displacement: " + Math.round(distance * 10.0) / 10.0 + " blocks; no coordinates exposed");
        attentionActive = false;
    }

    @Override
    public String name() {
        return "unstuck";
    }

    // ---- Reflex roster paperwork (constitution §6) ----

    @Override
    public String id() {
        return name();
    }

    @Override
    public String describe() {
        return "被地形卡住时会自己挣脱出来";
    }

    /** Face the chosen heading, push forward, and hop periodically to clear a lip/step. */
    private void driveWander(LocalPlayer companion) {
        InputDriver.look(companion, wanderYaw, companion.getXRot());
        InputDriver.applyMovement(companion, 1.0f, 0.0f,
                wanderTicksLeft % 5 == 0, false, false);
    }
}
