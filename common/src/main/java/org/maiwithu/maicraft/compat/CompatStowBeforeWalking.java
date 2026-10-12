// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat;

import java.util.Objects;
import java.util.Optional;

import org.maiwithu.maicraft.behavior.navigation.spi.StowBeforeWalking;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 联动模组交来的"走路前收起来"，登记表给它包的一层：模组停用后不再收；问的时候碰到模组接口对不上当作不用收，
 * 收的动作也包一层，出错如实交回，不让走路变成内部错误。
 */
final class CompatStowBeforeWalking implements StowBeforeWalking {

    private final CompatModule module;
    private final StowBeforeWalking stow;

    CompatStowBeforeWalking(CompatModule module, StowBeforeWalking stow) {
        this.module = Objects.requireNonNull(module, "module");
        this.stow = Objects.requireNonNull(stow, "stow");
    }

    @Override public Optional<Action> stow(PlayerContext context) {
        if (!module.active()) return Optional.empty();
        try {
            return stow.stow(context).map(action -> CompatAction.of(module, action));
        } catch (ModApiMismatch broken) {
            return Optional.empty();
        }
    }
}
