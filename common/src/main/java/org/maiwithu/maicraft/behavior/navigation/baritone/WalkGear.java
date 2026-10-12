// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import java.util.Objects;

import baritone.api.IBaritone;
import baritone.behavior.PathingBehavior;
import baritone.pathing.movement.movements.MovementFlyUp;
import baritone.pathing.path.PathExecutor;
import net.minecraft.client.player.LocalPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.PlayerInput;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 走路途中收拾随身装备：路线走到"飞上去"那一步、人在地上而喷气背包没开好，站定开飞行和悬停；
 * 平常走路时喷气背包误开着，站定关上（这一步、下一步要飞上去时不关）。收拾的这几刻引擎不推进、人站着。
 */
final class WalkGear {

    private static final Logger LOG = LoggerFactory.getLogger(WalkGear.class);

    /** 这一刻收拾得怎样。 */
    enum State {
        /** 不用收拾，引擎照常推进。 */
        FREE,
        /** 正在收拾，这一刻引擎不推进。 */
        BUSY,
        /** 刚收拾好，站着那几刻不算没进展。 */
        FINISHED,
        /** 要飞上去却开不起来喷气背包，见 {@link #problem()}。 */
        FAILED
    }

    private final BaritoneInternals owner;
    private Action current;
    private boolean forFlyUp;
    private Problem problem;

    WalkGear(BaritoneInternals owner) {
        this.owner = Objects.requireNonNull(owner, "owner");
    }

    // 先看要不要为飞上去开喷气背包，再看要不要关误开的；正在做的那一样做完才看下一样。
    State tick(PlayerContext context, LocalPlayer player, PathingBehavior pathing, IBaritone engine) {
        if (current == null) {
            forFlyUp = player.onGround() && flyUpNow(pathing);
            current = forFlyUp ? owner.prepareFlyUp(context).orElse(null)
                    : flyUpAhead(pathing) ? null : owner.stowBeforeWalking(context).orElse(null);
            if (current == null) return State.FREE;
            engine.getInputOverrideHandler().clearAllKeys();
            LOG.info("[maicraft-path] {}", current.describe());
        }
        context.input().applyNavigationMovement(PlayerInput.Movement.STOPPED, context.clientTick());
        ActionStatus status = current.tick(TickContext.of(context.level().getGameTime(), context));
        if (status instanceof ActionStatus.Running) return State.BUSY;
        current = null;
        if (status instanceof ActionStatus.Failed failed) {
            LOG.info("[maicraft-path] {}", failed.problem().message());
            // 关不上照走；要飞上去却开不起来就按走不通收场，下一次算路换别的走法。
            if (forFlyUp) {
                problem = Problem.of(Problem.Kind.STUCK, failed.problem().message(), null);
                return State.FAILED;
            }
        }
        return State.FINISHED;
    }

    /** 开不起来喷气背包的原因。 */
    Problem problem() {
        return problem;
    }

    /** 人接回角色、走路收场：手上收拾到一半的放下。 */
    void drop() {
        current = null;
    }

    // 路线这一步是不是"飞上去"。
    static boolean flyUpNow(PathingBehavior pathing) {
        PathExecutor executor = pathing == null ? null : pathing.getCurrent();
        if (executor == null) return false;
        var movements = executor.getPath().movements();
        int at = executor.getPosition();
        return at >= 0 && at < movements.size() && movements.get(at) instanceof MovementFlyUp;
    }

    // 路线这一步或下一步是"飞上去"：连着飞的中间落脚时不关喷气背包。
    private static boolean flyUpAhead(PathingBehavior pathing) {
        PathExecutor executor = pathing == null ? null : pathing.getCurrent();
        if (executor == null) return false;
        var movements = executor.getPath().movements();
        int from = Math.max(0, executor.getPosition());
        for (int index = from; index < Math.min(movements.size(), from + 2); index++) {
            if (movements.get(index) instanceof MovementFlyUp) return true;
        }
        return false;
    }
}
