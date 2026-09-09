// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;

/**
 * 等待玩家实际看向目标，避免只是发出了转头要求，就把点击或抛竿当成已经瞄好。
 * 计时从首次请求开始，reset 后下一次重新等待；它不自己转动相机。
 */
public final class ActualViewConvergenceGate {
    private static final double MINIMUM_DOT = Math.cos(Math.toRadians(1.0D));

    private long firstRequestRevision = Long.MIN_VALUE;

    // 首次要求转头时不允许同刻就出手；以后比较玩家实际视线与当前目标方向，误差在一度内才通过。
    public boolean ready(LocalPlayer player, Vec3 desiredDirection) {
        long revision = ClientRuntime.requireContext(player).tickRevision();
        if (firstRequestRevision == Long.MIN_VALUE) {
            firstRequestRevision = revision;
            return false;
        }
        if (revision <= firstRequestRevision) return false;
        return aligned(player.getViewVector(1.0F), desiredDirection);
    }

    public void reset() {
        firstRequestRevision = Long.MIN_VALUE;
    }

    private static boolean aligned(Vec3 currentLook, Vec3 desiredDirection) {
        if (desiredDirection.lengthSqr() < 1.0e-8D) return true;
        if (currentLook.lengthSqr() < 1.0e-8D) return false;
        return currentLook.normalize().dot(desiredDirection.normalize()) >= MINIMUM_DOT;
    }
}
