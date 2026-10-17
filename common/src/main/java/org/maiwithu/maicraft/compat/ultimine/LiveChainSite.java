// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ultimine;

import java.util.Optional;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.game.player.PlayerContext;

/** 连锁挖读的现场：读当刻的客户端世界与角色主手。 */
final class LiveChainSite implements ChainSite {

    @Override public Optional<Cell> cell(PlayerContext context, BlockPos pos) {
        ClientLevel level = context == null ? null : context.level();
        LocalPlayer player = player(context);
        // 客户端世界的 hasChunkAt 永远答“加载着”，没加载的格读出来是空气；按区块真加载没加载判，没加载不当成空的。
        if (level == null || player == null || !level.isLoaded(pos)) return Optional.empty();
        BlockState state = level.getBlockState(pos);
        return Optional.of(new Cell(state.isAir(), !state.getFluidState().isEmpty(),
                state.hasBlockEntity(), state.getDestroySpeed(level, pos) < 0,
                state.isAir() || player.hasCorrectToolForDrops(state)));
    }

    @Override public Tool tool(PlayerContext context) {
        LocalPlayer player = player(context);
        if (player == null) return new Tool("", Integer.MAX_VALUE);
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) return new Tool("", Integer.MAX_VALUE);
        // 挖的时候耐久一直在掉：认是不是同一件工具时按耐久满的样子比，物品和附魔这些不同才算换了。
        ItemStack fresh = held.copyWithCount(1);
        if (fresh.isDamageableItem()) fresh.setDamageValue(0);
        String identity = BuiltInRegistries.ITEM.getKey(held.getItem()) + "#" + ItemStack.hashItemAndComponents(fresh);
        int left = held.isDamageableItem() ? held.getMaxDamage() - held.getDamageValue() : Integer.MAX_VALUE;
        return new Tool(identity, left);
    }

    @Override public long tick(PlayerContext context) {
        return context == null ? 0 : context.clientTick();
    }

    @Override public LocalPlayer player(PlayerContext context) {
        return context == null ? null : context.localPlayer();
    }
}
