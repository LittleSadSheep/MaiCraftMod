// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import java.util.List;
import java.util.Optional;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

/**
 * 机械动力电梯的读写接缝：附近的电梯此刻的样子（楼层表、轿厢停在哪层、正要去哪层、停稳没有、轿厢里哪能站、
 * 装置控制器在哪），以及玩家能做的两件原生操作——要一次楼层表、在控制器上选层。只翻译，不判断。
 */
public interface CreateElevators {

    /**
     * 一层。
     *
     * @param contactY    这一层触点的高度（选层用它）
     * @param landingY    轿厢停在这一层时，人站在轿厢地板上脚下那一格的高度
     * @param name        楼层名（触点上写的短名，例如 1F）；没写为空串
     * @param description 楼层说明（触点上写的长名）；没写为空串
     */
    record Floor(int contactY, int landingY, String name, String description) {}

    /**
     * 一台电梯此刻的样子。
     *
     * @param entityId        轿厢实体的编号（要楼层表、选层用）
     * @param id              结构编号，和障碍来源报的同一个（坐着时按它放开轿厢与电梯井）
     * @param floors          楼层表，从低到高；还没同步时为空
     * @param currentContactY 轿厢此刻对着哪一层的触点高度
     * @param targetContactY  轿厢正要去的那一层（客户端看到的目标）
     * @param arrived         停稳了没有
     * @param cabin           轿厢此刻的范围
     * @param standCells      轿厢里此刻能站人的格（脚下是轿厢的地板、上面两格空着）
     * @param controls        轿厢里装置控制器此刻所在的格
     * @param columnX         触点那一列的 x：每层的触点在 (columnX, contactY, columnZ)
     * @param columnZ         触点那一列的 z
     * @param carriesPlayer   角色正站在或坐在轿厢里
     */
    record Elevator(int entityId, String id, List<Floor> floors, int currentContactY, int targetContactY, boolean arrived,
                    AABB cabin, List<BlockPos> standCells, List<BlockPos> controls, int columnX, int columnZ,
                    boolean carriesPlayer) {

        public Elevator {
            floors = List.copyOf(floors);
            standCells = List.copyOf(standCells);
            controls = List.copyOf(controls);
        }

        /** 某一层的触点所在格。 */
        public BlockPos contact(Floor floor) {
            return new BlockPos(columnX, floor.contactY(), columnZ);
        }
    }

    /** 和这个区域沾边的电梯。 */
    List<Elevator> elevators(ClientLevel level, Player viewer, AABB region);

    /** 按轿厢实体编号读一台；不在了（走远、被拆）为空。 */
    Optional<Elevator> elevator(ClientLevel level, Player viewer, int entityId);

    /** 向服务端要一次这台电梯的楼层表（轿厢刚进视野时楼层表是空的，要了才同步过来）。 */
    void requestFloors(int entityId);

    /** 在装置控制器上选层：和玩家在控制器上滚到那一层、右键确认时发的一样。 */
    void selectFloor(int entityId, int contactY);
}
