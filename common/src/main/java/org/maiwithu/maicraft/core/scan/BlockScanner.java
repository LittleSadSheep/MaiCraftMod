package org.maiwithu.maicraft.core.scan;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.BiPredicate;

/**
 * 执行一小段实际方块扫描，返回位置、方块状态和距离。
 * 大范围分刻调度由 BlockSearch 或 TargetIndex 负责，本类不自己创建后台搜索任务。
 */
public final class BlockScanner {

    private BlockScanner() {}

    /**
     * 返回 ({@code cx},{@code cz}) 处完整加载的区块；若尚未加载则返回 {@code null}。此方法只读客户端区块缓存，<b>绝不</b>强制加载、生成区块或切换到主线程。
     * 本包中的所有扫描都通过这里读取地形；带状态参数的 {@code getChunk} 可能因区块 I/O 或生成而阻塞服务器线程。
     * 扫描只是感知查询，只报告当前已加载内容，不会为了回答而扩展世界。
     */
    static ChunkAccess loadedChunk(Level level, int cx, int cz) {
        return level instanceof ClientLevel clientLevel
                ? clientLevel.getChunkSource().getChunkNow(cx, cz)
                : null;
    }

    /** 一个命中结果：世界坐标、方块状态以及到搜索中心的欧几里得距离。 */
    public record Hit(BlockPos pos, BlockState state, double distance) {}

    /**
     * 身边小盒范围内、离 {@code eye} 最近的满足 {@code match} 的方块;超出
     * {@code maxDist} 或没有则 null。同步逐格读,只适合以身体为中心的小半径
     * (必在加载区内)——远程找方块走 {@code BlockSearch} 的预算切片。
     *
     * <p>谓词带位置:有的判据要问方块实体(见 {@code CraftOps} 的行为探测),
     * 光有状态答不了。空气格不问谓词,直接跳过。
     */
    // 小范围直接逐格找非空气目标，按眼睛到方块中心的距离选；不在这里判断能否走到或视线是否被挡。
    public static BlockPos nearestBlock(Level level, BlockPos base, Vec3 eye,
                                        int hr, int vr, double maxDist,
                                        BiPredicate<BlockPos, BlockState> match) {
        BlockPos best = null;
        double bestD = maxDist * maxDist;
        for (BlockPos p : BlockPos.betweenClosed(base.offset(-hr, -vr, -hr), base.offset(hr, vr, hr))) {
            BlockState state = level.getBlockState(p);
            if (state.isAir() || !match.test(p, state)) {
                continue;
            }
            double d = eye.distanceToSqr(Vec3.atCenterOf(p));
            if (d < bestD) {
                bestD = d;
                best = p.immutable();
            }
        }
        return best;
    }

    /**
     * 扫描一个 section：它属于已经解析的 chunk；先用调色板快速跳过无关内容，再进行球形范围裁剪，并将命中加入
     * {@code out}。全仓找方块最终都落到这里——{@link BlockSearch} 一个配额换一节,
     * {@link #scanRings} 一口气走完一串。公开是因为前者要按这个粒度计费。
     */
    // 先看这一段是不是空的、是否可能含目标；有可能才遍历它的 16×16×16 个格子。
    public static void scanChunkSection(Level level, ChunkAccess chunk,
                                        int chunkX, int sectionY, int chunkZ,
                                        BlockPos center, int radius, double radiusSq,
                                        Predicate<BlockState> filter,
                                        List<Hit> out) {
        int idx = level.getSectionIndexFromSectionY(sectionY);
        if (idx < 0 || idx >= chunk.getSectionsCount()) return;
        LevelChunkSection section = chunk.getSection(idx);
        if (section == null || section.hasOnlyAir()) return;
        // 调色板预筛短路：若 section 调色板中没有目标类型，就跳过内部全部 4096 个方块。
        if (!section.maybeHas(filter)) return;
        scanSection(section, chunkX, sectionY, chunkZ, center, radius, radiusSq, filter, out);
    }

    // 从段内坐标还原世界坐标，再按球形半径过滤并记录匹配方块；不把方形外框里的所有格都当成范围内。
    private static void scanSection(LevelChunkSection section,
                                    int chunkX, int sectionY, int chunkZ,
                                    BlockPos center, int radius, double radiusSq,
                                    Predicate<BlockState> filter,
                                    List<Hit> out) {
        int baseX = SectionPos.sectionToBlockCoord(chunkX);
        int baseY = SectionPos.sectionToBlockCoord(sectionY);
        int baseZ = SectionPos.sectionToBlockCoord(chunkZ);
        for (int dx = 0; dx < 16; dx++) {
            int worldX = baseX + dx;
            int ddx = worldX - center.getX();
            if (ddx < -radius || ddx > radius) continue;
            for (int dy = 0; dy < 16; dy++) {
                int worldY = baseY + dy;
                int ddy = worldY - center.getY();
                if (ddy < -radius || ddy > radius) continue;
                for (int dz = 0; dz < 16; dz++) {
                    int worldZ = baseZ + dz;
                    int ddz = worldZ - center.getZ();
                    if (ddz < -radius || ddz > radius) continue;
                    double distSq = (double) ddx * ddx + (double) ddy * ddy + (double) ddz * ddz;
                    if (distSq > radiusSq) continue;
                    BlockState state = section.getBlockState(dx, dy, dz);
                    if (!filter.test(state)) continue;
                    out.add(new Hit(new BlockPos(worldX, worldY, worldZ), state, Math.sqrt(distSq)));
                }
            }
        }
    }
}
