// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.physics;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

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

    public static PhysicalObstacleSnapshot capture(ClientLevel level, Vec3 focus) {
        var frame = SableStructureBridge.open(level, focus, null);
        if ("not_installed".equals(frame.state())) return EMPTY;
        return capture(level, frame.structures(), focus, frame.state());
    }

    // 只展开玩家附近十六格内的部分；某结构的细节不完整时丢掉它已读出的零散盒子，改用整体范围。
    static PhysicalObstacleSnapshot capture(net.minecraft.world.level.BlockGetter world,
            List<SableStructureBridge.Structure> structures, Vec3 focus, String state) {
        var boxes = new ArrayList<AABB>();
        int reads = 0, conservative = 0;
        AABB interest = new AABB(focus, focus).inflate(16);
        for (var structure : structures) {
            AABB bounds = structure.worldBounds();
            if (bounds == null || !bounds.intersects(interest)) continue;
            int firstBox = boxes.size();
            boolean unknown = !Boolean.TRUE.equals(structure.ready()) || structure.pose() == null
                    || structure.storageBounds() == null;
            if (!unknown) {
                try {
                    // Neighbor origins may have shapes protruding into the world-space window.
                    AABB area = transformBox(structure.pose(), interest, false).inflate(1).intersect(structure.storageBounds());
                    BlockPos min = BlockPos.containing(area.minX, area.minY, area.minZ);
                    BlockPos max = BlockPos.containing(Math.nextDown(area.maxX), Math.nextDown(area.maxY), Math.nextDown(area.maxZ));
                    search: for (BlockPos cell : BlockPos.betweenClosed(min, max)) {
                        if (reads++ >= READ_BUDGET || boxes.size() >= BOX_BUDGET) { unknown = true; break; }
                        var read = structure.readBlock(cell);
                        if (!"known".equals(read.state())) { unknown = true; break; }
                        var block = read.blockState();
                        if (block.isAir()) continue;
                        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                            BlockPos neighbor = cell.offset(dx, 0, dz);
                            if (structure.storageBounds().contains(Vec3.atCenterOf(neighbor)) && !structure.isLoaded(neighbor)) {
                                unknown = true; break search;
                            }
                        }
                        var shape = block.getCollisionShape(world, cell, CollisionContext.empty()).toAabbs();
                        if (shape.size() > 64 || boxes.size() + shape.size() > BOX_BUDGET) { unknown = true; break; }
                        for (AABB local : shape) {
                            AABB obstacle = transformBox(structure.pose(), local.move(cell), true);
                            if (obstacle.intersects(interest)) boxes.add(obstacle);
                        }
                    }
                } catch (RuntimeException | LinkageError missingGeometry) { unknown = true; }
            }
            if (unknown) {
                // A partially observed vessel is an obstacle, never an empty corridor.
                boxes.subList(firstBox, boxes.size()).clear();
                boxes.add(bounds); conservative++;
            }
        }
        return new PhysicalObstacleSnapshot(boxes, Math.min(reads, READ_BUDGET), conservative,
                conservative > 0 ? "partial" : state);
    }

    /**
     * 转换盒子的八个角，再取包住这些点的世界或存储坐标盒；旋转后可能比真实斜盒更宽，是保守范围。
     */
    public static AABB transformBox(StructurePose pose, AABB box, boolean toWorld) {
        double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
        double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
        for (int bits = 0; bits < 8; bits++) {
            Vec3 corner = new Vec3((bits & 1) == 0 ? box.minX : box.maxX,
                    (bits & 2) == 0 ? box.minY : box.maxY, (bits & 4) == 0 ? box.minZ : box.maxZ);
            Vec3 p = toWorld ? pose.toWorld(corner) : pose.toStorage(corner);
            minX = Math.min(minX, p.x); minY = Math.min(minY, p.y); minZ = Math.min(minZ, p.z);
            maxX = Math.max(maxX, p.x); maxY = Math.max(maxY, p.y); maxZ = Math.max(maxZ, p.z);
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /** Swept upright body, with an outward escape from an already touching/moving obstacle. */
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
        double nearest = java.util.Arrays.stream(distances).min().orElseThrow();
        double[] movement = {from.x - to.x, to.x - from.x, from.y - to.y,
                to.y - from.y, from.z - to.z, to.z - from.z};
        for (int i = 0; i < distances.length; i++)
            if (distances[i] <= nearest + EPS && movement[i] > EPS) return true;
        return false;
    }
}
