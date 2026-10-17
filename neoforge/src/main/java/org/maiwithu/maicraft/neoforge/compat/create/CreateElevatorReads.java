// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.compat.create;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.ContraptionWorld;
import com.simibubi.create.content.contraptions.actors.contraptionControls.ContraptionControlsBlock;
import com.simibubi.create.content.contraptions.elevator.ElevatorColumn.ColumnCoords;
import com.simibubi.create.content.contraptions.elevator.ElevatorContraption;
import com.simibubi.create.content.contraptions.elevator.ElevatorFloorListPacket;
import com.simibubi.create.content.contraptions.elevator.ElevatorTargetFloorPacket;
import net.createmod.catnip.data.Couple;
import net.createmod.catnip.data.IntAttached;
import net.createmod.catnip.platform.CatnipServices;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import org.maiwithu.maicraft.compat.create.CreateElevators;

/**
 * 读机械动力电梯的读写端：轿厢实体上的电梯装置（楼层名表、到站、客户端看到的目标层、触点那一列、触点偏移），
 * 轿厢里哪几格有地板能站、装置控制器在哪（按装置的本地→世界换算），角色是不是在轿厢里；
 * 要楼层表、选层发 Create 自己的网络包（和玩家在控制器上操作时发的一样）。只翻译，不判断。
 */
public final class CreateElevatorReads implements CreateElevators {

    @Override public List<Elevator> elevators(ClientLevel level, Player viewer, AABB region) {
        List<Elevator> found = new ArrayList<>();
        for (AbstractContraptionEntity entity : level.getEntitiesOfClass(AbstractContraptionEntity.class, region,
                Entity::isAlive)) {
            if (entity.getContraption() instanceof ElevatorContraption elevator) found.add(read(entity, elevator, viewer));
        }
        return found;
    }

    @Override public Optional<Elevator> elevator(ClientLevel level, Player viewer, int entityId) {
        if (level.getEntity(entityId) instanceof AbstractContraptionEntity entity && entity.isAlive()
                && entity.getContraption() instanceof ElevatorContraption elevator) {
            return Optional.of(read(entity, elevator, viewer));
        }
        return Optional.empty();
    }

    @Override public void requestFloors(int entityId) {
        CatnipServices.NETWORK.sendToServer(new ElevatorFloorListPacket.RequestFloorList(entityId));
    }

    @Override public void selectFloor(int entityId, int contactY) {
        CatnipServices.NETWORK.sendToServer(new ElevatorTargetFloorPacket(entityId, contactY));
    }

    private static Elevator read(AbstractContraptionEntity entity, ElevatorContraption elevator, Player viewer) {
        // 轿厢地板：上表面是整格高的方块，上面两格没有碰撞；人站在它上面一格。
        List<BlockPos> localFeet = new ArrayList<>();
        List<BlockPos> controls = new ArrayList<>();
        Map<BlockPos, StructureBlockInfo> blocks = elevator.getBlocks();
        var view = elevator.getContraptionWorld();
        for (Map.Entry<BlockPos, StructureBlockInfo> entry : blocks.entrySet()) {
            BlockPos local = entry.getKey();
            if (entry.getValue().state().getBlock() instanceof ContraptionControlsBlock) controls.add(world(entity, local));
            VoxelShape shape = entry.getValue().state().getCollisionShape(view, local);
            if (shape.isEmpty() || shape.max(Direction.Axis.Y) < 1.0) continue;
            if (blocks(blocks, view, local.above()) || blocks(blocks, view, local.above(2))) continue;
            localFeet.add(local.above());
        }
        int feetY = localFeet.stream().mapToInt(BlockPos::getY).min().orElse(1);
        List<BlockPos> standCells = localFeet.stream().filter(cell -> cell.getY() == feetY)
                .map(cell -> BlockPos.containing(entity.toGlobalVector(Vec3.atBottomCenterOf(cell).add(0, 0.01, 0), 1)))
                .toList();
        int offset = elevator.getContactYOffset();
        List<Floor> floors = new ArrayList<>();
        for (IntAttached<Couple<String>> named : elevator.namesList) {
            int contactY = named.getFirst();
            Couple<String> names = named.getSecond();
            floors.add(new Floor(contactY, contactY - offset + feetY, text(names.getFirst()), text(names.getSecond())));
        }
        floors.sort(Comparator.comparingInt(Floor::contactY));
        ColumnCoords column = elevator.getGlobalColumn();
        boolean carries = viewer != null && (entity.hasPassenger(viewer) || entity.collidingEntities.containsKey(viewer));
        return new Elevator(entity.getId(), "create:" + entity.getStringUUID(), floors,
                Mth.floor(0.5 + entity.getY()) + offset, elevator.clientYTarget, elevator.arrived,
                entity.getBoundingBox(), standCells, controls, column.x(), column.z(), carries);
    }

    // 这一格（装置本地坐标）有没有挡人的碰撞。
    private static boolean blocks(Map<BlockPos, StructureBlockInfo> blocks, ContraptionWorld view, BlockPos local) {
        StructureBlockInfo info = blocks.get(local);
        return info != null && !info.state().getCollisionShape(view, local).isEmpty();
    }

    private static BlockPos world(AbstractContraptionEntity entity, BlockPos local) {
        return BlockPos.containing(entity.toGlobalVector(Vec3.atCenterOf(local), 1));
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
