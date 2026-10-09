// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.goal;

/**
 * 控制权交接接缝：目标下达这一侧向输入层请求角色的控制权，并查询此刻自动化是否拥有控制权。
 * 内核只认这个接缝；真正换掉玩家键盘输入的事由游戏接口层的玩家控制权边界做，启动时接上。
 *
 * <p>交接的时机由调用方掌握：新目标成为主任务、或暂停的目标恢复为主任务时请求一次；
 * 人类按 F8 把控制权抢回去之后，自动化不立刻抢回，等下一个目标下达或恢复时才再次请求。
 */
public interface PlayerControlHandover {

    /** 此刻自动化是否拥有角色的控制权；没有时目标推进不了，等待情况要如实呈现。 */
    boolean automationOwnsControls();

    /** 向输入层请求控制权；已经在等待或已拥有时重复请求没有副作用。 */
    void requestControl();
}
