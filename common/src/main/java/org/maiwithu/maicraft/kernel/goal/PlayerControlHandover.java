// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.goal;

/**
 * 控制权交接接缝：目标下达这一侧向输入层请求角色的控制权，并查询此刻自动化是否拥有控制权。
 * 内核只认这个接缝；真正换掉玩家键盘输入的事由游戏接口层的玩家控制权边界做，启动时接上。
 *
 * <p>交接的时机由调用方掌握：新目标成为主任务、或暂停的目标恢复为主任务时请求一次。
 * 人按 F8 把角色收回去之后，请求一律不生效，直到人再按 F8 交回——F8 是人的急停，
 * 重新下达或恢复目标都不能把角色从人手里抢回来。
 */
public interface PlayerControlHandover {

    /** 此刻自动化是否拥有角色的控制权；没有时目标推进不了，等待情况要如实呈现。 */
    boolean automationOwnsControls();

    /** 向输入层请求控制权；已经在等待或已拥有时重复请求没有副作用，人按 F8 收回期间不生效。 */
    void requestControl();

    /** 人按 F8 收回了角色、还没交回；这期间目标不推进，只能等人交回。 */
    default boolean humanTookOver() {
        return false;
    }
}
