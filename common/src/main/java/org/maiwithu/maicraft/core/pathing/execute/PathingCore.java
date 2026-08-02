package org.maiwithu.maicraft.core.pathing.execute;

import java.util.Optional;
import java.util.function.Supplier;

import org.maiwithu.maicraft.core.Constants;
import org.maiwithu.maicraft.core.pathing.astar.Favoring;
import org.maiwithu.maicraft.core.pathing.astar.NavPath;
import org.maiwithu.maicraft.core.pathing.astar.PathCalcResult;
import org.maiwithu.maicraft.core.pathing.bridge.SearchDispatcher;
import org.maiwithu.maicraft.core.pathing.bridge.SearchHandle;
import org.maiwithu.maicraft.core.pathing.goals.Goal;
import org.maiwithu.maicraft.core.pathing.moves.CalculationContext;
import org.maiwithu.maicraft.core.pathing.moves.ChunkLoadedTest;
import org.maiwithu.maicraft.core.pathing.moves.Movement;
import org.maiwithu.maicraft.core.pathing.moves.TerrainPermit;
import org.maiwithu.maicraft.core.pathing.settings.NavSettings;
import net.minecraft.client.player.LocalPlayer;

import net.minecraft.core.BlockPos;

/**
 * 段规划状态机:目标 → 首段搜索 → 执行 → 提前规划接续段 → 无缝接段,
 * 直到进入目标或被取消。持有 current(在执行的段)/ next(算好的下一
 * 段)/ inProgress(在飞搜索)三个槽位与本次导航的成本上下文。
 *
 * <p>每 tick 一次 {@link #tick()}:先记录假起点(expectedSegmentStart),
 * 依次处理暂停/取消请求、在飞搜索合法性、当前段推进、段界分流、提前
 * 接段与拼接、提前规划触发,最后把本 tick 的输入/视角记录提交到实体。
 *
 * <p>独立可实例化:构造只要玩家、搜索派发器与成本上下文工厂,不挂接
 * 任何任务层。
 */
public final class PathingCore {

    private final LocalPlayer player;
    private final ExecHarness harness;
    private final SearchDispatcher dispatcher;
    /**
     * 搜索用成本上下文工厂:每次派发搜索时取样一份冻结快照(背包/附魔/
     * 语义开关随之刷新),整场搜索用同一把尺。
     */
    private final Supplier<CalculationContext> searchContextFactory;
    /**
     * 执行期成本上下文工厂:活世界视图,执行器逐 tick 复核成本时取样。
     * 与搜索侧同一套成本函数,只是世界读的是当下。
     */
    private final Supplier<CalculationContext> executionContextFactory;

    private PathExecutor current;
    private PathExecutor next;
    /** 连续丢弃孤儿首段的容忍次数;超出即判首段失败,交上层裁决。 */
    private static final int MAX_ORPHAN_DISCARDS = 3;
    private int orphanDiscards;
    /** 连续"采纳即夭折且未挪窝"的路段容忍数;超出即判首段失败。 */
    private static final int MAX_STERILE_SEGMENTS = 4;
    private int sterileSegments;
    private int dispatches;
    private final java.util.TreeMap<String, Integer> outcomes = new java.util.TreeMap<>();

    private SearchHandle inProgress;
    /** 在飞搜索的 A* 展开起点(句柄不携带,提交时在此记录)。 */
    private BlockPos inProgressStart;
    private Goal goal;
    private CalculationContext context;
    private BlockPos expectedSegmentStart;

    /** 上一次 current.onTick() 的返回值(可安全中断)。 */
    private boolean safeToCancel = true;
    private boolean cancelRequested;
    private boolean calcFailedLastTick;

    public PathingCore(LocalPlayer player, SearchDispatcher dispatcher,
                       Supplier<CalculationContext> searchContextFactory,
                       Supplier<CalculationContext> executionContextFactory,
                       TerrainPermit permit) {
        this.player = player;
        this.harness = new ExecHarness(player, permit);
        this.dispatcher = dispatcher;
        this.searchContextFactory = searchContextFactory;
        this.executionContextFactory = executionContextFactory;
    }

    /**
     * 搜索与执行同一份快照的简便构造:执行期复核直接读本次搜索的上下文。
     * <b>注意</b>:执行期成本复核因此读不到世界变化(快照冻结),生产
     * 路径必须走双工厂构造(执行侧供活世界上下文),此构造仅限测试。
     */
    public PathingCore(LocalPlayer player, SearchDispatcher dispatcher,
                       Supplier<CalculationContext> contextFactory, TerrainPermit permit) {
        this(player, dispatcher, contextFactory, null, permit);
    }

    // ==================== 对外 API ====================

    /**
     * 设定目标并开始寻路。目标失效裁决:当前段终点原本在旧目标内、
     * 而不在新目标内 → 旧段作废(软取消,不清键,下一 tick 无缝重算)。
     * 已在目标内 / 已有段或在飞搜索时不再发起新搜索。
     *
     * @return 是否真的发起了一次新搜索
     */
    public boolean setGoalAndPath(Goal newGoal) {
        if (NavSettings.get().cancelOnGoalInvalidation && goalInvalidatedBy(newGoal)) {
            Constants.LOG.debug("当前段终点不再被新目标认可,软取消");
            softCancelIfSafe();
        }
        this.goal = newGoal;
        if (goal == null) {
            return false;
        }
        BlockPos feet = PathExecutor.playerFeet(player);
        if (goal.isInGoal(feet.getX(), feet.getY(), feet.getZ())) {
            return false;
        }
        if (current != null || inProgress != null) {
            return false;
        }
        expectedSegmentStart = pathStart();
        startSearch(expectedSegmentStart);
        return true;
    }

    /** 目标失效裁决:当前段终点原本在旧目标内、而不在新目标内。 */
    private boolean goalInvalidatedBy(Goal newGoal) {
        if (current == null || goal == null || newGoal == null) {
            return false;
        }
        BlockPos dest = current.getPath().getDest();
        return goal.isInGoal(dest.getX(), dest.getY(), dest.getZ())
                && !newGoal.isInGoal(dest.getX(), dest.getY(), dest.getZ());
    }

    /**
     * 软取消:取消在飞搜索、丢弃段,但不清键——身体保持惯性,下一
     * tick 的重规划无缝接手。不安全时只取消在飞搜索。
     */
    public void softCancelIfSafe() {
        if (inProgress != null) {
            inProgress.cancel();
        }
        if (!isSafeToCancel()) {
            return;
        }
        current = null;
        next = null;
        cancelRequested = true;
    }

    /** 是否正在沿路径行进。 */
    public boolean isPathing() {
        return current != null;
    }

    /** 当前是否可安全中断(悬空放置、跑酷空中等时刻为 false)。 */
    public boolean isSafeToCancel() {
        return current == null || safeToCancel;
    }

    /** 本状态机的执行器至今真动过的地形(账本住在执行器里,这里只是递出去)。 */
    public TerrainBill ledger() {
        return harness.ledger();
    }

    /** 上一 tick 是否有一次首段计算以失败告终。 */
    public boolean calcFailedLastTick() {
        return calcFailedLastTick;
    }

    public Goal getGoal() {
        return goal;
    }

    public PathExecutor getCurrent() {
        return current;
    }

    public PathExecutor getNext() {
        return next;
    }

    public boolean hasInProgressSearch() {
        return inProgress != null;
    }

    /** 本内核迄今的搜索结论分布(排障用:分辨"搜不到路"与"只搜到半程")。 */
    public String outcomeSummary() {
        return dispatches + "派/" + outcomes
                + " 段[" + (current == null ? "无" : current.progressSummary()) + "]";
    }

    /** 在飞搜索此刻的最优部分路径;无在飞搜索或暂无候选时为空。 */
    public Optional<NavPath> inProgressBestPath() {
        return inProgress == null ? Optional.empty() : inProgress.bestPathSoFar();
    }

    public LocalPlayer player() {
        return player;
    }

    // ==================== 活跃实例注册表 ====================

    /** 每同伴最近一次 tick 过的内核(调试可视化等旁路消费者按此取用)。 */
    private static final java.util.concurrent.ConcurrentHashMap<java.util.UUID, PathingCore> LIVE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 当前各同伴最近活跃的内核快照(已移除实体的条目顺手清掉)。 */
    public static java.util.Collection<PathingCore> liveCores() {
        LIVE.values().removeIf(core -> core.player.isRemoved());
        return LIVE.values();
    }

    // ==================== 每 tick ====================

    /**
     * 推进一 tick。执行前强制记录假起点(执行会移动身位,搜索起点要
     * 用执行前的);末尾统一提交输入/视角并落地疾跑决策。
     */
    public void tick() {
        LIVE.put(player.getUUID(), this);
        expectedSegmentStart = pathStart();
        calcFailedLastTick = false;
        tickPath();
        harness.setSprinting(current != null && current.isSprinting());
        harness.commitIfDirty();
    }

    private void tickPath() {
        pollSearchResult();
        if (cancelRequested) {
            cancelRequested = false;
            harness.clearAllKeys();
        }
        // 在飞搜索合法性:起点既不是当前段终点、也不是身位/假起点,
        // 其最优部分路径也不含这两者 → 玩家被打飞/传送,计算作废
        if (inProgress != null) {
            BlockPos calcFrom = inProgressStart;
            BlockPos feet = PathExecutor.playerFeet(player);
            Optional<NavPath> currentBest = inProgress.bestPathSoFar();
            if ((current == null || !current.getPath().getDest().equals(calcFrom))
                    && !calcFrom.equals(feet) && !calcFrom.equals(expectedSegmentStart)
                    && (currentBest.isEmpty()
                            || (!currentBest.get().positions().contains(feet)
                                && !currentBest.get().positions().contains(expectedSegmentStart)))) {
                Constants.LOG.debug("在飞搜索的起点已作废,取消该计算");
                inProgress.cancel();
            }
        }
        if (current == null) {
            return;
        }
        BlockPos feetBefore = PathExecutor.playerFeet(player);
        safeToCancel = current.onTick();
        if (current.failed() || current.finished()) {
            noteSegmentEnd(current, feetBefore);
            current = null;
            BlockPos feet = PathExecutor.playerFeet(player);
            if (goal == null || goal.isInGoal(feet.getX(), feet.getY(), feet.getZ())) {
                Constants.LOG.debug("已到达目标");
                next = null;
                sterileSegments = 0;
                return;
            }
            if (next != null && !next.getPath().positions().contains(feet)
                    && !next.getPath().positions().contains(expectedSegmentStart)) {
                // 段中途失败,身位已不在计划好的下一段上,只能忍痛丢弃
                Constants.LOG.debug("下一段不含当前身位,丢弃");
                next = null;
            }
            if (next != null) {
                Constants.LOG.debug("无缝接上已计划的下一段");
                current = next;
                next = null;
                current.onTick(); // 不浪费本 tick,立即推进
                return;
            }
            if (inProgress != null) {
                return; // 段刚结束,等在飞计算
            }
            startSearch(expectedSegmentStart);
            return;
        }
        // 当前段进行中:身位恰在下一段格位上时提前跳段
        if (safeToCancel && next != null && next.snipsnapifpossible()) {
            Constants.LOG.debug("提前接入下一段");
            current = next;
            next = null;
            current.onTick();
            return;
        }
        if (NavSettings.get().splicePath) {
            current = current.trySplice(next);
        }
        if (next != null && current.getPath().getDest().equals(next.getPath().getDest())) {
            next = null;
        }
        if (inProgress != null) {
            return;
        }
        if (next != null) {
