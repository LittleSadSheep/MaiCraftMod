// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.pathing.debug;

import java.util.List;
import net.minecraft.world.phys.Vec3;

/**
 * 给路径调试显示保存当前位置前一格起、最多五百一十二个路径点，并修正当前位置下标；它不控制导航。
 */
public record NavigationPathSnapshot(List<Vec3> points, int currentIndex, Vec3 destination, Vec3 steeringTarget) {
    public static final int MAX_POINTS = 512;

    public NavigationPathSnapshot {
        int cursor = Math.clamp(currentIndex, 0, Math.max(0, points.size() - 1));
        int start = Math.max(0, cursor - 1);
        points = List.copyOf(points.subList(start, Math.min(points.size(), start + MAX_POINTS)));
        currentIndex = cursor - start;
    }
}
