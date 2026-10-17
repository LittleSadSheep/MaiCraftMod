// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat;

import java.util.Objects;
import java.util.Optional;

import org.maiwithu.maicraft.behavior.navigation.spi.FlyUpGear;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 联动模组交来的"飞上去"的装备，登记表给它包的一层：模组停用后当没有；读的时候碰到模组接口对不上也当没有，
 * 开的动作包一层，出错如实交回，不让走路变成内部错误。
 */
final class CompatFlyUpGear implements FlyUpGear {

    private final CompatModule module;
    private final FlyUpGear gear;

    CompatFlyUpGear(CompatModule module, FlyUpGear gear) {
        this.module = Objects.requireNonNull(module, "module");
        this.gear = Objects.requireNonNull(gear, "gear");
    }

    @Override public Optional<Reach> reach(PlayerContext context) {
        if (!module.active()) return Optional.empty();
        try {
            return gear.reach(context);
        } catch (ModApiMismatch broken) {
            return Optional.empty();
        }
    }

    @Override public boolean ready(PlayerContext context) {
        if (!module.active()) return false;
        try {
            return gear.ready(context);
        } catch (ModApiMismatch broken) {
            return false;
        }
    }

    @Override public Action prepare(PlayerContext context) {
        return CompatAction.of(module, gear.prepare(context));
    }
}
