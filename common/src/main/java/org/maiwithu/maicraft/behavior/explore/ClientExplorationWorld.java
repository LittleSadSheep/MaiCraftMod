// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.explore;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.world.BlockScanService;
import org.maiwithu.maicraft.game.world.ObservationVisibility;
import org.maiwithu.maicraft.game.world.ScanTargets;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 探索现场的生产读端：逐列抽样问客户端世界的区块高度图与群系，视线闸走观察共用的
 * 遮挡判断，留意实体看一遍渲染范围，结构线索走方块扫描服务按证据组合评估。
 *
 * <p>只读，不动身体、不改方块；未加载的列如实给未加载样本，不猜群系也不判露天。
 * 高度图与群系只有已加载的区块才有，恰好是"先走近加载再采样"的边界。
 */
public final class ClientExplorationWorld implements ExplorationWorld {

    /** 结构线索扫描的预算：与寻找读端同一套分刻节奏。 */
    private static final int CLUE_SCAN_CHUNK_RADIUS = 8;
    private static final int CLUE_SCAN_BUDGET = 4096;
    private static final int CLUE_SCAN_WANT = 64;

    private final Supplier<PlayerContext> context;
    private final BlockScanService scans;
    /** 留意清单的结构证据方块按维度补登记进扫描索引：没登记的方块索引里查不到。 */
    private final ScanTargets registered;

    public ClientExplorationWorld(Supplier<PlayerContext> context, BlockScanService scans) {
        this.context = Objects.requireNonNull(context, "context");
        this.scans = Objects.requireNonNull(scans, "scans");
        this.registered = new ScanTargets(scans);
    }

    @Override
    public WorldPosition currentSpot() {
        LocalPlayer player = player();
        if (player == null) {
            return null;
        }
        return new WorldPosition((int) Math.floor(player.getX()), (int) Math.floor(player.getY()),
                (int) Math.floor(player.getZ()), player.level().dimension().location().toString());
    }

    @Override
    public Float facingYaw() {
        LocalPlayer player = player();
        return player == null ? null : player.getYRot();
    }

    @Override
    public ColumnSample sampleColumn(int x, int z) {
        ClientLevel level = level();
        if (level == null || !level.hasChunkAt(new BlockPos(x, 0, z))) {
            return ColumnSample.unloaded();
        }
        // 高度图返回的是顶块上面的第一个空位；减一才是"忽略树叶的最高实体或液体遮挡物"。
        int topY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        BlockPos top = new BlockPos(x, topY, z);
        BlockState state = level.getBlockState(top);
        String biome = level.getBiome(top).unwrapKey()
                .map(key -> key.location().toString()).orElse(null);
        return new ColumnSample(true, biome, topY,
                state.getBlock() == Blocks.WATER || state.getBlock() == Blocks.LAVA,
                state.blocksMotion(),
                BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
    }

    @Override
    public boolean sightClear(int x, int y, int z) {
        LocalPlayer player = player();
        return player != null && ObservationVisibility.point(player, new Vec3(x + 0.5, y + 0.5, z + 0.5));
    }

    @Override
    public boolean matches(String registeredId, List<String> selectors) {
        ClientLevel level = level();
        // 方块查静态注册表；群系在数据包注册表里，从世界拿。两边都不是就当没命中。
        if (SelectorMatch.blockMatches(registeredId, selectors)) {
            return true;
        }
        return level != null
                && SelectorMatch.biomeMatches(level.registryAccess().registryOrThrow(Registries.BIOME),
                        registeredId, selectors);
    }

    @Override
    public boolean supportedAt(int x, int footY, int z) {
        ClientLevel level = level();
        if (level == null || !level.isLoaded(new BlockPos(x, footY, z))) {
            return false;
        }
        // 站得住：脚那格身体进得去，下面同高的一格上面踩得稳。
        BlockPos feet = new BlockPos(x, footY, z);
        BlockPos floor = feet.below();
        return !level.getBlockState(feet).blocksMotion()
                && level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP);
    }

    @Override
    public List<Spotted> entitySightings(List<String> entitySelectors) {
        LocalPlayer player = player();
        ClientLevel level = level();
        if (player == null || level == null || entitySelectors.isEmpty()) {
            return List.of();
        }
        Set<EntityType<?>> wanted = new HashSet<>();
        for (String selector : entitySelectors) {
            ResourceLocation id = ResourceLocation.tryParse(selector.toLowerCase(Locale.ROOT));
            if (id != null) {
                BuiltInRegistries.ENTITY_TYPE.getOptional(id).ifPresent(wanted::add);
            }
        }
        List<Spotted> sightings = new ArrayList<>();
        for (Entity entity : level.entitiesForRendering()) {
            // 角色自己不算留意命中；被墙挡住的不冒充看见。
            if (entity == player || entity.isRemoved() || !wanted.contains(entity.getType())) {
                continue;
            }
            if (!ObservationVisibility.entity(player, entity)) {
                continue;
            }
            sightings.add(new Spotted(EntityType.getKey(entity.getType()).toString(),
                    new WorldPosition(entity.getBlockX(), entity.getBlockY(), entity.getBlockZ(),
                            level.dimension().location().toString())));
        }
        return sightings;
    }

    @Override
    public List<Spotted> structureClues(List<String> structureSelectors) {
        ClientLevel level = level();
        LocalPlayer player = player();
        if (level == null || player == null || structureSelectors.isEmpty()) {
            return List.of();
        }
        BlockPos center = player.blockPosition();
        List<Spotted> clues = new ArrayList<>();
        for (String selector : structureSelectors) {
            Optional<StructureClues.Resolved> resolved = StructureClues.resolve(selector);
            if (resolved.isEmpty()) {
                continue;
            }
            registered.ensure(level, resolved.get().unionBlocks());
            var result = scans.query(level, center, resolved.get().unionBlocks(),
                    CLUE_SCAN_WANT, CLUE_SCAN_CHUNK_RADIUS, CLUE_SCAN_BUDGET);
            List<StructureClues.Evidence> evidence = new ArrayList<>();
            for (BlockPos hit : result.hits()) {
                evidence.add(new StructureClues.Evidence(hit, level.getBlockState(hit).getBlock()));
            }
            StructureClues.anchorOf(resolved.get(), evidence).ifPresent(anchor ->
                    clues.add(new Spotted(selector, new WorldPosition(anchor.getX(), anchor.getY(),
                            anchor.getZ(), level.dimension().location().toString()))));
        }
        return clues;
    }

    private LocalPlayer player() {
        PlayerContext current = context.get();
        return current == null ? null : current.localPlayer();
    }

    private ClientLevel level() {
        PlayerContext current = context.get();
        return current == null ? null : current.level();
    }
}
