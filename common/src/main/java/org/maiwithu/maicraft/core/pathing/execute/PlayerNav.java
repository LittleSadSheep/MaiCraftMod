package org.maiwithu.maicraft.core.pathing.execute;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.maiwithu.maicraft.core.Constants;
import org.maiwithu.maicraft.core.pathing.astar.Favoring;
import org.maiwithu.maicraft.core.pathing.baritone.EmbeddedBaritoneNavigator;
import org.maiwithu.maicraft.core.pathing.astar.NavPath;
import org.maiwithu.maicraft.core.pathing.astar.PathCalcResult;
import org.maiwithu.maicraft.core.pathing.bridge.ContextFactory;
import org.maiwithu.maicraft.core.pathing.bridge.PoolSearchDispatcher;
import org.maiwithu.maicraft.core.pathing.bridge.SearchHandle;
import org.maiwithu.maicraft.core.pathing.calc.NavGoal;
import org.maiwithu.maicraft.core.pathing.execute.PathExecutor;
import org.maiwithu.maicraft.core.pathing.execute.PathingCore;
import org.maiwithu.maicraft.core.pathing.goal.GoalCompiler;
import org.maiwithu.maicraft.core.pathing.goals.Goal;
import org.maiwithu.maicraft.core.pathing.moves.CalculationContext;
import org.maiwithu.maicraft.core.pathing.moves.TerrainPermit;
import org.maiwithu.maicraft.core.pathing.settings.NavSettings;
import org.maiwithu.maicraft.core.pathing.util.NavProfiler;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.entity.InputDriver;
import net.minecraft.client.player.LocalPlayer;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.core.BlockPos;

/**
 * 任务层的导航正门:把一份编译好的导航契约({@link GoalCompiler.Compiled})
 * 交给段规划状态机 {@link PathingCore} 驱动,对外维持三态合同
 * ({@link Status}):
 * <ul>
 *   <li>caller 的 {@code reached} 谓词永远最先判、判真即 ARRIVED;</li>
 *   <li>搜索目标在脚下即满足(无路可走)时稳定持续返回 ARRIVED——任务层
 *       据此判"到位却不满足"(STANCE_DUD)并自行处置,绝不衰变成一跳
 *       而过的 target lost;</li>
 *   <li>目标中心移动超过 2 格重根;执行失败由状态机自动重搜,本层只做
 *       放弃判定的记账(见 {@link #accountReplan})。</li>
 * </ul>
 *
 * <p>引擎自身无限重试;"何时放弃"是任务过程层的语义,住在这里:连续
 * {@link #MAX_STALLED_REPLANS} 次失败重规划目标启发值无 ≥{@link #REPLAN_PROGRESS_EPS_H}
 * 改善 → FAILED(BOXED_IN)。只要身体仍在真实接近目标,重规划次数本身不构成失败。
 *
 * <p>sacred(自身目标格,不可挖不可埋)/ deniedPlace(执行层证明放不上
 * 的格)/ task-scoped forbidden body cells 等语义开关穿透本导航建的每一个
 * {@link CalculationContext}:搜索用冻结快照、执行期复核用活世界,同一
 * 套开关同一把尺。
 *
 * <p><b>不动地形的路找不到时</b>(许可为 PRESERVE 的首段 NO_PATH),不立刻终局:
 * 用可改地形的上下文只搜不走地探一次,把那条路<b>会</b>挖什么、放什么列成清单
 * ({@link TerrainBill})交给任务层——裁决是 {@link FailureType#TERRAIN_BLOCKED},
 * 模型读清单决定要不要授权(re-send with may_alter_terrain)。引擎不替它猜
 * "这是不是玩家的房子":木板玻璃在地表是房子,石头泥土在地下不是,这个判断归模型。
 */
public final class PlayerNav {

    public enum Status { RUNNING, ARRIVED, FAILED }

    /** 真正的放弃条件:连续这么多次失败重规划都没有真实接近目标。 */
    private static final int MAX_STALLED_REPLANS = 6;
    /**
     * 算作"真实接近"的启发值降幅——约等于按目标自己的尺度前进一格
     * (平走约 3.56 加权、爬升约 3.16、下降约 3.89)。进度以目标启发
     * 函数在脚下的取值度量,不用到 center() 的欧氏距离:yLevel 目标的
     * center 是 (0, level, 0),欧氏距离被无意义的水平偏移淹没。
     */
    private static final double REPLAN_PROGRESS_EPS_H = 4.0;
    /** 目标中心移动超过该距离平方(2 格)即重根。 */
    private static final double GOAL_MOVED_SQR = 4.0;

    /**
     * 活目标({@link #trackGoal})的<b>重规划节拍</b>(刻)。
     *
     * <p>只按 {@code center()} 位移触发是不够的:危险场那种目标,{@code center()} 只是她要打的
     * 那一只,<b>别的怪走动完全不改变它</b>。而威胁坐标是开路那一刻的快照,于是她躲的是几秒前
     * 那些怪站过的地方——场是对的,读数是旧的。
     *
     * <p>五刻约合怪走一格,短程 A* 一次很快,这个节拍买回来的是"滚动时域真的在滚"。
     */
    private static final int LIVE_GOAL_REPLAN_TICKS = 5;

    private final LocalPlayer player;
    private final Supplier<GoalCompiler.Compiled> compiledSupplier;
    private final BooleanSupplier reached;
    private final ContextProvider contextProvider;
    private final boolean revalidateGoalEachTick;
    /**
     * speed 参数保留在签名上;新执行体系不支持变速(移动全走原版输入
     * 物理),这里把它路由成疾跑门:speed &lt; 1.0 表示"慢速",本导航
     * 的每个 tick 里禁疾跑(见 {@link #tick} 的 allowSprint 包夹)。
     */
    private final boolean sprintAllowed;
    /** New full-engine backend behind the stable task-facing contract. */
    private final EmbeddedBaritoneNavigator embedded;

    /** 段规划状态机:搜索派发、段执行、无缝接段、失败自动重搜全在其内。 */
    private final PathingCore core;

    /**
     * 最近一次拉取的目标的 sacred 格({@code BlockPos.asLong()} 键)。
     * 每次拉目标同步刷新——goal 与 sacred 是同一份契约,不允许分开读。
     */
    private LongSet sacred = LongSets.emptySet();
    /**
     * 执行层证明"无支撑放不上"(NO_SUPPORT)的格,本次导航内累积、
     * 穿进此后每次搜索,确定性重搜才不会反复规划同一个不可能的脚手架。
     *
     * <p>回填点说明:新执行体系里放置失败表现为移动原语 FAILED → 整段
     * 取消重规划,途中不携带"哪一格无支撑"的结构化信息;能拿到该信息
     * 时在 {@link #tick} 的执行失败分支里 {@code deniedPlace.add(...)}。
     * 机制与上下文穿透保留,当前无生产者。
     */
    private final LongOpenHashSet deniedPlace = new LongOpenHashSet();

    /**
     * 搜索目标在脚下即满足、而 caller 的 reached 仍不满足:钉稳 ARRIVED
     * 结论供任务层判 STANCE_DUD(换站位、拉黑该成员),绝不衰变成撒谎
     * 的一跳 target lost。目标真移动时照常重根清位。
     */
    private boolean searchSatisfied;
    /** 最近一次下发给状态机的目标中心(重根判定的基准)。 */
    private BlockPos plannedCenter;

    private int ticksSincePlan;
    private int stalledReplans;
    /** 脚下到过的最优(最低)目标启发值——停滞重规划的量尺。 */
    private double bestGoalH = Double.MAX_VALUE;
    private String failReason = "target unreachable";
    private FailureType failType = FailureType.NO_PATH;
    /** 最近一次执行失败的原因(放弃报告里点名反复失败的动作)。 */
    private String lastExecFailure;
    /** 终局闩:FAILED 一经裁定即稳定持续(failReason 不再被覆写)。 */
    private boolean failedTerminal;
    private boolean stopped;

    /** 最近一次搜索上下文(验尸文案的素材:有无脚手架耗材等)。 */
    private CalculationContext lastSearchContext;

    /**
     * 无路时要不要探"若许改地形则此路"。默认不探:探针要站着等两秒,追怪/跟随这类
     * 活目标的导航等不起,也没人会为了够一只僵尸去拆墙。goto 与接近类交互任务开它——
     * 那里的失败回执是模型下一步决策的依据,清单值这两秒。
     */
    private boolean terrainProbe;
    /** 在飞的"若许改地形则此路"探针搜索;非空时本导航原地等它出结论,不再驱动状态机。 */
    private SearchHandle terraformProbe;
    /** 探针所针对的目标(验尸文案用)。 */
    private NavGoal terraformProbeGoal;

    /** 单格目标:按意图编译(可走格=站上去,占用格=贴脸即到,不吞噬目标)。 */
    public PlayerNav(LocalPlayer player, BlockPos goal, double speed, BooleanSupplier reached) {
        this(player, speed, reached, () -> GoalCompiler.block(player.level(), goal), ContextProvider.DEFAULT);
    }

    public PlayerNav(LocalPlayer player, Supplier<BlockPos> goalSupplier, double speed,
                     BooleanSupplier reached) {
        this(player, speed, reached, () -> {
            BlockPos goal = goalSupplier.get();
            return goal == null ? null : GoalCompiler.block(player.level(), goal);
        }, ContextProvider.DEFAULT);
    }

    public static PlayerNav to(LocalPlayer player, Supplier<GoalCompiler.Compiled> compiled,
                               double speed, BooleanSupplier reached) {
        return to(player, compiled, speed, reached, ContextProvider.DEFAULT);
    }

    public static PlayerNav to(LocalPlayer player, Supplier<GoalCompiler.Compiled> compiled,
                               double speed, BooleanSupplier reached, ContextProvider contextProvider) {
        return new PlayerNav(player, speed, reached, compiled, contextProvider);
    }

    /** Goal suppliers are revalidated on every tick, including task-specific protection cells. */
    public static PlayerNav toRevalidating(LocalPlayer player, Supplier<GoalCompiler.Compiled> compiled,
                                          double speed, BooleanSupplier reached, ContextProvider contextProvider) {
        return to(player, compiled, speed, reached, contextProvider);
    }

    public static PlayerNav toRevalidating(LocalPlayer player, Supplier<GoalCompiler.Compiled> compiled,
                                          double speed, BooleanSupplier reached) {
        return to(player, compiled, speed, reached);
    }

    public static PlayerNav toGoal(LocalPlayer player, Supplier<NavGoal> goals,
                                   double speed, BooleanSupplier reached) {
        return toGoal(player, goals, speed, reached, ContextProvider.DEFAULT);
    }

    public static PlayerNav toGoal(LocalPlayer player, Supplier<NavGoal> goals,
                                   double speed, BooleanSupplier reached, ContextProvider contextProvider) {
        return to(player, bare(goals), speed, reached, contextProvider);
    }

    public static PlayerNav trackGoal(LocalPlayer player, Supplier<NavGoal> goals,
                                      double speed, BooleanSupplier reached) {
        return toGoal(player, goals, speed, reached);
    }

    public static PlayerNav trackGoal(LocalPlayer player, Supplier<NavGoal> goals,
                                      double speed, BooleanSupplier reached, ContextProvider contextProvider) {
        return toGoal(player, goals, speed, reached, contextProvider);
    }

    private static Supplier<GoalCompiler.Compiled> bare(Supplier<NavGoal> goals) {
        return () -> {
            NavGoal goal = goals.get();
            return goal == null ? null : new GoalCompiler.Compiled(goal, LongSets.emptySet());
        };
    }

    private PlayerNav(LocalPlayer player, double speed, BooleanSupplier reached,
                      Supplier<GoalCompiler.Compiled> compiledSupplier, ContextProvider contextProvider) {
        navigator = new EmbeddedBaritoneNavigator(player, compiledSupplier, reached,
                contextProvider == null ? ContextProvider.DEFAULT : contextProvider, speed >= 1.0);
    }

    public PlayerNav withTerrainProbe() {
        navigator.withTerrainProbe();
        return this;
    }

    public interface ContextProvider {
        /** 缺省:只走不改。接近类动作全部用它,忘了指定也只会更保守。 */
        ContextProvider DEFAULT = of(TerrainPermit.PRESERVE);
        /** 可改地形:挖矿、施工,以及模型显式授权的 goto。 */
        ContextProvider TERRAFORM = of(TerrainPermit.TERRAFORM);

        static ContextProvider of(TerrainPermit permit) {
            return new ContextProvider() {
                @Override
                public CalculationContext forSearch(LocalPlayer player, LongSet sacred,
                                                    LongSet deniedPlace, LongSet forbiddenBodyCells) {
                    return ContextFactory.forSearch(player, sacred, deniedPlace,
                            forbiddenBodyCells, permit, CalculationContext::new);
                }

                @Override
                public CalculationContext forExecution(LocalPlayer player, LongSet sacred,
                                                       LongSet deniedPlace, LongSet forbiddenBodyCells) {
                    return ContextFactory.forExecution(player, sacred, deniedPlace,
                            forbiddenBodyCells, permit, CalculationContext::new);
                }

                @Override
                public TerrainPermit permit() {
                    return permit;
                }
            };
        }

        CalculationContext forSearch(LocalPlayer player, LongSet sacred, LongSet deniedPlace,
                                     LongSet forbiddenBodyCells);
        CalculationContext forExecution(LocalPlayer player, LongSet sacred, LongSet deniedPlace,
                                        LongSet forbiddenBodyCells);
        /** 本提供者建出的上下文所带的地形许可(执行器据此决定顺手的放置能不能做)。 */
        TerrainPermit permit();

        /**
         * Extra cells the embedded backend must never break or place in. The compiled goal's
         * sacred cells are added separately; this hook preserves task-specific policies such as
         * a construction footprint without recreating the retired pathfinder's cost context.
         */
        default LongSet embeddedProtectedMutationCells() {
            return NavigationSafetyContext.protectedMutationCells();
        }

        /** Extra cells the embedded first-person body must never occupy. */
        default LongSet embeddedForbiddenBodyCells() {
            return NavigationSafetyContext.forbiddenBodyCells();
        }
    }

    /** Shared feet convention for navigation and interaction stances. */
    public static BlockPos playerFeet(LocalPlayer player) {
        return org.maiwithu.maicraft.core.pathing.moves.Movement.feet(player);
    }

    public Status tick() { return navigator.tick(); }
    public boolean isSafeToCancel() { return navigator.isSafeToCancel(); }
    public BlockPos pathStart() { return navigator.pathStart(); }
    public TerrainBill ledger() { return navigator.ledger(); }
    public String failReason() { return navigator.failReason(); }
    public FailureType failType() { return navigator.failType(); }
    public int stallTicks() { return navigator.stallTicks(); }
    public boolean hasRecentPhysicalProgress(int graceTicks) {
        return navigator.hasRecentPhysicalProgress(graceTicks);
    }
    public String outcomeSummary() { return navigator.outcomeSummary(); }
    public boolean planningInFlight() { return navigator.planningInFlight(); }
    public void stop() { navigator.stop(); }
    public void pause() { navigator.pause(); }
    public boolean yieldForExternalAction() { return navigator.yieldForExternalAction(); }
}
