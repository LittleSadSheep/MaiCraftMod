// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.sleep;

import java.util.ArrayList;
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

    private final Supplier<PlayerContext> context;
    private final BlockScanService scans;
    private final Protection protection;
    private final BackpackView backpack;
    private final OffhandContents offhand;
    /** 注册表里全部种类的床；第一次找床时取一次，方块注册那时早已完成。 */
    private List<Block> bedBlocks;

    /** 注册过床种类的世界：换世界后对新的世界重新登记，退出的世界注销。 */
    private ClientLevel registeredLevel;

    public LiveBedFinder(Supplier<PlayerContext> context, BlockScanService scans, Protection protection,
            BackpackView backpack, OffhandContents offhand) {
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
        for (BlockPos hit : found.hits()) {
            BedCandidate bed = inspect(level, hit, current, protectedLandmarks);
            if (bed != null) {
                candidates.add(bed);
            }
        }
        return new BedScan(candidates, found.complete());
    }

    @Override
    public boolean bedReady(TickContext tick) {
        // 今晚有床 = 身上带着一张，或附近已扫到的候选里有能用的。身上有床夜休会放下来睡。
        if (carriedBed().isPresent()) {
            return true;
        }
        // 夜休没有这次任务的许可，地标保护按没给额外地标算。
        BedScan scan = scan(tick, Set.of(), Set.of());
        return BedChooser.choose(scan.candidates(), Set.of(), null).isPresent();
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
     * 现场查一张床：命中格可能是床头或床尾，先折到床头；床的两半有一半不在（被拆了一半、
     * 只同步了一半）就不算一张完整的床。占用、保护、怪物、距离各查一遍。
     */
    private BedCandidate inspect(ClientLevel level, BlockPos hit, PlayerContext current, Set<String> landmarks) {
        BlockState state = level.getBlockState(hit);
        BlockPos head = state.getValue(BedBlock.PART) == BedPart.HEAD
                ? hit.immutable()
                : hit.relative(state.getValue(BedBlock.FACING));
        BlockState headState = level.getBlockState(head);
        if (!headState.hasProperty(BedBlock.PART) || headState.getValue(BedBlock.PART) != BedPart.HEAD) {
            // 床头没同步好或已被拆：不把半张床当成能睡的床。
            return null;
        }
        boolean occupied = headState.getValue(BedBlock.OCCUPIED);
        boolean protectedLand = protection.blockProtected(here(head), blockId(headState), landmarks);
        AABB around = new AABB(head).inflate(HOSTILE_HORIZONTAL, HOSTILE_VERTICAL, HOSTILE_HORIZONTAL);
        boolean hostileNear = !level.getEntitiesOfClass(Monster.class, around).isEmpty();
        double distance = Math.sqrt(current.localPlayer().blockPosition().distSqr(head));
        return new BedCandidate(head, occupied, protectedLand, hostileNear, distance);
    }

    private static WorldPosition here(BlockPos pos) {
        return WorldPosition.here(pos.getX(), pos.getY(), pos.getZ());
    }

    private static String blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }
}
