// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat;

import java.util.Objects;
import java.util.Optional;

import net.minecraft.world.entity.Entity;

import org.maiwithu.maicraft.ability.follow.spi.RideAlong;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 联动模组交来的同乘办法，登记表给它包的一层：模组停用后答不上（跟随就走路跟）；
 * 上车上到一半停用或碰到模组接口对不上，按不支持收场，跟随记下没坐上、接着走路跟。
 */
final class CompatRideAlong implements RideAlong {

    private final CompatModule module;
    private final RideAlong ride;

    CompatRideAlong(CompatModule module, RideAlong ride) {
        this.module = Objects.requireNonNull(module, "module");
        this.ride = Objects.requireNonNull(ride, "ride");
    }

    @Override
    public Optional<Action> board(PlayerContext context, Entity vehicle, Permissions permissions) {
        if (!module.active()) return Optional.empty();
        try {
            return ride.board(context, vehicle, permissions).map(action -> CompatAction.of(module, action));
        } catch (ModApiMismatch broken) {
            return Optional.empty();
        }
    }
}
