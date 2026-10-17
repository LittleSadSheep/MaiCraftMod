// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.sleep;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.phys.AABB;

import org.maiwithu.maicraft.behavior.acquire.OffhandContents;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.survival.NightfallNeed;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryKind;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryRecord;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.world.BlockScanService;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 找床的生产读端：已加载世界里扫床，逐张判占用、保护、怪物，加上背包里的自带床，回答"今晚有没有床"。
 *
 * <p>扫描交给游戏接口层的分刻扫描服务，一刻只花小预算；候选床的每道检查都读当刻的现场，
 * 不用缓存里的旧结论。占用与怪物是原版判入睡的同一批规则：被占的床睡不下，水平八格、
 * 垂直五格内有怪时点了床也会被游戏拒绝，所以先在这里把它们筛掉。
 */
public final class LiveBedFinder implements BedScanner, NightfallNeed.ReadsBedAvailability {

    /** 找床扫多大：以角色（或床区中心）为准的区块半径。原版村庄也就在几十格内，扫太远走不过去。 */
    private static final int SCAN_CHUNK_RADIUS = 2;
    /** 一次要几张候选：够挑最近的，又不让扫描为凑数跑太远。 */
    private static final int WANT_BEDS = 8;
    /** 每刻给这次查询的建索引预算（毫秒级的粒度由扫描服务自己掌握）。 */
    private static final int QUERY_BUDGET = 200;
    /** 床边不许有怪的判定盒：水平八格、垂直五格，与原版拒绝入睡的检查同盒。 */
    private static final double HOSTILE_HORIZONTAL = 8.0;
    private static final double HOSTILE_VERTICAL = 5.0;
    /**
     * 自己睡过的床离这么远（格）以内也算附近：叫去睡觉时离家不远就回家睡，走二三十秒的路。
     * 夜里自己去睡另有更近的限（见选床的夜里最远距离），这么远的床夜里不去。
     */
    private static final double REMEMBERED_BED_RANGE = 128.0;

    private final Supplier<PlayerContext> context;
    private final BlockScanService scans;
    private final Protection protection;
    private final BackpackView backpack;
    private final OffhandContents offhand;
    /** 记住睡过的床；没接上为 null。 */
    private final WorldMemory memory;
    /** 注册表里全部种类的床；第一次找床时取一次，方块注册那时早已完成。 */
    private List<Block> bedBlocks;

    /** 注册过床种类的世界：换世界后对新的世界重新登记，退出的世界注销。 */
    private ClientLevel registeredLevel;

    public LiveBedFinder(Supplier<PlayerContext> context, BlockScanService scans, Protection protection,
            BackpackView backpack, OffhandContents offhand, WorldMemory memory) {
        this.memory = memory;
        this.context = context;
        this.scans = scans;
        this.protection = protection;
        this.backpack = backpack;
        this.offhand = offhand;
    }

    /** 把全部种类的床登记进扫描索引：按"方块状态带床半身属性"认床，不写方块 ID 名单。 */
    private void ensureRegistered(ClientLevel level) {
        if (bedBlocks == null) {
            bedBlocks = BuiltInRegistries.BLOCK.stream()
                    .filter(block -> block.defaultBlockState().hasProperty(BedBlock.PART))
                    .toList();
        }
        if (registeredLevel == level) {
            return;
        }
        if (registeredLevel != null) {
            scans.unregister(registeredLevel, bedBlocks);
        }
        scans.register(level, bedBlocks);
        registeredLevel = level;
    }

    @Override
    public BedScan scan(TickContext tick, Set<BlockPos> excluded, Set<String> protectedLandmarks) {
        PlayerContext current = context.get();
        if (current == null || current.level() == null) {
            return new BedScan(List.of(), true);
        }
        ClientLevel level = current.level();
        ensureRegistered(level);
        BlockPos center = current.localPlayer().blockPosition();
        var found = scans.query(level, center, bedBlocks, WANT_BEDS, SCAN_CHUNK_RADIUS, QUERY_BUDGET,
                excluded, false);
        List<BedCandidate> candidates = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        for (BlockPos hit : found.hits()) {
            BedCandidate bed = inspect(level, hit, current, protectedLandmarks);
            // 一张完整的床占两格、扫描命中两次：折到床头后只报一张。
            if (seen.add(bed.cell())) {
                candidates.add(bed);
            }
        }
        rememberedBeds(level, center, current, protectedLandmarks, seen, candidates);
        return new BedScan(candidates, found.complete());
    }

    // 自己睡过的床（家里那张）：不在附近两个区块里也算候选，夜里离家不远就回家睡，不在野外现找现做。
    // 床所在那片加载着就现场核对（拆掉了的不去）；没加载的按记忆照报——能用得上 128 格召回的床
    // 大多在加载区之外（实机 10-15 睡过的床在 70 格外，之后每晚都在它 32 格内报"没有床"），
    // 占用与怪物到了跟前由原版结算，点不上再换。
    private void rememberedBeds(ClientLevel level, BlockPos center, PlayerContext current, Set<String> landmarks,
            Set<BlockPos> seen, List<BedCandidate> candidates) {
        if (memory == null) return;
        for (MemoryRecord record : memory.recordsNear(here(center), REMEMBERED_BED_RANGE)) {
            if (record.kind() != MemoryKind.WORKSTATION || !record.blockType().endsWith("_bed")) continue;
            BlockPos cell = new BlockPos(record.position().x(), record.position().y(), record.position().z());
            if (!level.isLoaded(cell)) {
                if (seen.add(cell)) {
                    candidates.add(new BedCandidate(cell, false, false, false,
                            Math.sqrt(current.localPlayer().blockPosition().distSqr(cell))));
                }
                continue;
            }
            if (!(level.getBlockState(cell).getBlock() instanceof BedBlock)) continue;
            BedCandidate bed = inspect(level, cell, current, landmarks);
            if (seen.add(bed.cell())) candidates.add(bed);
        }
    }

    @Override
    public void sleptIn(BlockPos bed) {
        PlayerContext current = context.get();
        if (memory == null || current == null || current.level() == null) return;
        Block block = current.level().getBlockState(bed).getBlock();
        if (!(block instanceof BedBlock)) return;
        memory.rememberWorkstationUsed(here(bed), BuiltInRegistries.BLOCK.getKey(block).toString(), Instant.now());
    }

    @Override
    public void forgotBed(BlockPos bed) {
        // 收回的是自己放的床：它已经不在世界里，记忆里那条不删，下次召回还会按它指路。
        if (memory == null) return;
        memory.forget(MemoryKind.WORKSTATION, here(bed));
    }

    @Override
    public boolean bedReady(TickContext tick) {
        // 今晚有床 = 身上带着一张，或夜里肯走过去的那么近（32 格）有能用的。身上有床夜休会放下来睡；
        // 远处的床（自己睡过的家里那张也一样）夜里不去，不算今晚有床，夜晚改按挖坑、找掩体熬夜。
        if (carriedBed().isPresent()) {
            return true;
        }
        // 夜休没有这次任务的许可，地标保护按没给额外地标算。
        BedScan scan = scan(tick, Set.of(), Set.of());
        return BedChooser.choose(scan.candidates(), Set.of(), null, BedChooser.NIGHT_WALK_LIMIT_BLOCKS).isPresent();
    }

    /** 身上（主背包加副手）带着的床；夜休放下它睡，公开睡觉备床也算数。 */
    public Optional<String> carriedBed() {
        List<BackpackStack> stacks = new ArrayList<>(backpack.stacks());
        offhand.heldInOffhand().ifPresent(stacks::add);
        return stacks.stream()
                .map(BackpackStack::itemId)
                .filter(id -> id.endsWith("_bed"))
                .findFirst();
    }

    /**
     * 现场查一次命中：命中格可能是床头或床尾，先折成睡它要对着点的那一格——床头不在的半张床
     * 也照报，不再当作"半张床"悄悄丢掉（世界明明有床，选床却当没有，就退化成去现做一张）。
     * 占用读锚点格；保护只问这次任务点名保护的地标——睡是"用"床不是动方块，
     * 别人放的床、归属还没问到的床都照睡（村庄的床本来就是给人睡的），
     * "归属拿不准就按受保护处理"是拆改方块的规矩，不套在用床上。
     */
    private BedCandidate inspect(ClientLevel level, BlockPos hit, PlayerContext current, Set<String> landmarks) {
        BlockState state = level.getBlockState(hit);
        boolean headHit = state.getValue(BedBlock.PART) == BedPart.HEAD;
        BlockState stateTowardHead = headHit
                ? state
                : level.getBlockState(hit.relative(state.getValue(BedBlock.FACING)));
        BlockPos cell = BedCandidate.clickCell(hit, state, stateTowardHead);
        boolean occupied = level.getBlockState(cell).getValue(BedBlock.OCCUPIED);
        boolean protectedLand = protection.protectedLandmarkHit(here(cell), landmarks).isPresent();
        AABB around = new AABB(cell).inflate(HOSTILE_HORIZONTAL, HOSTILE_VERTICAL, HOSTILE_HORIZONTAL);
        boolean hostileNear = !level.getEntitiesOfClass(Monster.class, around).isEmpty();
        double distance = Math.sqrt(current.localPlayer().blockPosition().distSqr(cell));
        return new BedCandidate(cell, occupied, protectedLand, hostileNear, distance);
    }

    private static WorldPosition here(BlockPos pos) {
        return WorldPosition.here(pos.getX(), pos.getY(), pos.getZ());
    }
}
