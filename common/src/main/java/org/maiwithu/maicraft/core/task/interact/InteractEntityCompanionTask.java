package org.maiwithu.maicraft.core.task.interact;
import org.maiwithu.maicraft.core.task.MouseButton;
import org.maiwithu.maicraft.core.PlayerInv;

import org.maiwithu.maicraft.task.TaskState;
import org.maiwithu.maicraft.entity.InputDriver;

import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.core.pathing.calc.NavGoal;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.act.Interaction;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.task.base.GoToThenDoTask;
import org.maiwithu.maicraft.core.task.base.Precondition;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code interact_entity} on the player body: the entity-aimed native interaction.
 * It auto-paths and follows the live entity, then aims at it and presses the
 * requested mouse button only when the native raytrace reaches that entity.
 * A wall in between blocks it, and the task repositions instead of acting on
 * whatever the ray returns. LEFT+hold repeats the native attack until the hold
 * ends, the target dies, or the task times out.
 */
public final class InteractEntityCompanionTask extends GoToThenDoTask<InteractEntityTaskRecord> {

    private static final double REACH = 3.0;            // vanilla entity interaction range
    private static final double REACH_SQR = REACH * REACH;
    private static final double WALK_SPEED = 1.0;
    /** Reposition-rung stance radius: any feet cell this close to the entity's cell
     *  (< {@link #REACH}, so an accepted stance is still within interact reach). */
    private static final double REPOSITION_RADIUS = 2.5;
    /** The reposition rung runs at most once. */
    private static final int MAX_REPOSITIONS = 1;

    private Entity entity;
    // ---- bounded recovery state (fields, so a Suspendable mid-rung suspend/resume
    //      picks straight back up: the counter and the rebuilt nav both survive) ----
    /** Executions of the reposition rung so far (capped at {@link #MAX_REPOSITIONS}). */
    private int repositionAttempts;
    /** The FIRST nav failure's reason, preserved so the final give-up keeps the original wording. */
    private String firstNavFailReason;
    private Interaction interaction;
    private final org.maiwithu.maicraft.core.task.FirstPersonActionGate selection =
            new org.maiwithu.maicraft.core.task.FirstPersonActionGate();
    private boolean itemSelected;
    /** 按键前的世界快照,收尾时对账出"真发生了什么"。 */
    private org.maiwithu.maicraft.core.act.PressReceipt receipt;
    private List<String> changes = List.of();
    private long holdUntil = -1;
    private boolean acted = false;     // landed at least one press (death then = success, not failure)
    private String successMsg = "done";

    public InteractEntityCompanionTask(LocalPlayer player, InteractEntityTaskRecord record) {
        super(player, record);
    }

    @Override
    protected List<Precondition> preconditions() {
        return List.of(
                // Resolve + cache the target; fail fast if it despawned / moved out of range.
                () -> {
                    entity = player.clientLevel.getEntity(r.entityId);
                    return (entity == null || !entity.isAlive())
                            ? new Precondition.Failure("no entity with id " + r.entityId
                                    + " nearby (it may have despawned or moved out of range)",
                                    FailureType.TARGET_LOST)
                            : null;
                },
                () -> r.item == null || PlayerInv.count(player.getInventory(), r.item) > 0 ? null
                        : new Precondition.Failure("don't have "
                                + BuiltInRegistries.ITEM.getKey(r.item).getPath() + " to use on it",
                                FailureType.NO_MATERIAL));
    }

    @Override
    protected PlayerNav buildNav() {
        // Arrival = within reach AND a clear line of sight: nav keeps walking (toward the entity)
        // until BOTH hold, so a wall between us and the target is cleared by re-positioning rather
        // than stood in front of forever.
        return new PlayerNav(player, () -> entity.blockPosition(), WALK_SPEED, this::inReachAndLos)
                .withTerrainProbe();
    }

    /** Act this tick when the target is gone (report the outcome), a fixed hold has elapsed, or we're
     *  in reach with a clear line of sight; otherwise the base drives the nav to follow the entity. */
    @Override
    protected boolean reached() {
        return entity == null || !entity.isAlive()
                || (interaction != null && holdUntil >= 0 && player.level().getGameTime() >= holdUntil)
                || inReachAndLos();
    }

    @Override
    protected TaskState act() {
        // Target gone: death is success for a left-click that already landed; otherwise
        // the target slipped away before we could touch it.
        if (entity == null || !entity.isAlive()) {
            if (acted) {
                successMsg = r.button == MouseButton.LEFT
                        ? "defeated " + targetName() : "done with " + targetName();
                return TaskState.SUCCESS;
            }
            fail("the target entity is gone before I could reach it", FailureType.TARGET_LOST);
            return TaskState.FAILED;
        }

        // 目标是她自己坐着的载具:右键的意图(上船)早已是事实,立即了结;左键
        // 原版玩家也打不到自己的座驾。不拦的话,下面的准星确认要求"看见目标",
        // 而从座位上看自己的船永远确认不了——任务空转到超时,实测就是这么挂的。
        if (entity == player.getVehicle()) {
            if (r.button == MouseButton.LEFT) {
                fail("you are riding the " + targetName()
                        + " — can't hit your own vehicle; a goto somewhere else steps off first",
                        FailureType.UNKNOWN);
                return TaskState.FAILED;
            }
            successMsg = "already riding the " + targetName();
            return TaskState.SUCCESS;
        }

        // A fixed-duration hold completes on time even if the line of sight lapsed near the end.
        // 按住时限到了便停止并返回成功，即使最后一次交互还没确认；这也是审计记录 A30 的触发点。
        if (interaction != null && holdUntil >= 0 && player.level().getGameTime() >= holdUntil) {
            interaction.stop();
            successMsg = describeDone() + settle();
            return TaskState.SUCCESS;
        }

        // In reach + LOS: aim at the entity and confirm the crosshair actually resolves to IT
        // (e.g. not another entity wandered into the exact line) before pressing.
        InputDriver.lookAt(player, entity.getEyePosition());
        HitResult hit = Interaction.nativeRaytrace(player, REACH);
        boolean onTarget = hit.getType() == HitResult.Type.ENTITY
                && ((EntityHitResult) hit).getEntity() == entity;
        if (!onTarget) {
            return TaskState.RUNNING;   // settling / something briefly in the line — re-aim next tick
        }

        if (interaction == null) {
            if (r.item != null && !itemSelected) {
                var selected = selection.select(player, PlayerInv.findSlot(player.getInventory(), r.item));
                if (selected == org.maiwithu.maicraft.core.task.FirstPersonActionGate.Status.RUNNING) {
                    return TaskState.RUNNING;
                }
                if (selected == org.maiwithu.maicraft.core.task.FirstPersonActionGate.Status.FAILED) {
                    fail("couldn't select the requested item: " + selection.failure(), FailureType.UNKNOWN);
                    return TaskState.FAILED;
                }
                itemSelected = true;
            }
            receipt = org.maiwithu.maicraft.core.act.PressReceipt.before(player, null);
            // Real LocalPlayer semantics: when the entity does not consume the interaction,
            // vanilla may continue with the held item.
            interaction = Interaction.forHit(player, hit, button(), r.holdTicks, true);
            if (r.holdTicks > 0) {
                holdUntil = player.level().getGameTime() + r.holdTicks;
            }
        }
        acted = true;

        return switch (interaction.tick()) {
            case DONE -> {
                successMsg = describeDone() + settle();
                yield TaskState.SUCCESS;
            }
            case FAILED -> {
                fail(interaction.failReason(), FailureType.UNKNOWN);
                yield TaskState.FAILED;
            }
            case RUNNING -> TaskState.RUNNING;
        };
    }

    /**
     * Bounded recovery — ONE reposition rung, as an inline attempt counter (a single
     * rung doesn't warrant {@code RecoveryLadder}'s child-task plumbing). On an
     * in-ladder nav cause ({@code NO_PATH} / {@code BOXED_IN} / {@code OUT_OF_REACH})
     * retry the SAME bounded goal once with a looser stance goal — {@link NavGoal#near}
     * within {@link #REPOSITION_RADIUS} (&lt; {@link #REACH}) of the entity's LIVE cell,
     * so "can't stand exactly next to it" becomes "stand anywhere within interact reach".
     * The goal supplier re-reads the entity each tick, so a target that merely MOVED
     * while we repositioned is tracked (the nav replans), not failed; a genuinely gone
     * entity never reaches this seam — {@link #reached()} routes it to {@link #act()},
     * which reports {@code TARGET_LOST} immediately (no ladder). Never widens the
     * search, never acquires anything. Exhausted (or a cause no rung handles), give up
     * preserving the original "can't reach {name}: {reason}" wording plus a note of
     * what was tried, carrying the nav's failType.
     */
    @Override
    protected TaskState handleNavFailure(FailureType type, String reason) {
        if (repositionable(type) && repositionAttempts < MAX_REPOSITIONS) {
            repositionAttempts++;
            firstNavFailReason = reason;
            stopNav();
            nav = PlayerNav.toGoal(player,
                    () -> (entity == null || !entity.isAlive()) ? null
                            : NavGoal.near(entity.blockPosition(), REPOSITION_RADIUS),
                    WALK_SPEED, this::inReachAndLos).withTerrainProbe();
            return TaskState.RUNNING;
        }
        String original = firstNavFailReason != null ? firstNavFailReason : reason;
        String tried = repositionAttempts > 0
                ? " (also tried a looser stance anywhere within " + REPOSITION_RADIUS
                        + " blocks of it: " + reason + ")"
                : "";
        fail("can't reach " + targetName() + ": " + original + tried, type);
        return TaskState.FAILED;
    }

    /** In-ladder nav causes the reposition rung handles; anything else kicks straight back to the LLM. */
    private static boolean repositionable(FailureType type) {
        return type == FailureType.NO_PATH || type == FailureType.TERRAIN_BLOCKED
                || type == FailureType.BOXED_IN
                || type == FailureType.OUT_OF_REACH || type == FailureType.STANCE_DUD;
    }

    private Interaction.Button button() {
        return r.button == MouseButton.LEFT
                ? Interaction.Button.ATTACK : Interaction.Button.USE;
    }

    private boolean withinReach() {
        return bodySettled() && entity != null
                && player.distanceToSqr(entity.position()) <= REACH_SQR;
    }

    /** In arm's reach AND no block between our eyes and the entity (vanilla hasLineOfSight) —
     *  the nav arrival gate, so the body walks around a wall instead of freezing in front of it. */
    private boolean inReachAndLos() {
        return withinReach() && player.hasLineOfSight(entity);
    }

    private String targetName() {
        return entity != null ? entity.getName().getString() : "entity#" + r.entityId;
    }

    private String describeDone() {
        String verb = r.button == MouseButton.LEFT ? "attacked" : "interacted with";
        return verb + " " + targetName();
    }

    /** 收尾对账,与 interact_at 同款:只报事实,判断留给读回执的人。 */
    private String settle() {
        changes = receipt == null ? List.of() : receipt.diff(player);
        if (changes.isEmpty()) {
            return "";   // 实体交互多数不留可见痕迹(交易开了界面、动物进了求爱态),不硬报"没变"
        }
        return " — " + String.join("; ", changes);
    }

    /** Release the interaction, then the nav + overlay (base default). */
    @Override
    protected void cleanup() {
        selection.reset();
        if (interaction != null) interaction.stop();
        super.cleanup();
    }

    @Override
    protected Map<String, Object> resultData() {
        Map<String, Object> data = new HashMap<>();
        data.put("button", r.button == MouseButton.LEFT ? "left" : "right");
        data.put("entity_id", r.entityId);
        if (!changes.isEmpty()) {
            data.put("changes", changes);
        }
        return data;
    }

    @Override
    protected String successMessage() {
        return successMsg;
    }

    @Override
    protected String timeoutMessage() {
        return "timed out before interacting with " + targetName();
    }

    @Override
    protected String cancelledMessage() {
        return "interact_entity interrupted";
    }
}
