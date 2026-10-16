// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;

import org.maiwithu.maicraft.behavior.acquire.spi.FluidTransform;
import org.maiwithu.maicraft.behavior.acquire.spi.FluidTransforms;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.world.ScanTargets;

/**
 * 往水里扔料要看的现场的生产读端：配方按当刻角色的客户端世界读；水格按方块扫描找认的那种流体的方块，
 * 再一格格看是不是能扔料的静水源格，旁边一格半里漂着掉落物的往后排。
 */
public final class LiveTransformSites implements TransformSites {

    /** 一次最多看几格水：池塘一大片，最近的几十格里挑得出来。 */
    private static final int WANT = 64;
    private static final int BUILD_BUDGET = 4096;
    /** 一格水周围这么远里漂着掉落物就往后排：扔进去会凑到别人的东西。 */
    private static final double CROWD_RADIUS = 1.5;

    private final PlayerServices services;
    private final ScanTargets registered;

    public LiveTransformSites(PlayerServices services) {
        this.services = Objects.requireNonNull(services, "services");
        this.registered = new ScanTargets(services.blockScans());
    }

    @Override public Optional<List<FluidTransform>> recipes(FluidTransforms transforms) {
        PlayerContext current = services.context().get();
        if (current == null || current.level() == null) return Optional.empty();
        return Optional.of(transforms.recipes(current.level()));
    }

    @Override public Pools pools(BlockPos center, Set<String> fluids, int radius) {
        PlayerContext current = services.context().get();
        ClientLevel level = current == null ? null : current.level();
        if (level == null) return new Pools(List.of(), true);
        Set<Block> targets = blocksOf(fluids);
        if (targets.isEmpty()) return new Pools(List.of(), true);
        registered.ensure(level, targets);
        var result = services.blockScans().query(level, center, targets, WANT, radius / 16 + 1, BUILD_BUDGET, Set.of(), true);
        List<Pool> pools = new ArrayList<>();
        for (BlockPos hit : result.hits()) {
            double distance = Math.sqrt(hit.distSqr(center));
            if (distance > radius || !TransformSites.usable(level, hit, fluids)) continue;
            boolean crowded = !level.getEntitiesOfClass(ItemEntity.class, new AABB(hit).inflate(CROWD_RADIUS)).isEmpty();
            pools.add(new Pool(hit.immutable(), distance, crowded));
        }
        return new Pools(TransformSites.order(pools), result.complete());
    }

    // 认的流体对应的方块：水和流动的水都是水方块，按流体自己给的方块形态认。
    private static Set<Block> blocksOf(Set<String> fluids) {
        Set<Block> blocks = new LinkedHashSet<>();
        for (String id : fluids) {
            ResourceLocation key = ResourceLocation.tryParse(id);
            if (key == null) continue;
            BuiltInRegistries.FLUID.getOptional(key)
                    .map(fluid -> fluid.defaultFluidState().createLegacyBlock().getBlock())
                    .ifPresent(blocks::add);
        }
        return blocks;
    }
}
