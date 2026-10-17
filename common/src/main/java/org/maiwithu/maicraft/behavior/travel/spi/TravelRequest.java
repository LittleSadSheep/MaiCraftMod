// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel.spi;

import java.util.Map;
import java.util.Objects;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.kernel.goal.Permissions;

/**
 * 一次报价请求：从哪里往哪里、到达容差、这次的许可、参数里给这种方式的话（电梯的 floor、载具的 vehicle）。
 *
 * @param from        角色脚下这一格
 * @param to          目的地中心那一格；只坐电梯换楼层时为 null
 * @param dimension   所在维度
 * @param radius      到达容差（格）
 * @param permissions 这次任务的许可：指定方式不放宽它
 * @param options     travel 参数里给方式的话：floor、vehicle……没给为空表
 * @param via         这一轮还指定要用的方式（指定的那种已经坐过一段、或没指定为 null）：
 *                    指定开自己的载具时，人在船上不先下船
 */
public record TravelRequest(BlockPos from, BlockPos to, String dimension, double radius, Permissions permissions,
                            Map<String, String> options, String via) {

    public TravelRequest {
        from = Objects.requireNonNull(from, "from").immutable();
        to = to == null ? null : to.immutable();
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(permissions, "permissions");
        options = options == null ? Map.of() : Map.copyOf(options);
    }
}
