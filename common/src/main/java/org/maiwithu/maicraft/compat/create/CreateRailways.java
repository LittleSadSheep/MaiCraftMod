// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import java.util.List;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 机械动力铁路的读写接缝（客户端）：轨道图上的车站（站名、车站方块在哪、组车状态），近处的列车车厢（哪趟车、停没停、
 * 哪些座位空着、角色是不是坐在上面、操纵台座位上坐着谁），主手那张时刻表写着什么；
 * 以及玩家能做的原生操作——右键车厢上的座位坐上去、车站界面上切组车模式与按组车拆车、在时刻表界面里写好关上。
 * 只翻译，不判断。
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

    /**
     * 一座车站此刻的组车状态。
     *
     * @param assembling 在组车模式
     * @param failure    上次组车没成的原因（组车界面上显示的那句）；没有为空串
     */
    record StationState(boolean assembling, String failure) {}

    /**
     * 一张时刻表：每一站去哪、到了等什么，循环不循环。
     *
     * @param stops  按顺序的每一站
     * @param cyclic 跑完最后一站回到第一站
     */
    record Timetable(List<Stop> stops, boolean cyclic) {
        public Timetable {
            stops = List.copyOf(stops);
        }
    }

    /**
     * 时刻表的一站。
     *
     * @param station 站名（可带 * 通配）
     * @param conditions 到了等什么，都满足才走，每条一句：{@code delay 秒数}、{@code idle 秒数}（货物这么久没动）、
     *                {@code powered}（车站通着红石）、{@code unloaded}（车站那块没加载）；别的条件原样写它的 ID
     */
    record Stop(String station, List<String> conditions) {
        public Stop {
            conditions = List.copyOf(conditions);
        }
    }

    /** 这个维度轨道图上的全部车站。 */
    List<Station> stations(ClientLevel level);

    /** 和这个区域沾边的列车车厢。 */
    List<Car> cars(ClientLevel level, Player viewer, AABB region);

    /** 右键车厢上的这个座位坐上去：和玩家对着座位右键时发的一样。 */
    void sit(int entityId, BlockPos seat);

    /** 一座车站的组车状态；那一格不是车站、没加载为 null。 */
    StationState station(ClientLevel level, BlockPos pos);

    /** 切车站的组车模式：和车站界面上点组车模式那个按钮一样。 */
    void assemblyMode(BlockPos pos, boolean on);

    /** 组车模式下按"组装列车"：和组车界面上点那个按钮一样。 */
    void assembleTrain(BlockPos pos);

    /** 按"拆解列车"：停在站里的车拆回方块，车站回到组车模式。 */
    void disassembleTrain(BlockPos pos);

    /** 坐在这节车厢操纵台座位上的生物（列车长，玩家不算）的实体编号。 */
    List<Integer> conductors(ClientLevel level, int carEntityId);

    /** 主手那张时刻表写着什么；主手拿的不是时刻表为 null，是空白时刻表为没有站的表。 */
    Timetable heldTimetable(LocalPlayer player);

    /** 把时刻表写进主手那张：和在时刻表界面里编好关上时发的一样。 */
    void writeTimetable(Timetable timetable);
}
