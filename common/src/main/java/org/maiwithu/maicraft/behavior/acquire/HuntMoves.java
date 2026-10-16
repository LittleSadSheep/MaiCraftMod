// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.Optional;

import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 狩猎的出手与走位接缝：对一只生物原生攻击一次、朝一处走过去、角色此刻在哪。
 * 攻击的充能与确认都按游戏接口层的攻击交互走（与自卫、战斗同一条攻击事实），测试换替身。
 */
public interface HuntMoves {

    /** 对这只生物出手一次：先把挑好的近战武器拿到主手，再原生攻击（充能够格才挥、逐刻确认）。 */
    Action strike(TickContext context, int entityId);

    /**
     * 开追之前把身上最好的剑斧拿到手上：在快捷栏按数字键，在主背包也开背包换进快捷栏的空格再选中。
     * 手里已经是一样好的剑斧、身上没有剑斧时为空。出手那一刻已经贴着猎物，那时不为换武器开背包，所以要在追之前换好。
     */
    Optional<Action> readyWeapon(TickContext context);

    /** 站在这儿够不够得着它：按角色的实体触及距离量到它的包围盒，够不着就得先走近。 */
    boolean inReach(TickContext context, int entityId);

    /** 这只生物此刻在哪；不在了为空。走近时朝它现在的位置走，不朝扫描时记下的旧位置走。 */
    Optional<double[]> positionOf(TickContext context, int entityId);

    /**
     * 角色和这只猎物是不是泡在同一片水里：两个都在水里，眼睛到它身体中心那条直线一路是水，没有方块挡着。
     * 是的话不交给寻路：寻路在水里只在水面那一层找路，到不了水下它跟前，只会停在它头顶上够不着。
     */
    boolean sameWater(TickContext context, int entityId);

    /** 在水里朝这只猎物游过去：镜头一直跟着它，要下潜就下潜、要上浮就上浮；氧气不够先浮上去换满再回来接着追。 */
    Action swimTo(int entityId);

    /** 朝一个位置走过去（只走不改）。 */
    Action walkTo(double x, double y, double z);

    /**
     * 走到一片地方去找猎物：只认水平位置，到了那一列附近哪个高度都算（山地里按出发时的高度去找，
     * 点常在山体里或半空中，寻路对着它绕远路一分钟没进展）。默认按给的高度走。
     */
    default Action walkToArea(double x, double y, double z) {
        return walkTo(x, y, z);
    }

    /** 此刻角色所在的位置。 */
    double[] selfPosition(TickContext context);
}
