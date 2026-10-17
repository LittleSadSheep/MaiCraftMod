// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.combat;

import java.util.OptionalInt;

import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 战斗引擎动手的接缝：手上出手、拉弓、举盾，脚下迈步与寻路，以及看自己在哪、脚下能不能站。
 * 生产实现是原生攻击、按键走位与走到，离线测试换替身记录调用。
 */
public interface BattleMoves {

    /** 对这只出手一次：先把挑好的近战武器拿到主手，再原生攻击（充能够格才挥、无敌帧不挥、逐刻确认）。 */
    Action strike(TickContext context, int entityId);

    /** 冲这只射一箭（或用联动远程打一发）：拿弓弩、拉弓或装弩、对准、放。 */
    Action shoot(TickContext context, int entityId);

    /** 身上有没有能射的。 */
    boolean canShoot(TickContext context);

    /** 身上有没有剑斧这类近战武器。 */
    boolean hasMeleeWeapon(TickContext context);

    /** 攻击此刻充能好了没。 */
    boolean attackReady(TickContext context);

    /** 举盾放盾：对手离这么近、这一刻是不是正要出手（要出手就放下）。 */
    void guard(TickContext context, double distance, boolean aboutToStrike);

    /**
     * 还在走过去、离对手够远时，开始把最好的近战武器拿到手上（在主背包就开背包换进快捷栏的空格再选中）：
     * 出手那一刻贴着脸，就不为换武器开背包了。没有剑斧、已经在手上、已经在换就什么都不做；之后由
     * {@link #finishArming} 每刻推进。
     */
    default void arm(TickContext context, double distance) {}

    /**
     * 换到一半的武器接着换：换武器一旦开始就做完，"打完了"、贴到跟前都不中途收掉。每刻战斗开头推进一次；
     * 返回这一刻还在换（手腾不出来，不挥不射）。没接上时按没在换。
     */
    default boolean finishArming(TickContext context) {
        return false;
    }

    /** 这一仗真结束了（任务收尾、被替换）：换到一半的武器也收尾，开着的背包交给原版关上。 */
    default void stopArming() {}

    /** 不打了：盾放下（换到一半的武器不收，接着换完）。 */
    void lowerGuard();

    /** 当刻脚下的路：走位挑方向用。 */
    Footwork.Ground ground(TickContext context);

    /** 按挑好的一步迈这一刻；faceEntityId 为 -1 时朝走的方向。 */
    void step(TickContext context, int faceEntityId, Footwork.Step step);

    /**
     * 迈一步但不动镜头：手上这一下正瞄着（瞄点跟着对方横移、留在它碰撞箱里），走位要是再把镜头拉回它身体中心，
     * 出手的瞄点就白算了。按键量按此刻实际朝向折算。没接上时同普通迈步。
     */
    default void stepKeepingAim(TickContext context, Footwork.Step step) {
        step(context, -1, step);
    }

    /**
     * 迈一步，镜头压在出手要瞄的那一点上（它身上离眼睛最近、往里收一点的那一点，跟出手时瞄的是同一点）：
     * 跳劈起跳前、往上升时这样看着，往下落那几刻不用再转镜头就能砍。没接上时同普通迈步。
     */
    default void stepAimingStrike(TickContext context, int entityId, Footwork.Step step) {
        step(context, entityId, step);
    }

    /** 准星此刻正落在这只身上：够得着的距离内，眼睛顺着视线先碰到的就是它。没接上时按没有。 */
    default boolean crosshairOn(TickContext context, int entityId) {
        return false;
    }

    /** 交给寻路走一段（只走不改地形）。 */
    Action walk(GoalCompiler.Compiled goal);

    /** 此刻角色脚下的位置 {x, y, z}。 */
    double[] selfPosition(TickContext context);

    /** 这一格能不能站（已加载、脚下踩得实、身子两格空、没有水）：撤离挑落点用。 */
    boolean standable(TickContext context, int x, int y, int z);

    /** 此刻主手里拿着什么（物品 ID，空手为"空手"）。 */
    String mainHand(TickContext context);

    /**
     * 此刻身子在不在地上、在不在往下落、能不能起跳（不在水里岩浆里、没在爬梯子、没骑着东西、头顶有空），
     * 以及攻击充能到几成。跳劈要看这些；没接上时按站在地上、不能跳、充满算。
     */
    default Posture posture(TickContext context) {
        return Posture.GROUNDED;
    }

    /**
     * 能不能就地垫高：白天、身上有三块以上能拿去垫的废料、头顶三格空着。撤不掉又打不过时用；
     * 夜里不垫（交给夜晚需求的挖三填一）。没接上时不能。
     */
    default boolean canPillar(TickContext context) {
        return false;
    }

    /** 天黑了：撤不掉又打不过时不垫高，挖坑把自己封起来（由调用方交给挖三填一）。没接上时按白天。 */
    default boolean night(TickContext context) {
        return false;
    }

    /** 把这个飞来的火球打回去的动作；没接上时为 null（照常打）。 */
    default Action returnProjectile(int projectileId) {
        return null;
    }

    /** 就地往上垫三格，站到柱顶（只垫不挖，垫的是废料）。没接上时如实失败。 */
    default Action pillarUp(TickContext context) {
        return null;
    }

    /**
     * 砍这条龙的龙头：龙头是龙的一个碰撞部件，只有打这里是全额伤害；命中按龙本体的掉血、红闪确认。
     * 没接上时按打龙本体。
     */
    default Action strikeHead(TickContext context, int dragonId) {
        return strike(context, dragonId);
    }

    /** 冲这只射一箭，射程放到 maxRange 格（站在爆炸范围外仰射柱顶的末影水晶）。没接上时同普通射箭。 */
    default Action shoot(TickContext context, int entityId, double maxRange) {
        return shoot(context, entityId);
    }

    /** 此刻站在这里，有没有一条射得中它的弹道（maxRange 格内、不被方块挡住）。没接上时按有。 */
    default boolean arcTo(TickContext context, int entityId, double maxRange) {
        return true;
    }

    /**
     * 迈一步、镜头看着这只（-1 时朝走的方向）；这一眼会对上没惹毛的末影人的眼睛，就把镜头往下压看脚前，
     * 走的方向不变。没接上时同普通迈步。
     */
    default void stepAvertingGaze(TickContext context, int faceEntityId, Footwork.Step step) {
        step(context, faceEntityId, step);
    }

    /** 地表：一列站得住的脚的高度（已加载、踩得住、不伤人、不是虚空），挑打龙的落点用。没接上时哪儿都站不住。 */
    default DragonGeometry.Surface surface(TickContext context) {
        return (x, z) -> OptionalInt.empty();
    }

    /** 这一点所在的区块加载着没有：看不见的水晶是被打掉了还是没加载，靠它分。没接上时按加载着。 */
    default boolean loaded(TickContext context, double x, double z) {
        return true;
    }

    /** 副手拿着盾：躲不开龙的冲撞时举盾。没接上时按没有。 */
    default boolean hasShield(TickContext context) {
        return false;
    }

    /**
     * 身子此刻的姿势。
     *
     * @param falling 离地往下落：这时候砍是原版暴击（伤害 × 1.5）
     * @param charge  攻击充能（0 到 1）
     */
    record Posture(boolean onGround, boolean falling, boolean canJump, float charge) {
        public static final Posture GROUNDED = new Posture(true, false, false, 1f);
    }
}
