// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.follow;

import net.minecraft.client.player.LocalPlayer;

import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.PlayerInput;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 从载具上下来：像玩家一样按住潜行，直到不再坐在上面；两秒还下不来如实失败。
 * 船、骆驼、列车的座位都是这样下的。
 */
final class GetsOff implements Action {

    /** 按住潜行最多这么多刻。 */
    private static final int LIMIT_TICKS = 40;

    private int ticks;
    private PlayerContext last;

    @Override
    public ActionStatus tick(TickContext context) {
        last = context.player();
        LocalPlayer me = last.localPlayer();
        PlayerInput input = last.input();
        if (!me.isPassenger()) {
            input.halt(me);
            return ActionStatus.done();
        }
        if (++ticks > LIMIT_TICKS) {
            input.halt(me);
            return ActionStatus.failed(Problem.of(Problem.Kind.STUCK, "按住潜行两秒还没从载具上下来"));
        }
        input.applyMovement(new PlayerInput.Movement(0.0f, 0.0f, false, true, false), context.gameTick());
        return ActionStatus.running();
    }

    @Override
    public void pause() {
        release();
    }

    @Override
    public void close() {
        release();
    }

    private void release() {
        if (last != null && last.input() != null && last.localPlayer() != null) {
            last.input().halt(last.localPlayer());
        }
    }

    @Override
    public Interruptibility interruptibility() {
        return Interruptibility.WORKING;
    }

    @Override
    public String describe() {
        return "按住潜行从载具上下来";
    }
}
