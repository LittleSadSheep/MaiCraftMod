package org.maiwithu.maicraft.core.pathing.calc;

import org.maiwithu.maicraft.core.pathing.goals.GoalAvoidEntities;
import org.maiwithu.maicraft.core.pathing.settings.NavSettings;
import org.maiwithu.maicraft.core.pathing.moves.ActionCosts;
import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * What the search is trying to reach. Until
 * now arrival semantics were written five times in five places (the search's
 * tolerance hack, goto's radius, three hand-rolled "stand next to X"
 * pickers); a goal object makes the search terminate, the heuristic aim and
 * the caller assert against the SAME definition.
 *
 * <p>Node-domain only: {@link #isAt} judges feet CELLS during the search.
 * Live-entity arrival predicates (exact doubles, reach distances) remain the
 * task layer's business — they answer a different question ("is my body close
 * enough") than the search's ("may this node end the path").
 *
 * <p>工厂产物均为具名嵌套类(参数以 final 字段暴露):桥接层按类型识别
 * 工厂产物并直映射到新内核目标族;匿名实现(任务层手写的自定义目标)
 * 无法识别,走通用包装。行为与先前的匿名类逐字相同。
 */
public interface NavGoal {

    // ---- 启发式常量(数值与内核成本表同源) ----

    /** 对角步的水平距离系数。 */
    double SQRT_2 = Math.sqrt(2.0);
    /** 加权 A* 沿用的乐观单格成本(≈疾跑单格)。 */
    double COST_HEURISTIC = 3.563;
    /** 升一格的乐观成本(跳跃抛物线差)。 */
    double JUMP_ONE_BLOCK = ActionCosts.JUMP_ONE_BLOCK_COST;
    /** 降一格的乐观成本(坠落两格耗时之半)。 */
    double DESCEND_ONE_BLOCK = ActionCosts.FALL_N_BLOCKS_COST[2] / 2.0;

    /** May a path legitimately end at this feet cell? */
    boolean isAt(BlockPos feet);

    /** Admissible lower bound (ticks) on the remaining cost from {@code from}. */
    double heuristic(BlockPos from);

    /** Representative position — diagnostics, goal-moved checks, locate math. */
    BlockPos center();

    // ---- the shared octile + vertical point bound ----

    /**
     * Octile horizontal distance (× walk cost) plus a vertical term — the
     * admissible point-to-point bound every concrete goal builds on. Downward
     * must cost &gt; 0 (see {@link #DESCEND_ONE_BLOCK}): with a free
     * down-direction every node straight above a deep target scored h == 0
     * and partial paths collapsed to the start node.
     *
     * <p>Known weight inconsistency (deliberate legacy parity): the weighted-A*
     * factor {@code COST_HEURISTIC} (3.563) is folded into the XZ term only,
     * while the vertical terms ({@code JUMP_ONE_BLOCK} / {@code DESCEND_ONE_BLOCK})
     * are unweighted — the split the verified behaviour was tuned on. Normalizing
     * the weights is a flagged follow-up, not something to "fix" in passing.
     *
     * <p>Adapter contract: {@code heuristic()} / {@code isAt()} implementations
     * must not retain the {@code BlockPos} argument (adapters may reuse a
     * mutable cursor across calls); read x/y/z (or compare/measure) and return.
     */
    static double pointBound(BlockPos goal, BlockPos from) {
        double dx = Math.abs(goal.getX() - from.getX());
        double dz = Math.abs(goal.getZ() - from.getZ());
        // Horizontal: (diagonal·√2 + straight) × COST_HEURISTIC. COST_HEURISTIC
        // (≈ sprint cost) IS the per-block weight here — the heap key adds no
        // further multiplier (the weight is folded into the heuristic itself).
        double horizontal = (Math.min(dx, dz) * SQRT_2 + Math.abs(dx - dz))
                * COST_HEURISTIC;
        // Vertical: up costs JUMP per block, down costs DESCEND (fall[2]/2).
        int dy = goal.getY() - from.getY();
        double vertical = dy > 0
                ? dy * JUMP_ONE_BLOCK
                : -dy * DESCEND_ONE_BLOCK;
        return horizontal + vertical;
    }

    // ---- factories ----

    /** Exactly this feet cell. */
    static NavGoal exact(BlockPos pos) {
        return new Exact(pos);
    }

    /**
     * Reach the {@code (x, z)} column at ANY height.
     * The heuristic is the pure horizontal
     * octile term (no vertical), so the search heads for the column and stops at
     * whatever ground exists there. This is the "go to a location" goal: the
     * caller's Y is irrelevant, so a wrong/guessed Y can never make it unreachable.
     */
    static NavGoal column(int x, int z) {
        return new Column(x, z);
    }

    /**
     * Reach a Y level at ANY X/Z: "change elevation to this height" (climb to the surface,
     * descend to a mining depth). Heuristic is the pure vertical term — up costs
     * {@link #JUMP_ONE_BLOCK} per block, down {@link #DESCEND_ONE_BLOCK}.
     */
    static NavGoal yLevel(int level) {
        return new YLevel(level);
    }

    /**
     * 「离目标还有多远」——<b>卡住检测</b>专用的量尺,缺省就是估价本身。
     *
     * <p>估价里可以掺着别的项(比如危险场),而那些项在原地不动时也会随周围的怪进进出出而
     * 大幅起落。拿它当进度,噪声会被读成"在前进",卡住永远检测不出来。进度只该问一件事:
     * 她离要去的地方近了没有。
     */
    default double progressHeuristic(BlockPos from) {
        return heuristic(from);
    }

    /**
     * 环形站位:离 {@code pos} 在 {@code [inner, outer]} 之间。
     *
     * <p>它给的不只是到达条件,更是<b>估价</b>:到<b>带</b>的距离,两侧都朝带递减。
     * 用球形邻域的话估价只会说"越靠近中心越好",而弓那一套的合格落点全在远处 ——
     * 搜索于是往怪身上挖,{@code bestSoFar} 挑出来的正是贴脸那一格。
     */
    static NavGoal ring(BlockPos pos, double inner, double outer) {
        return new Ring(pos, inner, outer);
    }

    /** Any feet cell within {@code radius} (Euclidean, blocks) of {@code pos}. */
    static NavGoal near(BlockPos pos, double radius) {
        return new Near(pos, radius);
    }

    /**
     * Any feet cell within {@code radius} (Euclidean, blocks) of {@code pos}
     * HORIZONTALLY, with the feet at the target's height ±1. This is the
     * vicinity goal for chasing/following things that live on the ground: unlike
     * the raw 3D {@link #near} sphere it admits no elevated cell, so "place a
     * scaffold, stand on it, count as arrived" is not a satisfying completion —
     * the geometry that once made an approach finish by frantically pillaring
     * beside its target.
     */
    static NavGoal nearGround(BlockPos pos, double radius) {
        return new NearGround(pos, radius);
    }

    /**
     * Any feet cell horizontally adjacent to {@code target} (±1 step on one
     * axis), at the target's height ±1 — "stand next to this block so you can
     * work on it". The standability of the ending cell is the graph's own
     * guarantee (only occupiable cells become nodes).
     */
    static NavGoal adjacent(BlockPos target) {
        return new Adjacent(target);
    }

    /**
     * Any feet cell TOUCHING {@code target}: beside it, on top of it, up to two
     * below it (the two-block body still reaches its underside) — or in it, if
     * the cell is enterable. Membership is a Manhattan bound with the body-height
     * correction: {@code |dx| + |dy'| + |dz| <= 1} where {@code dy' = dy < 0 ?
     * dy+1 : dy}. This is the "get to this block to use it" goal — the target
     * cell itself stays untouched (a chest, a crafting table), the path ends on
     * whichever neighbouring cell the graph can actually stand on.
     */
    static NavGoal getToBlock(BlockPos target) {
        return new GetToBlock(target);
    }

    /**
     * 挖它的站位:<b>身体贴着它,但不踩在它头上</b>。
     *
     * <p>贴着 = 它是脚那格或头那格的邻格,所以中间<b>按定义没有东西</b> —— 不必射线也知道
     * 打得到。这正是挖掘那一侧"眼睛拉得出一条不被挡的射线"的下界近似,而射线太贵、不能
     * 塞进 {@code isAt}(每展开一个节点跑一次)。
     *
     * <p><b>踩在它头上必须排除</b>:脚下那一格是她自己的地板,挖掘层永远不碰
     * ({@code MineCompanionTask.reachableTarget} 里的 {@code ore.equals(support)}
     * 那一条)。收进来就是死循环 —— 导航说"你已经站到位了",挖掘说"这格不能挖",
     * 于是拆导航、重规划、脚下还是那格,实测能一直转下去。
     */
    static NavGoal mineStance(BlockPos ore) {
        return new MineStance(ore);
    }

    /**
     * Any of several goals. Satisfied by reaching
     * ANY member; the heuristic is the minimum over members, so a single A* search
     * naturally heads for the CLOSEST reachable one. This is how mining targets a
     * whole field of ore at once instead of greedily picking the nearest (which is
     * often the one walled in and unreachable).
     */
    static NavGoal composite(java.util.List<NavGoal> goals) {
        return new Composite(goals);
    }

    /**
     * Stand in the ore's own column to mine it — a family of mining stance
     * goals, parameterised by how far BELOW the ore the feet may be:
     * <ul>
     *   <li>{@code maxBelow == 0} → feet exactly at the ore;</li>
     *   <li>{@code maxBelow == 1} → feet at the ore or one below;</li>
     *   <li>{@code maxBelow == 2} → feet at the ore, one, or two below.</li>
     * </ul>
     * Which one a given ore gets is decided by {@code MineCompanionTask.coalesce}:
     * the bottom
     * of a vertical run gets the exact ({@code maxBelow == 0}) stance so the body
     * mines it in place rather
     * than tunnelling under it. The vertical term in the heuristic folds the whole
     * accepted band to zero cost.
     */
    static NavGoal mineColumn(BlockPos ore, int maxBelow) {
        return new MineColumn(ore, maxBelow);
    }

    /** Loosest-stance shorthand (feet at the ore, one, or two below). */
    static NavGoal mine(BlockPos ore) {
        return mineColumn(ore, 2);
    }

    /**
     * Get as FAR as possible from {@code from} while holding a y-level —
     * used for branch mining: when no ore is
     * known, head out along the level to dig fresh tunnel and expose more. Never
     * "arrived" (isAt always false) so the search returns a best-effort partial that
     * walks outward; the next replan continues exploring.
     */
    static NavGoal runAway(BlockPos from, int maintainY) {
        return new RunAway(from, maintainY);
    }

    /**
     * 躲开一组威胁,站到每一只的危险半径之外。与 {@link #runAway} 的两点差别:
     * <b>它认得完所有威胁</b>(runAway 的估价只看最近那一个,两只怪一左一右时会直穿其中一只),
     * 而且<b>它有终点</b>——出了半径就停,不必在上层每 tick 手动喊停。
     *
     * <p>威胁坐标是<b>快照</b>。实体走动由重规划跟上({@code PlayerNav} 比对 {@link #center()}
     * 的位移),不由估价函数实时跟随——搜索途中变化的估价会让 A* 失去最优性保证。
     *
     * @param penaltyFactor 势场强度,见 {@link GoalAvoidEntities}
     */
    static NavGoal avoid(double penaltyFactor, List<GoalAvoidEntities.Threat> threats) {
        return new Avoid(penaltyFactor, threats);
    }

    /**
     * 走到一个目标跟前,<b>路上绕开别的敌对生物</b>。到达要两项都点头:走到了,而且脚下这一格
     * 不在任何一只的危险半径里。
     *
     * <p>调用方必须把<b>要去的那个目标本身</b>也放进 {@code threats}——它当然也会打她,
     * 由它自己的危险半径把她顶在够不着的地方,中间那条缝就是拉扯的位置。
     *
     * @return {@code threats} 为空时直接返回 {@code approach},不白包一层
     */
    static NavGoal approachAvoiding(NavGoal approach, double penaltyFactor,
                                    List<GoalAvoidEntities.Threat> threats) {
        return threats.isEmpty() ? approach : new ApproachAvoiding(approach, penaltyFactor, threats);
    }

    // ---- 工厂产物(具名,参数可读;行为与原匿名类逐字一致) ----

    /** {@link #exact} 的产物:三轴全等才到达。 */
    final class Exact implements NavGoal {
        public final BlockPos goal;

        Exact(BlockPos pos) {
            this.goal = pos.immutable();
        }

        @Override public boolean isAt(BlockPos feet) {
            return feet.equals(goal);
        }

        @Override public double heuristic(BlockPos from) {
            return pointBound(goal, from);
        }

        @Override public BlockPos center() {
            return goal;
        }
    }

    /** {@link #column} 的产物:任意高度的 XZ 列。 */
    final class Column implements NavGoal {
        public final int x;
        public final int z;

        Column(int x, int z) {
            this.x = x;
            this.z = z;
        }

        @Override public boolean isAt(BlockPos feet) {
            return feet.getX() == x && feet.getZ() == z;
        }

        @Override public double heuristic(BlockPos from) {
