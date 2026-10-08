// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.wait;

import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 等待任务要读的世界事实：现在几点、自己多少血、饱食度多少。
 *
 * <p>只读，一次取齐；生产实现逐刻从角色身上读，离线测试直接喂数值。
 */
interface WaitWorld {

    /** 世界已经走过的总时刻（dayTime），昼夜相位由世界时间规则判断。 */
    long dayTime();

    /** 当前生命（一颗心算 2 点）。 */
    double health();

    /** 生命上限。 */
    double maxHealth();

    /** 当前饱食度（0 到 20）。 */
    int food();

    /** 从本刻的角色上下文读一份世界事实；角色不在时返回 null，按条件不成立处理。 */
    static WaitWorld read(TickContext context) {
        var player = context.player();
        if (player == null || player.localPlayer() == null || player.level() == null) {
            return null;
        }
        var body = player.localPlayer();
        long dayTime = player.level().getDayTime();
        return new WaitWorld() {
            @Override public long dayTime() { return dayTime; }
            @Override public double health() { return body.getHealth(); }
            @Override public double maxHealth() { return body.getMaxHealth(); }
            @Override public int food() { return body.getFoodData().getFoodLevel(); }
        };
    }
}
