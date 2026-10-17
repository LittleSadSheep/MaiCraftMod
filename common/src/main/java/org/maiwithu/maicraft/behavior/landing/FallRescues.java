// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.landing;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import org.maiwithu.maicraft.behavior.landing.spi.FallBrake;
import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 意外往下掉时用什么接住自己：先问联动登记的刹车（穿着有气的喷气背包就开悬停，不用找水、不留水），
 * 都刹不住再就地找落点放东西接住。
 */
public final class FallRescues {

    private FallRescues() {}

    /** 一次空中救援的做法；救不了为 null。 */
    public static Function<PlayerContext, CatchesFall> withBrakes(Supplier<List<FallBrake>> brakes) {
        return context -> {
            for (FallBrake brake : brakes.get()) {
                Optional<CatchesFall> braking = brake.brake(context);
                if (braking.isPresent()) return braking.get();
            }
            return EmergencyLanding.findToSaveLife(context);
        };
    }
}
