// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.construction;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 工地读数的生产实现：全部按这一刻的客户端现场回答，角色不在世界里或那一格没加载时如实给空。
 * 临时方块的材料从身上挑：整块、易挖、没有方块实体、不会掉落的建材，越多的越先用。
 */
public final class LiveSite implements ConstructionSeams.ReadsSite {

    private final Supplier<PlayerContext> context;

    public LiveSite(Supplier<PlayerContext> context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override public boolean loaded(BlockPos pos) {
        ClientLevel level = level();
        return level != null && level.hasChunkAt(pos) && level.isInWorldBounds(pos);
    }

    @Override public BlockState state(BlockPos pos) {
        ClientLevel level = level();
        return level == null ? null : level.getBlockState(pos);
    }

    @Override public String dimension() {
        ClientLevel level = level();
        return level == null ? null : level.dimension().location().toString();
    }

    @Override public BlockPos feet() {
        LocalPlayer player = player();
        return player == null ? null : player.blockPosition();
    }

    @Override public int carried(String itemId) {
        LocalPlayer player = player();
        Item item = BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(itemId)).orElse(null);
        return player == null || item == null ? 0 : player.getInventory().countItem(item);
    }

    @Override public boolean creative() {
        LocalPlayer player = player();
        return player != null && player.getAbilities().instabuild;
    }

    // 基岩这类挖掘速度为负的挖不动；世界边界外、建筑高度外也算做不了。
    @Override public boolean unbreakable(BlockPos pos) {
        ClientLevel level = level();
        if (level == null || !level.isInWorldBounds(pos) || !level.getWorldBorder().isWithinBounds(pos)) return true;
        BlockState state = level.getBlockState(pos);
        return !state.isAir() && state.getDestroySpeed(level, pos) < 0;
    }

    @Override public Optional<String> temporaryMaterial() {
        PlayerContext current = context.get();
        if (current == null || current.backpack() == null) return Optional.empty();
        BackpackStack best = null;
        for (BackpackStack stack : current.backpack().stacks()) {
            if (!stack.buildingMaterial() || stack.precious() || stack.gear() || stack.food() || !plainFullBlock(stack.itemId())) continue;
            if (best == null || stack.count() > best.count()) best = stack;
        }
        return Optional.ofNullable(best).map(BackpackStack::itemId);
    }

    // 能当垫脚的：整块碰撞、没有方块实体、不会掉落（沙子、沙砾垫上去会塌）。
    private static boolean plainFullBlock(String itemId) {
        Item item = BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(itemId)).orElse(null);
        if (!(item instanceof BlockItem blockItem)) return false;
        BlockState state = blockItem.getBlock().defaultBlockState();
        return state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) && !state.hasBlockEntity()
                && !(blockItem.getBlock() instanceof FallingBlock);
    }

    private ClientLevel level() {
        PlayerContext current = context.get();
        return current == null ? null : current.level();
    }

    private LocalPlayer player() {
        PlayerContext current = context.get();
        return current == null ? null : current.localPlayer();
    }
}
