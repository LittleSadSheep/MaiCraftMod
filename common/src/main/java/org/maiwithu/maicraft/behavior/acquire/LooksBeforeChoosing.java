// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.Objects;
import java.util.function.Supplier;

import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 几种做法分不出先后时先看一眼再挑：像玩家要木板时先打开身上的 AE 终端看看有哪种木头，再决定做哪种木板。
 * 看一眼没成（打不开、没连上）也照样往下挑，只是按看之前的顺序。
 */
final class LooksBeforeChoosing implements Action {

    private final Action look;
    private final Supplier<Action> choose;
    private Action chosen;

    LooksBeforeChoosing(Action look, Supplier<Action> choose) {
        this.look = Objects.requireNonNull(look, "look");
        this.choose = Objects.requireNonNull(choose, "choose");
    }

    // 先看：看完（成没成都算看过）再按新看到的挑做法，之后交给挑出来的那一串推进。
    @Override public ActionStatus tick(TickContext context) {
        if (chosen == null) {
            ActionStatus looking = look.tick(context);
            if (looking instanceof ActionStatus.Running) return looking;
            look.close();
            chosen = choose.get();
            return ActionStatus.progressed();
        }
        return chosen.tick(context);
    }

    @Override public void pause() {
        if (chosen != null) chosen.pause(); else look.pause();
    }

    @Override public void close() {
        if (chosen != null) chosen.close(); else look.close();
    }

    @Override public Interruptibility interruptibility() {
        return chosen != null ? chosen.interruptibility() : look.interruptibility();
    }

    @Override public String describe() {
        return chosen != null ? chosen.describe() : "先看一眼有哪些现成的再挑做法：" + look.describe();
    }
}
