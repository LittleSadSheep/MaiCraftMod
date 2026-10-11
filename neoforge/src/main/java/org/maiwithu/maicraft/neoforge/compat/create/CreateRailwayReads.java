// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.compat.create;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.simibubi.create.Create;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.sync.ContraptionInteractionPacket;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.station.GlobalStation;
import net.createmod.catnip.platform.CatnipServices;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.compat.create.CreateRailways;

/**
 * 读机械动力铁路的读写端（客户端）：客户端自己那份铁路管理器里的轨道图与车站（站名、车站方块位置与维度）；
 * 近处的车厢装置实体：属于哪趟车、这一刻挪没挪、座位表里哪些座位没人坐、角色是不是坐在上面；
 * 坐座位发 Create 自己的装置交互包（和玩家对着座位右键时发的一样）。只翻译，不判断。
 */
public final class CreateRailwayReads implements CreateRailways {

    /** 每刻挪不到这么远（格）当它停着。 */
    private static final double STILL = 1e-3;

    @Override public List<Station> stations(ClientLevel level) {
        List<Station> found = new ArrayList<>();
        String here = level.dimension().location().toString();
        for (TrackGraph graph : Create.RAILWAYS.sided(level).trackNetworks.values()) {
            for (GlobalStation station : graph.getPoints(EdgePointType.STATION)) {
                if (station.blockEntityPos == null || station.blockEntityDimension == null) continue;
                String dimension = station.blockEntityDimension.location().toString();
                if (dimension.equals(here)) found.add(new Station(station.name, station.blockEntityPos, dimension));
            }
        }
        return found;
    }

    @Override public List<Car> cars(ClientLevel level, Player viewer, AABB region) {
        List<Car> found = new ArrayList<>();
        for (CarriageContraptionEntity car : level.getEntitiesOfClass(CarriageContraptionEntity.class, region,
                Entity::isAlive)) {
            Train train = car.trainId == null ? null : Create.RAILWAYS.sided(level).trains.get(car.trainId);
            Contraption contraption = car.getContraption();
            List<Seat> free = new ArrayList<>();
            if (contraption != null) {
                // 座位表里有人坐的座位按序号记着；没在里面的就是空着。
                Set<Integer> taken = new HashSet<>(contraption.getSeatMapping().values());
                List<BlockPos> seats = contraption.getSeats();
                for (int index = 0; index < seats.size(); index++) {
                    if (taken.contains(index)) continue;
                    BlockPos local = seats.get(index);
                    free.add(new Seat(local, car.toGlobalVector(Vec3.atCenterOf(local), 1)));
                }
            }
            boolean still = car.position().subtract(car.xo, car.yo, car.zo).length() <= STILL;
            found.add(new Car(car.getId(), car.trainId == null ? "" : car.trainId.toString(),
                    train == null ? "" : train.name.getString(), car.getBoundingBox(), still, free,
                    viewer != null && car.hasPassenger(viewer)));
        }
        return found;
    }

    @Override public void sit(int entityId, BlockPos seat) {
        CatnipServices.NETWORK.sendToServer(new ContraptionInteractionPacket(InteractionHand.MAIN_HAND, entityId, seat,
                Direction.UP));
    }
}
