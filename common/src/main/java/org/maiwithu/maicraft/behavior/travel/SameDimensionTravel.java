// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import java.util.Optional;

import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.behavior.travel.spi.TravelVia;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Task;

/**
 * 同一个维度里的出行怎么建：跨维度那一程前后、在一个维度里的那段交给它。
 * 到了目标维度接着去坐标；人在末地外岛要回主世界时，先只用折跃门送一段回主岛。
 *
 * <p>出行能力给的那一份和下达同维度的 travel 用同一个取舍：此刻所在维度有用得上的出行方式（末地的折跃门、
 * 装了的联动方式）就交给行程，近处走路、远处比报价、隔着虚空只能过门；没有就只走路。
 * 跨维度任务自己不认出行方式。
 */
public interface SameDimensionTravel {

    /**
     * 去这个目的地，不指定方式。
     *
     * @param permissions 这次任务的许可，原样交给那一程
     * @param maxSeconds  那一程最多多久（秒）；不超过 0 表示不设时限
     */
    Task toward(TravelDestination destination, Permissions permissions, int maxSeconds);

    /**
     * 只用这一种方式送一段、不往别处去（在外岛过最近的一扇折跃门回主岛）；
     * 这一份里没有这种方式（只走路的那一份）为空。
     */
    Optional<Task> only(TravelVia via, Permissions permissions, int maxSeconds);

    /**
     * 只走路的那一份：跟着别人过门不带坐标、也不从末地出发，用不上别的方式。
     * 走路这一程照出行任务的那一套：挖垫、泅渡、落地救援、脱困。
     */
    static SameDimensionTravel walkingOnly(DimensionTravelServices services) {
        return new SameDimensionTravel() {
            @Override public Task toward(TravelDestination destination, Permissions permissions, int maxSeconds) {
                return new TravelTask(destination, maxSeconds, TerrainPermit.of(permissions), services.walks(),
                        services.placedBlocks(), services.progressListener());
            }

            @Override public Optional<Task> only(TravelVia via, Permissions permissions, int maxSeconds) {
                return Optional.empty();
            }
        };
    }
}
