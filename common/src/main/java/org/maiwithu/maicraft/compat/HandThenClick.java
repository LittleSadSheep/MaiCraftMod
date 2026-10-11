// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

import org.maiwithu.maicraft.behavior.approach.ApproachTarget;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 备好手 → 走过去 → 对准那一块右键并等游戏确认，跨刻推进的一个动作：往置物台上放东西、空手从台上或盆里取东西都是它。
 *
 * <p>手上要拿的东西由调用方给出办法（换到主手、腾空手）；已经备好时给空，直接走过去。
 * 走不到、点不中、等不到确认都把原因原样交回，不重点：游戏没确认的右键再点一次可能就是放了第二叠。
 */
public final class HandThenClick implements Action {

    private enum Stage { HAND, APPROACH, CLICK }

    private final PlayerServices services;
    private final String what;
    private final BlockPos cell;
    private final AABB face;
    private final Permissions permissions;
    private final Supplier<Optional<Action>> hand;
    private final Supplier<InteractionConfirmation> confirmation;
    private Stage stage = Stage.HAND;
    private Action current;

    /**
     * @param what         在做什么，给描述与失败说明，例如"把铁锭放上置物台"
     * @param cell         要点的那一格
     * @param face         点在那一格的哪一块（世界坐标）：置物台要点在顶面上才算放上去
     * @param hand         备手的办法：给出要做的换手动作；手上已经对了给空
     * @param confirmation 出手前那一刻建确认条件：右键之后什么样才算做成（台上多了东西、手上少了东西）
     */
    public HandThenClick(PlayerServices services, String what, BlockPos cell, AABB face, Permissions permissions,
            Supplier<Optional<Action>> hand, Supplier<InteractionConfirmation> confirmation) {
        this.services = Objects.requireNonNull(services, "services");
        this.what = Objects.requireNonNull(what, "what");
        this.cell = Objects.requireNonNull(cell, "cell").immutable();
        this.face = Objects.requireNonNull(face, "face");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.hand = Objects.requireNonNull(hand, "hand");
        this.confirmation = Objects.requireNonNull(confirmation, "confirmation");
    }

    @Override public ActionStatus tick(TickContext context) {
        if (current == null) {
            current = next();
            if (current == null) return ActionStatus.done();
        }
        ActionStatus status = current.tick(context);
        if (status instanceof ActionStatus.Failed failed) {
            return ActionStatus.failed(Problem.of(failed.problem().kind(), what + "没做成：" + failed.problem().message(),
                    failed.problem().suggestion()));
        }
        if (!(status instanceof ActionStatus.Done)) return status;
        current.close();
        current = null;
        if (stage == Stage.CLICK) return ActionStatus.done();
        stage = stage == Stage.HAND ? Stage.APPROACH : Stage.CLICK;
        return ActionStatus.progressed();
    }

    // 按阶段建下一个动作：备手不用做就直接去走；点击在走到之后才建，确认条件按出手前那一刻的样子定。
    private Action next() {
        return switch (stage) {
            case HAND -> {
                Optional<Action> preparing = hand.get();
                if (preparing.isPresent()) yield preparing.get();
                stage = Stage.APPROACH;
                yield next();
            }
            case APPROACH -> services.approach().toward(ApproachTarget.ofBlockPart(cell, face), permissions);
            case CLICK -> services.interactions().useBlockPart(cell, face, confirmation.get());
        };
    }

    @Override public void pause() {
        if (current != null) current.pause();
    }

    @Override public void close() {
        if (current != null) current.close();
        current = null;
    }

    @Override public Interruptibility interruptibility() {
        return current == null ? Interruptibility.WORKING : current.interruptibility();
    }

    @Override public String describe() {
        return what + switch (stage) {
            case HAND -> "：先备好手上的东西";
            case APPROACH -> "：走过去";
            case CLICK -> "：对准右键";
        };
    }
}
