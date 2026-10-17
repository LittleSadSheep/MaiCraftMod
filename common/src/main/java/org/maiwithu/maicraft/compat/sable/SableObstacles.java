// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.sable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.behavior.navigation.spi.MovingStructure;
import org.maiwithu.maicraft.behavior.navigation.spi.ObstacleSink;
import org.maiwithu.maicraft.behavior.navigation.spi.ObstacleSource;

/**
 * 物理结构当寻路障碍：船身按逐格碰撞换到世界（读不全就按整体范围），在挪就是平移、挪多快照报；
 * 装在船上的螺旋桨、风帆这类装置另报一份（装置的范围本来就盖住它转过的整圈），自己站着的那艘船身不算墙，
 * 它上面的桨照样算。
 */
final class SableObstacles implements ObstacleSource {

    private final SableCompat compat;

    SableObstacles(SableCompat compat) {
        this.compat = Objects.requireNonNull(compat, "compat");
    }

    @Override public void obstacles(Level level, Player viewer, AABB region, ObstacleSink sink) {
        if (!(level instanceof ClientLevel client)) return;
        for (SableStructures.Structure structure : compat.structures(client, viewer, region)) {
            StructureGeometry.Scan scan = compat.scan(client, structure);
            List<AABB> boxes = new ArrayList<>(scan.boxes().size());
            for (AABB local : scan.boxes()) boxes.add(StructureGeometry.toWorld(structure.pose(), local));
            Vec3 velocity = StructureGeometry.velocity(structure);
            MovingStructure.Motion motion = StructureGeometry.moving(structure)
                    ? new MovingStructure.Motion.Sliding(velocity) : new MovingStructure.Motion.Still();
            String id = "sable:" + structure.id();
            sink.add(new MovingStructure(id, boxes, structure.worldBounds(), scan.complete(), motion, null,
                    structure.carriesPlayer()));
            int index = 0;
            for (AABB rotor : compat.attachedEntities(client, structure.id())) {
                sink.add(new MovingStructure(id + ":" + index++, List.of(rotor), rotor, true, motion, null, false));
            }
        }
    }
}
