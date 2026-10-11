// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.sable;

import java.util.Objects;
import java.util.Optional;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.behavior.approach.ApproachTarget;
import org.maiwithu.maicraft.behavior.navigation.StraightWalk;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.behavior.travel.spi.TravelLeg;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 上下物理结构的一段：
 * 上船——走到船边那格地上 → 迈上甲板（高一格就跳一下）→ 在船上站稳 8 刻；
 * 下船——等船停稳 → 在甲板上走到船沿 → 迈下去 → 在地上站稳；
 * 船上走几步——等船停稳 → 走到那一格 → 站稳。
 * 船在动时不在甲板上走（会被甩下去），等它停；等太久如实说还在动。甲板格按此刻的位姿换到世界里再走。
 */
final class DeckLeg implements TravelLeg {

    /** 走的几步。 */
    enum Step { APPROACH, WAIT_STILL, TO_DECK, TO_GROUND, SETTLE }

    /** 船停着这么多刻才在甲板上走。 */
    static final int STILL_TICKS = 10;
    /** 等船停最多多少刻（30 秒）。 */
    static final int MAX_WAIT_TICKS = 600;
    /** 站稳要连着多少刻。 */
    static final int SETTLE_TICKS = 8;
    static final int MAX_SETTLE_TICKS = 100;
    /** 迈一步最多走多少刻。 */
    static final int STEP_TICKS = 100;

    private final SableCompat compat;
    private final PlayerServices services;
    private final Permissions permissions;
    private final DeckMode.Kind kind;
    private final String structureId;
    private final BlockPos deckLocal;
    private final BlockPos ground;

    private Step step;
    private Action current;
    private long stepStart = Long.MIN_VALUE;
    private long stillSince = Long.MIN_VALUE;
    private int steady;

    DeckLeg(SableCompat compat, PlayerServices services, Permissions permissions, DeckMode.Kind kind, String structureId,
            BlockPos deckLocal, BlockPos ground) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.services = Objects.requireNonNull(services, "services");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.structureId = Objects.requireNonNull(structureId, "structureId");
        this.deckLocal = Objects.requireNonNull(deckLocal, "deckLocal");
        this.ground = ground;
        this.step = kind == DeckMode.Kind.BOARD ? Step.APPROACH : Step.WAIT_STILL;
    }

    @Override public ActionStatus tick(TickContext tick) {
        PlayerContext context = services.context().get();
        ClientLevel level = context == null ? null : context.level();
        LocalPlayer player = context == null ? null : context.localPlayer();
        if (level == null || player == null || context.input() == null) {
            return ActionStatus.failed(Problem.of(Problem.Kind.INTERNAL_ERROR, "角色不在，上下不了船"));
        }
        long now = level.getGameTime();
        if (stepStart == Long.MIN_VALUE) stepStart = now;
        Optional<SableStructures.Structure> ship = compat.structures(level, player, new AABB(player.blockPosition())
                .inflate(DeckMode.SEARCH)).stream().filter(structure -> structure.id().equals(structureId)).findFirst();
        if (ship.isEmpty() && step != Step.SETTLE) {
            return ActionStatus.failed(Problem.of(Problem.Kind.TARGET_GONE, "那艘船看不到了（走远或被拆）"));
        }
        if (current != null) {
            ActionStatus status = current.tick(tick);
            if (status instanceof ActionStatus.Running) return status;
            current.close();
            current = null;
            if (status instanceof ActionStatus.Failed failed) return ActionStatus.failed(failed.problem());
            return next(now);
        }
        return switch (step) {
            case APPROACH -> approach();
            case WAIT_STILL -> waitStill(context, player, ship.get(), now);
            case TO_DECK -> toDeck(ship.get());
            case TO_GROUND -> toGround();
            case SETTLE -> settle(player, ship.orElse(null), now);
        };
    }

    // 上船先走到船边那格地上：地面上的路寻路认得。
    private ActionStatus approach() {
        current = services.approach().toward(ApproachTarget.ofBlock(ground.below()), permissions);
        return ActionStatus.progressed();
    }

    // 船在动不在甲板上走：停稳一会儿再走；等太久如实说。
    private ActionStatus waitStill(PlayerContext context, LocalPlayer player, SableStructures.Structure ship, long now) {
        context.input().halt(player);
        if (StructureGeometry.moving(ship)) {
            stillSince = Long.MIN_VALUE;
            if (now - stepStart >= MAX_WAIT_TICKS) {
                return ActionStatus.failed(Problem.of(Problem.Kind.STUCK, "船一直在动，等了 " + MAX_WAIT_TICKS / 20
                        + " 秒没停，没在甲板上走（不跳下移动的船）"));
            }
            return ActionStatus.running();
        }
        if (stillSince == Long.MIN_VALUE) stillSince = now;
        if (now - stillSince < STILL_TICKS) return ActionStatus.running();
        return go(Step.TO_DECK, now);
    }

    // 走到甲板那一格：按此刻的位姿换到世界里，直着走过去（从地上迈上来时高一格就跳）。
    private ActionStatus toDeck(SableStructures.Structure ship) {
        Vec3 target = ship.pose().toWorld(new Vec3(deckLocal.getX() + 0.5, deckLocal.getY(), deckLocal.getZ() + 0.5));
        current = new StraightWalk(services.context(), target, 0.3, STEP_TICKS,
                kind == DeckMode.Kind.BOARD ? "迈上甲板" : "在甲板上走到船沿", kind == DeckMode.Kind.BOARD);
        return ActionStatus.progressed();
    }

    // 从船沿迈下去，走到地上那一格。
    private ActionStatus toGround() {
        current = new StraightWalk(services.context(), Vec3.atBottomCenterOf(ground), 0.3, STEP_TICKS, "从船沿迈下去");
        return ActionStatus.progressed();
    }

    // 站稳：上船、船上走——站在这艘船上踩实了；下船——不在船上、踩在地上。连着几刻才算。
    private ActionStatus settle(LocalPlayer player, SableStructures.Structure ship, long now) {
        boolean onShip = ship != null && ship.carriesPlayer();
        boolean right = kind == DeckMode.Kind.LEAVE ? !onShip && player.onGround() : onShip && player.onGround();
        steady = right ? steady + 1 : 0;
        if (steady >= SETTLE_TICKS) return ActionStatus.done();
        if (now - stepStart >= MAX_SETTLE_TICKS) {
            return ActionStatus.failed(Problem.of(Problem.Kind.STUCK, kind == DeckMode.Kind.LEAVE
                    ? "迈下船沿后没在地上站稳" : "走到甲板那一格了，但没在船上站稳"));
        }
        return ActionStatus.running();
    }

    // 一步做完往下走：走到地上那格后迈上甲板；到了甲板那格（下船）再迈下去，否则站稳；迈下去后站稳。
    private ActionStatus next(long now) {
        return switch (step) {
            case APPROACH -> {
                // 走到船边那格还差几步：先直着走进那一格，再迈上甲板。
                current = new StraightWalk(services.context(), Vec3.atBottomCenterOf(ground), 0.3, STEP_TICKS, "走到船边");
                step = Step.WAIT_STILL;
                stepStart = now;
                yield ActionStatus.progressed();
            }
            case WAIT_STILL -> go(Step.WAIT_STILL, now);
            case TO_DECK -> go(kind == DeckMode.Kind.LEAVE ? Step.TO_GROUND : Step.SETTLE, now);
            case TO_GROUND -> go(Step.SETTLE, now);
            case SETTLE -> ActionStatus.running();
        };
    }

    private ActionStatus go(Step next, long now) {
        step = next;
        stepStart = now;
        steady = 0;
        stillSince = Long.MIN_VALUE;
        return ActionStatus.progressed();
    }

    @Override public void requestSafeStop() {
        // 上下船都是几步的事：做完这几步再停。
    }

    @Override public void pause() {
        if (current != null) current.pause();
    }

    @Override public void close() {
        if (current != null) {
            current.close();
            current = null;
        }
        PlayerContext context = services.context().get();
        if (context != null && context.input() != null && context.localPlayer() != null) context.input().halt(context.localPlayer());
    }

    @Override public Interruptibility interruptibility() {
        // 正迈上、迈下船沿时停下不安全：半个身子悬在船边。
        if (step == Step.TO_DECK || step == Step.TO_GROUND) return Interruptibility.UNSAFE_TO_STOP;
        return current == null ? Interruptibility.BETWEEN_ACTIONS : current.interruptibility();
    }

    @Override public String summary() {
        return switch (kind) {
            case BOARD -> "上船";
            case LEAVE -> "下船";
            case ACROSS -> "在船上走";
        };
    }

    @Override public String describe() {
        String doing = switch (step) {
            case APPROACH -> "走到船边";
            case WAIT_STILL -> "等船停稳";
            case TO_DECK -> "走到甲板那一格";
            case TO_GROUND -> "迈下船沿";
            case SETTLE -> "站稳";
        };
        return summary() + "：" + doing + (current == null ? "" : "，" + current.describe());
    }
}
