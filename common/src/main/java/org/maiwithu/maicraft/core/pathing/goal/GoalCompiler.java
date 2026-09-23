package org.maiwithu.maicraft.core.pathing.goal;

import org.maiwithu.maicraft.core.pathing.calc.NavGoal;
import org.maiwithu.maicraft.core.pathing.util.BlockHelper;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 把“站到这一格”“走到方块旁边”“靠近这一片”等意图转换成导航要求，并附上途中不能破坏的目标格。
 * 目标位置和被保护的位置不是总相同：靠近箱子时保护箱子本身，站到空地则没有要保护的箱子。
 */
public final class GoalCompiler {

    private GoalCompiler() {}

    /**
     * 编译后的导航约定。
     *
     * @param goal           搜索目标，表示在移动图节点域中的到达条件。
     * @param sacred         路线不得破坏或掩埋的格子键（{@link BlockPos#asLong()}）；属于 {@code CalculationContext} 的检查范围，无方块目标时为空。
     */
    public record Compiled(NavGoal goal, LongSet sacred) {
        /**
         * 用于比较实时目标的冻结值键。复制基础类型构成的 sacred 集合，避免调用方在寻路器背后修改先前 tick 的基线。
         */
        public CompiledFingerprint semanticFingerprint() {
            return new CompiledFingerprint(goal.semanticFingerprint(), sacred);
        }
    }

    public record CompiledFingerprint(
            NavGoal.SemanticFingerprint goal,
            Set<Long> sacred) {
        public CompiledFingerprint {
            sacred = Set.copyOf(sacred);
        }
    }

    /**
     * 一个挖矿站位：包含矿石（作为 sacred 保护目标）、脚位范围所依附的站位基准（通常是矿石本身；竖向矿脉的顶块可能以其下方一格为基准，见 {@code MineCompanionTask.coalesce}），
     * 以及最终脚位允许低于基准多少格（{@link NavGoal#mineColumn}）。
     */

    /**
     * 用于在方块上操作、打开或工作（工作台、箱子、熔炉、门）：终点必须能触及方块（{@link NavGoal#getToBlock} 以目标高度为基准，不接受高处格），
     * 目标方块自身受 sacred 保护，且只有角色落地并处于交互距离内才算到达。
     */
    public static Compiled interact(BlockPos target) {
        BlockPos t = target.immutable();
        return new Compiled(NavGoal.getToBlock(t), single(t));
    }

    /** 要求占据指定方块格；没有 sacred 保护格。 */
    public static Compiled standOn(BlockPos cell) {
        BlockPos c = cell.immutable();
        return new Compiled(NavGoal.exact(c), LongSets.emptySet());
    }

    /** 要求正交相邻站在 {@code target} 旁作为放置站位；目标格受 sacred 保护，路线不能在任务即将填入方块的位置搭设脚手架。 */
    public static Compiled standAdjacent(BlockPos target) {
        BlockPos t = target.immutable();
        return new Compiled(NavGoal.adjacent(t), single(t));
    }

    /**
     * 用于通常会移动的地面目标：在目标高度上下各一格内按水平方向半径接近（{@link NavGoal#nearGround}，有意不用三维球形范围）；没有 sacred 保护格。
     */
    public static Compiled near(BlockPos center, double radius) {
        BlockPos c = center.immutable();
        return new Compiled(NavGoal.nearGround(c, radius), LongSets.emptySet());
    }

    /** 替代 {@code resolveBlockGoal}：目标为空地时将其作为站位；目标已被占据时则走到方块旁，不消耗该方块。 */
    public static Compiled block(Level level, BlockPos cell) {
        return block(BlockHelper.canWalkThrough(level, cell), cell);
    }

    /** {@link #block(Level, BlockPos)} 的纯逻辑核心，可在无游戏环境下测试。 */
    public static Compiled block(boolean cellWalkable, BlockPos cell) {
        return cellWalkable ? standOn(cell) : interact(cell);
    }

    /**
     * 用一次搜索处理完整挖矿目标：组合每个矿物的站位，并为附近每个掉落物添加一个可行走目标，使同一路线也能收集它们。
     *
     * <p>目标格有意不加入 sacred 集合，允许路线经过时顺手挖掉目标。站位可能位于目标自身柱列中（例如树干下方的脚位本身也是原木格），
     * 禁止路线破坏目标会让尚未砍伐的树干站位全部无法满足。途中挖掉目标不会丢失结果：方块会从实时目标索引移除，角色沿路线经过时再按原生方式拾取掉落物。
     */
    public static Compiled mineField(List<BlockPos> ores, List<BlockPos> drops) {
        List<NavGoal> members = new ArrayList<>(ores.size() + drops.size());
        for (BlockPos ore : ores) {
            members.add(NavGoal.mineStance(ore));
        }
        for (BlockPos drop : drops) {
            members.add(NavGoal.exact(drop));     // items, not blocks
        }
        return new Compiled(NavGoal.composite(members), LongSets.emptySet());
    }

    /**
     * 走到这些同类方块中的任意一个旁边（“前往最近的某类方块”）：组合每个候选的 {@link NavGoal#getToBlock} 目标，
     * 并将每个候选都设为 sacred，路线不能破坏或掩埋正在前往的方块；最终选择成本最低的一项。
     */
    public static Compiled anyOf(List<BlockPos> candidates) {
        List<NavGoal> members = new ArrayList<>(candidates.size());
        LongSet sacred = new LongOpenHashSet(candidates.size());
        for (BlockPos c : candidates) {
            BlockPos t = c.immutable();
            members.add(NavGoal.getToBlock(t));
            sacred.add(t.asLong());
        }
        return new Compiled(NavGoal.composite(members), sacred);
    }

    private static LongSet single(BlockPos pos) {
        LongSet set = new LongOpenHashSet(1);
        set.add(pos.asLong());
        return set;
    }
}
