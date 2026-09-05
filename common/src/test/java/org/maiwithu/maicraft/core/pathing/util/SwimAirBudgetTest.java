package org.maiwithu.maicraft.core.pathing.util;

import org.maiwithu.maicraft.core.task.survival.SurvivalDecisions;

/** Air is reserved for the actual ascent rather than surfacing after a fixed three-second dip. */
public final class SwimAirBudgetTest {
    public static void main(String[] args) {
        int shallow = SwimAirBudget.requiredAirForAscent(1.0, 1.0);
        int deep = SwimAirBudget.requiredAirForAscent(12.0, 1.0);
        if (shallow < 60 || shallow >= 90 || deep <= shallow) throw new AssertionError("incorrect depth-dependent reserve");
        if (SwimAirBudget.requiredAirForAscent(0, 1) < 60) throw new AssertionError("keep three seconds even directly below the surface");
        if (SurvivalDecisions.breathTriggered(true, 240, shallow)) throw new AssertionError("full swim interrupted after three seconds");
        if (!SurvivalDecisions.breathTriggered(true, shallow, shallow)) throw new AssertionError("ascent must start with reserve intact");
        if (SurvivalDecisions.breathTriggered(false, 0, deep)) throw new AssertionError("breathable eyes do not need rescue");
        if (!SurvivalDecisions.breathRecoveryRequired(true, false, false, 299, 300)) throw new AssertionError("refill must finish");
        if (SurvivalDecisions.breathRecoveryRequired(true, false, false, 300, 300)) throw new AssertionError("full refill must release");
        if (!SurvivalDecisions.breathRecoveryRequired(false, false, false, 145, 300)) throw new AssertionError("airborne crest must not cancel refill");
        if (SurvivalDecisions.breathRecoveryRequired(false, false, true, 145, 300)) throw new AssertionError("landing on dry ground ends the water recovery");
        SwimAirBudget observed = new SwimAirBudget();
        observed.observe(1, 300, true);
        observed.observe(6, 290, true);
        if (observed.airPerTick() != 2.0 || SwimAirBudget.requiredAirForAscent(1, observed.airPerTick()) != shallow * 2) {
            throw new AssertionError("increased observed oxygen consumption must increase reserve");
        }
        observed.observe(7, 294, false);
        if (observed.airPerTick() != 1.0) throw new AssertionError("new dive must resample consumption");
        if (SwimAirBudget.requiredAirForAscent(Double.NaN, 1) != Integer.MAX_VALUE) throw new AssertionError("unknown ascent cannot be assumed free");
        System.out.println("SwimAirBudgetTest: passed");
    }
}
