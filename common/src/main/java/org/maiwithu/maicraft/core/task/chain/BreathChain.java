package org.maiwithu.maicraft.core.task.chain;

import org.maiwithu.maicraft.task.reflex.Reflex;
import org.maiwithu.maicraft.entity.InputDriver;

import org.maiwithu.maicraft.core.WorkProfile;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskState;
import org.maiwithu.maicraft.core.task.survival.SurvivalDecisions;
import org.maiwithu.maicraft.client.runtime.GameplayAttentionMonitor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Autonomous surface-for-air survival chain — the player-body equivalent of the
 * float instinct every vanilla Mob gets for free. Automated LocalPlayer input does not have a
 * holding the jump key: navigation strokes it afloat only while a move is being
 * executed, so a body left idle in deep water (a task that ended mid-swim, an
 * owner Stop, plain wandering) sinks, runs out of air, and drowns. This chain
 * polls head-submersion + air supply each tick; once air dips past
 * {@link SurvivalDecisions#LOW_AIR_TICKS} it takes the body, swims straight up,
 * and keeps ownership after the head clears until the authoritative air value
 * is full.  That recovery hysteresis prevents the interrupted navigation edge
 * from immediately diving again on the first breathable tick.
 *
 * <p>Straight-up handles the open-water cases. Under a sealed ceiling (frozen
 * ocean, flooded cave — the terrain that actually drowned a body while it
 * pressed uselessly against pack ice) it BFS-walks the connected water for the
 * nearest column with breathable space above and swims toward that opening,
 * still stroking upward. Only when no opening exists within the search budget
 * does it fall back to best-effort straight-up and diaries the entrapment so
 * the cognition layer hears about it while there is still air to act on.
 */
public final class BreathChain implements Task, org.maiwithu.maicraft.task.reflex.Reflex {

    /** How high the straight-up column is probed before calling the ceiling sealed;
     *  deeper unbroken water than this means "open ocean, just keep rising". */
    private static final int CEILING_PROBE = 16;
    /** BFS budget over connected water cells when hunting a breathable opening. */
    private static final int AIR_SEARCH_BUDGET = 400;
    /** Horizontal cap of that hunt (per axis, blocks from the start column). */
    private static final int AIR_SEARCH_RADIUS = 16;
    /** Ticks between re-validating/re-picking the opening being swum toward. */
    private static final int RETARGET_TICKS = 20;

    /** Lowest air seen during the current episode (drives the one diary line). */
    private int worstAir = Integer.MAX_VALUE;
    private boolean episodeActive;
    /** Water cell with breathable space above it — the opening being swum toward
     *  while a ceiling seals the straight-up column (null = rising straight). */
    private BlockPos airColumn;
    private int retargetCooldown;
    /** One trapped-diary line per episode, written the moment the search comes up
     *  empty — while there is still air left for the cognition layer to act on. */
    private boolean trappedNoted;
    /** 无畏画像的入水计时(不扣氧,改按持续没顶时间触发漂浮)。 */
    private int submergedTicks;
    private float attentionStartHealth;
    private int swimTicks;
    /** 没顶多久后开始上浮——对齐生存端低氧窗口的量级(300-240=60 tick,3 秒)。 */
    private static final int FEARLESS_FLOAT_DELAY_TICKS = 60;

    public BreathChain() {
    }

    @Override
    public boolean canRun(LocalPlayer companion) {
        // 无畏画像(创造)不扣氧气,airSupply 恒满——但这条反射是假玩家唯一的
        // 漂浮本能,不能跟着休眠(否则闲置沉底就永远留在水底)。改按
        // "眼在水下持续 N tick"触发,窗口对齐生存的低氧阈值。
        boolean triggered;
        boolean headUnderWater = companion.isEyeInFluid(FluidTags.WATER);
        if (WorkProfile.of(companion).fearless()) {
            if (headUnderWater) {
                submergedTicks++;
            } else {
                submergedTicks = 0;
            }
            // Creative/fearless bodies do not consume air, so their episode ends
            // at a genuinely breathable eye position rather than waiting for an
            // air value which was full throughout.
            triggered = episodeActive
                    ? headUnderWater
                    : submergedTicks > FEARLESS_FLOAT_DELAY_TICKS;
        } else {
            submergedTicks = 0;
            triggered = episodeActive
                    ? SurvivalDecisions.breathRecoveryRequired(
                            companion.isInWater(), headUnderWater,
                            companion.getAirSupply(), companion.getMaxAirSupply())
                    : SurvivalDecisions.breathTriggered(headUnderWater, companion.getAirSupply());
        }
        if (!triggered && episodeActive) {
            noteEpisode(companion);
        }
        return triggered;
    }

    @Override
    public TaskState tick(LocalPlayer companion) {
        if (!episodeActive) {
            attentionStartHealth = companion.getHealth();
            swimTicks = 0;
            GameplayAttentionMonitor.reflexStarted(
                    id(), "air supply is low while submerged", "emergency movement",
                    "no item consumption expected", "drowning damage or death if air is not reached");
        }
        episodeActive = true;
        swimTicks++;
        worstAir = Math.min(worstAir, companion.getAirSupply());
        InputDriver.halt(companion);
        // Straight up is the cheap common rescue (open water). Only a sealed column
        // engages the lateral hunt: swim through connected water toward the nearest
        // opening with air above it (an ice hole, the cave mouth), still stroking up.
        if (!ceilingSealed(companion)) {
            airColumn = null;
        } else {
            if (airColumn == null || --retargetCooldown <= 0
                    || !breathableAbove(companion.level(), airColumn)) {
                airColumn = findAirColumn(companion);
                retargetCooldown = RETARGET_TICKS;
                if (airColumn == null) {
                    noteTrapped(companion);
                }
            }
            if (airColumn != null) {
                InputDriver.stepToward(companion, Vec3.atCenterOf(airColumn), false);
            }
        }
        // While the body is still touching water this both surfaces and holds the
        // eyes above the interface during the refill phase.  Once fully ashore,
        // canRun() releases immediately instead of making the player hop on land.
        InputDriver.jump(companion);
        return TaskState.RUNNING;
    }

    /**
     * Is the column straight above the head sealed before it reaches breathable
     * space? Unbroken water deeper than {@link #CEILING_PROBE} counts as open —
     * that is the deep-ocean case where rising is exactly right.
     */
    private static boolean ceilingSealed(LocalPlayer companion) {
        Level level = companion.level();
        BlockPos p = BlockPos.containing(companion.getEyePosition());
        for (int i = 0; i < CEILING_PROBE; i++) {
            p = p.above();
            BlockState s = level.getBlockState(p);
            if (s.getFluidState().is(FluidTags.WATER)) continue;
            return !breathable(level, p, s);
        }
        return false;
    }

    /** A cell the head could breathe in: no fluid, nothing to collide with. */
    private static boolean breathable(Level level, BlockPos pos, BlockState state) {
        return state.getFluidState().isEmpty() && state.getCollisionShape(level, pos).isEmpty();
    }

    /** Is {@code waterCell} still a valid opening: water with breathable space above? */
    private static boolean breathableAbove(Level level, BlockPos waterCell) {
        if (!level.getFluidState(waterCell).is(FluidTags.WATER)) return false;
        BlockPos above = waterCell.above();
        return breathable(level, above, level.getBlockState(above));
    }

    /**
     * BFS through connected water from the head for the nearest cell with
     * breathable space directly above — nearest-by-swim-distance, so the body
     * heads for the closest real opening, not a straight-line mirage behind a
     * wall. Bounded by {@link #AIR_SEARCH_BUDGET}/{@link #AIR_SEARCH_RADIUS}:
     * ~400 block reads once per {@link #RETARGET_TICKS} during an episode.
     */
    private static BlockPos findAirColumn(LocalPlayer companion) {
        Level level = companion.level();
        BlockPos start = BlockPos.containing(companion.getEyePosition());
        if (!level.getFluidState(start).is(FluidTags.WATER)) {
            start = companion.blockPosition();
        }
        java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
        java.util.HashSet<Long> seen = new java.util.HashSet<>();
        queue.add(start);
        seen.add(start.asLong());
        int budget = AIR_SEARCH_BUDGET;
        while (!queue.isEmpty() && budget-- > 0) {
            BlockPos cell = queue.poll();
            if (breathableAbove(level, cell)) {
                return cell;
            }
            for (Direction d : Direction.values()) {
                BlockPos n = cell.relative(d);
                if (Math.abs(n.getX() - start.getX()) > AIR_SEARCH_RADIUS
                        || Math.abs(n.getZ() - start.getZ()) > AIR_SEARCH_RADIUS) continue;
                if (!level.getFluidState(n).is(FluidTags.WATER)) continue;
                if (seen.add(n.asLong())) {
                    queue.add(n);
                }
            }
        }
        return null;
    }

    /** Diary the entrapment the moment it is diagnosed — not post-mortem. */
    private void noteTrapped(LocalPlayer companion) {
        if (trappedNoted) return;
        trappedNoted = true;
        GameplayAttentionMonitor.reflexEscalated(
                id(), "the direct ascent is sealed and no nearby opening was proved",
                "air continues falling while the reflex uses best-effort upward movement");
        org.maiwithu.maicraft.core.Constants.LOG.info(
                "[maicraft-breath] drowning under a sealed ceiling with {}s of air; no opening within {} blocks",
                Math.max(0, companion.getAirSupply() / 20), AIR_SEARCH_RADIUS);
    }

    /** One diary line per near-drowning, stamped with how close it got (in seconds of air left). */
    private void noteEpisode(LocalPlayer companion) {
        int worst = worstAir;
        float healthLost = Math.max(0.0F, attentionStartHealth - companion.getHealth());
        GameplayAttentionMonitor.reflexFinished(
                id(), "breathable air reached", swimTicks,
                "no item consumption observed",
                healthLost > 0.0F ? "health lost during reflex: " + healthLost : "no health loss observed");
        episodeActive = false;
        worstAir = Integer.MAX_VALUE;
        swimTicks = 0;
        airColumn = null;
        retargetCooldown = 0;
        trappedNoted = false;
        org.maiwithu.maicraft.core.Constants.LOG.info(
                "[maicraft-breath] nearly drowned ({}s of air left); swam up for a breath",
                Math.max(0, worst / 20));
    }

    @Override
    public void stop(LocalPlayer companion, StopReason why) {
        // No cross-tick body state to unwind; the episode bookkeeping closes on the
        // next dormant read (or is superseded by a fresh dip).
        if (episodeActive && why != StopReason.PREEMPTED) {
            float healthLost = Math.max(0.0F, attentionStartHealth - companion.getHealth());
            GameplayAttentionMonitor.reflexFinished(
                    id(), "body or reflex unavailable; air recovery unconfirmed", swimTicks,
                    "no item consumption observed",
                    healthLost > 0.0F ? "health lost during reflex: " + healthLost : "no health loss observed");
            episodeActive = false;
            worstAir = Integer.MAX_VALUE;
            swimTicks = 0;
            airColumn = null;
            trappedNoted = false;
        }
    }

    @Override
    public String name() {
        return "breath";
    }

    // ---- Reflex roster paperwork (constitution §6) ----

    @Override
    public String id() {
        return name();
    }

    @Override
    public String describe() {
        return "在水里快憋不住气时会自己浮上来换气,头顶被冰面/岩层封住时会游向最近的透气口";
    }
}
