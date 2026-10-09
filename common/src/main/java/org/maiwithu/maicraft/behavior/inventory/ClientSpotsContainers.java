// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
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
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.level.block.AbstractChestBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import org.maiwithu.maicraft.behavior.perception.FacilityKinds;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryKind;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryRecord;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.world.BlockScanService;
import org.maiwithu.maicraft.game.world.ScanTargets;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 找容器的读端：现场扫描出这一片的容器方块，再把世界记忆里记过、现场还没扫到的并进来，
 * 按存东西挑选需要的事实（盖子上方、有没有猫坐着、开过没有）把每只候选读好。
 *
 * <p>方块索引按维度补登记容器方块，之后每刻只花小预算；没扫完如实标出，"没扫完"不冒充"没有"。
 * 是不是别人的箱子、压住盖子的方块是不是别人放的，都问这个世界的那一份保护判断；
 * 盖子压不压得住按原版的规矩读：只有箱子类会被上方的实心方块压住，树叶、台阶压不住，木桶不看上方。
 */
public final class ClientSpotsContainers implements SpotsContainers {

    // 与附近搜索同一套预算：一刻扫不完就分刻扫，不冒充扫完。
    private static final int MAX_CHUNK_RADIUS = 4;
    private static final int BUILD_BUDGET = 4096;

    private final BlockScanService scans;
    private final Supplier<PlayerContext> context;
    private final WorldMemory memory;
    /** 容器方块按维度登记进扫描索引：去了下界、末地照样认得出那里的箱子。 */
    private final ScanTargets registered;
    /** 这个世界的保护判断：容器与压住盖子的方块是不是别人的。 */
    private final Protection protection;
    /** 设施分类：哪些方块算容器按标签认，模组的箱子也在内，末影箱不在内。 */
    private final FacilityKinds kinds;
    private boolean lastScanComplete;

    public ClientSpotsContainers(BlockScanService scans, Supplier<PlayerContext> context, WorldMemory memory,
            Protection protection, FacilityKinds kinds) {
        this.scans = Objects.requireNonNull(scans, "scans");
        this.context = Objects.requireNonNull(context, "context");
        this.kinds = Objects.requireNonNull(kinds, "kinds");
        this.memory = Objects.requireNonNull(memory, "memory");
        this.protection = Objects.requireNonNull(protection, "protection");
        this.registered = new ScanTargets(scans);
    }

    @Override
    public List<ContainerChooser.Candidate> around(BlockPos center, int radius) {
        PlayerContext current = context.get();
        ClientLevel level = current == null ? null : current.level();
        if (level == null) {
            lastScanComplete = false;
            return List.of();
        }
        Set<Block> kinds = containerBlocks();
        registered.ensure(level, kinds);
        var result = scans.query(level, center, kinds, Integer.MAX_VALUE,
                Math.min(MAX_CHUNK_RADIUS, Math.max(1, radius / 16 + 1)), BUILD_BUDGET);
        lastScanComplete = result.complete();
        List<ContainerChooser.Candidate> found = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        for (BlockPos hit : result.hits()) {
            seen.add(hit);
            candidateFromWorld(level, hit, center).ifPresent(found::add);
        }
        // 世界记忆里记过的容器补进来：现场还看得见的按现场读，没加载的按记忆读（到场还要核对）。
        for (MemoryRecord record : memory.recordsNear(WorldPosition.here(center.getX(), center.getY(), center.getZ()),
                radius)) {
            if (record.kind() != MemoryKind.CONTAINER) continue;
            BlockPos at = new BlockPos(record.position().x(), record.position().y(), record.position().z());
            if (seen.contains(at)) continue;
            found.add(candidateFromMemory(record, center));
        }
        // 由近及远给出去，挑选的排序再按内容与空位细分。
        found.sort(Comparator.comparingDouble(candidate -> candidate.walkCost()));
        return List.copyOf(found);
    }

    @Override
    public boolean scanComplete() {
        return lastScanComplete;
    }

    @Override
    public Optional<ContainerChooser.Candidate> at(WorldPosition position) {
        PlayerContext current = context.get();
        ClientLevel level = current == null ? null : current.level();
        if (level == null) {
            return Optional.empty();
        }
        BlockPos at = new BlockPos(position.x(), position.y(), position.z());
        if (level.isLoaded(at)) {
            String blockTypeId = blockTypeIdOf(level.getBlockState(at));
            if (kinds.isContainer(blockTypeId)) {
                return candidateFromWorld(level, at, at);
            }
            return Optional.empty();
        }
        return memory.recordAt(MemoryKind.CONTAINER, position).map(record -> candidateFromMemory(record, at));
    }

    // 现场的一格容器读成候选：盖子、坐着的猫、开过的内容都按当刻的事实读。
    private Optional<ContainerChooser.Candidate> candidateFromWorld(ClientLevel level, BlockPos at, BlockPos from) {
        String blockTypeId = blockTypeIdOf(level.getBlockState(at));
        if (!kinds.isContainer(blockTypeId)) {
            return Optional.empty();
        }
        // 记忆里开过的内容跟位置走：现场还在，上次看到的清单仍可当"已经放着同种东西"的线索。
        Optional<MemoryRecord> known = memory.recordAt(MemoryKind.CONTAINER, WorldPosition.here(
                at.getX(), at.getY(), at.getZ()));
        WorldPosition position = positionOf(level, at);
        return Optional.of(new ContainerChooser.Candidate(
                describe(blockTypeId, at),
                blockTypeId,
                position,
                known.filter(MemoryRecord::openedBefore).map(MemoryRecord::contents).orElse(null),
                true,
                Math.sqrt(at.distSqr(from)),
                protection.blockProtected(position, blockTypeId, Set.of()),
                true,
                lidOf(level, at),
                catSitting(level, at)));
    }

    // 记忆里的一只容器读成候选：没加载的只给记忆里的事实，盖子与猫到场再核对。
    private ContainerChooser.Candidate candidateFromMemory(MemoryRecord record, BlockPos from) {
        BlockPos at = new BlockPos(record.position().x(), record.position().y(), record.position().z());
        String blockTypeId = record.blockType() == null ? "minecraft:chest" : record.blockType();
        return new ContainerChooser.Candidate(
                describe(blockTypeId, at),
                blockTypeId,
                record.position(),
                record.openedBefore() ? record.contents() : null,
                true,
                Math.sqrt(at.distSqr(from)),
                protection.blockProtected(record.position(), blockTypeId, Set.of()),
                true,
                ContainerChooser.Lid.CLEAR,
                false);
    }

    // 盖子压没压住按原版：箱子类上方是实心导电方块才打不开，其余容器不看上方。
    // 压住的方块受保护（别人的）要先问；不受保护的可以挖开。
    private ContainerChooser.Lid lidOf(ClientLevel level, BlockPos at) {
        if (!(level.getBlockState(at).getBlock() instanceof AbstractChestBlock<?>)) {
            return ContainerChooser.Lid.CLEAR;
        }
        BlockPos lid = at.above();
        BlockState above = level.getBlockState(lid);
        if (!above.isRedstoneConductor(level, lid)) {
            return ContainerChooser.Lid.CLEAR;
        }
        return protection.blockProtected(positionOf(level, lid), blockTypeIdOf(above), Set.of())
                ? ContainerChooser.Lid.BLOCKED_BY_FOREIGN : ContainerChooser.Lid.BLOCKED_BY_NATURAL;
    }

    private static WorldPosition positionOf(ClientLevel level, BlockPos at) {
        return new WorldPosition(at.getX(), at.getY(), at.getZ(), level.dimension().location().toString());
    }

    // 上面坐着猫的箱子开不了：按盖子那一格附近的猫找。
    private boolean catSitting(ClientLevel level, BlockPos at) {
        AABB lidCell = new AABB(at.above());
        return !level.getEntitiesOfClass(Cat.class, lidCell.inflate(0.5)).isEmpty();
    }

    private String describe(String blockTypeId, BlockPos at) {
        return blockTypeId.substring(blockTypeId.indexOf(':') + 1) + "（" + at.getX() + ", "
                + at.getY() + ", " + at.getZ() + "）";
    }

    private static String blockTypeIdOf(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    private Set<Block> containerBlocks() {
        Set<Block> blocks = new HashSet<>();
        for (String id : kinds.containerBlockTypes()) {
            BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse(id.toLowerCase(Locale.ROOT)))
                    .ifPresent(blocks::add);
        }
        return Set.copyOf(blocks);
    }

}
