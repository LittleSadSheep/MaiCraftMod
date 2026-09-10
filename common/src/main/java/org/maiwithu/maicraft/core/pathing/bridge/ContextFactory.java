package org.maiwithu.maicraft.core.pathing.bridge;

import org.maiwithu.maicraft.core.pathing.moves.CalculationContext;
import org.maiwithu.maicraft.core.pathing.moves.ChunkLoadedTest;
import org.maiwithu.maicraft.core.pathing.moves.TerrainPermit;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.BlockGetter;

/**
 * 为现用挖矿与方块查询准备费用计算环境：forExecution 只读取当前已加载的现场。
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
