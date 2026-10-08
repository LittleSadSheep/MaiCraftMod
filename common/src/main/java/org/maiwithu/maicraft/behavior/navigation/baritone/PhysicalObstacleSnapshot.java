// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import java.util.Arrays;
import net.minecraft.world.level.BlockGetter;

/**
 * 把移动结构的实际方块碰撞转换到世界位置，供当前导航或后台搜索读取。能读清细节时保留门洞；读不全某个结构时用它的整体范围作保守障碍。
 */
public record PhysicalObstacleSnapshot(List<AABB> boxes, int blockReads, int conservativeStructures, String state) {
    public static final PhysicalObstacleSnapshot EMPTY = new PhysicalObstacleSnapshot(List.of(), 0, 0, "not_installed");
    private static final int READ_BUDGET = 4096, BOX_BUDGET = 4096;
    private static final double EPS = 1e-5;

    public PhysicalObstacleSnapshot { boxes = List.copyOf(boxes); }
    public PhysicalObstacleSnapshot plus(PhysicalObstacleSnapshot other) {
        if(other.boxes().isEmpty()) return this;
        var combined=new ArrayList<>(boxes); combined.addAll(other.boxes());
        return new PhysicalObstacleSnapshot(combined,blockReads+other.blockReads(),
                conservativeStructures+other.conservativeStructures(),state+"+"+other.state());
    }

    /** 扫掠直立身体空间；若已接触或正向障碍移动，则额外检查向外脱离路线。 */
    public boolean clearSegment(Vec3 from, Vec3 to, double width, double height) {
        for (AABB obstacle : boxes) {
            AABB expanded = new AABB(obstacle.minX - width / 2 + EPS, obstacle.minY - height + EPS,
                    obstacle.minZ - width / 2 + EPS, obstacle.maxX + width / 2 - EPS,
                    obstacle.maxY - EPS, obstacle.maxZ + width / 2 - EPS);
            if (expanded.contains(from) && escapesNearestFace(expanded, from, to)) continue;
            if (expanded.contains(from) || expanded.contains(to) || expanded.clip(from, to).isPresent()) return false;
        }
        return true;
    }

    // 身体已经与障碍重叠时，允许朝最近边界向外挪；否则会连脱离接触的第一步也拒绝。
    private static boolean escapesNearestFace(AABB box, Vec3 from, Vec3 to) {
        double[] distances = {from.x - box.minX, box.maxX - from.x, from.y - box.minY,
                box.maxY - from.y, from.z - box.minZ, box.maxZ - from.z};
        double nearest = Arrays.stream(distances).min().orElseThrow();
        double[] movement = {from.x - to.x, to.x - from.x, from.y - to.y,
                to.y - from.y, from.z - to.z, to.z - from.z};
        for (int i = 0; i < distances.length; i++)
            if (distances[i] <= nearest + EPS && movement[i] > EPS) return true;
        return false;
    }
}
