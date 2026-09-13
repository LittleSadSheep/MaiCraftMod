// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;

/** 已逐边证明可走的地面路线只合并共线同高的中间记号；不画新捷径，转弯、回头与台阶仍原样保留。 */
final class BuildWorksiteRoute {
    private static final double EPS = 1e-7;
    private BuildWorksiteRoute() {}

    static List<Vec3> compact(List<Vec3> proven) {
        if (proven.size() < 3) return List.copyOf(proven);
        var result = new ArrayList<Vec3>(); result.add(proven.getFirst());
        for (int at = 1; at < proven.size() - 1; at++) {
            Vec3 before = result.getLast(), current = proven.get(at), next = proven.get(at + 1);
            Vec3 incoming = current.subtract(before), outgoing = next.subtract(current);
            // 共线段覆盖的身体走廊与原来所有小段的并集相同，已有碰撞、禁行和连续落脚证明仍然成立。
            boolean straight = Double.isFinite(incoming.lengthSqr() + outgoing.lengthSqr())
                    && Math.abs(incoming.y) < EPS && Math.abs(outgoing.y) < EPS
                    && incoming.dot(outgoing) > 0 && incoming.cross(outgoing).lengthSqr() < EPS * EPS;
            if (!straight) result.add(current);
        }
        result.add(proven.getLast()); return List.copyOf(result);
    }
}
