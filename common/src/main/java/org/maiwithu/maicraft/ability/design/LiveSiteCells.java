// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.design;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.behavior.acquire.NaturalBlocks;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.world.ObservationVisibility;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 读现场的生产实现：按这一刻的客户端世界回答。看不看得见与观察用同一套遮挡判断；
 * 是不是自己放的问保护判断（它读服务端的方块归属记录）；天然地形照拿东西时"地图上自然长的"那一份认。
 * 角色不在世界里时一格都不算加载。
 */
public final class LiveSiteCells implements ReadsSiteCells {

    private final Supplier<PlayerContext> context;
    private final Protection protection;
    /** 一次读现场里查过的树干：同一根树干不重复数树冠。 */
    private final Map<BlockPos, Boolean> trunks = new HashMap<>();

    public LiveSiteCells(Supplier<PlayerContext> context, Protection protection) {
        this.context = Objects.requireNonNull(context, "context");
        this.protection = Objects.requireNonNull(protection, "protection");
    }

    @Override public String dimension() {
        ClientLevel level = level();
        return level == null ? null : level.dimension().location().toString();
    }

    @Override public boolean loaded(BlockPos pos) {
        ClientLevel level = level();
        // 客户端世界的 hasChunkAt 永远答"加载着"：按区块真加载没加载、在不在世界高度里判。
        return level != null && level.isLoaded(pos) && level.isInWorldBounds(pos);
    }

    @Override public BlockState state(BlockPos pos) {
        return level().getBlockState(pos);
    }

    @Override public boolean visible(BlockPos pos) {
        PlayerContext current = context.get();
        return current != null && ObservationVisibility.block(current.localPlayer(), pos);
    }

    @Override public boolean selfPlaced(BlockPos pos) {
        return protection.selfPlaced(new WorldPosition(pos.getX(), pos.getY(), pos.getZ(), dimension()));
    }

    @Override public boolean natural(BlockPos pos) {
        ClientLevel level = level();
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || !state.getFluidState().isEmpty() || state.canBeReplaced()) return true;
        return NaturalBlocks.grown(state, pos, level, this::loaded, trunks);
    }

    private ClientLevel level() {
        PlayerContext current = context.get();
        return current == null ? null : current.level();
    }
}
