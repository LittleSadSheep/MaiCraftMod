package org.maiwithu.maicraft.core.pathing.bridge;

import org.maiwithu.maicraft.core.pathing.cache.CachedNavView;
import org.maiwithu.maicraft.core.pathing.cache.LoadedChunks;
import org.maiwithu.maicraft.core.pathing.cache.PathCaches;
import org.maiwithu.maicraft.core.pathing.moves.CalculationContext;
import org.maiwithu.maicraft.core.pathing.moves.ChunkLoadedTest;
import org.maiwithu.maicraft.core.pathing.moves.TerrainPermit;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.BlockGetter;

/**
 * 准备方块和挖掘费用计算所需的环境：forExecution 读取已加载现场，当前挖矿和找方块仍调用它；forSearch 准备旧搜索的冻结区块。
 * 调用者可提供自己的创建方法；共同的保护格会与当前任务继承的保护范围合并。
 */
public final class ContextFactory {

    private ContextFactory() {}

    @FunctionalInterface
    public interface ContextBuilder {
        CalculationContext create(LocalPlayer player, BlockGetter view, ChunkLoadedTest loadedTest,
                                  boolean safeForThreadedUse, LongSet sacred, LongSet deniedPlace,
                                  LongSet forbiddenBodyCells, TerrainPermit permit);
    }

    /**
     * 搜索用冻结上下文。必须在主线程调用(快照补建与背包取样都要求
     * 主线程);返回后可交给 worker 线程只读使用。
     *
     * @param sacred      不可挖不可埋的自身目标格({@code BlockPos.asLong} 键)
     * @param deniedPlace 执行层证明放不上的格
     * @param permit      这次移动对地形的许可(没有缺省值:每次导航都得说清自己的意图)
     */
    public static CalculationContext forSearch(LocalPlayer player, LongSet sacred,
                                               LongSet deniedPlace, TerrainPermit permit) {
        return forSearch(player, sacred, deniedPlace, LongSets.emptySet(), permit,
                CalculationContext::new);
    }

    public static CalculationContext forSearch(LocalPlayer player, LongSet sacred,
                                               LongSet deniedPlace, TerrainPermit permit,
                                               ContextBuilder builder) {
        return forSearch(player, sacred, deniedPlace, LongSets.emptySet(), permit, builder);
    }

    public static CalculationContext forSearch(LocalPlayer player, LongSet sacred,
                                               LongSet deniedPlace, LongSet forbiddenBodyCells,
                                               TerrainPermit permit, ContextBuilder builder) {
        if (!(player.level() instanceof ClientLevel level)) {
            throw new IllegalArgumentException("path search requires a LocalPlayer in ClientLevel");
        }
        // 旧搜索分支先在游戏线程复制区块，再把只读快照和对应的加载判断一起交给费用计算对象。
        LoadedChunks loaded = PathCaches.ensureSnapshot(level, player.blockPosition());
        CachedNavView view = new CachedNavView(loaded);
        return builder.create(player, view, view::isLoaded, true, protectedSacred(sacred), deniedPlace,
                forbiddenBodyCells, permit);
    }

    /** 无目标格/禁放格开关的搜索用冻结上下文。 */
    public static CalculationContext forSearch(LocalPlayer player, TerrainPermit permit) {
        return forSearch(player, LongSets.emptySet(), LongSets.emptySet(), permit);
    }

    /**
     * 执行期实时上下文:活世界的"只读已加载"视图——未加载区块读作
     * 空气,绝不触发同步加载/生成。主线程专用
     * ({@code safeForThreadedUse=false}),用于逐 tick 成本复核与
     * 装配期重算。
     */
    public static CalculationContext forExecution(LocalPlayer player, LongSet sacred,
                                                  LongSet deniedPlace, TerrainPermit permit) {
        return forExecution(player, sacred, deniedPlace, LongSets.emptySet(), permit,
                CalculationContext::new);
    }

    public static CalculationContext forExecution(LocalPlayer player, LongSet sacred,
                                                  LongSet deniedPlace, TerrainPermit permit,
                                                  ContextBuilder builder) {
        return forExecution(player, sacred, deniedPlace, LongSets.emptySet(), permit, builder);
    }

    public static CalculationContext forExecution(LocalPlayer player, LongSet sacred,
                                                  LongSet deniedPlace, LongSet forbiddenBodyCells,
                                                  TerrainPermit permit, ContextBuilder builder) {
        // 现用挖矿等查询读最新现场，只限制为已加载区块；这里不是搜索快照。
        var view = org.maiwithu.maicraft.core.pathing.cache.LoadedOnlyView.of(player.level());
        ChunkLoadedTest loaded = view instanceof org.maiwithu.maicraft.core.pathing.cache.LoadedOnlyView v
                ? v::isLoaded : ChunkLoadedTest.ALWAYS;
        return builder.create(player, view, loaded, false, protectedSacred(sacred), deniedPlace,
                forbiddenBodyCells, permit);
    }

    /** 无目标格/禁放格开关的执行期实时上下文。 */
    public static CalculationContext forExecution(LocalPlayer player, TerrainPermit permit) {
        return forExecution(player, LongSets.emptySet(), LongSets.emptySet(), permit);
    }

    private static LongSet protectedSacred(LongSet taskSacred) {
        LongSet inherited = NavigationSafetyContext.protectedMutationCells();
        if (inherited.isEmpty()) return taskSacred;
        if (taskSacred == null || taskSacred.isEmpty()) return inherited;
        LongOpenHashSet combined = new LongOpenHashSet(taskSacred);
        combined.addAll(inherited);
        return LongSets.unmodifiable(combined);
    }
}
