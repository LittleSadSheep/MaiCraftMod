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
        // 客户端世界的 hasChunkAt 永远答“加载着”，没加载的格读出来是空气、地表落在世界最底层；按区块真加载没加载判。
        if (current == null || current.level() == null || !current.level().isLoaded(at)) {
            return Optional.empty();
        }
        return Optional.of(current.level().getBlockState(at));
    }
}
