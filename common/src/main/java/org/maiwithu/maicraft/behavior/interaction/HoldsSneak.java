// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.interaction;

import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 按住潜行做一件事：动作推进的每一刻都按着潜行，动作收尾时松开。
 * 被点的方块自己会开界面时（箱子、工作台）要潜行着放方块，扳手潜行右键是把机器方块整块拆下来，
 * 这些手势都靠它。不按潜行的手势照常用别的动作，不经这里。
 */
@FunctionalInterface
public interface HoldsSneak {

    /** 包一层：推进 inner 的每一刻都按住潜行，inner 结束、被暂停或收尾时松开。 */
    Action sneaking(Action inner);
}
