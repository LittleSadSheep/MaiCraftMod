// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import net.minecraft.core.Direction;
import net.minecraft.util.Mth;

/**
 * Candidate-angle equivalent of Minecraft 1.21.1 Direction.orderedByNearest and BlockPlaceContext
 * (Mojang mappings). Keep their float comparisons and tie order without rotating the real player.
 */
final class NativePlacementDirections {
    private NativePlacementDirections() {}

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

    static Direction vertical(float pitch) { return pitch < 0F ? Direction.UP : Direction.DOWN; }

    /** Only the plural native query prioritizes the supporting face when placing beside a solid block. */
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
