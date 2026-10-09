// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.interrupt;

import org.maiwithu.maicraft.game.player.DeathFacts;

/**
 * 死亡决策的挂载对象：控制循环看到角色死亡时，把死亡事实交给它去挂「死亡恢复」问题；
 * 回到活体时告诉它本轮决策已经了结。实现方是目标运行表一侧，问题经内核的问题通道给到 LLM。
 */
public interface DeathDecisionHost {

    /**
     * 角色死了（停摆的第一刻）：挂一次死亡恢复决策。同一个死亡过程只叫这一次，
     * 之后每刻停摆不再重复叫。
     *
     * @param facts           死亡现场的可见事实；这一刻拿不到时为 null
     * @param connectionAlive 到服务器的连接还在不在；不在时不提供切观战这个选项
     */
    void characterDied(DeathFacts facts, boolean connectionAlive);

    /** 死亡过程结束（重生或以别的方式回到活体）：本轮决策收尾，下次死亡再挂新的。 */
    void characterAliveAgain();

    /** 没接上挂载对象时的空实现：死亡照常停摆，只是不挂决策。 */
    DeathDecisionHost NONE = new DeathDecisionHost() {
        @Override public void characterDied(DeathFacts facts, boolean connectionAlive) {}
        @Override public void characterAliveAgain() {}
    };
}
