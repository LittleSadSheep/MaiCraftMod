// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 近身打的走位与出手：走位是一个环——外沿是我够得着它，内沿是它够不着我；离远了往前凑，凑太近往后退，
 * 环里就站住。出手和走位互不排斥：够得着就一直挥（充能、无敌帧由原生攻击自己等），不管这一刻是在凑近还是在后退，
 * 退的时候也还手。原先打和追是二选一的两档，追着就不挥、贴脸了又站着挨打，骷髅一边退一边射也追不上。
 *
 * <p>只管近处（几格内、看得见）；再远的交给寻路去追。后退那一步落不落脚由走位的实现看过再迈，脚下没路就站住。
 *
 * <p>打玩家另有一副环：两边手一样长，没有"我够得着它够不着我"的地方，所以按出刀节拍走——充能好了凑进去出手，
 * 没好就退到它够不着的地方等，不站在对方手里干等冷却。副手有盾时，对手近、自己又还没到出手的时候就举盾，要挥了先放下。
 */
public final class CloseCombat {

    /** 几格内算近身：再远就交给寻路追过去。 */
    public static final double CLOSE_RANGE = 5.0;
    /** 离得比这还远就往前凑：比手够得着的距离（三格）留一点余量。 */
    static final double OUTER = 2.9;
    /** 离得比这还近就往后退：近战怪的手大约够一格半，留出半格。 */
    static final double INNER = 2.2;

    /**
     * 走位环：比外沿远就往前凑，比内沿近就往后退。
     *
     * @param inner 内沿（格）
     * @param outer 外沿（格）
     */
    public record Ring(double inner, double outer) {
        /** 打怪：我够三格，近战怪的手大约够一格半。 */
        public static final Ring MOB = new Ring(INNER, OUTER);

        /**
         * 打玩家：充能好了凑进自己够得着的地方（玩家手够三格，加上对方半个身子），没好就退到对方够不着的地方
         * （再留三分之一格），等冷却。
         */
        public static Ring player(boolean attackReady) {
            return attackReady ? new Ring(2.3, 3.15) : new Ring(3.65, 4.5);
        }
    }

    /** 走位要做的事：对哪只出手、朝它的方向往前（+1）或往后（-1）迈，以及打玩家的节拍和举盾。 */
    public interface Moves {
        Action strike(TickContext context, int entityId);

        /** 面朝它，往前（正）或往后（负）迈这一刻；0 是站住。后退那一步脚下没路时实现方自己站住。没接上时什么都不做。 */
        default void step(TickContext context, int entityId, float forward) {}

        /** 打这一只用哪副走位环：打怪按怪的，打玩家按出刀节拍。 */
        default Ring ring(TickContext context, int entityId) {
            return Ring.MOB;
        }

        /** 自己的攻击此刻充能好了没。 */
        default boolean attackReady(TickContext context) {
            return true;
        }

        /** 举盾放盾：对手离这么近、这一刻是不是正要出手（要出手就放下）。没有盾、没接上时什么都不做。 */
        default void guard(TickContext context, int entityId, double distance, boolean aboutToStrike) {}
    }

    private Action strike;
    private int strikeTarget = -1;

    /**
     * 近身打这一刻：够得着就推进出手，同时按距离走位。返回这一刻有没有真打出去一下（一次出手做完）。
     *
     * @param distance 到目标的距离（格）
     * @param reach    手够得着的距离（格）
     * @param retreating 正在撤离：只往后退，够得着才还手，不往前凑
     */
    public boolean tick(TickContext context, Moves moves, int targetId, double distance, double reach, boolean retreating) {
        boolean struck = false;
        if (strike != null && strikeTarget != targetId) close();
        Ring ring = moves.ring(context, targetId);
        // 举盾放盾在出手之前：充能好了、够得着要挥，先把盾放下；没到出手的时候、对手又近，就举着。
        moves.guard(context, targetId, distance, distance <= reach && moves.attackReady(context));
        if (distance <= reach) {
            if (strike == null) {
                strike = moves.strike(context, targetId);
                strikeTarget = targetId;
            }
            ActionStatus status = strike.tick(context);
            if (status instanceof ActionStatus.Done || status instanceof ActionStatus.Failed) {
                struck = status instanceof ActionStatus.Done;
                close();
            }
        } else if (strike != null) {
            close();
        }
        // 出手的那一刻原生攻击会松开移动键：走位放在出手之后，退着打、凑着打都照常迈步。
        moves.step(context, targetId, forward(distance, retreating, ring));
        return struck;
    }

    /** 这一刻往前、往后还是站住（打怪的环）：撤离时一律往后退。 */
    static float forward(double distance, boolean retreating) {
        return forward(distance, retreating, Ring.MOB);
    }

    /** 这一刻往前、往后还是站住：撤离时一律往后退；比外沿远往前，比内沿近往后。 */
    static float forward(double distance, boolean retreating, Ring ring) {
        if (retreating) return -1f;
        if (distance > ring.outer()) return 1f;
        if (distance < ring.inner()) return -1f;
        return 0f;
    }

    public void close() {
        if (strike != null) strike.close();
        strike = null;
        strikeTarget = -1;
    }
}
