// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.explore;

/**
 * 扔出去的一只末影之眼看到的经过：从哪飞起、最后看到它在哪、是落下了还是碎了。
 *
 * <p>原版的眼离要塞起点水平超过十二格时，朝要塞方向横飞十二格、往上升八格后落下或碎掉（两成几率碎）；
 * 十二格以内就直接朝要塞起点往下钻。所以从出手点到最后看到的位置，水平方向就是要塞的方向，
 * 落点比出手点低就是要塞在脚下附近。只是看到的事实，判断在 {@link EyeBearings}。
 *
 * @param fromX      眼刚飞起时的位置
 * @param toX        眼消失前最后看到的位置
 * @param landing    落下了（能捡回）、碎了，还是没看到它怎么没的
 * @param dropEntity 落下的那颗眼（掉落物）的游戏实体编号；没落下为 -1
 */
public record EyeFlight(double fromX, double fromY, double fromZ, double toX, double toY, double toZ,
        Landing landing, int dropEntity) {

    /** 眼最后怎么样了。 */
    public enum Landing {
        /** 落下来变回一颗末影之眼，躺在地上能捡回。 */
        DROPPED,
        /** 碎了：原版两成几率，这只眼没了。 */
        SHATTERED,
        /** 一直没看到它落下或碎掉（飞出了看得见的范围）：方向照样读，眼当作没捡回。 */
        LOST_SIGHT
    }

    public EyeFlight {
        if (landing == null) throw new IllegalArgumentException("要写明眼最后怎么样了");
    }
}
