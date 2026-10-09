// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.goal;

/**
 * 死亡恢复的原生动作：向游戏发重生或观战的请求。游戏接口一侧实现，启动时创建、登记接上；
 * 内核只发请求，成不成由游戏结算，发不出去就如实返回 false，由目标运行表把问题重新挂上。
 */
public interface DeathRecoveryActions {

    /**
     * 发原版重生请求（死亡界面上「重生」按钮发的那个包）。请求发出去为 true；
     * 角色上下文不在、连接不在或发包出错为 false。
     */
    boolean requestRespawn();

    /**
     * 请求切到旁观模式：复用同一个原版请求，服务器按自己的规则决定结果。
     * 请求发出去为 true；连接不在或发包出错为 false。
     */
    boolean requestSpectate();

    /** 没接上游戏一侧时的空实现：请求发不出去，问题照常重新挂上等下一次。 */
    DeathRecoveryActions NONE = new DeathRecoveryActions() {
        @Override public boolean requestRespawn() { return false; }
        @Override public boolean requestSpectate() { return false; }
    };
}
