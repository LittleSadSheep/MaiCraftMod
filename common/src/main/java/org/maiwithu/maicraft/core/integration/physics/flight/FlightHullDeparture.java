package org.maiwithu.maicraft.core.integration.physics.flight;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import org.maiwithu.maicraft.core.integration.physics.PhysicalObstacleSnapshot;
import org.maiwithu.maicraft.core.integration.physics.SableStructureBridge;

/** 飞艇垂直起飞按真实吊舱、蒙皮与转子扫掠；机身包围盒内的空隙不能凭空变成实体。 */
final class FlightHullDeparture {
    private FlightHullDeparture() {}
    static FlightPathProbe.Space inspect(ClientLevel level, SableStructureBridge.Structure ship,
                                         FlightWorldProbe probe, boolean grounded) {
        var coarse = FlightPathProbe.verticalDeparture(probe, ship.worldBounds(), grounded);
        // 独立转子可能伸出主船体范围，主包围盒畅通也仍要检查这些本机活动部件。
        if (coarse == FlightPathProbe.Space.CLEAR)
            return inspectParts(probe,probe.ownMovingParts(),grounded,ship.worldBounds().minY);
        if (coarse == FlightPathProbe.Space.UNKNOWN) return coarse;
        var bounds = ship.storageBounds();
        if (bounds == null || bounds.getXsize()*bounds.getYsize()*bounds.getZsize()>8192)
            return probe.unknownObservation("departure_hull_geometry_unavailable_or_over_budget");
        var parts = new ArrayList<AABB>();
        var min = BlockPos.containing(bounds.minX,bounds.minY,bounds.minZ);
        var max = BlockPos.containing(Math.nextDown(bounds.maxX),Math.nextDown(bounds.maxY),Math.nextDown(bounds.maxZ));
        try {
            for (BlockPos pos : BlockPos.betweenClosed(min,max)) {
                var read = ship.readBlock(pos);
                if (!"known".equals(read.state())) return probe.unknownObservation("departure_hull_block_unloaded");
                if (read.blockState().isAir()) continue;
                for (AABB box : read.blockState().getCollisionShape(level,pos).toAabbs()) {
                    if (parts.size()>=8192) return probe.unknownObservation("departure_hull_shapes_over_budget");
                    parts.add(PhysicalObstacleSnapshot.transformBox(ship.pose(),box.move(pos),true));
                }
            }
            // 已成型桨叶属于原生运动装置；虽不作为外部障碍，也仍占据本机的起飞扫掠空间。
            parts.addAll(probe.ownMovingParts());
            if (parts.isEmpty()) return probe.unknownObservation("departure_hull_has_no_observed_shapes");
            return inspectParts(probe,parts,grounded,ship.worldBounds().minY);
        } catch (RuntimeException | LinkageError unavailable) {
            return probe.unknownObservation("departure_hull_shape_unavailable");
        }
    }
    static FlightPathProbe.Space inspectParts(FlightPathProbe.World probe,List<AABB> parts,boolean grounded,double bottom) {
        // 只有最低支撑面采用接地容差，上方桨叶与蒙皮仍从自己的真实高度向上检查。
        for (AABB part : parts) {
            var state = FlightPathProbe.verticalDeparture(probe,part,grounded&&part.minY<=bottom+.06);
            if (state != FlightPathProbe.Space.CLEAR) return state;
        }
        return FlightPathProbe.Space.CLEAR;
    }
}
