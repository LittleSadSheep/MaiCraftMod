package org.maiwithu.maicraft.core.pathing.execute;
import org.maiwithu.maicraft.core.pathing.moves.AimGeometry;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import org.maiwithu.maicraft.core.Constants;
import org.maiwithu.maicraft.core.pathing.astar.NavPath;
import org.maiwithu.maicraft.core.pathing.astar.SplicedPath;
import org.maiwithu.maicraft.core.pathing.astar.CutoffPath;
import org.maiwithu.maicraft.core.pathing.goals.Goal;
import org.maiwithu.maicraft.core.pathing.moves.CalculationContext;
import org.maiwithu.maicraft.core.pathing.moves.ChunkLoadedTest;
import org.maiwithu.maicraft.core.pathing.moves.Input;
import org.maiwithu.maicraft.core.pathing.moves.Movement;
import org.maiwithu.maicraft.core.pathing.moves.MovementHelper;
import org.maiwithu.maicraft.core.pathing.moves.MovementState;
import org.maiwithu.maicraft.core.pathing.moves.MovementStatus;
import org.maiwithu.maicraft.core.pathing.moves.MutableMoveResult;
import org.maiwithu.maicraft.core.pathing.moves.movements.MovementFall;
import org.maiwithu.maicraft.core.pathing.settings.NavSettings;
import net.minecraft.client.player.LocalPlayer;

import net.minecraft.core.BlockPos;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

import static org.maiwithu.maicraft.core.pathing.moves.ActionCosts.COST_INF;

/**
 * 路径执行器:驱动一条已算好的路径逐移动执行完。每 tick 一次
 * {@link #onTick()},完整职责链:
 * 终点推进 → 位置重定位(回退扫/前跳扫)→ 脱轨双带看门狗 →
 * 边界暂停 → 成本核验(进入新移动时前瞻重算)→ 本移动逐 tick 涨价
 * 中止 → 回头暂停 → 移动状态机分派 → 疾跑整体决策 → 单移动超时。
 *
 * <p>疾跑不走按键:移动原语请求的 SPRINT 键被执行器没收,由执行器
 * 结合前后移动的上下文(直跳上台、下坡连锁、V 形谷、坠落前越)统一
 * 决策,直接写实体疾跑状态。
 */
public final class PathExecutor {

    /** 离路径超过该距离立即取消。 */
    static final double MAX_MAX_DIST_FROM_PATH = 3;
    /** 离路径超过该距离开始计时。 */
    static final double MAX_DIST_FROM_PATH = 2;
    /** 脱轨计时超过该 tick 数取消(10 秒)。 */
    static final double MAX_TICKS_AWAY = 200;

    private final NavPath path;
    private final LocalPlayer player;
    private final ExecHarness harness;
    /** 执行期重算成本用的上下文(活世界视图,主线程)。 */
    private final Supplier<CalculationContext> contextSupplier;
    /** 在飞搜索的当前最优部分路径;无在飞搜索或暂无候选时为空。 */
    private final Supplier<Optional<NavPath>> inProgressBestPath;
    private final ChunkLoadedTest loadedTest;

    private int pathPosition;
    private int ticksAway;
    private int ticksOnCurrent;
    /** 距上次真实推进(移动完成/重定位/活跃挖掘)的 tick 数,liveness 信号。 */
    private int ticksSinceProgress;
    /** 身位连续不在路径任何合法位上的刻数;超限即取消重算。 */
    private int ticksNotInValid;
    private static final int MAX_TICKS_NOT_IN_VALID = 20;
    /** 单次 onTick 内递归推进次数守卫:回退扫/SUCCESS 推进后递归 onTick
     * 可能在某 movement 的 SUCCESS 判定与 validPositions 不自洽时空转
     * 爆栈。超过路径长度即判定为病态循环,取消而非继续递归。 */
    private int tickRecursionDepth;
    /** 最近若干次推进类型(pos@feet:reason)的环形记录,守卫触发时回溯定位循环。 */
    private final String[] recentSteps = new String[16];
    private int recentStepsHead;
    private Double currentMovementOriginalCostEstimate;
    private Integer costEstimateIndex;
    /** 剩余路径的三类格集需要重建(格集内容较上 tick 有变化时置位)。 */
    private boolean recalcBP = true;
    /** 剩余路径将要挖穿的全部格(每 tick 维护,可视化/外部查询用)。 */
    private HashSet<BlockPos> toBreak = new HashSet<>();
    /** 剩余路径将要放方块的全部格。 */
    private HashSet<BlockPos> toPlace = new HashSet<>();
    /** 剩余路径将要挤身而过的全部格。 */
    private HashSet<BlockPos> toWalkInto = new HashSet<>();
    private boolean failed;
    /** 取消原因(失败验尸与放弃判定的素材);未失败时为 null。 */
    private String failureCause;
    private boolean sprintNextTick;
    /** 疾跑裁决(纯决策);副作用在 onTick0 的一处统一施加。 */
    private final SprintPolicy sprint;

    public PathExecutor(NavPath path, LocalPlayer player, ExecHarness harness,
                        Supplier<CalculationContext> contextSupplier,
                        Supplier<Optional<NavPath>> inProgressBestPath,
                        ChunkLoadedTest loadedTest) {
        this.path = path;
        path.movements().forEach(movement -> movement.bindPlayer(player));
        this.player = player;
        this.harness = harness;
        this.contextSupplier = contextSupplier;
        this.inProgressBestPath = inProgressBestPath;
        this.loadedTest = loadedTest;
        this.sprint = new SprintPolicy(path, player, contextSupplier);
        this.pathPosition = 0;
    }

    /**
     * 脚位约定:实体坐标 y 加 0.1251(灵魂沙/农田顶面矮一截仍归上格),
     * 落在台阶格里再上抬一格。转发 {@link Movement#feet}——执行器与
     * 移动原语共用同一把尺,避免半砖顶面错位导致推进-回退循环。
     */
    public static BlockPos playerFeet(LocalPlayer player) {
        return Movement.feet(player);
    }

    /**
     * 推进一 tick(首层入口:重置递归深度守卫,委派 {@link #onTick0()})。
     *
     * @return true 表示一个移动刚完成/路径进入暂停等"稳定"状态,
     *         false 表示位置刚被重定位(本 tick 已递归重跑);进行中
     *         返回该移动的 safeToCancel。
     */
    public boolean onTick() {
        tickRecursionDepth = 0;
        recentStepsHead = 0;
        java.util.Arrays.fill(recentSteps, null);
        return onTick0();
    }

    /** 记一次推进到环形缓冲,守卫触发时回溯定位循环路径。 */
    private void recordStep(String step) {
        recentSteps[recentStepsHead] = step;
        recentStepsHead = (recentStepsHead + 1) % recentSteps.length;
    }

    /** 递归实现:回退扫/SUCCESS 推进后自调,深度守卫止爆。 */
    private boolean onTick0() {
        if (tickRecursionDepth++ > path.length()) {
            // 回退扫/SUCCESS 推进后递归 onTick 超过路径长度:某 movement 的
            // SUCCESS 判定与 validPositions 不自洽,推进-回退空转。取消止爆。
            BlockPos feet = playerFeet(player);
            Movement m = path.movements().get(Math.min(pathPosition, path.movements().size() - 1));
            StringBuilder trace = new StringBuilder();
            for (int i = 0; i < recentSteps.length; i++) {
                String s = recentSteps[(recentStepsHead + i) % recentSteps.length];
                if (s != null) {
                    trace.append('[').append(s).append("] ");
                }
            }
            Constants.LOG.warn("onTick 递归超路长 {} 格,推进空转取消。pos={} feet={} movement={} src={} dest={} valid={} 迹:{}",
                    path.length(), pathPosition, feet, m.getClass().getSimpleName(),
                    m.getSrc(), m.getDest(), m.getValidPositions(), trace);
            cancel("执行器推进空转(SUCCESS 与合法位不自洽,超 " + path.length() + " 步)");
            return false;
        }
        if (pathPosition == path.length() - 1) {
            pathPosition++; // 最后一个格位没有对应移动,直接完成
        }
        if (pathPosition >= path.length()) {
            return true;
        }
        Movement movement = path.movements().get(pathPosition);
        movement.setExecutionDelegate(harness);
        BlockPos feet = playerFeet(player);
        boolean inValid = movement.getValidPositions().contains(feet);
        if (tickRecursionDepth < 6 && !inValid) {
            Constants.LOG.debug("[diag] pos={} feet={} movement={} src={} dest={} valid={} contains={}",
                    pathPosition, feet, movement.getClass().getSimpleName(),
                    movement.getSrc(), movement.getDest(),
                    movement.getValidPositions(), inValid);
        }
        if (!inValid) {
            // 回退扫:被击退/卡顿回传后落在已走过的移动里,退回去重走
            int back = findBackwardMatch(path.movements(), pathPosition, feet);
            if (back != -1) {
                int previousPos = pathPosition;
                pathPosition = back;
                for (int j = pathPosition; j <= previousPos; j++) {
                    path.movements().get(j).reset();
                }
                recordStep("back " + previousPos + "->" + back + " feet=" + feet);
                onChangeInPathPosition();
                onTick0();
                return false;
            }
            // 前跳扫:从 +3 起(+1 由移动自己报成功,+2 不查),身位落在
            // 后面的移动里则跳过去
            int forward = findForwardSkip(path.movements(), pathPosition, feet);
            if (forward != -1) {
                pathPosition = forward - 1;
                recordStep("fwd ->" + (forward - 1) + " feet=" + feet);
                onChangeInPathPosition();
                onTick0();
                return false;
            }
            // 前后都对不上:身位不在这条路径的任何合法位里。动作的前提就是"人在
            // 起点",前提破了硬撑没有意义——只会耗满超时再重规划,期间人一动不动。
            // 台阶/楼梯把她自动抬高一格、蹭偏一格就足以造成这种脱节,而日式小屋
            // 满地是楼梯,越往高层越密。给一点宽限(可能只是瞬时越界),超了就
            // 取消,让规划器按她实际所在的位置重新算。
            // 裁决必须和动作自己的合法性判定同源:脚下那格没支撑时(站在柱顶
            // 边沿之类),搜索用的是旁边那格作"假起点",动作也认这个假起点。
            // 只按 feet 判就比动作自己更严,会把本来健康的路径一条条掐掉。
            if (movement.getValidPositions().contains(Movement.pathStart(player))) {
                ticksNotInValid = 0;
            } else if (++ticksNotInValid > MAX_TICKS_NOT_IN_VALID) {
                Constants.LOG.info(
                        "[maicraft-path] 身位脱离路径 {} 刻(身位{} 应在{}),取消重算",
                        ticksNotInValid, feet.toShortString(),
                        movement.getSrc().toShortString());
                cancel("身位 " + feet.toShortString() + " 不在路径任何合法位上");
                return false;
            }
        } else {
            ticksNotInValid = 0;
        }
        double distFromPath = closestPathPosDist();
        if (possiblyOffPath(distFromPath, MAX_DIST_FROM_PATH)) {
            ticksAway++;
            if (ticksAway > MAX_TICKS_AWAY) {
                Constants.LOG.debug("离路径太远太久,取消({} tick)", ticksAway);
                cancel("离开路径 " + ticksAway + " tick 未能回归");
                return false;
            }
        } else {
            ticksAway = 0;
        }
        if (possiblyOffPath(distFromPath, MAX_MAX_DIST_FROM_PATH)) {
            Constants.LOG.debug("离路径过远,立即取消");
            cancel("身位离开路径超过 " + (int) MAX_MAX_DIST_FROM_PATH + " 格");
            return false;
        }
        // 对 pathPosition±10 的移动丢弃格集缓存按当前世界重算;任一移动的
        // 格集内容有变即重建"剩余路径全量格集"(可视化与外部查询的数据源)。
        // 读取经"只读已加载"钳制:路径末端伸进未生成地形时不触发同步区块生成
        var level = org.maiwithu.maicraft.core.pathing.cache.LoadedOnlyView.of(player.level());
        for (int i = pathPosition - 10; i < pathPosition + 10; i++) {
            if (i < 0 || i >= path.movements().size()) {
                continue;
            }
            Movement m = path.movements().get(i);
            List<BlockPos> prevBreak = m.toBreak(level);
            List<BlockPos> prevPlace = m.toPlace(level);
            List<BlockPos> prevWalkInto = m.toWalkInto(level);
            m.resetBlockCache();
            if (!prevBreak.equals(m.toBreak(level))) {
                recalcBP = true;
            }
            if (!prevPlace.equals(m.toPlace(level))) {
                recalcBP = true;
            }
            if (!prevWalkInto.equals(m.toWalkInto(level))) {
                recalcBP = true;
            }
        }
        if (recalcBP) {
            HashSet<BlockPos> newBreak = new HashSet<>();
            HashSet<BlockPos> newPlace = new HashSet<>();
            HashSet<BlockPos> newWalkInto = new HashSet<>();
            for (int i = pathPosition; i < path.movements().size(); i++) {
                Movement m = path.movements().get(i);
                newBreak.addAll(m.toBreak(level));
                newPlace.addAll(m.toPlace(level));
                newWalkInto.addAll(m.toWalkInto(level));
            }
            toBreak = newBreak;
            toPlace = newPlace;
            toWalkInto = newWalkInto;
            recalcBP = false;
        }
        if (pathPosition < path.movements().size() - 1) {
            Movement next = path.movements().get(pathPosition + 1);
            if (!loadedTest.isLoaded(next.getDest().getX(), next.getDest().getZ())) {
                Constants.LOG.debug("下一移动的终点在已加载区块边缘,暂停");
                harness.clearAllKeys();
                return true;
            }
        }
        boolean canCancel = movement.safeToCancel();
        CalculationContext context = contextSupplier.get();
        if (costEstimateIndex == null || costEstimateIndex != pathPosition) {
            Movement.ItemSelection supplies = prepareSupplies(movement);
            if (supplies == Movement.ItemSelection.WAITING) {
                harness.clearAllKeys();
                return true;
            }
            if (supplies == Movement.ItemSelection.UNAVAILABLE && canCancel) {
                cancel("当前移动需要的水桶无法备入快捷栏");
                return true;
            }
            // 只有跨 tick 备货确认后,才钉住本移动的估价并开始执行。
            costEstimateIndex = pathPosition;
            currentMovementOriginalCostEstimate = movement.getCost();
            int lookahead = NavSettings.get().costVerificationLookahead;
            int deadFuture = firstFutureImpossible(path.movements(), pathPosition, lookahead, context);
            if (deadFuture != -1 && canCancel) {
                Constants.LOG.debug("世界已变化,后续移动不可行,取消");
                cancel("世界已变化," + describe(path.movements().get(deadFuture)) + " 不再可行");
                return true;
            }
        }
        double currentCost = movement.recalculateCost(context);
        if (currentCost >= COST_INF && canCancel) {
            Constants.LOG.debug("世界已变化,当前移动不可行,取消");
            cancel("世界已变化," + describe(movement) + " 不再可行");
            return true;
        }
        if (!movement.calculatedWhileLoaded()
                && costIncreaseExceedsTolerance(currentMovementOriginalCostEstimate, currentCost,
                        NavSettings.get().maxCostIncrease)
                && canCancel) {
            // 只对"当时在未加载区块里估出来的"移动生效:加载后货不对板;
            // 加载时算的涨价属于自己路径的连锁反应,不管
            Constants.LOG.debug("移动估价 {} 涨到 {},取消", currentMovementOriginalCostEstimate, currentCost);
            cancel(describe(movement) + " 加载后估价从 "
                    + (int) (double) currentMovementOriginalCostEstimate + " 涨到 " + (int) currentCost);
            return true;
        }
        if (shouldPause()) {
            Constants.LOG.debug("在飞搜索的最优路径会回头经过脚下,暂停");
            harness.clearAllKeys();
            return true;
        }
        MovementStatus movementStatus = movement.update();
        if (movementStatus == MovementStatus.UNREACHABLE || movementStatus == MovementStatus.FAILED) {
            Constants.LOG.debug("移动报 {},取消", movementStatus);
            cancel(describe(movement) + " 执行报 " + movementStatus);
            return true;
        }
        if (movementStatus == MovementStatus.SUCCESS) {
            pathPosition++;
            recordStep("success ->" + pathPosition + " feet=" + playerFeet(player));
            onChangeInPathPosition();
            onTick0();
            return true;
        } else {
            // 先没收移动原语请求的 SPRINT 键(疾跑由执行器直接写实体状态,
            // 不靠按键),把请求作为事实交给策略;裁决的全部副作用在此统一施加。
            boolean sprintRequested = harness.isKeyRequested(Input.SPRINT);
            harness.forceKey(Input.SPRINT, false);
            SprintPolicy.Decision d = sprint.decide(pathPosition, sprintRequested);
            if (d.skipTo() >= 0) {
                pathPosition = d.skipTo();
                onChangeInPathPosition();
                onTick0();
            }
            if (d.jumpUp()) {
                harness.forceKey(Input.JUMP, true);
            }
            if (d.jumpDown()) {
                harness.forceKey(Input.JUMP, false);
            }
            if (d.steer() != null) {
                // 疾跑冲下坡不减速:清键、直接压目标视线与前进
                harness.clearAllKeys();
                Vec3 eye = player.getEyePosition();
                harness.applyRotation(new MovementState.MovementTarget(
                        AimGeometry.yawTo(eye, d.steer()),
                        AimGeometry.pitchTo(eye, d.steer()), false));
                harness.forceKey(Input.MOVE_FORWARD, true);
            }
            sprintNextTick = d.sprint();
            ticksOnCurrent++;
            // 活跃挖掘算真实推进(硬方块一挖几十 tick 是正常工作)
            if (harness.isDigging()) {
                ticksSinceProgress = 0;
            } else {
                ticksSinceProgress++;
            }
            if (ticksOnCurrent > timedOutAt(currentMovementOriginalCostEstimate,
                    NavSettings.get().movementTimeoutTicks)) {
                // 卡死的动作必须留声:类型、起讫、四邻实况、身位一次性摊开。
                // 动作卡住是寻路故障里最常见的一类,所以这条是 INFO 不是 debug——
                // 发布态不落盘的话,线上出问题只能靠猜。这是排障的第一现场。
                Constants.LOG.info(
                        "[maicraft-path] 动作卡死 {} 耗时{}刻(估价{}) 无进展{}刻 身位{} 精确({}) "
                                + "起点格={} 终点格={} 终点上={} 终点下={}",
                        describe(movement), ticksOnCurrent,
                        (int) (double) currentMovementOriginalCostEstimate, ticksSinceProgress,
                        playerFeet(player).toShortString(),
                        String.format("%.2f,%.2f,%.2f", player.getX(), player.getY(), player.getZ()),
                        blockName(movement.getSrc()), blockName(movement.getDest()),
                        blockName(movement.getDest().above()), blockName(movement.getDest().below()));
                cancel(describe(movement) + " 卡住:耗时 " + ticksOnCurrent
                        + " tick,远超估价 " + (int) (double) currentMovementOriginalCostEstimate);
                return true;
            }
        }
        return canCancel;
    }

    // ==================== 重定位(纯逻辑,可测) ====================

    /** 回退扫:在 [0, pathPosition) 里找第一个合法位含 feet 的移动;无则 -1。 */
    static int findBackwardMatch(List<Movement> movements, int pathPosition, BlockPos feet) {
        for (int i = 0; i < pathPosition && i < movements.size(); i++) {
            if (movements.get(i).getValidPositions().contains(feet)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 前跳扫:在 [pathPosition+3, movements.size()) 里找第一个合法位含
     * feet 的移动;无则 -1。刻意跳过 +1(该移动自己会报 SUCCESS)与 +2。
     */
    static int findForwardSkip(List<Movement> movements, int pathPosition, BlockPos feet) {
        for (int i = pathPosition + 3; i < movements.size(); i++) {
            if (movements.get(i).getValidPositions().contains(feet)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 提前接段判定(纯逻辑):空中且不在液体里不接;严格下沉(竖速
     * < -0.1)不接(可能正穿水下坠);feet 恰在格位序列里 → 返回该
     * 下标,否则 -1。
     */
    static int snipsnapIndex(List<BlockPos> positions, BlockPos feet,
                             boolean onGround, boolean feetInLiquid, double deltaY) {
        if (!onGround && !feetInLiquid) {
            return -1;
        }
        if (deltaY < -0.1) {
            return -1;
        }
        return positions.indexOf(feet);
    }

    // ==================== 成本核验 / 超时(纯逻辑,可测) ====================

    /**
     * 从当前步后一格起向前看 {@code lookahead} 个移动,返回第一个成本
     * {@code >= COST_INF} 的下标;全可行返回 -1。窗口不含当前步与路径
     * 末位(末位无对应移动)。
     */
    static int firstFutureImpossible(List<Movement> movements, int pathPosition,
                                      int lookahead, CalculationContext context) {
        for (int i = 1; i < lookahead && pathPosition + i < movements.size(); i++) {
            Movement future = movements.get(pathPosition + i);
            if (future.calculateCost(context, new MutableMoveResult()) >= COST_INF) {
                return pathPosition + i;
            }
        }
        return -1;
    }

    /** 成本涨幅是否超过容差(仅对猜价移动生效,调用方负责 calculatedWhileLoaded)。 */
    static boolean costIncreaseExceedsTolerance(double originalEstimate, double currentCost,
                                                double maxCostIncrease) {
        return currentCost - originalEstimate > maxCostIncrease;
    }

    /** 单移动超时阈值:估价 tick + 宽限。 */
    static double timedOutAt(double originalCostEstimate, int movementTimeoutTicks) {
        return originalCostEstimate + movementTimeoutTicks;
    }

    // ==================== 脱轨 ====================

    /** 玩家到全路径所有合法位格中心的最小距离。 */
    private double closestPathPosDist() {
        return closestPathPosDist(path.movements(), player.getX(), player.getY(), player.getZ());
    }

    /** 纯逻辑:给定路径与玩家坐标,返回到全路径合法位格中心的最小距离。 */
    static double closestPathPosDist(List<Movement> movements, double px, double py, double pz) {
        double best = -1;
        for (Movement movement : movements) {
            for (BlockPos pos : movement.getValidPositions()) {
                double dist = distanceToCenter(pos, px, py, pz);
                if (dist < best || best == -1) {
                    best = dist;
                }
            }
        }
        return best;
    }

    /**
     * 是否可能脱轨。坠落中同时远离起点与终点属正常,当前移动是坠落时
     * 改用到坠落终点的水平距离判定。
     */
    private boolean possiblyOffPath(double distanceFromPath, double leniency) {
        return possiblyOffPath(path, pathPosition, distanceFromPath, leniency,
                player.getX(), player.getZ(), pos -> entityFlatDistanceToCenter(pos));
    }

    /**
     * 纯逻辑:给定路径、当前下标、玩家到路径最小距离与容差,判定是否
     * 可能脱轨。当前移动是坠落时改用到坠落终点(下标+1)的水平距离。
     * flatDist 按格位算玩家到该格中心的水平距离(坠落终点用)。
     */
    static boolean possiblyOffPath(NavPath path, int pathPosition, double distanceFromPath,
                                    double leniency, double px, double pz,
                                    java.util.function.Function<BlockPos, Double> flatDist) {
        if (distanceFromPath <= leniency) {
            return false;
        }
        if (path.movements().get(pathPosition) instanceof MovementFall) {
            BlockPos fallDest = path.positions().get(pathPosition + 1);
            return flatDist.apply(fallDest) >= leniency;
        }
        return true;
    }

    /** 纯逻辑:玩家坐标到格位中心的 3D 距离。 */
    static double distanceToCenter(BlockPos pos, double px, double py, double pz) {
        double dx = pos.getX() + 0.5 - px;
        double dy = pos.getY() + 0.5 - py;
        double dz = pos.getZ() + 0.5 - pz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private double entityFlatDistanceToCenter(BlockPos pos) {
        return flatDistanceToCenter(pos, player.getX(), player.getZ());
    }

    /** 纯逻辑:玩家坐标到格位中心的水平距离。 */
    static double flatDistanceToCenter(BlockPos pos, double px, double pz) {
        double dx = pos.getX() + 0.5 - px;
        double dz = pos.getZ() + 0.5 - pz;
        return Math.sqrt(dx * dx + dz * dz);
    }

    // ==================== 回头暂停 / 提前接段 ====================

    /**
     * 在飞搜索的最优部分路径(≥3 格,去掉首格后)包含脚下 → 新路径
     * 会经过这里,不必再往前走。仅在站稳、身位通透、当前移动可取消
     * 时生效。
     */
    private boolean shouldPause() {
        Optional<NavPath> currentBest = inProgressBestPath.get();
        if (currentBest.isEmpty()) {
            return false;
        }
        if (!player.onGround()) {
            return false;
        }
        BlockPos feet = playerFeet(player);
        var level = org.maiwithu.maicraft.core.pathing.cache.LoadedOnlyView.of(player.level());
        if (!MovementHelper.canWalkOn(level, feet.below())) {
            return false; // 站位本身可疑(可能跑酷中),别停
        }
        if (!MovementHelper.canWalkThrough(level, feet)
                || !MovementHelper.canWalkThrough(level, feet.above())) {
            return false; // 身位被埋,别停
        }
        if (!path.movements().get(pathPosition).safeToCancel()) {
            return false;
        }
        List<BlockPos> positions = currentBest.get().positions();
        if (positions.size() < 3) {
            return false; // 太短,远不能确定真会走这条
        }
        return positions.subList(1, positions.size()).contains(feet);
    }

    /** 无论当前推进到哪,身位恰在路径格位上时直接吸附过去。 */
    public boolean snipsnapifpossible() {
        BlockPos feet = playerFeet(player);
        boolean feetInLiquid = !player.level().getFluidState(feet).isEmpty();
        int index = snipsnapIndex(path.positions(), feet,
                player.onGround(), feetInLiquid, player.getDeltaMovement().y);
        if (index == -1) {
            return false;
        }
        pathPosition = index;
        harness.clearAllKeys();
        return true;
    }

    // ==================== 备货 / 状态迁移 ====================

    /** Dangerous falls must wait for a confirmed water-bucket staging transaction. */
    private Movement.ItemSelection prepareSupplies(Movement movement) {
        if (movement instanceof MovementFall
                && movement.getSrc().getY() - movement.getDest().getY() > NavSettings.get().maxFallHeightNoWater
                && !MovementHelper.isWater(player.level().getBlockState(movement.getDest()))) {
            return harness.ensureWaterBucketInHotbar();
        }
        // Placement movements select the exact build/scaffold predicate themselves. This avoids
        // staging a generic block ahead of a provider-specific construction material.
        return Movement.ItemSelection.READY;
    }

    private void onChangeInPathPosition() {
        harness.clearAllKeys();
        ticksOnCurrent = 0;
        ticksSinceProgress = 0;
    }

    /** 方块的短名(排障日志用)。 */
