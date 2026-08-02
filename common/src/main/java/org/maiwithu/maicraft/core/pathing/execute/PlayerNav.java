package org.maiwithu.maicraft.core.pathing.execute;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.maiwithu.maicraft.core.Constants;
import org.maiwithu.maicraft.core.pathing.astar.Favoring;
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
 * 改善 → FAILED(BOXED_IN);{@link #MAX_REPLANS} 次硬保险丝兜底。
 *
 * <p>sacred(自身目标格,不可挖不可埋)/ deniedPlace(执行层证明放不上
 * 的格)两个语义开关穿透本导航建的每一个
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

    /** 重规划次数硬保险丝(理智上限,不是真正的放弃条件)。 */
    private static final int MAX_REPLANS = 200;
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

    private int replans;
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
        this(player, speed, reached, () -> GoalCompiler.block(player.level(), goal));
    }

    /** 可移动的单格目标:每次拉取重新按意图编译(格位腾空后收紧为站上去)。 */
    public PlayerNav(LocalPlayer player, Supplier<BlockPos> goalSupplier, double speed,
                     BooleanSupplier reached) {
        this(player, speed, reached, () -> {
            BlockPos g = goalSupplier.get();
            return g == null ? null : GoalCompiler.block(player.level(), g);
        });
    }

    /** 编译契约正门:goal + sacred + 到达原料一体下发。 */
    public static PlayerNav to(LocalPlayer player, Supplier<GoalCompiler.Compiled> compiled,
                               double speed, BooleanSupplier reached) {
        return new PlayerNav(player, speed, reached, compiled);
    }

    /** 编译契约正门,带任务专用的搜索/执行成本上下文。 */
    public static PlayerNav to(LocalPlayer player, Supplier<GoalCompiler.Compiled> compiled,
                               double speed, BooleanSupplier reached,
                               ContextProvider contextProvider) {
        return new PlayerNav(player, speed, reached, compiled, false, contextProvider);
    }

    /** 编译契约正门,并在每 tick 重新读取目标与保护格。 */
    public static PlayerNav toRevalidating(LocalPlayer player, Supplier<GoalCompiler.Compiled> compiled,
                                           double speed, BooleanSupplier reached,
                                           ContextProvider contextProvider) {
        return new PlayerNav(player, speed, reached, compiled, true, contextProvider);
    }

    /** 同上,用缺省搜索/执行上下文。 */
    public static PlayerNav toRevalidating(LocalPlayer player, Supplier<GoalCompiler.Compiled> compiled,
                                           double speed, BooleanSupplier reached) {
        return new PlayerNav(player, speed, reached, compiled, true);
    }

    /** 裸自定义目标(runAway、column 等)。不带 sacred——有方块目标的意图
     *  应走 {@link #to} / {@link GoalCompiler},让目标受保护。 */
    public static PlayerNav toGoal(LocalPlayer player, Supplier<NavGoal> goalSupplier,
                                   double speed, BooleanSupplier reached) {
        return new PlayerNav(player, speed, reached, bare(goalSupplier));
    }

    /** 同上,带任务专用的搜索/执行成本上下文(挖矿等要改地形的意图从这儿进)。 */
    public static PlayerNav toGoal(LocalPlayer player, Supplier<NavGoal> goalSupplier,
                                   double speed, BooleanSupplier reached,
                                   ContextProvider contextProvider) {
        return new PlayerNav(player, speed, reached, bare(goalSupplier), false, contextProvider);
    }

    /**
     * 目标<b>会动</b>的裸目标导航。两件事:每 tick 重新校验她还在不在目标里,走出去了就接着走;
     * 每 {@link #LIVE_GOAL_REPLAN_TICKS} 刻重取一次目标,让威胁快照跟上。
     *
     * <p>{@link #toGoal} 一旦搜索满足就永久返回 {@code ARRIVED},不再驱动移动 —— 那对固定
     * 坐标是对的,对"跟着一只怪保持站位"就成了站死。追击与近身走位都要这一个。
     */
    public static PlayerNav trackGoal(LocalPlayer player, Supplier<NavGoal> goalSupplier,
                                      double speed, BooleanSupplier reached) {
        return new PlayerNav(player, speed, reached, bare(goalSupplier), true);
    }

    /** 同上,带任务专用的搜索/执行成本上下文。 */
    public static PlayerNav trackGoal(LocalPlayer player, Supplier<NavGoal> goalSupplier,
                                      double speed, BooleanSupplier reached,
                                      ContextProvider contextProvider) {
        return new PlayerNav(player, speed, reached, bare(goalSupplier), true, contextProvider);
    }

    /**
     * 开启无路探针:只走不改找不到路时,探一条可改地形的路并把要动的方块列给任务层
     * (见类文档)。建好导航、首次 tick 前调用。
     */
    public PlayerNav withTerrainProbe() {
        this.terrainProbe = true;
        return this;
    }

    /** 把裸目标包成无 sacred 的编译契约(engineGoal 经词表映射同步派生)。 */
    private static Supplier<GoalCompiler.Compiled> bare(Supplier<NavGoal> goals) {
        return () -> {
            NavGoal g = goals.get();
            return g == null ? null
                    : new GoalCompiler.Compiled(g, LongSets.emptySet());
        };
    }

    private PlayerNav(LocalPlayer player, double speed, BooleanSupplier reached,
                      Supplier<GoalCompiler.Compiled> compiledSupplier) {
        this(player, speed, reached, compiledSupplier, false, ContextProvider.DEFAULT);
    }

    private PlayerNav(LocalPlayer player, double speed, BooleanSupplier reached,
                      Supplier<GoalCompiler.Compiled> compiledSupplier,
                      boolean revalidateGoalEachTick) {
        this(player, speed, reached, compiledSupplier, revalidateGoalEachTick,
                ContextProvider.DEFAULT);
    }

    private PlayerNav(LocalPlayer player, double speed, BooleanSupplier reached,
                      Supplier<GoalCompiler.Compiled> compiledSupplier,
                      boolean revalidateGoalEachTick, ContextProvider contextProvider) {
        this.player = player;
        this.compiledSupplier = compiledSupplier;
        this.sprintAllowed = speed >= 1.0;
        this.reached = reached;
        this.contextProvider = contextProvider == null ? ContextProvider.DEFAULT : contextProvider;
        this.revalidateGoalEachTick = revalidateGoalEachTick;
        this.core = new PathingCore(player, PoolSearchDispatcher.INSTANCE,
                this::searchContext, this::executionContext, this.contextProvider.permit());
    }

    /** 搜索用冻结上下文:快照世界 + 快照背包,穿透三个语义开关。 */
    private CalculationContext searchContext() {
        CalculationContext ctx = contextProvider.forSearch(player, sacred, deniedPlace);
        lastSearchContext = ctx;
        return ctx;
    }

    /** 执行期复核用实时上下文:活世界 + 当下背包,同一套语义开关。 */
    private CalculationContext executionContext() {
        return contextProvider.forExecution(player, sacred, deniedPlace);
    }

    /**
     * 一次导航的成本上下文来源:搜索用冻结快照、执行用活世界,外加这次移动的地形许可。
     * 许可只在这里定一次——上下文按它折成本,执行器按它决定能不能顺手放一块。
     */
    public interface ContextProvider {
        /** 缺省:只走不改。接近类动作全部用它,忘了指定也只会更保守。 */
        ContextProvider DEFAULT = of(TerrainPermit.PRESERVE);
        /** 可改地形:挖矿、施工,以及模型显式授权的 goto。 */
        ContextProvider TERRAFORM = of(TerrainPermit.TERRAFORM);

        static ContextProvider of(TerrainPermit permit) {
            return new ContextProvider() {
                @Override
                public CalculationContext forSearch(LocalPlayer player, LongSet sacred, LongSet deniedPlace) {
                    return ContextFactory.forSearch(player, sacred, deniedPlace, permit);
                }

                @Override
                public CalculationContext forExecution(LocalPlayer player, LongSet sacred, LongSet deniedPlace) {
                    return ContextFactory.forExecution(player, sacred, deniedPlace, permit);
                }

                @Override
                public TerrainPermit permit() {
                    return permit;
                }
            };
        }

        CalculationContext forSearch(LocalPlayer player, LongSet sacred, LongSet deniedPlace);
        CalculationContext forExecution(LocalPlayer player, LongSet sacred, LongSet deniedPlace);
        /** 本提供者建出的上下文所带的地形许可(执行器据此决定顺手的放置能不能做)。 */
        TerrainPermit permit();
    }

    /** ARRIVED-IN-PLACE 已打点(边沿去重)。 */
    private boolean arrivedInPlaceLogged;

    public Status tick() {
        NavProfiler.tickFrame();
        if (reached.getAsBoolean()) {
            return Status.ARRIVED;
        }
        if (failedTerminal) {
            return Status.FAILED;
        }
        if (stopped) {
            failReason = "target lost";
            failType = FailureType.TARGET_LOST;
            return Status.FAILED;
        }
        if (terraformProbe != null) {
            return pollTerraformProbe();
        }
        // 步行导航驱动的是脚下的身体:坐着任何载具都先下来——乘客的行走输入对载具
        // 无效,不在这儿下就坐着"走"到失速。全仓步行任务共用这一处,别在任务层各判各的。
        // 放在 reached 之后:已经到位就不惊动座驾(跟主人同船漂着的 follow 不该把她甩下去)。
        if (player.isPassenger()) {
            player.stopRiding();
        }

        // goal 与 sacred 一体拉取——两者是同一份契约
        long tCompile = NavProfiler.begin();
        GoalCompiler.Compiled compiled = compiledSupplier.get();
        NavProfiler.end("goal.compile", tCompile);
        if (compiled == null) {
            return fail(FailureType.TARGET_LOST, "target lost");
        }
        sacred = compiled.sacred();
        NavGoal navGoal = compiled.goal();

        // 目标中心移动 >2 格:重根(进度量尺随之复位,状态机自会软取消旧段)
        if (plannedCenter != null && navGoal.center().distSqr(plannedCenter) > GOAL_MOVED_SQR) {
            searchSatisfied = false;
            bestGoalH = Double.MAX_VALUE;
            ticksSincePlan = 0;
            plannedCenter = navGoal.center();
            withSprintGate(() -> core.setGoalAndPath(compiled.engineGoal()));
            return Status.RUNNING;
        }
        // 活目标:到点就重取一次。进度量尺不复位——那是给"卡住了"用的,不该被节拍抹平。
        if (revalidateGoalEachTick && ++ticksSincePlan >= LIVE_GOAL_REPLAN_TICKS) {
            ticksSincePlan = 0;
            searchSatisfied = false;
            plannedCenter = navGoal.center();
            withSprintGate(() -> core.setGoalAndPath(compiled.engineGoal()));
            return Status.RUNNING;
        }
        if (plannedCenter == null) {
            plannedCenter = navGoal.center();
        }

        if (searchSatisfied) {
            if (!revalidateGoalEachTick) {
                return Status.ARRIVED;
            }
            BlockPos feet = PathExecutor.playerFeet(player);
            if (compiled.engineGoal().isInGoal(feet.getX(), feet.getY(), feet.getZ())) {
                return Status.ARRIVED;
            }
            searchSatisfied = false;
        }

        PathExecutor before = core.getCurrent();
        long tExec = NavProfiler.begin();
        withSprintGate(() -> {
            // 状态机空闲(初次、或结果被判孤儿丢弃)时(重新)下发目标;
            // setGoalAndPath 已在目标内/已有段/已有在飞搜索时自会不派发
            if (revalidateGoalEachTick || core.getGoal() == null
                    || (core.getCurrent() == null && !core.hasInProgressSearch())) {
                core.setGoalAndPath(compiled.engineGoal());
            }
            core.tick();
        });
        NavProfiler.end("core.tick", tExec);

        // 首段搜索失败:验尸并终局。裁定前再问一次 caller 谓词——本 tick
        // 身体可能已挪进满足位,ARRIVED 优先于失败结论
        if (core.calcFailedLastTick()) {
            if (reached.getAsBoolean()) {
                return Status.ARRIVED;
            }
            // 只走不改找不到路:先探一条可改地形的路,把它会动什么列出来再裁决——
            // 模型要的是"授权什么"的具体清单,不是一句 no path
            if (terrainProbe && contextProvider.permit() == TerrainPermit.PRESERVE
                    && submitTerraformProbe(compiled.engineGoal(), navGoal)) {
                InputDriver.halt(player);
                return Status.RUNNING;
            }
            return fail(FailureType.NO_PATH, noPathAutopsy(navGoal,
                    contextProvider.permit() == TerrainPermit.PRESERVE ? " without altering terrain" : ""));
        }

        // 执行失败(段被取消,状态机已自动重搜):做放弃判定的记账
        PathExecutor after = core.getCurrent();
        if (before != null && after != before && before.failed()) {
            lastExecFailure = before.failureCause();
            Status verdict = accountReplan(navGoal);
            if (verdict != null) {
                return verdict;
            }
        }

        // 到达判定:状态机归于空闲且脚下满足搜索目标 → 稳定 ARRIVED。
        // 覆盖两种情形:路径走完进入目标;以及"原地即满足"(setGoalAndPath
        // 因脚下已在目标内根本不派发搜索)。
        Goal engineGoal = core.getGoal();
        if (engineGoal != null && core.getCurrent() == null && !core.hasInProgressSearch()) {
            BlockPos feet = PathExecutor.playerFeet(player);
            if (engineGoal.isInGoal(feet.getX(), feet.getY(), feet.getZ())) {
                searchSatisfied = true;
                if (!arrivedInPlaceLogged) {
                    // 只在进入边沿打一次:任务层反复重建导航时,同一驻留会逐 tick 重进
                    // 这个分支,连续打点是日志洪水
                    arrivedInPlaceLogged = true;
                    Constants.LOG.info(
                            "[maicraft-path] ARRIVED-IN-PLACE feet={} goal-center={} —— 搜索目标在脚下"
                                    + "即满足,钉稳结论交任务层裁决",
                            feet.toShortString(), plannedCenter.toShortString());
                }
                return Status.ARRIVED;
            }
        }
        return Status.RUNNING;
    }

    /**
     * speed 参数的落点:本导航的每一段驱动都包在 allowSprint 门里,
     * slow(speed&lt;1.0)时全局禁疾跑——搜索上下文的 canSprint 快照与
     * 执行器的逐 tick 疾跑决策读的都是这一个开关,包夹后原值复原,
     * 不影响别的同伴。
     */
    private void withSprintGate(Runnable body) {
        NavSettings settings = NavSettings.get();
        boolean saved = settings.allowSprint;
        settings.allowSprint = saved && sprintAllowed;
        try {
            body.run();
        } finally {
            settings.allowSprint = saved;
        }
    }

    /**
     * 一次失败重规划的记账。进度按目标自己的启发函数在脚下的取值度量
     * (yLevel 只看竖直、column 只看水平、composite 看最近成员、runAway
     * 负值随逃离下降,各自天然正确);有真实改善清零连击,否则连击到
     * {@link #MAX_STALLED_REPLANS} 判 BOXED_IN。返回 null 表示继续跑。
     */
    private Status accountReplan(NavGoal liveGoal) {
        BlockPos feet = PathExecutor.playerFeet(player);
        double h = liveGoal.progressHeuristic(feet);
        if (bestGoalH - h >= REPLAN_PROGRESS_EPS_H) {
            bestGoalH = h;
            stalledReplans = 0;
        } else if (++stalledReplans >= MAX_STALLED_REPLANS) {
            Status verdict = fail(FailureType.BOXED_IN,
                    "gave up: no real progress toward the target over "
                            + MAX_STALLED_REPLANS + " consecutive attempts"
                            + (lastExecFailure != null
                                    ? "; the recurring failure: " + lastExecFailure : ""));
            return reached.getAsBoolean() ? Status.ARRIVED : verdict;
        }
        if (replans++ >= MAX_REPLANS) {
            Status verdict = fail(FailureType.BOXED_IN,
                    "gave up after " + MAX_REPLANS + " replans");
            return reached.getAsBoolean() ? Status.ARRIVED : verdict;
        }
        return null;
    }

    /** 终局裁定:停下身体、钉住原因,此后 tick 稳定返回 FAILED。 */
    private Status fail(FailureType type, String reason) {
        failReason = reason;
        failType = type;
        failedTerminal = true;
        core.forceCancel();
        return Status.FAILED;
    }

    /**
     * 派一次"若许改地形则此路"的探针:与状态机同一派发器、同一目标、同一起点,只换成
     * TERRAFORM 上下文;只搜不走——结论到手只产出清单,绝不执行。
     *
     * @return 是否真的派出去了(派不出去时直接按 NO_PATH 裁决)
     */
    private boolean submitTerraformProbe(Goal engineGoal, NavGoal navGoal) {
        BlockPos start = core.pathStart();
        if (start == null) {
            return false;
        }
        CalculationContext probeContext = ContextFactory.forSearch(player, sacred, deniedPlace,
                TerrainPermit.TERRAFORM);
        NavSettings settings = NavSettings.get();
        terraformProbe = PoolSearchDispatcher.INSTANCE.submit(PathExecutor.playerFeet(player), start,
                engineGoal, probeContext, Favoring.empty(),
                settings.primaryTimeoutMS, settings.failureTimeoutMS);
        terraformProbeGoal = navGoal;
        Constants.LOG.info("[maicraft-path] 只走不改无路,探一条可改地形的路 start={} goal={}",
                start.toShortString(), navGoal.center().toShortString());
        return true;
    }

    /**
     * 探针出结论:有路 → 列清单,TERRAIN_BLOCKED;无路 → 连挖都到不了,NO_PATH。
     * 等结论期间身体原地站住。裁决前仍让 caller 的 reached 谓词先说话。
     */
    private Status pollTerraformProbe() {
        PathCalcResult result = terraformProbe.poll();
        if (result == null) {
            InputDriver.halt(player);
            return Status.RUNNING;
        }
        terraformProbe = null;
        NavGoal goal = terraformProbeGoal;
        terraformProbeGoal = null;
        if (reached.getAsBoolean()) {
            return Status.ARRIVED;
        }
        // 搜索器交出的路径已经装配过(postProcess 在 calculate 模板里),直接读移动原语
        NavPath path = result.getPath().orElse(null);
        TerrainBill bill = path == null ? null : TerrainBill.planned(path, player.level());
        if (bill == null || bill.isEmpty()) {
            // 连可改地形都搜不出路(或搜出的路根本不动地形——那就是清洁搜索自己的预算问题):
            // 如实说没路,别把"挖"当万能解
            return fail(FailureType.NO_PATH, noPathAutopsy(goal,
                    path == null ? ", not even by digging or bridging" : ""));
        }
        BlockPos feet = PathExecutor.playerFeet(player);
        BlockPos center = goal.center();
        // 措辞对任何任务都成立:goto 自己重发带标记,别的任务先 goto 开路再做事
        String reason = String.format(
                "no route without altering terrain (from %s toward %s, about %.0f blocks away). %s would %s."
                        + " Altering terrain needs consent: if that is acceptable, goto there with"
                        + " may_alter_terrain=true; otherwise pick another spot or ask.",
                feet.toShortString(), center.toShortString(), Math.sqrt(feet.distSqr(center)),
                result.getType() == PathCalcResult.Type.SUCCESS_TO_GOAL
                        ? "The cheapest route through" : "Even the first leg of a route through",
                bill.describe());
        Constants.LOG.info("[maicraft-path] TERRAIN-BLOCKED start={} goal={} | {}",
                feet.toShortString(), center.toShortString(), reason);
        return fail(FailureType.TERRAIN_BLOCKED, reason);
    }

    /**
     * 空搜索结果的教学式验尸——直接喂给模型的人话:离目标多远、有无
     * 搭路耗材、还有什么可解锁的手段。搜索器统计面未随异步句柄暴露,
     * 此处按可得素材给结构化结论。
     */
    /** @param qualifier 紧跟 "no path to target" 之后的限定语(地形许可的说明),可为空串 */
    private String noPathAutopsy(NavGoal goal, String qualifier) {
        BlockPos feet = PathExecutor.playerFeet(player);
        BlockPos center = goal.center();
        double dist = Math.sqrt(feet.distSqr(center));
        StringBuilder r = new StringBuilder("no path to target").append(qualifier);
        r.append(String.format(" (from %s toward %s, about %.0f blocks away;"
                        + " the search burned its whole budget without finding a route",
                feet.toShortString(), center.toShortString(), dist));
        if (lastSearchContext != null && !lastSearchContext.hasThrowaway) {
            r.append("; carrying no scaffolding blocks to bridge or pillar with");
        }
        if (!deniedPlace.isEmpty()) {
            r.append("; ").append(deniedPlace.size())
                    .append(" scaffold spot(s) already proven unplaceable this navigation");
        }
        r.append(')');
        String reason = r.toString();
        Constants.LOG.info("[maicraft-path] NO-PATH start={} goal={} | {}",
                feet.toShortString(), center.toShortString(), reason);
        return reason;
    }

    public boolean isSafeToCancel() {
        return core.isSafeToCancel();
    }

    public BlockPos pathStart() {
