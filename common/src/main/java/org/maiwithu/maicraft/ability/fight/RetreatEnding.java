// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.fight;

import java.util.Optional;
import java.util.OptionalInt;

import org.maiwithu.maicraft.behavior.combat.CombatSenses;
import org.maiwithu.maicraft.behavior.combat.ThreatAssessment;
import org.maiwithu.maicraft.behavior.survival.HungerNeed;
import org.maiwithu.maicraft.kernel.result.Problem;

/**
 * 点名战斗撤离收场时交给 LLM 的问题：甩开了追兵、或撤不掉又天黑露天交给挖坑自保，都写明这一仗没打赢与出路。
 *
 * <p>血在拒战线之下、身上没有吃了不伤身的食物、饱食度又低于原版自然回血线三样同时成立时，
 * "吃东西回血再来打"这条链断了：照普通的话术写，LLM 会在吃东西（没吃的）和再打（又撤）之间来回，
 * 所以点破这条链并给全出路——先弄吃的，或开着死亡不掉落时寻死重置。睡觉不回血，不写这条。
 * 有吃的、饱食度够自然回血、读不到饱食度（创造模式）时照普通话术，不插长文。
 */
final class RetreatEnding {

    /** 回不了血时的出路：弄吃的让血自己回，或寻死重置。 */
    private static final String HEAL_FIRST = "先 obtain 弄些吃了不伤身的食物（面包、熟肉这类）吃到饱食度 "
            + HungerNeed.NATURAL_REGEN_FOOD + " 以上，血会自己回，再来打；附近弄不到吃的、又开着死亡不掉落时，"
            + "也可以 suicide 回出生点重置";

    private RetreatEnding() {
    }

    /** 甩开了追兵：撤离成功不等于打赢，目标没死就以危险收场。 */
    static Problem escaped(String why, CombatSenses.CombatProfile profile, OptionalInt foodLevel) {
        String message = "撤离成功，甩开了追兵（" + why + "），但目标没有击败——这一仗算没打赢";
        return cannotHeal(profile, foodLevel)
                .map(dead -> Problem.of(Problem.Kind.DANGER, message + "；" + dead, HEAL_FIRST))
                .orElseGet(() -> Problem.of(Problem.Kind.DANGER, message, "吃点东西回血到拒战线以上，或换把武器再来"));
    }

    /** 撤不掉又打不过、天黑露天：这一仗停下，交给自保挖坑熬到天亮。 */
    static Problem needsShelter(CombatSenses.CombatProfile profile, OptionalInt foodLevel) {
        String message = "撤不掉又打不过，天黑露天：这一仗停下，交给自保挖坑熬到天亮";
        return cannotHeal(profile, foodLevel)
                .map(dead -> Problem.of(Problem.Kind.DANGER, message + "；" + dead, "天亮后" + HEAL_FIRST))
                .orElseGet(() -> Problem.of(Problem.Kind.DANGER, message, "天亮后回血、换把武器再来"));
    }

    /** 血线、口粮、饱食度三样都卡住时说清卡在哪；任何一样还有路就为空。 */
    static Optional<String> cannotHeal(CombatSenses.CombatProfile profile, OptionalInt foodLevel) {
        if (foodLevel.isEmpty()) return Optional.empty();
        double effective = ThreatAssessment.effectiveHealth(profile.health(), profile.armorPoints());
        if (effective >= ThreatAssessment.RETREAT_LINE_EFFECTIVE_HEALTH || profile.foodCount() > 0
                || foodLevel.getAsInt() >= HungerNeed.NATURAL_REGEN_FOOD) {
            return Optional.empty();
        }
        return Optional.of("现在血 " + Math.round(profile.health()) + " 点在拒战线之下，身上没有吃了不伤身的食物，饱食度 "
                + foodLevel.getAsInt() + " 又低于自然回血的 " + HungerNeed.NATURAL_REGEN_FOOD
                + "：吃东西回血再来打这条路现在走不通，原样再下 fight 还会撤");
    }
}
