// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.use;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.world.BlockScanService;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 附近搜索的生产实现：方块走扫描服务的索引，实体直接扫一遍渲染范围内的实体。
 *
 * <p>方块索引首次用到时登记一次，之后每刻只花小预算；没扫完如实标出，
 * "没扫完"不冒充"没有"。实体按距离取最近的一只，找到的连实体对象一起给。
 */
public final class LiveNearbySearcher implements SearchesNearby {

    // 与感知读端同一套预算：一刻扫不完就分刻扫，不冒充扫完。
    private static final int MAX_CHUNK_RADIUS = 4;
    private static final int BUILD_BUDGET = 4096;

    private final BlockScanService scans;
    private final Supplier<PlayerContext> context;
    private boolean registered;

    public LiveNearbySearcher(BlockScanService scans, Supplier<PlayerContext> context) {
        this.scans = Objects.requireNonNull(scans, "scans");
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    public BlockResult nearestBlock(String blockTypeId, BlockPos center, int radius) {
        PlayerContext current = context.get();
        ClientLevel level = current == null ? null : current.level();
        if (level == null) {
            return new BlockResult(Optional.empty(), false);
        }
        Optional<Block> block = BuiltInRegistries.BLOCK.getOptional(parse(blockTypeId));
        if (block.isEmpty()) {
            // 写岔的方块 ID 不是"附近没有"，让调用方按找不到目标交代。
            return new BlockResult(Optional.empty(), true);
        }
        ensureRegistered(level, block.get());
        var result = scans.query(level, center, Set.of(block.get()), 1,
                Math.min(MAX_CHUNK_RADIUS, Math.max(1, radius / 16 + 1)), BUILD_BUDGET);
        if (result.hits().isEmpty()) {
            return new BlockResult(Optional.empty(), result.complete());
        }
        BlockPos nearest = nearestTo(result.hits(), center);
        return new BlockResult(Optional.of(WorldPosition.here(nearest.getX(), nearest.getY(), nearest.getZ())),
                result.complete());
    }

    @Override
    public EntityResult nearestEntity(String entityTypeId, BlockPos center, int radius) {
        PlayerContext current = context.get();
        ClientLevel level = current == null ? null : current.level();
        if (level == null) {
            return new EntityResult(Optional.empty(), false);
        }
        Entity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity entity : level.entitiesForRendering()) {
            if (!BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString().equals(lower(entityTypeId))) {
                continue;
            }
            double distance = entity.blockPosition().distSqr(center);
            if (distance <= (double) radius * radius && distance < bestDistance) {
                best = entity;
                bestDistance = distance;
            }
        }
        return new EntityResult(Optional.ofNullable(best), true);
    }

    @Override
    public List<WorldPosition> allOf(String blockTypeId, BlockPos center, int radius) {
        PlayerContext current = context.get();
        ClientLevel level = current == null ? null : current.level();
        if (level == null) {
            return List.of();
        }
        Optional<Block> block = BuiltInRegistries.BLOCK.getOptional(parse(blockTypeId));
        if (block.isEmpty()) {
            return List.of();
        }
        ensureRegistered(level, block.get());
        var result = scans.query(level, center, Set.of(block.get()), Integer.MAX_VALUE,
                Math.min(MAX_CHUNK_RADIUS, Math.max(1, radius / 16 + 1)), BUILD_BUDGET);
        List<WorldPosition> all = new ArrayList<>();
        for (BlockPos hit : result.hits()) {
            all.add(WorldPosition.here(hit.getX(), hit.getY(), hit.getZ()));
        }
        return List.copyOf(all);
    }

    // 首次用到这种方块时登记进扫描索引；重复登记不叠加负担。
    private void ensureRegistered(ClientLevel level, Block block) {
        if (registered) {
            return;
        }
        scans.register(level, Set.of(block));
        registered = true;
    }

    private static BlockPos nearestTo(List<BlockPos> hits, BlockPos center) {
        BlockPos best = hits.get(0);
        for (BlockPos hit : hits) {
            if (hit.distSqr(center) < best.distSqr(center)) {
                best = hit;
            }
        }
        return best;
    }

    private static String lower(String id) {
        return id == null ? null : id.toLowerCase(Locale.ROOT);
    }

    private static ResourceLocation parse(String id) {
        return ResourceLocation.parse(lower(id));
    }
}
