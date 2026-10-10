// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.find;

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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.boss.EnderDragonPart;
import net.minecraft.world.level.block.Block;
import org.maiwithu.maicraft.behavior.permission.ReadsCreatureSituation;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryKind;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryRecord;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.world.BlockScanService;
import org.maiwithu.maicraft.game.world.ObservationVisibility;
import org.maiwithu.maicraft.game.world.ScanTargets;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 附近寻找的生产读端：方块走扫描服务的分刻索引，实体看一遍渲染范围内的实体，
 * 结构查世界记忆里记过的产地线索。只读，不动身体、不改方块。
 *
 * <p>命中先复核实际方块状态（索引可能过时），再过视线闸——墙后的、埋着的不算找到；
 * 刚放下的方块经"最近方块变化"通道补查，不能刚放下就找不到。实体的多部件
 * （末影龙的身子各段）不是独立的实体条目，主体一条就是一条；有名字、被驯服、
 * 拴着绳、圈养着的实体照常报告，但带保护事实，不冒充"无主的找到了"。
 */
final class LiveFinder implements FindsAround {

    // 与感知读端同一套预算：一刻扫不完就分刻扫；半径换算成区块圈数封顶。
    private static final int MAX_CHUNK_RADIUS = 8;
    private static final int BUILD_BUDGET = 4096;

    private final BlockScanService scans;
    private final Supplier<PlayerContext> context;
    private final ReadsCreatureSituation creatures;
    private final WorldMemory memory;
    /** 这次要找的方块按维度补登记进扫描索引：没登记的方块索引里查不到。 */
    private final ScanTargets registered;
    /** 上一轮所在维度：扫描中换世界，这轮作废，不冒充结论。 */
    private String lastDimension;

    LiveFinder(BlockScanService scans, Supplier<PlayerContext> context,
            ReadsCreatureSituation creatures, WorldMemory memory) {
        this.scans = Objects.requireNonNull(scans, "scans");
        this.context = Objects.requireNonNull(context, "context");
        this.creatures = Objects.requireNonNull(creatures, "creatures");
        this.memory = Objects.requireNonNull(memory, "memory");
        this.registered = new ScanTargets(scans);
    }

    @Override
    public Round scan(FindInput input) {
        PlayerContext current = context.get();
        ClientLevel level = current == null ? null : current.level();
        LocalPlayer player = current == null ? null : current.localPlayer();
        if (level == null || player == null) {
            // 不在世界里：任务按"换世界"作废收场，这一轮没有任何结论。
            return new Round(List.of(), false, true);
        }
        String dimension = level.dimension().location().toString();
        boolean changed = lastDimension != null && !lastDimension.equals(dimension);
        lastDimension = dimension;
        if (changed) {
            return new Round(List.of(), false, true);
        }
        BlockPos center = player.blockPosition();
        return switch (input.kind()) {
            case BLOCK -> scanBlocks(input, level, player, center);
            case ENTITY -> scanEntities(input, level, player, center);
            case STRUCTURE -> structureLeads(input, center, dimension);
        };
    }

    /** 找方块：索引分刻给命中，复核实际状态、过视线闸，最近变化通道补查刚放下的。 */
    private Round scanBlocks(FindInput input, ClientLevel level, LocalPlayer player, BlockPos center) {
        Set<Block> targets = LiveWorldTypes.blocksOf(input.selectors());
        if (targets.isEmpty()) {
            // 决定阶段已校验过 ID；到不了这里，真到了也按"这一片扫完了没有"如实交代。
            return new Round(List.of(), true, false);
        }
        registered.ensure(level, targets);
        var result = scans.query(level, center, targets, input.count(),
                Math.max(1, Math.min(MAX_CHUNK_RADIUS, input.radius() / 16 + 1)), BUILD_BUDGET);
        List<Hit> hits = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (BlockPos pos : result.hits()) {
            confirmBlock(level, player, pos, targets, center, input.radius(), hits, seen);
        }
        // 刚放下的方块可能还没进扫描游标：最近同步的变化优先补查一遍，不能刚放下就找不到。
        for (BlockPos pos : scans.recentWrites(level)) {
            confirmBlock(level, player, pos, targets, center, input.radius(), hits, seen);
        }
        return new Round(List.copyOf(hits), result.complete(), false);
    }

    /** 复核一格是不是真目标（索引可能过时）、在不在半径里、看不看得见，都过才收。 */
    private void confirmBlock(ClientLevel level, LocalPlayer player, BlockPos pos, Set<Block> targets,
            BlockPos center, int radius, List<Hit> hits, Set<String> seen) {
        double dx = pos.getX() - center.getX();
        double dy = pos.getY() - center.getY();
        double dz = pos.getZ() - center.getZ();
        if (dx * dx + dy * dy + dz * dz > (double) radius * radius) {
            return;
        }
        Block state = level.getBlockState(pos).getBlock();
        if (!targets.contains(state)) {
            return;
        }
        // 视线被挡住的不是找到：等角色走近、看得见了再报。
        if (!ObservationVisibility.block(player, pos)) {
            return;
        }
        if (seen.add(pos.getX() + "," + pos.getY() + "," + pos.getZ())) {
            String typeId = BuiltInRegistries.BLOCK.getKey(state).toString();
            hits.add(Hit.place(FindInput.FindKind.BLOCK, typeId,
                    WorldPosition.here(pos.getX(), pos.getY(), pos.getZ())));
        }
    }

    /** 找实体：渲染范围内按类型数一遍，多部件折叠到主体，受保护的带保护事实。 */
    private Round scanEntities(FindInput input, ClientLevel level, LocalPlayer player, BlockPos center) {
        Set<EntityType<?>> wanted = new HashSet<>();
        for (String selector : input.selectors()) {
            LiveWorldTypes.entityTypeOf(selector).ifPresent(wanted::add);
        }
        List<Hit> hits = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        double reach = (double) input.radius() * input.radius();
        for (Entity entity : level.entitiesForRendering()) {
            // 角色自己不算目标；部件不是一条：末影龙的身子各段折到主体，一条就是一条。
            if (entity == player || entity.isRemoved() || entity instanceof EnderDragonPart) {
                continue;
            }
            if (!wanted.contains(entity.getType())) {
                continue;
            }
            if (entity.blockPosition().distSqr(center) > reach) {
                continue;
            }
            // 被墙挡住的实体不冒充看见：跑过去或看得见了再算。
            if (!ObservationVisibility.entity(player, entity)) {
                continue;
            }
            if (!seen.add(entity.getId())) {
                continue;
            }
            boolean creatureProtected = false;
            String reason = null;
            Optional<ReadsCreatureSituation.CreatureSituation> situation =
                    creatures.situationOf(entity.getUUID());
            if (situation.isPresent() && situation.get().bondedToSomeone()) {
                creatureProtected = true;
                reason = protectionWord(situation.get());
            }
            hits.add(new Hit(FindInput.FindKind.ENTITY,
                    EntityType.getKey(entity.getType()).toString(),
                    WorldPosition.here(entity.getBlockX(), entity.getBlockY(), entity.getBlockZ()),
                    entity.getId(), creatureProtected, reason));
        }
        // 实体清单一刻读得完：这轮就是全部，如实报扫完。
        return new Round(List.copyOf(hits), true, false);
    }

    // 有名字、被驯服、拴着绳、圈养着，占哪条就写哪条：照常报告，但能不能动归许可。
    private String protectionWord(ReadsCreatureSituation.CreatureSituation situation) {
        List<String> reasons = new ArrayList<>();
        if (situation.named()) reasons.add("有名字");
        if (situation.tamed()) reasons.add("被驯服");
        if (situation.leashed()) reasons.add("拴着绳");
        if (situation.inEnclosure()) reasons.add("圈养着");
        return String.join("、", reasons);
    }

    /** 找结构：查世界记忆里记过的产地线索；线索只是"这附近大概有"，到场要亲眼确认。 */
    private Round structureLeads(FindInput input, BlockPos center, String dimension) {
        WorldPosition here = new WorldPosition(center.getX(), center.getY(), center.getZ(), dimension);
        List<Hit> hits = new ArrayList<>();
        for (MemoryRecord record : memory.recordsNear(here, input.radius())) {
            if (record.kind() != MemoryKind.SITE) {
                continue;
            }
            // 线索记的是关键词（例如记过"这一片有煤矿"）：与要找的结构 ID 或它的路径对得上才算线索。
            if (!mentions(record.contents(), input.selectors())) {
                continue;
            }
            for (String selector : input.selectors()) {
                hits.add(Hit.place(FindInput.FindKind.STRUCTURE, selector, record.position()));
            }
        }
        // 线索是记忆不是扫描：问完记忆这一轮就完了，没有记过的线索如实按没有交代。
        return new Round(List.copyOf(hits), true, false);
    }

    /** 线索关键词与要找的 ID 对得上吗：整 ID、ID 的路径，或关键词出现在路径里，都算提到过。 */
    private boolean mentions(List<String> keywords, List<String> selectors) {
        if (keywords == null) {
            return false;
        }
        for (String keyword : keywords) {
            String word = keyword.toLowerCase(Locale.ROOT);
            for (String selector : selectors) {
                String id = selector.toLowerCase(Locale.ROOT);
                String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
                if (word.equals(id) || word.equals(path) || path.contains(word) || word.contains(path)) {
                    return true;
                }
            }
        }
        return false;
    }
}
