// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.use;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;

import org.maiwithu.maicraft.behavior.approach.InteractionTarget;
import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 用东西的世界读数：每次都按这一刻的客户端现场回答，角色不在世界里或那一格没加载时如实给空。
 */
final class LiveUseWorld implements UseSeams.ReadsWorld {

    private final Supplier<PlayerContext> context;

    LiveUseWorld(Supplier<PlayerContext> context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override public String dimension() {
        ClientLevel level = level();
        return level == null ? null : level.dimension().location().toString();
    }

    @Override public BlockPos feet() {
        LocalPlayer player = player();
        return player == null ? null : player.blockPosition();
    }

    // 只看那一格所在的区块在不在客户端：高度不影响"那一片加载了没有"。
    @Override public boolean loaded(BlockPos cell) {
        ClientLevel level = level();
        return level != null && level.hasChunkAt(cell);
    }

    @Override public Optional<String> blockId(BlockPos cell) {
        return state(cell).map(state -> BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
    }

    // 方块 ID 按注册名比；标签按方块自己挂没挂这个标签比，模组加进标签的方块也认。
    @Override public boolean blockIs(BlockPos cell, String blockOrTag) {
        Optional<BlockState> state = state(cell);
        if (state.isEmpty()) return false;
        String spec = blockOrTag.toLowerCase(Locale.ROOT);
        if (spec.startsWith("#")) {
            ResourceLocation tagId = ResourceLocation.tryParse(spec.substring(1));
            return tagId != null && state.get().is(TagKey.create(Registries.BLOCK, tagId));
        }
        return BuiltInRegistries.BLOCK.getKey(state.get().getBlock()).toString().equals(spec);
    }

    // 那一列最上面一块挡得住身体或有流体的方块：按客户端同步下来的高度图读，不逐格往下扫。
    @Override public Optional<BlockPos> ground(int x, int z) {
        ClientLevel level = level();
        BlockPos column = new BlockPos(x, 0, z);
        if (level == null || !level.hasChunkAt(column)) return Optional.empty();
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
        return top < level.getMinBuildHeight() ? Optional.empty() : Optional.of(new BlockPos(x, top, z));
    }

    @Override public Fluid fluid(BlockPos cell) {
        ClientLevel level = level();
        if (level == null || !level.hasChunkAt(cell)) return Fluid.NONE;
        FluidState fluid = level.getFluidState(cell);
        if (fluid.isEmpty()) return Fluid.NONE;
        return fluid.isSource() ? Fluid.SOURCE : Fluid.FLOWING;
    }

    // 同种流体按流体类型自己的"是不是同一种"判断（流动的水与水源是同一种水）；只看已加载的格子。
    @Override public Optional<BlockPos> nearestSource(BlockPos near, int radius) {
        ClientLevel level = level();
        if (level == null || !level.hasChunkAt(near)) return Optional.empty();
        FluidState kind = level.getFluidState(near);
        if (kind.isEmpty()) return Optional.empty();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos cell : BlockPos.betweenClosed(near.offset(-radius, -radius, -radius),
                near.offset(radius, radius, radius))) {
            if (!level.hasChunkAt(cell)) continue;
            FluidState fluid = level.getFluidState(cell);
            if (!fluid.isSource() || !fluid.getType().isSame(kind.getType())) continue;
            double distance = cell.distSqr(near);
            if (distance <= (double) radius * radius && distance < bestDistance) {
                best = cell.immutable();
                bestDistance = distance;
            }
        }
        return Optional.ofNullable(best);
    }

    // 告示牌的上蜡状态随方块实体同步到客户端：上过蜡的游戏不让改字。
    @Override public Sign sign(BlockPos cell) {
        ClientLevel level = level();
        if (level == null || !level.hasChunkAt(cell)) return Sign.NOT_A_SIGN;
        if (!(level.getBlockEntity(cell) instanceof SignBlockEntity sign)) return Sign.NOT_A_SIGN;
        return sign.isWaxed() ? Sign.WAXED : Sign.WRITABLE;
    }

    // 准星选不选得中按实体自己的说法：掉落物、经验球、射出去的箭都选不中。
    @Override public Optional<SeenEntity> entity(int entityId) {
        return entityById(entityId).map(entity -> new SeenEntity(
                EntityType.getKey(entity.getType()).toString(), entity.blockPosition(), entity.isPickable()));
    }

    @Override public boolean riding(int entityId) {
        LocalPlayer player = player();
        Entity vehicle = player == null ? null : player.getVehicle();
        return vehicle != null && vehicle.getId() == entityId;
    }

    @Override public Optional<Held> heldItem() {
        LocalPlayer player = player();
        if (player == null) return Optional.empty();
        ItemStack held = player.getMainHandItem();
        return held.isEmpty() ? Optional.empty()
                : Optional.of(new Held(BuiltInRegistries.ITEM.getKey(held.getItem()).toString(), held.getCount()));
    }

    // 实体会走动：靠近时取它这一刻的包围盒，不用落实目标时记下的位置。
    @Override public Optional<InteractionTarget> approachTarget(ResolvedTarget target) {
        if (!target.isEntity()) {
            return Optional.of(InteractionTarget.ofBlock(target.cell()));
        }
        return entityById(target.entityId()).map(entity -> InteractionTarget.ofEntity(entity.getBoundingBox()));
    }

    private Optional<BlockState> state(BlockPos cell) {
        ClientLevel level = level();
        if (level == null || !level.hasChunkAt(cell)) return Optional.empty();
        return Optional.of(level.getBlockState(cell));
    }

    private Optional<Entity> entityById(int entityId) {
        ClientLevel level = level();
        Entity entity = level == null ? null : level.getEntity(entityId);
        return entity == null || entity.isRemoved() ? Optional.empty() : Optional.of(entity);
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
