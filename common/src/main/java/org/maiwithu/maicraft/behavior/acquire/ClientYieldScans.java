// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.world.BlockScanService;
import org.maiwithu.maicraft.game.world.ObservationVisibility;
import org.maiwithu.maicraft.game.world.ScanTargets;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 按产出扫方块的读端：要一件东西时，把"挖掉会掉它"或"熟了能收它"的方块登记进
 * 方块扫描服务，再从索引里把这一片的角色附近的候选格找出来。
 *
 * <p>方块与产出对不上号的部分如实说没有：客户端读不到掉落表，会掉什么按原版常识对照
 * （见 VanillaBlockDrops、VanillaCropProducts），对照没有的物品报"附近没有"，
 * 不冒充找遍了。扫描分刻推进，没扫完时返回已找到的部分，"没扫完"不冒充"没有"。
 *
 * <p>采掘候选不透视：只算从角色眼睛看得见的那几格（与观察、find 同一套遮挡判断），埋在石头里的不算，
 * 要先下矿洞、挖开或走近。索引只是帮忙快点找到，不是"世界里哪里有矿"的答案。
 */
public final class ClientYieldScans implements ScansMinables, ScansMatureCrops {

    // 与附近搜索同一套预算：一刻扫不完就分刻扫；候选给够数量，距离与许可由来源再筛。
    private static final int WANT = 64;
    // 采掘只认看得见的：最近的几十格里可能全是埋在石头里的，露在洞壁上的排得更远，候选要多取一些再筛。
    private static final int MINABLE_WANT = 512;
    private static final int MAX_CHUNK_RADIUS = 4;
    private static final int BUILD_BUDGET = 4096;

    private final BlockScanService scans;
    private final Supplier<PlayerContext> context;
    /** 每次要找的方块都不一样（要煤找煤矿、要土豆找土豆地）：每次查询前按维度补登记。 */
    private final ScanTargets registered;

    public ClientYieldScans(BlockScanService scans, Supplier<PlayerContext> context) {
        this.scans = Objects.requireNonNull(scans, "scans");
        this.context = Objects.requireNonNull(context, "context");
        this.registered = new ScanTargets(scans);
    }

    @Override
    public List<MinableSpot> minable(WantedItem wanted, WorldPosition center, int radiusBlocks) {
        if (notInWorld()) {
            return List.of();
        }
        List<String> productIds = expand(wanted);
        Set<Block> targets = new LinkedHashSet<>();
        for (String productId : productIds) {
            for (String blockTypeId : VanillaBlockDrops.blocksDropping(productId)) {
                blockOf(blockTypeId).ifPresent(targets::add);
            }
        }
        List<BlockPos> hits = scan(targets, center, radiusBlocks, MINABLE_WANT);
        List<MinableSpot> spots = new ArrayList<>();
        PlayerContext current = context();
        for (BlockPos hit : hits) {
            // 不透视：埋在石头里、六面都被实心方块挡着的先筛掉（便宜），露出来的再按观察同一套遮挡判断
            // 从眼睛看一眼；看不见的不算候选，要先下矿洞、挖开或走近。
            if (!exposed(current.level(), hit) || !ObservationVisibility.block(current.localPlayer(), hit)) {
                continue;
            }
            String blockTypeId = blockTypeIdOf(current, hit);
            String dropped = VanillaBlockDrops.droppedBy(blockTypeId);
            // 同名通例带出的方块未必真的掉想要的东西：掉落对不上这次要的就不报。
            if (productIds.contains(dropped)) {
                spots.add(new MinableSpot(hit, blockTypeId, dropped));
            }
        }
        return spots;
    }

    @Override
    public boolean anyBlockDrops(WantedItem wanted) {
        for (String productId : expand(wanted)) {
            for (String blockTypeId : VanillaBlockDrops.blocksDropping(productId)) {
                if (blockOf(blockTypeId).isPresent()) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public List<CropSpot> mature(WantedItem wanted, WorldPosition center, int radiusBlocks) {
        if (notInWorld()) {
            return List.of();
        }
        Set<Block> targets = new LinkedHashSet<>();
        for (String productId : expand(wanted)) {
            for (String blockTypeId : VanillaCropProducts.cropsProducing(productId)) {
                blockOf(blockTypeId).ifPresent(targets::add);
            }
        }
        List<BlockPos> hits = scan(targets, center, radiusBlocks, WANT);
        List<CropSpot> spots = new ArrayList<>();
        for (BlockPos hit : hits) {
            BlockState state = context().level().getBlockState(hit);
            // 只有熟了的才算收成：没熟的被踩掉就白长了，等它长熟再来。
            if (state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state)) {
                spots.add(new CropSpot(hit, blockTypeIdOf(context(), hit)));
            }
        }
        return spots;
    }

    // 登记目标并查索引：只留半径内的命中，由近及远；目标认不出或角色不在世界里时给空。
    private List<BlockPos> scan(Set<Block> targets, WorldPosition center, int radiusBlocks, int want) {
        PlayerContext current = context.get();
        ClientLevel level = current == null ? null : current.level();
        if (level == null || targets.isEmpty()) {
            return List.of();
        }
        registered.ensure(level, targets);
        BlockPos at = new BlockPos(center.x(), center.y(), center.z());
        var result = scans.query(level, at, targets, want,
                Math.max(1, Math.min(MAX_CHUNK_RADIUS, radiusBlocks / 16 + 1)), BUILD_BUDGET);
        List<BlockPos> hits = new ArrayList<>(result.hits());
        // 索引给的是这一片的命中：超出这次愿意找的半径的不算数。
        hits.removeIf(hit -> Math.sqrt(hit.distSqr(at)) > radiusBlocks);
        hits.sort(Comparator.comparingDouble(hit -> hit.distSqr(at)));
        return hits;
    }

    // 有没有一面露在外面：相邻六格里有一格不是挡光的实心方块（空气、水、玻璃、树叶……），这一格才可能被看见。
    private static boolean exposed(ClientLevel level, BlockPos at) {
        for (Direction face : Direction.values()) {
            BlockPos next = at.relative(face);
            if (!level.getBlockState(next).isSolidRender(level, next)) {
                return true;
            }
        }
        return false;
    }

    // 要的东西展开成具体的物品 ID：标签按注册表里挂这个标签的物品展开，写法不合法给空。
    private static List<String> expand(WantedItem wanted) {
        if (!wanted.isTag()) {
            return List.of(wanted.itemId());
        }
        ResourceLocation tagId = ResourceLocation.tryParse(wanted.tagId().toLowerCase(Locale.ROOT));
        if (tagId == null) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (var holder : BuiltInRegistries.ITEM.getTagOrEmpty(TagKey.create(Registries.ITEM, tagId))) {
            ids.add(BuiltInRegistries.ITEM.getKey(holder.value()).toString());
        }
        return List.copyOf(ids);
    }

    private PlayerContext context() {
        PlayerContext current = context.get();
        if (current == null || current.level() == null) {
            throw new IllegalStateException("不在世界里却来读方块产出");
        }
        return current;
    }

    // 角色不在世界或世界还没就绪时，扫描答"没有"，不把没读到的世界当成空场。
    private boolean notInWorld() {
        PlayerContext current = context.get();
        return current == null || current.level() == null;
    }

    private static String blockTypeIdOf(PlayerContext current, BlockPos at) {
        return BuiltInRegistries.BLOCK.getKey(current.level().getBlockState(at).getBlock()).toString();
    }

    private static Optional<Block> blockOf(String blockTypeId) {
        ResourceLocation id = ResourceLocation.tryParse(blockTypeId.toLowerCase(Locale.ROOT));
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
            return Optional.empty();
        }
        return Optional.of(BuiltInRegistries.BLOCK.get(id));
    }
}
