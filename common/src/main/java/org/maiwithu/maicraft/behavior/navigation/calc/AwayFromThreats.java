// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.calc;

import java.util.List;

import net.minecraft.core.BlockPos;

/**
 * 离开一组威胁：走到每一只的脱身圈外就到了。和"持续远离起点"的两点不同：它认得全部威胁
 * （一左一右两只怪时不会直穿其中一只），而且有终点——出了圈就停，不用上层每刻喊停。
 *
 * <p>用在：战斗里挑不出能打的目标、只剩点着引信的苦力怕时退开等它熄；撤离时躲开追兵；冷却中退到够不着的地方。
 */
public final class AwayFromThreats implements NavGoal {

    private final ThreatField field;
    private final BlockPos centroid;

    public AwayFromThreats(ThreatField field) {
        this.field = field;
        double x = 0;
        double y = 0;
        double z = 0;
        for (ThreatField.Threat threat : field.threats()) {
            x += threat.x();
            y += threat.y();
            z += threat.z();
        }
        int count = field.threats().size();
        this.centroid = BlockPos.containing(x / count, y / count, z / count);
    }

    public ThreatField field() {
        return field;
    }

    @Override
    public boolean isAt(BlockPos feet) {
        return field.clearAt(feet.getX(), feet.getY(), feet.getZ());
    }

    // 宽松的范围目标：水里漂出每一只的脱身圈也算脱身。
    @Override
    public boolean acceptsWaterArrival() {
        return true;
    }

    // 长程撤离要有"离脱身还差多远"的估价：单靠近怪惩罚衰减得太快，部分路线不愿继续往外延伸。
    @Override
    public double heuristic(BlockPos from) {
        return field.remainingClearance(from.getX(), from.getZ()) * COST_HEURISTIC
                + field.penaltyAt(from.getX(), from.getY(), from.getZ());
    }

    /** 威胁群的重心：它一挪动，调用方就知道该重开一次寻路。 */
    @Override
    public BlockPos center() {
        return centroid;
    }

    @Override
    public SemanticFingerprint semanticFingerprint() {
        return new SemanticFingerprint("away_from_threats", List.of(field.penaltyFactor(), field.sortedThreats()));
    }
}
