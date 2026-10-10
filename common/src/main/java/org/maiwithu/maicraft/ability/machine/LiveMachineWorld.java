// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 机器现场视图的生产读端：角色在哪、一格是什么，都从当刻的角色上下文读。
 * 不在世界里、上下文过期、格子没加载都如实给空，不猜。
 */
final class LiveMachineWorld implements MachineWorldView {

    private final Supplier<PlayerContext> context;

    LiveMachineWorld(Supplier<PlayerContext> context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override public Optional<Spot> playerSpot() {
        PlayerContext current = context.get();
        if (current == null || current.localPlayer() == null || current.level() == null) {
            return Optional.empty();
        }
        return Optional.of(new Spot(current.localPlayer().blockPosition(),
                current.level().dimension().location().toString()));
    }

    @Override public Optional<BlockState> stateAt(BlockPos at) {
        PlayerContext current = context.get();
        if (current == null || current.level() == null || !current.level().hasChunkAt(at)) {
            return Optional.empty();
        }
        return Optional.of(current.level().getBlockState(at));
    }
}
