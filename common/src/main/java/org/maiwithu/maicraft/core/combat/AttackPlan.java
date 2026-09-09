package org.maiwithu.maicraft.core.combat;

import org.maiwithu.maicraft.core.combat.Battlefield.Foe;

/**
 * 看这一刻的战场，建议继续近战、用弓、撤退或结束。
 * 它只读传入的数据，不控制玩家。上一刻选中的敌人还能打时尽量继续打它，避免在一群怪之间不断换目标。
 * 真正的移动、挥击、举盾和拾取由 AttackCompanionTask 执行，这里不保证这些后续动作一定成功。
 */
public final class AttackPlan {

    /** 这一刻做什么。 */
    public enum Action {
        /**
         * 走位。<b>这是一个移动动作,不是"打不打"</b> —— 打由攻击系统每刻独立判(冷却好了
         * 且够得着就打),与她这一刻在靠近还是在拉开无关。
         *
         * <p>曾经这里分 MELEE 与 CLOSE_IN 两个动作,于是"靠近"那一支不挥刀、"拉开"那一支
         * 也不挥刀 —— 她躲的时候不还手,骷髅一边后退一边射她也追不上。
         */
        SKIRMISH,
        /**
         * 弓战斗。和 {@link #SKIRMISH} <b>同一个形状,只是环换了一副</b>:内沿是"拉得开弓的
         * 距离",外沿是射程。带内导航自然到达、她停下来,攻击层就把弓拉满 —— "什么时候该
         * 站定"不用另写。
         *
         * <p>剑的环是 {@code [它够得着我 2.02, 我够得着它 3.30]},拿它给弓用的时候两者
         * 打架:环把她往 3.3 拉,而弓要求五格开外,于是她永远到不了射击距离,只会跟着怪
         * 一点点蹭 —— 那就是只给弓和箭时"边缘抖动"的来历。
         */
        BOW,
        /** 脱离接触:打不过,跑。 */
        DISENGAGE,
        /** 没什么可打的了。 */
        DONE
    }

    /**
     * 决定。
     *
     * @param action 做什么
     * @param foeId  对谁做;{@link Action#DISENGAGE}、{@link Action#DONE}
     *               是对全场的,此时为 {@link #NO_FOE}
     */
    public record Move(Action action, int foeId) {}

    public static final int NO_FOE = Integer.MIN_VALUE;

    /**
     * 低于这个有效血量就别打了。
     *
     * <p>比的是<b>折算后</b>的血:满血裸奔与满血下界合金曾经判得一模一样,而后者能多扛
     * 四五倍。数值沿用一直以来的那条裸血线(八点,四颗心),只是喂进来的输入变实在了。
     */
    private static final double MIN_EFFECTIVE_HEALTH = 8.0;

    private AttackPlan() {}

    /** 这点有效血量还够不够站着打。阈值只有这一处。 */
    public static boolean outmatched(double effectiveHealth) {
        return effectiveHealth <= MIN_EFFECTIVE_HEALTH;
    }

    /**
     * @param last 上一刻的决定;第一次传 {@code null}
     */
    // 依次判断是否要逃、选谁打、用近战还是弓。低血量或没武器会先考虑撤退，除非已被判定无路可退。
    public static Move decide(Battlefield b, Move last) {
        // ① 扛不住 —— 一切"怎么打"的讨论都以她还站得住为前提。
        if (outmatched(b.effectiveHealth()) && !b.cornered()) {
            return new Move(Action.DISENGAGE, NO_FOE);
        }
        // 「会炸的贴太近了」也不在这儿判。点着的爬行者<b>危险半径就是它的爆炸波及范围</b>
        // (6.71 格),走位环的内沿自然把她顶到那之外 —— 曾经它是一个独立动作(AVOID),
        // 于是"躲爆炸"和"走位"成了互斥的两个状态,躲的那一支还不还手。
        //
        // ③ 手上没有能打的东西:赤手对上会还手的东西不是一条出路,退开。
        //
        //    空手就该一路走脱离这一支,不该跟着距离线在两个动作之间换。
        if (!b.hasMelee() && !b.hasRanged() && anyEngaging(b) && !b.cornered()) {
            return new Move(Action.DISENGAGE, NO_FOE);
        }
        // 「太近了」不在这儿判。它是<b>寻路的事</b>:战斗的走位目标是一个环 —— 内沿是目标
        // 够不着她,外沿是别跟丢,太近自然往外走、太远自然往回走。曾经它是一个独立动作
        // (AVOID),于是"拉开"和"走位"成了互斥的两个状态,而拉开那一支还不挥刀。
        //
        // 判据只回答两件事:<b>还打不打得过</b>(打不过就跑),以及<b>用弓还是走位</b>。
        Foe foe = pick(b, last);
        if (foe != null) {
            return new Move(actionAgainst(b, foe), foe.id());
        }
        // 挑不出目标,但还有东西在追她 —— <b>这不是"打不过"</b>。她照样走位:环退化成
        // "离每一只威胁都出了它的危险半径",引信熄了、或者别的怪凑上来,下一刻自会有目标。
        //
        // 这里曾经判 DISENGAGE。于是场上只剩一只点着的爬行者时,拿着下界合金剑的满血玩家
        // 直接跑三十二格 —— 明明退七格引信就倒退了。<b>顶层只判打不过</b>:血量撑不住,
        // 或者手上没有任何武器。"眼前这只暂时不能打"不属于那两条。
        if (anyEngaging(b)) {
            return new Move(Action.SKIRMISH, NO_FOE);
        }
        return new Move(Action.DONE, NO_FOE);
    }

    /**
     * 打谁。<b>先打近的,但选定之后打完再换</b>——每刻按距离重选的话,一群会分裂的史莱姆里
     * "最近那只"每刻都在变,她永远在转向,而每次转向都会拆掉刚算好的路径。
     */
    // 上次目标仍允许打就继续盯住它；否则在能打的候选里选最近者，同距时用编号稳定顺序。
    private static Foe pick(Battlefield b, Move last) {
        Foe kept = last == null ? null : b.byId(last.foeId());
        if (kept != null && fightable(b, kept)) {
            return kept;
        }
        Foe best = null;
        for (Foe f : b.foes()) {
            if (!fightable(b, f)) {
                continue;
            }
            if (best == null || f.distance() < best.distance()
                    || (f.distance() == best.distance() && f.id() < best.id())) {
                best = f;
            }
        }
        return best;
    }

    /**
     * 这一只值不值得当目标。
     *
     * <p><b>会炸的东西,没有远程手段就根本不该当目标</b>:她要的是躲开它,不是打它。让它进
     * 候选的话,判据会在安全线上一格 AVOID、一格 ABANDON 地来回跳——放弃后下一刻又被选回来,
     * 实测每秒三轮。躲它归 ② 那一档,不归"打谁"。
     */
    // 这里只选获准攻击的目标。已经准备爆炸的目标还要求有远程武器，避免计划贴脸去打。
    private static boolean fightable(Battlefield b, Foe f) {
        if (!f.authorized()) {
            return false;
        }
        // 引信没点着的爬行者就是一只普通怪:她够得着 4 格、它 3 格才点火,中间那条一格宽的
        // 带能打到它而不触发。曾经"会炸的一律不当目标",于是她只会绕着走、永远解决不掉,
        // 还在安全线上一格 AVOID 一格 ABANDON 地来回跳。
        return !f.armed() || b.hasRanged();
    }

    /**
     * 用哪一套打这一只。<b>只看两件事:够不够得到它,以及手上有什么。</b>
     *
     * <pre>
     * 走得到 且 有近战武器          → 剑战斗(省箭)
     * 走不到,或者只有弓            → 弓战斗
     * 引信在走的会炸物             → 弓战斗(贴上去等于自己引爆)
     * 两样都没有                   → 拳头也得上,当剑战斗
     * </pre>
     *
     * <p><b>"走不到又射不到"不在这儿处理</b>:那一只根本不该还是目标。任务层在寻路判出
     * NO-PATH 那一刻就把它撤了授权,{@link #fightable} 自然选不中它。
     */
    // 有远程武器时，爆炸危险、近战走不到或没有近战武器，都倾向用弓；其他情况继续近战走位。
    private static Action actionAgainst(Battlefield b, Foe foe) {
        if (!b.hasRanged()) {
            return Action.SKIRMISH;   // 没弓:走得到走不到都只能凑近了打
        }
        if (foe.armed() || !foe.reachable() || !b.hasMelee()) {
            return Action.BOW;
        }
        return Action.SKIRMISH;
    }

    private static boolean anyEngaging(Battlefield b) {
        return b.foes().stream().anyMatch(Foe::engaging);
    }
}
