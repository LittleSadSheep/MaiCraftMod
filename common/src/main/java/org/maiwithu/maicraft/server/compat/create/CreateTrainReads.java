// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.compat.create;

import java.util.List;

import net.minecraft.server.level.ServerLevel;

/**
 * 在服务端读机械动力列车时刻表的读写接缝：每趟车的编号、车名、时刻表循环不循环、走到第几项、每一站的站名过滤。
 * 时刻表只在服务端，客户端读不到（没装铁路导航时靠它查哪趟车去哪）。只读，不改时刻表、不动车。
 * 只用 Minecraft 与 Java 的类型；直接调 Create 铁路管理器的读写端在 NeoForge 模块里，测试里用替身。
 */
public interface CreateTrainReads {

    /** 这个世界里所有有时刻表的列车。 */
    List<TrainSchedule> trains(ServerLevel level);

    /**
     * 一趟车的时刻表。
     *
     * @param id           列车编号
     * @param name         车名
     * @param cyclic       时刻表走完从头再来
     * @param currentEntry 现在走到时刻表第几项（从 0 起）
     * @param stops        时刻表里的停靠站，按顺序；只收"前往车站"那一种项
     */
    record TrainSchedule(String id, String name, boolean cyclic, int currentEntry, List<Stop> stops) {
        public TrainSchedule {
            stops = List.copyOf(stops);
        }
    }

    /**
     * 时刻表里的一站。
     *
     * @param entry  在时刻表里是第几项（从 0 起，含不是停站的项）
     * @param filter 站名过滤，原样（可带 * 通配）
     * @param regex  Create 自己把过滤换成的正则，按它认哪些车站对得上
     */
    record Stop(int entry, String filter, String regex) {}
}
