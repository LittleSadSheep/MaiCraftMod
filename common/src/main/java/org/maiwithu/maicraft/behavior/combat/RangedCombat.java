// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.combat;

import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 用弓弩打：和近身打同一个形状，只是环换了一副——内沿是"拉得开弓的距离"，外沿是"稳稳射得中"的距离。
 * 离得近往后退，离得远往前凑，带里就站住；拉弓不停脚（原版拉弓本来就能走），只在快放手对准的那几刻站住，
 * 免得迈步把准星带偏。一箭射完接着拉下一箭。
 *
 * <p>什么时候用弓见 {@link #preferBow}：没有剑斧、对手是会炸的、走不到它跟前，就射；
 * 都有时七格外换弓、贴近到四格半以内才换回近战，进出不同线，对手在六七格晃的时候不来回换手。
 * 射不出去（没箭、换不上、连着没得到确认）连着两次就交回近战，不在弓上卡死。
 */
public final class RangedCombat {

    /**
     * 内沿：比这近就往后退。拉满一张弓要一秒，这一秒僵尸能走四格多；五格的话刚拉到一半就贴脸了。
     */
    public static final double INNER = 8.0;
    /** 外沿：比这远就往前凑。再远箭会下坠、目标会走，射不准。 */
    public static final double OUTER = 12.0;
    /** 这么远以内、看得见，才按弓的走位带打；再远交给寻路走近。 */
    public static final double ENGAGE_RANGE = 20.0;
    /** 离得比这远就换弓。 */
    static final double SWITCH_TO_BOW = 7.0;
    /** 正在射时，贴近到比这近才换回近战。 */
    static final double SWITCH_TO_MELEE = 4.5;
    /** 连着这么多箭没射出去就交回近战。 */
    private static final int MAX_MISFIRES = 2;

    /** 射箭要做的事：近身那一套之外，再加射一箭、身上能不能射、有没有剑斧。 */
    public interface Moves extends CloseCombat.Moves {
        /** 冲这一只射一箭：拿弓弩、拉弓或装弩、对准、放。没接上射箭时给一个立刻失败的动作。 */
        default Action shoot(TickContext context, int entityId) {
            return NO_SHOT;
        }

        /** 身上有没有能射的：有箭的弓、上了弦或有箭可装的弩。 */
        default boolean canShoot(TickContext context) {
            return false;
        }

        /** 身上有没有剑斧。 */
        default boolean hasMeleeWeapon(TickContext context) {
            return true;
        }
    }

    /** 没接上射箭：一推进就如实失败。 */
    private static final Action NO_SHOT = new Action() {
        @Override
        public ActionStatus tick(TickContext context) {
            return ActionStatus.failed(Problem.of(Problem.Kind.UNSUPPORTED, "射箭还没接上"));
        }

        @Override
        public String describe() {
            return "射箭还没接上";
        }
    };

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

    /**
     * 用弓打这一刻：推进手上这一箭，同时按距离走位。返回这一刻有没有真射出去一箭。
     *
     * @param retreating 正在撤离：只往后退，射得到就射
     */
    public boolean tick(TickContext context, Moves moves, int targetId, double distance, boolean retreating) {
        if (shot != null && shotTarget != targetId) close();
        if (shot == null) {
            shot = moves.shoot(context, targetId);
            shotTarget = targetId;
        }
        ActionStatus status = shot.tick(context);
        boolean fired = false;
        boolean steady = shot instanceof RangedShot ranged && ranged.aiming();
        if (status instanceof ActionStatus.Done || status instanceof ActionStatus.Failed) {
            fired = shot instanceof RangedShot ranged && ranged.fired();
            misfires = status instanceof ActionStatus.Failed ? misfires + 1 : 0;
            close();
        }
        // 快放手对准的那几刻站住、不转头：转头和迈步都会把准星带偏。其余时候边拉边走位。
        if (!steady) moves.step(context, targetId, forward(distance, retreating));
        return fired;
    }

    /** 这一刻往前、往后还是站住：撤离时一律往后退。 */
    static float forward(double distance, boolean retreating) {
        if (retreating) return -1f;
        if (distance > OUTER) return 1f;
        if (distance < INNER) return -1f;
        return 0f;
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
