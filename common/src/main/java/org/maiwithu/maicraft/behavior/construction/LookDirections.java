// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.construction;

import net.minecraft.core.Direction;
import net.minecraft.util.Mth;

/**
 * 原版按视角排的"最近朝向"顺序：放置上下文决定方块朝哪边时用它。这里按同样的浮点比较与并列规则算，
 * 不真的转动角色；放置预测拿候选视角来算，和出手后原版算出来的一致。
 */
final class LookDirections {

    private LookDirections() {}

    /** 从最接近视线的方向到最背离的方向。 */
    static Direction[] ordered(float yaw, float pitch) {
        float pitchRadians = pitch * ((float) Math.PI / 180F);
        float yawRadians = -yaw * ((float) Math.PI / 180F);
        float pitchSin = Mth.sin(pitchRadians), pitchCos = Mth.cos(pitchRadians);
        float yawSin = Mth.sin(yawRadians), yawCos = Mth.cos(yawRadians);
        boolean east = yawSin > 0F, up = pitchSin < 0F, south = yawCos > 0F;
        float x = east ? yawSin : -yawSin;
        float y = up ? -pitchSin : pitchSin;
        float z = south ? yawCos : -yawCos;
        float horizontalX = x * pitchCos, horizontalZ = z * pitchCos;
        Direction alongX = east ? Direction.EAST : Direction.WEST;
        Direction alongY = up ? Direction.UP : Direction.DOWN;
        Direction alongZ = south ? Direction.SOUTH : Direction.NORTH;
        if (x > z) {
            if (y > horizontalX) return mirrored(alongY, alongX, alongZ);
            return horizontalZ > y ? mirrored(alongX, alongZ, alongY) : mirrored(alongX, alongY, alongZ);
        }
        if (y > horizontalZ) return mirrored(alongY, alongZ, alongX);
        return horizontalX > y ? mirrored(alongZ, alongX, alongY) : mirrored(alongZ, alongY, alongX);
    }

    /** 竖直方向：抬头是上，低头是下。 */
    static Direction vertical(float pitch) {
        return pitch < 0F ? Direction.UP : Direction.DOWN;
    }

    /** 贴着实心方块放时，原版会把支撑面那一向提到最前。 */
    static Direction[] forPlacement(Direction[] ordered, Direction clickedFace, boolean replacingClicked) {
        Direction[] result = ordered.clone();
        if (!replacingClicked) {
            Direction support = clickedFace.getOpposite();
            for (int index = 0; index < result.length; index++) {
                if (result[index] != support) continue;
                System.arraycopy(result, 0, result, 1, index);
                result[0] = support;
                break;
            }
        }
        return result;
    }

    private static Direction[] mirrored(Direction first, Direction second, Direction third) {
        return new Direction[]{first, second, third, third.getOpposite(), second.getOpposite(), first.getOpposite()};
    }
}
