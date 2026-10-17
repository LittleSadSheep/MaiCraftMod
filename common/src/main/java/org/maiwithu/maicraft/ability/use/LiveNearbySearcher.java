// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.use;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import java.util.function.Supplier;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;

import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.world.BlockScanService;
import org.maiwithu.maicraft.game.world.ScanTargets;

/**
 * 附近搜索的生产实现：方块走扫描服务的索引，实体直接看一遍渲染范围内的实体。
 *
 * <p>方块标签按注册表展开成具体的方块再登记；每次要找的方块不一样（这次找门、下次找拉杆），
 * 每次查询前按维度补登记，不会因为"只登记过第一种"把没登记误报成附近没有。
 * 索引给的是这一片的命中，超出这次 radius 的不算数；没扫完如实标出，"没扫完"不冒充"没有"。
 */
final class LiveNearbySearcher implements SearchesNearby {

    // 与感知读端同一套预算：一刻扫不完就分刻扫，不冒充扫完。
    private static final int WANT = 16;
    private static final int MAX_CHUNK_RADIUS = 8;
    private static final int BUILD_BUDGET = 4096;

    private final BlockScanService scans;
    private final Supplier<PlayerContext> context;
    private final ScanTargets registered;

    LiveNearbySearcher(BlockScanService scans, Supplier<PlayerContext> context) {
        this.scans = Objects.requireNonNull(scans, "scans");
        this.context = Objects.requireNonNull(context, "context");
        this.registered = new ScanTargets(scans);
    }

    @Override
    public BlockResult nearestBlock(String blockOrTag, BlockPos center, int radius, Predicate<BlockPos> skip) {
        PlayerContext current = context.get();
        ClientLevel level = current == null ? null : current.level();
        if (level == null) {
            return new BlockResult(Optional.empty(), false);
        }
        Set<Block> targets = blocksOf(blockOrTag);
        if (targets.isEmpty()) {
            // 写岔的方块 ID、空标签不是"附近还没扫完"：按扫完了没有交代。
            return new BlockResult(Optional.empty(), true);
        }
        registered.ensure(level, targets);
        var result = scans.query(level, center, targets, WANT,
                Math.max(1, Math.min(MAX_CHUNK_RADIUS, radius / 16 + 1)), BUILD_BUDGET);
        List<BlockPos> hits = new ArrayList<>(result.hits());
        // 索引给的是这一片的命中：超出这次愿意找的范围的不算数。
        hits.removeIf(hit -> hit.distSqr(center) > (double) radius * radius);
        // 由近到远问一遍要不要跳过（别人的东西），第一个不跳过的就是目标。
        hits.sort(Comparator.comparingDouble(hit -> hit.distSqr(center)));
        Optional<BlockPos> nearest = hits.stream().filter(hit -> !skip.test(hit)).findFirst();
        return new BlockResult(nearest, result.complete());
    }

    @Override
    public EntityResult nearestEntity(String entityTypeId, BlockPos center, int radius, IntPredicate skip) {
        PlayerContext current = context.get();
        ClientLevel level = current == null ? null : current.level();
        if (level == null) {
            return new EntityResult(OptionalInt.empty(), false);
        }
        String wanted = entityTypeId.toLowerCase(Locale.ROOT);
        List<Entity> candidates = new ArrayList<>();
        double reach = (double) radius * radius;
        for (Entity entity : level.entitiesForRendering()) {
            // 角色自己不算目标：对自己用东西是"只用手上的东西"。
            if (entity == current.localPlayer() || entity.isRemoved()) continue;
            if (!EntityType.getKey(entity.getType()).toString().equals(wanted)) continue;
            if (entity.blockPosition().distSqr(center) <= reach) candidates.add(entity);
        }
        // 由近到远问一遍要不要跳过（别人的生物），第一个不跳过的就是目标。
        candidates.sort(Comparator.comparingDouble(entity -> entity.blockPosition().distSqr(center)));
        OptionalInt best = candidates.stream().mapToInt(Entity::getId).filter(id -> !skip.test(id)).findFirst();
        return new EntityResult(best, true);
    }

    // 方块 ID 给那一种；标签按注册表展开成挂着这个标签的全部方块；写法不合法给空。
    private static Set<Block> blocksOf(String blockOrTag) {
        String spec = blockOrTag.toLowerCase(Locale.ROOT);
        if (spec.startsWith("#")) {
            ResourceLocation tagId = ResourceLocation.tryParse(spec.substring(1));
            if (tagId == null) return Set.of();
            TagKey<Block> tag = TagKey.create(Registries.BLOCK, tagId);
            return BuiltInRegistries.BLOCK.getTag(tag)
                    .map(set -> {
                        Set<Block> blocks = new LinkedHashSet<>();
                        set.forEach(holder -> blocks.add(holder.value()));
                        return blocks;
                    })
                    .orElse(Set.of());
        }
        ResourceLocation id = ResourceLocation.tryParse(spec);
        return id == null ? Set.of() : BuiltInRegistries.BLOCK.getOptional(id).map(Set::of).orElse(Set.of());
    }
}
