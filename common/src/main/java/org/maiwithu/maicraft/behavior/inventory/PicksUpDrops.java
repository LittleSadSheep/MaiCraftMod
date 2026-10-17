// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

import java.util.Set;

import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 捡起掉出来的东西：挖开一格、剪下羊毛之后，走过去让原版的拾取把地上的东西吸进包。
 *
 * <p>只捡这一下带出来的：动手前先记下脚边已经躺着哪些，动手后只捡新冒出来的，
 * 别人丢的、早就在地上的不去碰。进没进包以地上那件消失为准，不把"走过去了"当成"捡到了"。
 */
public interface PicksUpDrops {

    /** 角色附近此刻躺着哪些掉落物（实体编号）；动手前记一份。 */
    Set<Integer> nearby();

    /**
     * 走过去捡起动手后新冒出来的东西：先等一小会儿让掉落物冒出来（刚生成要过一两刻才看得到），
     * 一件都没冒出来就直接做完；捡不到的如实失败。
     */
    Action pickUpNewSince(Set<Integer> before);
}
