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
