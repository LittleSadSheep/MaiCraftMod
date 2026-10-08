// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.follow;

import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 跟随目标视图：锁定要跟的目标，并逐刻报出它此刻的观察。
 *
 * <p>身份核对在这里做：客户端的实体编号会被重用，编号还在而类型换了就是换了东西，
 * 按"目标丢失"处理，不跟着新实体走。生产实现读观察登记与世界，离线测试直接喂数值。
 */
interface FollowView {

    /**
     * 锁定这次要跟的目标：观察编号按本会话的登记找回真实实体，玩家按名字找。
     * 找不到（编号没登记过、名字不在线）返回 null。
     */
    Locked lock(TickContext context, Target target);

    /** 目标此刻的观察；看不见（走远、下线、死亡）或编号已被重用给别的东西时返回 null。 */
    Observed observe(TickContext context);

    /** 刚锁定时的目标：游戏实体编号、类型、位置与最后方位。 */
    record Locked(int entityId, String type, WorldPosition position, String direction) {}

    /** 目标此刻的观察：位置、方位与与角色的三维距离。 */
    record Observed(WorldPosition position, String direction, double distance) {}
}
