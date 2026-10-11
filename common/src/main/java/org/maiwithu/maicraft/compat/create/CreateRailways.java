// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import java.util.List;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 机械动力铁路的读写接缝（客户端）：轨道图上的车站（站名、车站方块在哪），近处的列车车厢（哪趟车、停没停、哪些座位空着、
 * 角色是不是坐在上面），以及玩家能做的一件原生操作——右键车厢上的座位坐上去。只翻译，不判断。
 */
public interface CreateRailways {

    /**
     * 一个车站。
     *
     * @param name      站名
     * @param pos       车站方块所在格
     * @param dimension 车站方块所在维度
     */
    record Station(String name, BlockPos pos, String dimension) {}

    /**
     * 一节车厢此刻的样子。
     *
     * @param entityId      车厢实体编号（坐座位用）
     * @param trainId       属于哪趟车
     * @param trainName     车名
     * @param box           车厢此刻的范围
     * @param still         这一刻没在动
     * @param freeSeats     空着的座位
     * @param carriesPlayer 角色正坐在这节车厢上
     */
    record Car(int entityId, String trainId, String trainName, AABB box, boolean still, List<Seat> freeSeats,
               boolean carriesPlayer) {
        public Car {
            freeSeats = List.copyOf(freeSeats);
        }
    }

    /**
     * 一个座位。
     *
     * @param local 在车厢装置里的格（坐座位时交给 Create）
     * @param world 此刻在世界里的位置
     */
    record Seat(BlockPos local, Vec3 world) {}

    /** 这个维度轨道图上的全部车站。 */
    List<Station> stations(ClientLevel level);

    /** 和这个区域沾边的列车车厢。 */
    List<Car> cars(ClientLevel level, Player viewer, AABB region);

    /** 右键车厢上的这个座位坐上去：和玩家对着座位右键时发的一样。 */
    void sit(int entityId, BlockPos seat);
}
