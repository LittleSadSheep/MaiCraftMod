// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;

/** 登阶途中仍由导航掌舵；连续三刻落地且脚高稳定后，施工才可接手，平地顺路放置仍由原通道证明约束。 */
final class BuildPlacementFooting {
    private long lastTick = Long.MIN_VALUE;
    private Vec3 previous;
    private int groundedTicks;

    boolean ready(LocalPlayer player) {
        return observe(player.position(), player.onGround() && !player.isInWater() && !player.isPassenger(),
                ClientRuntime.requireContext(player).tickRevision());
    }

    // 同刻反复询问不增加稳定次数；重新起跳、下落或暂停缺刻会使旧站位失效，不把安全平地步行当作腾空。
    boolean observe(Vec3 feet, boolean grounded, long tick) {
        if (!grounded) groundedTicks = 0;
        if (tick == lastTick) return groundedTicks >= 3 && Math.abs(previous.y - feet.y) <= .01;
        boolean consecutive = lastTick != Long.MIN_VALUE && tick == lastTick + 1;
        groundedTicks = grounded ? consecutive && Math.abs(previous.y - feet.y) <= .01 ? groundedTicks + 1 : 1 : 0;
        previous = feet; lastTick = tick;
        return groundedTicks >= 3;
    }
}
