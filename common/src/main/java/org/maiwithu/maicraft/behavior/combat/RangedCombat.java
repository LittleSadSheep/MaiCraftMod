// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.combat;

import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 用弓弩打的那只手：拉弓、装弩、对准、放，一箭射完接着拉下一箭；脚怎么走由战斗引擎按拉弓的距离带定，
 * 快放手对准的那几刻引擎让脚站住（{@link #steady}），免得迈步把准星带偏。
 *
 * <p>什么时候用弓见 {@link #preferBow}：没有剑斧、对手是会炸的、走不到它跟前，就射；
 * 都有时七格外换弓、贴近到四格半以内才换回近战，进出不同线，对手在六七格晃的时候不来回换手。
 * 射不出去（没箭、换不上、连着没得到确认）连着两次就交回近战，不在弓上卡死。
 */
public final class RangedCombat {

    /** 这么远以内、看得见，才按弓的走位带打；再远交给寻路走近。 */
    public static final double ENGAGE_RANGE = 20.0;
    /** 离得比这远就换弓。 */
    static final double SWITCH_TO_BOW = 7.0;
    /** 正在射时，贴近到比这近才换回近战。 */
    static final double SWITCH_TO_MELEE = 4.5;
    /** 连着这么多箭没射出去就交回近战。 */
    private static final int MAX_MISFIRES = 2;

    private Action shot;
    private int shotTarget = -1;
    private int misfires;

    /**
     * 用不用弓打这一只。
     *
     * @param explosive   对手是会炸的（苦力怕）：能射就别凑近
     * @param unreachable 走不到它跟前（隔着悬崖、在高处）
     * @param shootingNow 这一刻已经在用弓：贴得更近才换回近战
     */
    public static boolean preferBow(boolean canShoot, boolean hasMelee, boolean explosive, boolean unreachable,
            double distance, boolean shootingNow) {
        if (!canShoot) return false;
        if (!hasMelee || explosive || unreachable) return true;
        return distance >= (shootingNow ? SWITCH_TO_MELEE : SWITCH_TO_BOW);
    }

    /** 正在用弓（有一箭在拉、在装、在瞄）。 */
    public boolean shooting() {
        return shot != null;
    }

    /** 正在快放手对准的那几刻：脚要站住、不转头，迈步会把准星带偏。 */
    public boolean steady() {
        return shot instanceof RangedShot ranged && ranged.aiming();
    }

    /** 连着几箭没射出去：该交回近战了。 */
    public boolean givenUp() {
        return misfires >= MAX_MISFIRES;
    }

    /** 用弓打这一刻：推进手上这一箭。返回这一刻有没有真射出去一箭。 */
    public boolean tick(TickContext context, BattleMoves moves, int targetId) {
        if (shot != null && shotTarget != targetId) close();
        if (shot == null) {
            shot = moves.shoot(context, targetId);
            shotTarget = targetId;
        }
        ActionStatus status = shot.tick(context);
        boolean fired = false;
        if (status instanceof ActionStatus.Done || status instanceof ActionStatus.Failed) {
            fired = shot instanceof RangedShot ranged && ranged.fired();
            misfires = status instanceof ActionStatus.Failed ? misfires + 1 : 0;
            close();
        }
        return fired;
    }

    /** 不再用弓：拉了一半的取消（切槽，不放箭）。 */
    public void close() {
        if (shot != null) shot.close();
        shot = null;
        shotTarget = -1;
    }

    /** 换了打法（交回近战、换目标）：射不出去的账清零。 */
    public void reset() {
        close();
        misfires = 0;
    }
}
