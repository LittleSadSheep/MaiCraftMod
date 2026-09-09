package org.maiwithu.maicraft.core.pathing.calc;

import org.maiwithu.maicraft.core.pathing.goals.GoalAvoidEntities;
import org.maiwithu.maicraft.core.pathing.settings.NavSettings;
import org.maiwithu.maicraft.core.pathing.moves.ActionCosts;
import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 集中描述导航走到哪里算到达：精确格、某个高度、目标附近、方块旁边，或远离威胁。
 * 这里判断的是脚所在的导航格；玩家真实距离、视线、能否实际点击，仍由任务执行器继续检查。
 * 不同目标还提供搜索估价和一个代表位置；代表位置不一定是应站的位置，例如避险目标的代表点在威胁附近。
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

    /**
     * 判断路线是否可以在这个脚位格结束；不在这里移动玩家或验证实际交互。
     */
    boolean isAt(BlockPos feet);

    /**
     * 给搜索比较候选位置的估计分数。部分宽松到达目标仍朝中心估价，避险还会加入惩罚，因此这里没有统一的最短路下界保证。
     */
    double heuristic(BlockPos from);

    /**
     * 返回用于定位、诊断和发现目标移动的代表点；单高度目标的 X/Z 为零，并不表示要求玩家去世界原点。
     */
    BlockPos center();

    /**
     * 把类型和具体要求变成可比较的值；位置相同但半径或威胁变化时，也能认出目标已经不同。
     */
    default SemanticFingerprint semanticFingerprint() {
        return SemanticFingerprint.of(this);
    }

    /** Immutable value key used to compare two independently rebuilt goal snapshots. */
    record SemanticFingerprint(String kind, List<?> parameters) {
        public SemanticFingerprint {
            kind = Objects.requireNonNull(kind, "kind");
            parameters = List.copyOf(parameters);
        }

        public static SemanticFingerprint of(NavGoal goal) {
            Objects.requireNonNull(goal, "goal");
            if (goal instanceof Exact g) {
                return key("exact", g.goal.asLong());
            }
            if (goal instanceof Column g) {
                return key("column", g.x, g.z, g.radius);
            }
            if (goal instanceof YLevel g) {
                return key("y_level", g.level);
            }
            if (goal instanceof Near g) {
                return key("near", g.goal.asLong(), g.radius);
            }
            if (goal instanceof Ring g) {
                return key("ring", g.goal.asLong(), g.inner, g.outer);
            }
            if (goal instanceof NearGround g) {
                return key("near_ground", g.goal.asLong(), g.radius, g.verticalTolerance);
            }
            if (goal instanceof Adjacent g) {
                return key("adjacent", g.goal.asLong());
            }
            if (goal instanceof MineStance g) {
                return key("mine_stance", g.ore.asLong());
            }
            if (goal instanceof GetToBlock g) {
                return key("get_to_block", g.goal.asLong());
            }
            if (goal instanceof Composite g) {
                return key("composite", multiset(g.members.stream()
                        .map(NavGoal::semanticFingerprint).toList()));
            }
            if (goal instanceof MineColumn g) {
                return key("mine_column", g.ore.asLong(), g.maxBelow);
            }
            if (goal instanceof Avoid g) {
                return key("avoid", g.penaltyFactor, multiset(g.threats));
            }
            if (goal instanceof ApproachAvoiding g) {
                return key("approach_avoiding", g.approach.semanticFingerprint(),
                        g.penaltyFactor, multiset(g.threats));
            }
            if (goal instanceof RunAway g) {
                return key("run_away", g.from.asLong(), g.maintainY);
            }
            // There is one legacy anonymous goal whose only varying parameter is its center.
            // Future custom goals with additional parameters must override semanticFingerprint().
            // 无法识别的自定义目标默认只比较类名和代表位置；有其他会变化的要求时，应由自定义实现提供自己的比较值。
            return key("custom:" + goal.getClass().getName(), goal.center().asLong());
        }

        private static SemanticFingerprint key(String kind, Object... parameters) {
            return new SemanticFingerprint(kind, List.of(parameters));
        }

        /** Composite/avoid semantics are order-insensitive but duplicate-sensitive. */
        private static <T> Map<T, Integer> multiset(List<T> values) {
            Map<T, Integer> counts = new HashMap<>();
            for (T value : values) {
                counts.merge(value, 1, Integer::sum);
            }
            return Map.copyOf(counts);
        }
    }

    // ---- the shared octile + vertical point bound ----

    /**
     * 按横向直走／斜走以及上下高差估算到中心点的费用；这个帮助方法自身不知道目标允许多大的到达范围。
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
        return new NearGround(pos, radius, 1);
    }

    static NavGoal nearGround(BlockPos pos, double radius, double verticalTolerance) {
        return new NearGround(pos, radius, verticalTolerance);
    }

    static NavGoal column(int x, int z, double radius) {
        return new Column(x, z, radius);
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

    /**
     * 脚位格的三轴必须与指定格相同。实际身体是否站稳由调用者检查。
     */
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

    /**
     * 只要求进入指定 X/Z 的水平范围，不限制高度；未知高度的普通移动使用这种目标。
     */
    final class Column implements NavGoal {
        public final int x;
        public final int z;
        public final double radius;

        Column(int x, int z) {
            this(x, z, 0);
        }

        Column(int x, int z, double radius) {
            this.x = x;
            this.z = z;
            this.radius = radius;
        }

        @Override public boolean isAt(BlockPos feet) {
            double dx = (double) feet.getX() - x, dz = (double) feet.getZ() - z;
            return dx * dx + dz * dz <= radius * radius;
        }

        @Override public double heuristic(BlockPos from) {
            double dx = Math.abs(x - from.getX());
            double dz = Math.abs(z - from.getZ());
            return Math.max(0, Math.min(dx, dz) * SQRT_2 + Math.abs(dx - dz) - radius * SQRT_2)
                    * COST_HEURISTIC;
        }

        @Override public BlockPos center() {
            return new BlockPos(x, 0, z);   // y irrelevant — goal is XZ-only
        }
    }

    /**
     * 只要求脚位达到指定高度，X/Z 可以不同。
     */
    final class YLevel implements NavGoal {
        public final int level;

        YLevel(int level) {
            this.level = level;
        }

        @Override public boolean isAt(BlockPos feet) {
            return feet.getY() == level;
        }

        @Override public double heuristic(BlockPos from) {
            int cy = from.getY();
            if (cy > level) return DESCEND_ONE_BLOCK * (cy - level);
            if (cy < level) return (level - cy) * JUMP_ONE_BLOCK;
            return 0.0;
        }

        @Override public BlockPos center() {
            return new BlockPos(0, level, 0);   // x/z irrelevant — goal is Y-only
        }
    }

    /**
     * 脚位进入目标周围的三维球体就算到达；搜索仍按中心点排序。
     */
    final class Near implements NavGoal {
        public final BlockPos goal;
        public final double radius;
        public final double radiusSqr;

        Near(BlockPos pos, double radius) {
            this.goal = pos.immutable();
            this.radius = radius;
            this.radiusSqr = radius * radius;
        }

        @Override public boolean isAt(BlockPos feet) {
            return feet.distSqr(goal) <= radiusSqr;
        }

        @Override public double heuristic(BlockPos from) {
            // 到达时允许进圈即可，搜索排序仍指向中心；这是当前为了稳定部分路线而保留的选择，不保证估值总小于剩余最短费用。
            return pointBound(goal, from);
        }

        @Override public BlockPos center() {
            return goal;
        }
    }

    /**
     * 只看水平距离，要求既不过近也不过远；内圈不小于外圈时，当前实现把内圈改为零。
     */
    final class Ring implements NavGoal {
        public final BlockPos goal;
        public final double inner;
        public final double outer;

        Ring(BlockPos pos, double inner, double outer) {
            this.goal = pos.immutable();
            this.outer = outer;
            this.inner = inner >= outer ? 0.0 : inner;
        }

        private double horizontal(BlockPos p) {
            double dx = p.getX() - goal.getX();
            double dz = p.getZ() - goal.getZ();
            return Math.sqrt(dx * dx + dz * dz);
        }

        @Override public boolean isAt(BlockPos feet) {
            double d = horizontal(feet);
            return d >= inner && d <= outer;
        }

        /** 到带的距离,不是到中心的距离 —— 太近往外、太远往里,两侧都有梯度。 */
        @Override public double heuristic(BlockPos from) {
            double d = horizontal(from);
            double gap = d < inner ? inner - d : d > outer ? d - outer : 0.0;
            return gap * NavSettings.get().costHeuristic;
        }

        @Override public BlockPos center() {
            return goal;
        }
    }

    /**
     * 水平进入半径范围，并且与指定高度相差不超过允许值；它本身不读取地面支撑。
     */
    final class NearGround implements NavGoal {
        public final BlockPos goal;
        public final double radius;
        public final double radiusSqr;
        public final double verticalTolerance;

        NearGround(BlockPos pos, double radius, double verticalTolerance) {
            this.goal = pos.immutable();
            this.radius = radius;
            this.radiusSqr = radius * radius;
            this.verticalTolerance = verticalTolerance;
        }

        @Override public boolean isAt(BlockPos feet) {
            int dy = feet.getY() - goal.getY();
            if (Math.abs((double) dy) > verticalTolerance) return false;
            double dx = feet.getX() - goal.getX();
            double dz = feet.getZ() - goal.getZ();
            return dx * dx + dz * dz <= radiusSqr;
        }

        @Override public double heuristic(BlockPos from) {
            // Full point bound, radius not subtracted — same deliberate slight
            // inadmissibility as near(): aim at the centre for stable ordering.
            return pointBound(goal, from);
        }

        @Override public BlockPos center() {
            return goal;
        }
    }

    /**
     * 水平紧邻目标一格，上下可相差一格；不允许只在同一列上下接近。
     */
    final class Adjacent implements NavGoal {
        public final BlockPos goal;

        Adjacent(BlockPos target) {
            this.goal = target.immutable();
        }

        @Override public boolean isAt(BlockPos feet) {
            int dx = Math.abs(feet.getX() - goal.getX());
            int dz = Math.abs(feet.getZ() - goal.getZ());
            int dy = Math.abs(feet.getY() - goal.getY());
            return dx + dz == 1 && dy <= 1;
        }

        @Override public double heuristic(BlockPos from) {
            // One step + one jump of slack vs the point bound.
            return Math.max(0.0, pointBound(goal, from)
                    - COST_HEURISTIC - JUMP_ONE_BLOCK);
        }

        @Override public BlockPos center() {
            return goal;
        }
    }

    /** {@link #getToBlock} 的产物:身高修正的 Manhattan 贴脸邻域。 */
    /**
     * 提供侧面或下方的挖掘脚位范围，排除站在矿石上方；实际挖掘距离和视线仍由矿工检查。
     */
    final class MineStance implements NavGoal {
        public final BlockPos ore;

        MineStance(BlockPos ore) {
            this.ore = ore.immutable();
        }

        @Override public boolean isAt(BlockPos feet) {
            int dy = feet.getY() - ore.getY();
            if (dy > 0) {
                return false;   // 踩在它头上:那是自己的地板
            }
            int dx = Math.abs(feet.getX() - ore.getX());
            int dz = Math.abs(feet.getZ() - ore.getZ());
            // 两格高的身体:脚在下方时头那格也算贴着,所以负的 dy 折一格
            int bodyDy = dy + 1 <= 0 ? dy + 1 : 0;
            return dx + dz + Math.abs(bodyDy) <= 1;
        }

        @Override public double heuristic(BlockPos from) {
            return Math.max(0.0, pointBound(ore, from) - COST_HEURISTIC - JUMP_ONE_BLOCK);
        }

        @Override public BlockPos center() {
            return ore;
        }

        @Override public String toString() {
            return "MineStance{" + ore.toShortString() + "}";
        }
    }

    // 允许从旁边、上方或下方接近方块，把角色身体高度计入格距；这里只描述接近范围。
    final class GetToBlock implements NavGoal {
        public final BlockPos goal;

        GetToBlock(BlockPos target) {
            this.goal = target.immutable();
        }

        @Override public boolean isAt(BlockPos feet) {
            int dx = Math.abs(feet.getX() - goal.getX());
            int dz = Math.abs(feet.getZ() - goal.getZ());
            int dy = feet.getY() - goal.getY();
            int bodyDy = dy < 0 ? dy + 1 : dy;
            return dx + dz + Math.abs(bodyDy) <= 1;
        }

        @Override public double heuristic(BlockPos from) {
            // One step + one jump of slack vs the point bound (same slack as
            // adjacent(): any accepted cell is at most that much off-centre).
            return Math.max(0.0, pointBound(goal, from)
                    - COST_HEURISTIC - JUMP_ONE_BLOCK);
        }

        @Override public BlockPos center() {
            return goal;
        }
    }

    /**
     * 一组候选中满足任意一个就算到达，搜索取最有希望的一项；平均位置只用于定位和诊断。
     */
    final class Composite implements NavGoal {
        public final java.util.List<NavGoal> members;
        private final BlockPos centroid;

        Composite(java.util.List<NavGoal> goals) {
            java.util.List<NavGoal> gs = java.util.List.copyOf(goals);
            if (gs.isEmpty()) {
                throw new IllegalArgumentException("composite goal needs at least one member");
            }
            // Centre = centroid of the members, NOT gs.get(0). The member list is
            // rebuilt every tick (ores re-sorted by distance as the body moves), so a
            // first-member centre would jitter and trip PlayerNav's goal-moved replan
            // every tick. The centroid only shifts when the SET changes (an ore mined
            // or found), which is what "the goal moved" should actually mean.
            long sx = 0, sy = 0, sz = 0;
            for (NavGoal g : gs) {
                BlockPos c = g.center();
                sx += c.getX();
                sy += c.getY();
                sz += c.getZ();
            }
            this.members = gs;
            this.centroid = new BlockPos(
                    (int) (sx / gs.size()), (int) (sy / gs.size()), (int) (sz / gs.size()));
        }

        @Override public boolean isAt(BlockPos feet) {
            for (NavGoal g : members) {
                if (g.isAt(feet)) return true;
            }
            return false;
        }

        @Override public double heuristic(BlockPos from) {
            double min = Double.MAX_VALUE;
            for (NavGoal g : members) {
                min = Math.min(min, g.heuristic(from));
            }
            return min;
        }

        @Override public BlockPos center() {
            return centroid;
        }
    }

    /**
     * 脚位必须在矿石正下方同一列，最多低指定格数；不包含侧面站位。
     */
    final class MineColumn implements NavGoal {
        public final BlockPos ore;
        public final int maxBelow;

        MineColumn(BlockPos ore, int maxBelow) {
            this.ore = ore.immutable();
            this.maxBelow = maxBelow;
        }

        @Override public boolean isAt(BlockPos feet) {
            return feet.getX() == ore.getX() && feet.getZ() == ore.getZ()
                    && feet.getY() <= ore.getY() && feet.getY() >= ore.getY() - maxBelow;
        }

        @Override public double heuristic(BlockPos from) {
            double dx = Math.abs(ore.getX() - from.getX());
            double dz = Math.abs(ore.getZ() - from.getZ());
            double horizontal = (Math.min(dx, dz) * SQRT_2 + Math.abs(dx - dz))
                    * COST_HEURISTIC;
            // Feet anywhere in {o.y .. o.y-maxBelow} count as arrived: fold that
            // band to zero.
            int yDiff = from.getY() - ore.getY();
            int adj = yDiff >= 0 ? yDiff : Math.min(0, yDiff + maxBelow);
            // Above the goal (adj>0) we DESCEND to it,
            // below it (adj<0) we ASCEND. (The old mine() had these two swapped,
            // overestimating descents — an inadmissible heuristic.)
            double vertical = adj > 0
                    ? adj * DESCEND_ONE_BLOCK
                    : -adj * JUMP_ONE_BLOCK;
            return horizontal + vertical;
        }

        @Override public BlockPos center() {
            return ore;
        }
    }

    /**
     * 要求离每个威胁都足够远，复用 GoalAvoidEntities 的脱身条件和远近惩罚。
     */
    final class Avoid implements NavGoal {
        public final GoalAvoidEntities engine;
        public final double penaltyFactor;
        public final List<GoalAvoidEntities.Threat> threats;
        private final BlockPos centroid;

        Avoid(double penaltyFactor, List<GoalAvoidEntities.Threat> threats) {
            this.penaltyFactor = penaltyFactor;
            this.threats = List.copyOf(threats);
            this.engine = new GoalAvoidEntities(penaltyFactor,
                    this.threats.toArray(GoalAvoidEntities.Threat[]::new));
            double x = 0.0;
            double y = 0.0;
            double z = 0.0;
            for (GoalAvoidEntities.Threat t : this.threats) {
                x += t.x();
                y += t.y();
                z += t.z();
            }
            int n = this.threats.size();
            this.centroid = BlockPos.containing(x / n, y / n, z / n);
        }

        @Override public boolean isAt(BlockPos feet) {
            return engine.isInGoal(feet.getX(), feet.getY(), feet.getZ());
        }

        @Override public double heuristic(BlockPos fromPos) {
            return engine.heuristic(fromPos.getX(), fromPos.getY(), fromPos.getZ());
        }

        /** 威胁群的重心:它一挪动就触发重规划,快照因此不会用旧太久。 */
        @Override public BlockPos center() {
            return centroid;
        }
    }

    /**
     * 既要达到原目标，又要离威胁足够远；路线估价含避险惩罚，但判断是否正在接近时只看原目标。
     */
    final class ApproachAvoiding implements NavGoal {
        public final NavGoal approach;
        public final GoalAvoidEntities repulsion;
        public final double penaltyFactor;
        public final List<GoalAvoidEntities.Threat> threats;

        ApproachAvoiding(NavGoal approach, double penaltyFactor,
                         List<GoalAvoidEntities.Threat> threats) {
            this.approach = approach;
            this.penaltyFactor = penaltyFactor;
            this.threats = List.copyOf(threats);
            this.repulsion = new GoalAvoidEntities(penaltyFactor,
                    this.threats.toArray(GoalAvoidEntities.Threat[]::new));
        }

        /** 走到了,而且脚下这一格不在任何一只的危险半径里。见 GoalApproachAvoiding。 */
        @Override public boolean isAt(BlockPos feet) {
            return approach.isAt(feet)
                    && repulsion.isInGoal(feet.getX(), feet.getY(), feet.getZ());
        }

        @Override public double heuristic(BlockPos fromPos) {
            return approach.heuristic(fromPos)
                    + repulsion.heuristic(fromPos.getX(), fromPos.getY(), fromPos.getZ());
        }

        /** 进度只看走没走近目标。危险场是"值不值得走那条路",不是"走到哪了"。 */
        @Override public double progressHeuristic(BlockPos fromPos) {
            return approach.progressHeuristic(fromPos);
        }

        /** 跟着要去的那个目标走:它一挪动就触发重规划。 */
        @Override public BlockPos center() {
            return approach.center();
        }
    }

    /**
     * 持续向远离起点的方向搜索，并偏好保持指定高度；自身永不宣告到达，需要调用者决定何时停止。
     */
    final class RunAway implements NavGoal {
        public final BlockPos from;
        public final int maintainY;

        RunAway(BlockPos from, int maintainY) {
            this.from = from.immutable();
            this.maintainY = maintainY;
        }

        @Override public boolean isAt(BlockPos feet) {
            return false;   // never done — keep exploring outward
        }

        @Override public double heuristic(BlockPos fromPos) {
            // Run-away heuristic: −(octile×weight) — negated so farther = lower h
            // = preferred — then blended with the y-hold term:
            // min*0.6 + yLevelTerm*1.5.
            double dx = Math.abs(from.getX() - fromPos.getX());
            double dz = Math.abs(from.getZ() - fromPos.getZ());
            double xz = (Math.min(dx, dz) * SQRT_2 + Math.abs(dx - dz))
                    * COST_HEURISTIC;
            double min = -xz;
            int cy = fromPos.getY();
            double yLevel = cy > maintainY ? (cy - maintainY) * DESCEND_ONE_BLOCK
                    : cy < maintainY ? (maintainY - cy) * JUMP_ONE_BLOCK : 0.0;
            return min * 0.6 + yLevel * 1.5;
        }

        @Override public BlockPos center() {
            return from;
        }
    }
}
