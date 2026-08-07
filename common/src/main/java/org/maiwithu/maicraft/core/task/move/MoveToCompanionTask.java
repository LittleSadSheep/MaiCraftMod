package org.maiwithu.maicraft.core.task.move;
import org.maiwithu.maicraft.core.pathing.settings.ScaffoldMaterials;
import org.maiwithu.maicraft.core.FailureType;

import org.maiwithu.maicraft.task.TaskState;

import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.core.pathing.calc.NavGoal;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.Map;

/**
 * {@code goto} on the companion player body — a coordinate walk whose goal
 * type is chosen by which coordinates were supplied
 * ({@link MoveToTaskRecord.Kind}):
 * <ul>
 *   <li>{@link MoveToTaskRecord.Kind#COLUMN} → {@link NavGoal#column}:
 *       reach the (x,z) location at any height — the default "go there", a wrong/
 *       absent Y can never make it unreachable;</li>
 *   <li>{@link MoveToTaskRecord.Kind#BLOCK} → {@link NavGoal#exact}: occupy exactly
 *       that cell; whatever occupies it has to be dug out, which only a goto with
 *       may_alter_terrain may do (the block form is how the caller says "walk up
 *       beside it instead");</li>
 *   <li>{@link MoveToTaskRecord.Kind#YLEVEL} → {@link NavGoal#yLevel}:
 *       reach a target elevation.</li>
 * </ul>
 * The planner is untouched; only the goal/arrival/result semantics differ per kind.
 * Results always echo the ACTUAL position reached (and the real ground height) so
 * the model learns the terrain and which intent to use next time.
 *
 * <p>Nav-only reactive task: it drives {@link PlayerNav} with a custom settle loop
 * (no "act" step), so it grows on {@link AbstractCompanionTask} directly rather than
 * {@code GoToThenDoTask}.
 */
public final class MoveToCompanionTask extends AbstractCompanionTask<MoveToTaskRecord> {

    private static final long TICKS_PER_BLOCK = 20;
    private static final long MAX_EXTRA_TICKS = 5 * 60 * 20;
    /** Progress lease: while the journey is consuming its plan, the deadline is kept
     *  this far ahead — a healthy multi-minute dig route never times out mid-stride,
     *  and a stalled one still returns the body within one lease. */
    private static final long PROGRESS_LEASE_TICKS = 30 * 20;
    /** How recent "progress" must be to renew the lease. Generous enough to span one
     *  slow legitimate move (a long bare-hand dig holds the executor's progress clock
     *  at 0 anyway; this covers place maneuvers and replan gaps). */
    private static final int PROGRESS_GRACE_TICKS = 100;
    /** Hard check-in cap: even a healthy marathon yields (with a resumable result) after
     *  this long, bounding how long the LLM goes without control. Renewals never push
     *  the deadline past start + this. */
    private static final long CHECK_IN_CAP_TICKS = 5 * 60 * 20;
    /** When the planner CAN'T reach the exact goal, a stop within this of the
     *  requested column still counts as "got there" (a teaching success, not a
     *  thrash). This is the only tolerance — arrival itself is exact. */
    private static final double WALK_SPEED = 1.0;
    private static final double NEAR_SUCCESS_RADIUS = 3.0;
    /** Once the planner can't get closer (e.g. it stopped at the water surface above an
     *  underwater goal), keep the task alive this many ticks of NO progress before giving
     *  up — long enough for the body to passively drift onto a reachable underwater target,
     *  short enough to bail under an out-of-reach above-water one. */
    private static final int MAX_SETTLE_TICKS = 60;

    private final int bx;
    private final int by;
    private final int bz;
    private final BlockPos blockTarget;   // only meaningful for BLOCK kind

    private double bestDist = Double.MAX_VALUE;   // closest we've gotten to the goal
    private int settleTicks = 0;                  // ticks of no progress after the planner gave up
    /** The one near-retry recovery rung has been consumed (ladder state — survives suspend). */
    private boolean nearRetried;
    /** Absolute ceiling for lease renewals (start + {@link #CHECK_IN_CAP_TICKS}); 0 = unset. */
    private long leaseCapGameTime;

    /** FIND(就近方块)子系统:扫描/入册/契约/轮换全在组件里,此处只驱动。 */
    private NearestBlockFinder finder;

    /** 船腿:开工时坐在船上就先驾船,靠岸(或搁浅)后接步行。null = 没有/已交棒。 */
    private org.maiwithu.maicraft.core.pathing.execute.BoatNav boatLeg;

    public MoveToCompanionTask(LocalPlayer player, MoveToTaskRecord record) {
        super(player, record);
        this.bx = record.x != null ? (int) Math.floor(record.x) : 0;
        this.by = record.y != null ? (int) Math.floor(record.y) : 0;
        this.bz = record.z != null ? (int) Math.floor(record.z) : 0;
        this.blockTarget = new BlockPos(bx, by, bz);
    }

    @Override
    protected void onStart() {
        // 载具处置:坐在船上且有明确去处,先驾船——船腿走到离目标最近的水格,
        // 靠岸后接步行(见 tickBoatLeg)。其余情况(矿车没有舵、马的寻路仍按步行
        // 物理算、FIND 要先扫描)直接走步行段;下座驾是步行导航自己的事(PlayerNav)。
        if (player.isPassenger()
                && player.getVehicle() instanceof net.minecraft.world.entity.vehicle.Boat
                && (r.kind == MoveToTaskRecord.Kind.BLOCK || r.kind == MoveToTaskRecord.Kind.COLUMN)
                && !reached()) {
            boatLeg = new org.maiwithu.maicraft.core.pathing.execute.BoatNav(player, blockTarget);
            long extra = Math.min(MAX_EXTRA_TICKS,
                    600 + (long) (repDistance() * TICKS_PER_BLOCK));
            r.extendDeadlineTo(player.level().getGameTime() + extra);
            leaseCapGameTime = player.level().getGameTime() + CHECK_IN_CAP_TICKS;
            org.maiwithu.maicraft.core.Constants.LOG.info(
                    "[maicraft-task] goto start kind={} target={},{},{} 驾船先行",
                    r.kind, bx, by, bz);
            return;
        }
        if (r.kind == MoveToTaskRecord.Kind.FIND) {
            // 就近方块:解析 id → 离线扫描附近候选;导航等首批候选到手再建
            var id = net.minecraft.resources.ResourceLocation.tryParse(r.block);
            var b = id == null ? null : net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(id);
            if (b == null || b == net.minecraft.world.level.block.Blocks.AIR) {
                fail("unknown block id '" + r.block
                        + "' — use a namespaced block id like minecraft:crafting_table",
                        FailureType.NO_PATH);
                return;
            }
            finder = new NearestBlockFinder(player, b);
            long findExtra = Math.min(MAX_EXTRA_TICKS,
                    600 + (long) NearestBlockFinder.BUDGET_BLOCKS * TICKS_PER_BLOCK);
            r.extendDeadlineTo(player.level().getGameTime() + findExtra);
            leaseCapGameTime = player.level().getGameTime() + CHECK_IN_CAP_TICKS;
            finder.kickScan();
            org.maiwithu.maicraft.core.Constants.LOG.info(
                    "[maicraft-task] goto start kind=FIND block={}", r.block);
            return;
        }
        // Already there: don't build a nav (and don't extend the deadline). The first
        // onTick observes reached() and returns SUCCESS — same outcome as the old
        // start-time short-circuit, one tick later per the base's lifecycle.
        if (reached()) return;
        startWalkingNav();
    }

    /** 这次 goto 的地形许可:模型点头了才开路,否则只走不改。四处建导航都从这儿取。 */
    private PlayerNav.ContextProvider terrain() {
        return r.mayAlterTerrain ? PlayerNav.ContextProvider.TERRAFORM : PlayerNav.ContextProvider.DEFAULT;
    }

    /**
     * 步行段的启动:预算、租约、建导航。开工时走它,船腿靠岸后接力也走它——
     * 两个入口一份逻辑。
     */
    private void startWalkingNav() {
        // Initial budget from straight-line distance (terrain difficulty is unknowable
        // here — the progress lease below takes over once the journey is under way).
        long extra = Math.min(MAX_EXTRA_TICKS, 600 + (long) (repDistance() * TICKS_PER_BLOCK));
        r.extendDeadlineTo(player.level().getGameTime() + extra);
        leaseCapGameTime = player.level().getGameTime() + CHECK_IN_CAP_TICKS;
        // BLOCK targets go through the compiled front door so the target cell is
        // SACRED when solid — the route may neither dig through nor bury the very
        // block it was asked to reach. COLUMN/YLEVEL have no block objective.
        nav = (r.kind == MoveToTaskRecord.Kind.BLOCK
                ? PlayerNav.to(player, this::blockCompiled, WALK_SPEED, this::reached, terrain())
                : PlayerNav.toGoal(player, this::goal, WALK_SPEED, this::reached, terrain()))
                .withTerrainProbe();
        org.maiwithu.maicraft.core.Constants.LOG.info(
                "[maicraft-task] goto start kind={} target={},{},{} solid={}",
                r.kind, bx, by, bz,
                r.kind == MoveToTaskRecord.Kind.BLOCK && targetCellSolid());
        // Highlight the ACTUAL requested cell (not the path's best-effort end) so the overlay
        // box sits on the real target — e.g. a BLOCK goal under/over water that the path can
    }

    /** The navigation goal for this move's kind. */
    private NavGoal goal() {
        return switch (r.kind) {
            case BLOCK -> blockGoal();
            case COLUMN -> NavGoal.column(bx, bz);
            case YLEVEL -> NavGoal.yLevel(by);
            case FIND -> finder.contract() == null ? null : finder.contract().goal();
        };
    }

    /**
     * BLOCK auto-typing: an enterable target cell means "stand exactly there"
     * ({@link NavGoal#exact}); a cell occupied by a solid means "get to that
     * block" ({@link NavGoal#getToBlock} — beside/on top counts, the block stays
     * untouched). Re-evaluated per replan, so a cell that opens up mid-journey
     * (the occupant broke) tightens back to exact.
     */
    private NavGoal blockGoal() {
        return blockCompiled().goal();
    }

    /** The BLOCK kind's navigation contract: bare coordinates mean occupy
     *  exactly that cell, digging out whatever is there (the block form is
     *  the way to say "walk up beside it instead"). */
    private org.maiwithu.maicraft.core.pathing.goal.GoalCompiler.Compiled blockCompiled() {
        return org.maiwithu.maicraft.core.pathing.goal.GoalCompiler.standOn(blockTarget);
    }

    /** Does a collision shape occupy the target cell (feet can't go there)? */
    private boolean targetCellSolid() {
        return !player.level().getBlockState(blockTarget)
                .getCollisionShape(player.level(), blockTarget).isEmpty();
    }

    /** Slab-aware feet cell — the pathing node, not raw blockPosition (standing on a
     *  bottom slab counts as the cell above it, like the planner sees it). */
    private BlockPos feet() {
        return org.maiwithu.maicraft.core.pathing.util.BlockHelper.playerFeet(
                player.level(), player.getX(), player.getY(), player.getZ());
    }

    /**
     * Live arrival — DOUBLE membership: the feet cell AND the supported
     * fake-start cell must both satisfy the goal. The second gate is what keeps
     * transient cell-entry from counting as arrival: a pillar's final jump puts
     * the feet in the goal cell at the APEX a tick before its support block is
     * placed, and a bridge's final backplace hovers the feet into the goal cell
     * while sneak-clinging to the previous block's edge — in both states the
     * body has no support under the goal cell yet, pathStart resolves to the
     * neighbouring supported cell, and arrival is (correctly) withheld until
     * the block is actually placed and stood on. Declaring success on the
     * feet-only test stopped the nav mid-move: the place never fired and the
     * halt released the sneak that was holding the body on the edge — the
     * "one block short, one step too far" fall.
     */
    private boolean reached() {
        return inGoalCell(feet())
                && inGoalCell(org.maiwithu.maicraft.core.pathing.moves.Movement.pathStart(player));
    }

    /** ONE membership definition per kind, shared with the search:
     *  BLOCK (cell == target per arrival mode), COLUMN (x/z match),
     *  YLEVEL (y match + on the ground). */
    private boolean inGoalCell(BlockPos cell) {
        return switch (r.kind) {
            case BLOCK -> blockGoal().isAt(cell);
            case COLUMN -> cell.getX() == bx && cell.getZ() == bz;
            case YLEVEL -> cell.getY() == by && player.onGround();
            case FIND -> finder.contract() != null && finder.contract().goal().isAt(cell);
        };
    }

    @Override
    protected TaskState onTick() {
        // reached() is checked BEFORE the nav==null guard so an already-at-target start
        // (which never builds a nav) lands on SUCCESS rather than the defensive FAILED.
        if (reached()) return TaskState.SUCCESS;
        if (boatLeg != null) {
            return tickBoatLeg();
        }
        if (r.kind == MoveToTaskRecord.Kind.FIND && nav == null) {
            TaskState pre = tickFindDiscovery();
            if (pre != null) {
                return pre;
            }
        }
        if (nav == null) {
            fail(blockedMessage("no path"), FailureType.NO_PATH);
            return TaskState.FAILED;
        }
        // Progress lease: while the nav is consuming its plan (steps advancing / digging),
        // keep the deadline PROGRESS_LEASE ahead — never past the check-in cap. Plan
        // consumption, NOT goal distance, is the liveness signal: healthy routes routinely
        // move away from the goal (skirting a lake, spiraling down), and the flat budget
        // above can't price terrain (a dig-heavy route once died 1 block short).
        if (nav.stallTicks() <= PROGRESS_GRACE_TICKS && leaseCapGameTime > 0) {
            long now = player.level().getGameTime();
            r.extendDeadlineTo(Math.min(now + PROGRESS_LEASE_TICKS, leaseCapGameTime));
        }
        // Track passive progress toward the goal: the planner stops at the water surface
        // above an underwater target, but the body keeps drifting toward it on its own (it
        // sinks). Reset the settle timer whenever we get closer.
        double d = repDistance();
        if (d < bestDist - 0.1) {
            bestDist = d;
            settleTicks = 0;
        } else {
            settleTicks++;
        }
        return switch (nav.tick()) {
            case RUNNING -> TaskState.RUNNING;
            case ARRIVED -> TaskState.SUCCESS;
            case FAILED -> {
                // FIND:打不通就近候选 -> 除名,朝余下候选重开导航
                if (r.kind == MoveToTaskRecord.Kind.FIND && finder.rotateAfterFailure()) {
                    stopNav();
                    nav = PlayerNav.to(player, finder::contract, WALK_SPEED, this::reached, terrain())
                            .withTerrainProbe();
                    yield TaskState.RUNNING;
                }
                // The planner can't get closer. In water, keep waiting while the body is
                // still drifting toward the goal (sinking onto an underwater target); give
                // up only once it's stopped making progress (bobbing at the surface below an
                // out-of-reach above-water target). So the body settles onto an underwater
                // goal but bails under an unreachable air one. On land a failure is final.
                if (player.isInWater() && settleTicks < MAX_SETTLE_TICKS) {
                    yield TaskState.RUNNING;
                }
                // Otherwise: as close as the terrain allows → (teaching) success or fail.
                if (closeEnoughToSucceed()) yield TaskState.SUCCESS;
                // Recovery ladder — ONE retry rung, land nav only: re-plan accepting
                // anywhere within NEAR_SUCCESS_RADIUS of the destination. Goal-consistent,
                // not scope creep: a stop within that radius already counts as arrival
