// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import java.util.List;
import java.util.Optional;

/**
 * 对付会炸的（苦力怕）：引信点着又离得近，先躲——退到它够不着、引信也会熄的距离之外，别的都不做；
 * 没点引信的照常当一只怪打：打一下它开始膨胀，退开让引信熄了再上，一下一下磨死。
 * 引信点着但已经离得远的不去凑：凑近了只会让它接着膨胀。全仓只有这一份规则，自卫与战斗都用它。
 */
public final class BlastAvoidance {

    /** 原版苦力怕在目标离开 7 格后停止膨胀：这个距离内引信还在走，必须躲。 */
    public static final double DEFUSE_DISTANCE = 7.0;
    /** 躲到离它这么远：出了熄引信的距离再留两格余量，它追上来也来得及再退。 */
    public static final double EVADE_TO_DISTANCE = 9.0;

    private BlastAvoidance() {}

    /** 点着引信、还在熄引信距离之内的那只（最近的）；没有为空。 */
    public static Optional<CombatSenses.Threat> armedWithinFuseRange(List<CombatSenses.Threat> threats) {
        CombatSenses.Threat nearest = null;
        for (CombatSenses.Threat threat : threats) {
            if (!armedWithinFuseRange(threat)) continue;
            if (nearest == null || threat.distance() < nearest.distance()) nearest = threat;
        }
        return Optional.ofNullable(nearest);
    }

    /** 这只是不是点着引信、离得近到还会炸的会炸物。 */
    public static boolean armedWithinFuseRange(CombatSenses.Threat threat) {
        return threat.kind() == ThreatAssessment.Kind.EXPLOSIVE && threat.armed()
                && threat.distance() <= DEFUSE_DISTANCE;
    }

    /** 能不能当近战目标：会炸的只打没点引信的；点着的躲开或等它熄了再说。 */
    public static boolean meleeTarget(ThreatAssessment.Kind kind, boolean armed) {
        return !(kind == ThreatAssessment.Kind.EXPLOSIVE && armed);
    }

    /** 躲到哪：从它背对的方向走到 EVADE_TO_DISTANCE 远；贴在一起分不清方向时朝东退。返回 {x, z}。 */
    public static double[] evadePoint(double selfX, double selfZ, double foeX, double foeZ) {
        double awayX = selfX - foeX;
        double awayZ = selfZ - foeZ;
        double length = Math.hypot(awayX, awayZ);
        if (length < 0.5) {
            awayX = 1;
            awayZ = 0;
            length = 1;
        }
        return new double[] {selfX + awayX / length * EVADE_TO_DISTANCE, selfZ + awayZ / length * EVADE_TO_DISTANCE};
    }
}
