// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.interaction;

import java.util.Objects;
import java.util.function.Supplier;

import org.maiwithu.maicraft.game.player.InputDriver;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 按住潜行的生产实现：每刻经角色的输入按下潜行再推进里面的动作，暂停与收尾时松开，
 * 不让潜行键留在按下的状态把之后走路也拖慢。
 */
public final class LiveHoldsSneak implements HoldsSneak {

    private final InputDriver inputs;
    private final Supplier<PlayerContext> context;

    public LiveHoldsSneak(InputDriver inputs, Supplier<PlayerContext> context) {
        this.inputs = Objects.requireNonNull(inputs, "inputs");
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override public Action sneaking(Action inner) {
        return new Sneaking(Objects.requireNonNull(inner, "inner"));
    }

    /** 整个动作里每刻按着潜行；暂停与结束时松开。 */
    private final class Sneaking implements Action {
        private final Action inner;

        Sneaking(Action inner) {
            this.inner = inner;
        }

        @Override public ActionStatus tick(TickContext tick) {
            hold(true);
            return inner.tick(tick);
        }

        @Override public void pause() {
            // 被生存需求打断时先松开潜行：插进来的临时任务要正常走路、正常点东西。
            hold(false);
            inner.pause();
        }

        @Override public void close() {
            hold(false);
            inner.close();
        }

        @Override public Interruptibility interruptibility() {
            return inner.interruptibility();
        }

        @Override public String describe() {
            return "潜行着" + inner.describe();
        }

        private void hold(boolean on) {
            PlayerContext current = context.get();
            if (current != null && current.localPlayer() != null) inputs.sneak(current.localPlayer(), on);
        }
    }
}
