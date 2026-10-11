// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.fight;

import java.util.UUID;

import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 点名目标的解析与逐刻核对：观察编号换回真实实体，编号被重用就当目标没了。
 */
interface SeenTargets {

    /** 解析一个观察编号；登记里没有（从未见过或已失效）返回 null。 */
    Locked lock(TickContext context, String observedId);

    /** 实体此刻的观察；走远、死亡或编号被重用给别的东西时返回 null。 */
    Observed observe(TickContext context, int entityId);

    /** 刚锁定时的目标：游戏实体编号、身份（查它有没有主用）与类型。 */
    record Locked(int entityId, UUID uuid, String type) {}

    /**
     * 实体此刻的观察：在哪、离多远、是否已经死亡（死亡证据由游戏侧确认）、看不看得见（中间有没有方块挡着；
     * 看得见的近处目标按走位环打，看不见的交给寻路绕过去）。
     */
    record Observed(double x, double y, double z, double distance, boolean dead, boolean visible) {
        public Observed(double x, double y, double z, double distance, boolean dead) {
            this(x, y, z, distance, dead, false);
        }
    }
}
