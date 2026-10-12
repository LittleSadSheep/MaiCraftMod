// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import baritone.api.utils.IInputOverrideHandler;
import baritone.api.utils.input.Input;
import baritone.behavior.PathingBehavior;
import baritone.pathing.movement.movements.MovementParkour;
import baritone.pathing.path.PathExecutor;

/**
 * 走路这一刻疾不疾跑：引擎要跑就跑；调用方不让跑（跟人快追上了，疾跑的惯性会冲过头撞上去）就放慢走，
 * 但跳远这一步不疾跑跳不过两三格宽的沟，照样跑。
 */
final class WalkSprint {

    private WalkSprint() {}

    /**
     * 执行段算出"下一刻该疾跑"后会把疾跑键松开、自己记在路线上（原版 Baritone 靠一个改疾跑键的 mixin 读它，
     * 我们没装那个 mixin）。只读按键就永远读不到疾跑，实测 220 格直道一路慢走 4.3 格/秒、一次没跑跳，所以两处都看。
     *
     * @param allowed 调用方让不让跑
     */
    static boolean sprint(IInputOverrideHandler input, PathingBehavior pathing, boolean allowed) {
        PathExecutor executor = pathing == null ? null : pathing.getCurrent();
        boolean wanted = input.isInputForcedDown(Input.SPRINT) || executor != null && executor.isSprinting();
        return wanted && (allowed || parkourStep(executor));
    }

    // 路线当前这一步是不是跳远。
    private static boolean parkourStep(PathExecutor executor) {
        if (executor == null) return false;
        var movements = executor.getPath().movements();
        int index = executor.getPosition();
        return index >= 0 && index < movements.size() && movements.get(index) instanceof MovementParkour;
    }
}
