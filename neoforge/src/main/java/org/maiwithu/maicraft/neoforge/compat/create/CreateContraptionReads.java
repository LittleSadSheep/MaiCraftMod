// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.compat.create;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import com.simibubi.create.content.contraptions.elevator.ElevatorContraption;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.behavior.navigation.spi.MovingStructure;
import org.maiwithu.maicraft.compat.create.CreateContraptions;

/**
 * 读机械动力移动装置的读写端：区域里每个装置实体（风车与机械轴承、活塞、龙门、绳索滑轮、矿车装置、电梯轿厢、列车车厢）
 * 的逐格碰撞按装置自己的本地→世界换算放到世界坐标；轴承装置这一刻转了就报转动（转轴中心、轴向、最远一格多远），
 * 位置这一刻挪了就报平移（每刻挪多少）；电梯轿厢按最低到最高触点算出整条电梯井；角色坐在上面、或站在上面被它带着走，
 * 标成角色脚下的结构。逐格读到一半读不下去（方块太多、碰撞形状读不出）就标没读全，寻路按整体范围绕。只翻译，不判断。
 */
public final class CreateContraptionReads implements CreateContraptions {

    /** 一个装置逐格最多读这么多格；再多就按整体范围，算路时逐格比也太慢。 */
    private static final int MAX_BLOCKS = 4096;
    /** 每刻挪不到这么远（格）当它停着。 */
    private static final double STILL = 1e-3;

    @Override public List<MovingStructure> near(Level level, Player viewer, AABB region) {
        List<MovingStructure> structures = new ArrayList<>();
        for (AbstractContraptionEntity entity : level.getEntitiesOfClass(AbstractContraptionEntity.class, region,
                Entity::isAlive)) {
            structures.add(read(entity, viewer));
        }
        return structures;
    }

    private static MovingStructure read(AbstractContraptionEntity entity, Player viewer) {
        AABB bounds = entity.getBoundingBox();
        boolean carries = viewer != null && (entity.hasPassenger(viewer) || entity.collidingEntities.containsKey(viewer));
        String id = "create:" + entity.getStringUUID();
        Contraption contraption = entity.getContraption();
        if (contraption == null) {
            return new MovingStructure(id, List.of(), bounds, false, motion(entity, null), null, carries);
        }
        List<AABB> boxes = new ArrayList<>();
        boolean complete = readBoxes(entity, contraption, boxes);
        return new MovingStructure(id, boxes, bounds, complete, motion(entity, contraption), shaft(entity, contraption, bounds),
                carries);
    }

    // 逐格碰撞：装置里每格方块自己的碰撞形状，八个角换到世界坐标取包围盒；藏在传送门里的格不算。
    private static boolean readBoxes(AbstractContraptionEntity entity, Contraption contraption, List<AABB> boxes) {
        Map<BlockPos, StructureBlockInfo> blocks = contraption.getBlocks();
        if (blocks.size() > MAX_BLOCKS) return false;
        var view = contraption.getContraptionWorld();
        for (Map.Entry<BlockPos, StructureBlockInfo> entry : blocks.entrySet()) {
            BlockPos local = entry.getKey();
            if (contraption.isHiddenInPortal(local)) continue;
            for (AABB part : entry.getValue().state().getCollisionShape(view, local).toAabbs()) {
                boxes.add(toWorld(entity, part.move(local)));
            }
        }
        return true;
    }

    // 转着还是平移着：轴承装置这一刻的转角变了就是转着；别的看这一刻的位置比上一刻挪了多少。
    private static MovingStructure.Motion motion(AbstractContraptionEntity entity, Contraption contraption) {
        if (entity instanceof ControlledContraptionEntity controlled && controlled.getRotationAxis() != null
                && contraption != null) {
            if (Math.abs(controlled.getAngle(1) - controlled.getAngle(0)) <= STILL) return new MovingStructure.Motion.Still();
            Direction.Axis axis = controlled.getRotationAxis();
            Vec3 center = entity.toGlobalVector(new Vec3(0.5, 0.5, 0.5), 1);
            double radius = Contraption.getRadius(contraption.getBlocks().keySet(), axis) + 1;
            return new MovingStructure.Motion.Turning(center, axis, radius);
        }
        Vec3 perTick = entity.position().subtract(entity.xo, entity.yo, entity.zo);
        return perTick.length() <= STILL ? new MovingStructure.Motion.Still() : new MovingStructure.Motion.Sliding(perTick);
    }

    // 电梯井：轿厢那么宽，竖直方向从轿厢停在最低触点时的底到停在最高触点时的顶。
    private static AABB shaft(AbstractContraptionEntity entity, Contraption contraption, AABB bounds) {
        if (!(contraption instanceof ElevatorContraption elevator)) return null;
        double anchorY = entity.getAnchorVec().y;
        double lowest = elevator.minContactY - elevator.getContactYOffset();
        double highest = elevator.maxContactY - elevator.getContactYOffset();
        return new AABB(bounds.minX, lowest + (bounds.minY - anchorY), bounds.minZ,
                bounds.maxX, highest + (bounds.maxY - anchorY), bounds.maxZ);
    }

    private static AABB toWorld(AbstractContraptionEntity entity, AABB local) {
        Vec3 first = entity.toGlobalVector(new Vec3(local.minX, local.minY, local.minZ), 1);
        double minX = first.x, minY = first.y, minZ = first.z, maxX = first.x, maxY = first.y, maxZ = first.z;
        for (int bits = 1; bits < 8; bits++) {
            Vec3 corner = entity.toGlobalVector(new Vec3((bits & 1) == 0 ? local.minX : local.maxX,
                    (bits & 2) == 0 ? local.minY : local.maxY, (bits & 4) == 0 ? local.minZ : local.maxZ), 1);
            minX = Math.min(minX, corner.x);
            minY = Math.min(minY, corner.y);
            minZ = Math.min(minZ, corner.z);
            maxX = Math.max(maxX, corner.x);
            maxY = Math.max(maxY, corner.y);
            maxZ = Math.max(maxZ, corner.z);
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
