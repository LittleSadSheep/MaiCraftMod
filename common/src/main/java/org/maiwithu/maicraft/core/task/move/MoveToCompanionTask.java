package org.maiwithu.maicraft.core.task.move;
import org.maiwithu.maicraft.core.pathing.settings.ScaffoldMaterials;
import org.maiwithu.maicraft.core.FailureType;

import org.maiwithu.maicraft.task.TaskState;

import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.core.pathing.calc.NavGoal;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.Map;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.block.Blocks;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.Constants;
import org.maiwithu.maicraft.core.integration.jetpack.JetpackGroundMode;
import org.maiwithu.maicraft.core.pathing.baritone.landing.LandingAssistPolicy;
import org.maiwithu.maicraft.core.pathing.execute.BoatNav;
import org.maiwithu.maicraft.core.pathing.goal.GoalCompiler;
import org.maiwithu.maicraft.core.pathing.moves.Movement;
import org.maiwithu.maicraft.core.pathing.transport.TransportMode;
import org.maiwithu.maicraft.core.pathing.transport.TransportRuntime;
import org.maiwithu.maicraft.core.pathing.util.BlockHelper;
import org.maiwithu.maicraft.task.InternalPositionReceipt;
import org.maiwithu.maicraft.task.ProgressBudget;

/**
 * 执行一次移动：到坐标附近、准确站到某格、改变高度，或找到某种方块走到旁边。
 * 具体路线由 PlayerNav 负责；这里决定何时开始、找不到路怎么办，以及玩家真的到达后怎样报告结果。
 * x/y/z 都有也不一定要求精确站位，是否精确由 exact 与到达误差共同决定。
 */
public final class MoveToCompanionTask extends AbstractCompanionTask<MoveToTaskRecord> {
    private Map<String, Object> finalNavigationEvidence = Map.of();

    private static final long TICKS_PER_BLOCK = 20;
    private static final long MAX_EXTRA_TICKS = 5 * 60 * 20;
    /** 行进期间将截止时间保持在此期限之后，使健康的多分钟挖掘路线不会半途超时；若路线卡住，也会在一个期限内结束并归还角色控制。 */
    private static final long PROGRESS_LEASE_TICKS = 30 * 20;
    /** 规划器无法抵达精确目标时，若停在请求柱列附近此距离内，仍视为到达（教学性成功，避免反复抖动）。这是唯一容差；真正的精确到达仍按原目标判定。 */
    private static final double WALK_SPEED = 1.0;
    private static final double NEAR_SUCCESS_RADIUS = 3.0;
    /** 规划器无法再接近时（例如停在水下目标上方的水面），等待此数量的无进展 tick 再放弃：
     *  时间足以让角色被水流带到可达的水下目标附近，也能及时放弃水面上方不可达的目标。 */
    private static final int MAX_SETTLE_TICKS = 60;
    /** 规划预算仅消耗无进展的活动时间；真实计算、位移或原生确认都会通过公共预算补满。 */
    private static final long PLANNING_IDLE_TICKS = 30 * 20;
    /** 规划收敛熔断的累计搜索工作量：地下封闭环境的挖洞寻路会持续展开节点却永不接近目标，
     *  无进展预算因此永远不满；累计超过此量且距离无收敛时按病态搜索收场，不再无限消耗。 */
    private static final long PLANNING_WORK_FUSE_UNITS = 60_000;
    /** 距离最后一次接近目标超过这么长的活动 tick（且工作量已越熔断线），判定这次规划无收敛。 */
    private static final long PLANNING_CONVERGENCE_WINDOW_TICKS = 30 * 20;
    /** 中转段准入增量：候选已知格必须比当前站位距目标至少近这么多格，否则不值得多走一段。 */
    private static final double KNOWN_CELL_WAYPOINT_MARGIN = 4.0;

    private final int bx;
    private final int by;
    private final int bz;
    private final BlockPos blockTarget;   // 仅在 BLOCK 目标类型下有意义。

    private double bestDist = Double.MAX_VALUE;   // 到目标曾达到的最近距离。
    private int settleTicks = 0;                  // 规划器放弃后无进展的 tick 数。
    private final ProgressBudget planningBudget;
    /** 历次规划累计的搜索工作单位高水位；只有超过熔断线且距离无收敛才生效，正常分段规划永远到不了。 */
    private long planningWorkHighWater;
    /** 上一次到目标距离有意义的缩短（>0.1 格）发生的活动 tick；首个观察刻由距离更新自动初始化。 */
    private long lastApproachTick = Long.MIN_VALUE;
    /** 移动记分牌的量化档位：档内行走不触发进度事件（契约见 docs/architecture/07-attention.md）。 */
    private static final int DISTANCE_QUANTUM_BLOCKS = AbstractCompanionTask.DISTANCE_QUANTUM_BLOCKS;
    /** 出发时（或路线重估后）的分母；剩余变大说明在绕路或新路线更长，分母跟着刷新。 */
    private int initialRemaining = -1;
    /** 唯一一次近距离重试恢复阶梯已用完；此阶梯状态会在挂起期间保留。 */
    private boolean nearRetried;
    /** 规划收敛熔断触发后唯一一次「井口旁楼梯头」降级行程：先站到目标柱旁一格，再用全新搜索恢复原目标。 */
    private boolean degradedShaftLegTried;
    /** 当前导航是否为降级行程；到达后恢复原目标并重开规划。 */
    private boolean degradedShaftLegActive;
    private boolean worldViewPrepared;
    /** 已知格中转段只试一次：熔断或无路失败后先走到对本目标最有增量的已知可站立格，再全新搜索恢复原目标。 */
    private boolean knownCellLegTried;
    /** 进行中的中转段目标；到达后清空。非空且 tried=true 表示该段已用过但尚未走完。 */
    private BlockPos knownCellLegTarget;
    private long landingBaseline = Long.MAX_VALUE;
    private Map<String,Object> landingFacts = Map.of();
    /** 到达后落地保护仍未验证或已失败；到达事实成立，只作注记降级交付。 */
    private boolean landingProtectionUnverified;
    private final JetpackGroundMode groundFlight=
            new JetpackGroundMode();
    /** FIND(就近方块)子系统:扫描/入册/契约/轮换全在组件里,此处只驱动。 */
    private NearestBlockFinder finder;

    /** 行船段:开工时坐在船上就先驾船,靠岸(或搁浅)后接步行。null = 没有/已交棒。 */
    private BoatNav boatLeg;

    /** 到达后的自有垫柱回收；非空表示回收阶段进行中或已收场（成功路径收尾注记消费）。 */
    private PillarRecovery pillarRecovery;

    public MoveToCompanionTask(LocalPlayer player, MoveToTaskRecord record) {
        super(player, record);
        planningBudget = record.progressBudget(PLANNING_IDLE_TICKS);
        this.bx = record.x != null ? (int) Math.floor(record.x) : 0;
        this.by = record.y != null ? (int) Math.floor(record.y) : 0;
        this.bz = record.z != null ? (int) Math.floor(record.z) : 0;
        this.blockTarget = new BlockPos(bx, by, bz);
    }

    @Override
    protected void onStart() {
        // 已经坐船且目标适合驾船时先走水路；找某种方块则先扫描，其他目标直接准备导航。
        landingBaseline = LandingAssistPolicy.observation().revision();
        // 载具处置:坐在船上且有明确去处,先驾船——行船段走到离目标最近的水格,
        // 靠岸后接步行(见 tickBoatLeg)。其余情况(矿车没有舵、马的寻路仍按步行
        // 物理算、FIND 要先扫描)直接走步行段;下座驾是步行导航自己的事(PlayerNav)。
        if (player.isPassenger()
                && (r.transportMode == TransportMode.AUTO
                    || r.transportMode == TransportMode.GROUND)
                && player.getVehicle() instanceof Boat
                && (r.kind == MoveToTaskRecord.Kind.BLOCK || r.kind == MoveToTaskRecord.Kind.COLUMN)
                && !reached()) {
            boatLeg = new BoatNav(player, blockTarget);
            long extra = Math.min(MAX_EXTRA_TICKS,
                    600 + (long) (repDistance() * TICKS_PER_BLOCK));
            r.extendDeadlineTo(player.level().getGameTime() + extra);
            Constants.LOG.info(
                    "[maicraft-task] goto start kind={} target={},{},{} 驾船先行",
                    r.kind, bx, by, bz);
            return;
        }
        if (r.kind == MoveToTaskRecord.Kind.FIND) {
            // 就近方块:解析 id → 离线扫描附近候选;导航等首批候选到手再建
            var id = ResourceLocation.tryParse(r.block);
            var b = id == null ? null : BuiltInRegistries.BLOCK.get(id);
            if (b == null || b == Blocks.AIR) {
                fail("unknown block id '" + r.block
                        + "' — use a namespaced block id like minecraft:crafting_table",
                        FailureType.NO_PATH);
                return;
            }
            finder = new NearestBlockFinder(player, b);
            long findExtra = Math.min(MAX_EXTRA_TICKS,
                    600 + (long) NearestBlockFinder.BUDGET_BLOCKS * TICKS_PER_BLOCK);
            r.extendDeadlineTo(player.level().getGameTime() + findExtra);
            finder.kickScan();
            Constants.LOG.info(
                    "[maicraft-task] goto start kind=FIND block={}", r.block);
            return;
        }
        // 已经符合到达条件时不用新建导航，下一次 tick 就可以报告成功。
        if (reached()) return;
        // 出发前上超预检：只有授权动土（规划器才会垫柱）且目标确在头顶方向时才有意义；
        // 未授权路径与常规目标行为完全不变（169）。
        if (r.kind == MoveToTaskRecord.Kind.BLOCK && r.mayAlterTerrain
                && by > feet().getY() && !targetCellSolid()) {
            var precheck = TravelUphillPrecheck.evaluate(player.level(), blockTarget, feet().getY());
            if (precheck.gated()) {
                Constants.LOG.info(
                        "[maicraft-task] goto end kind={} result=failed type=NO_PATH feet={}"
                                + " reason=uphill_precheck verdict={} surface_y={} rise={}",
                        r.kind, player.blockPosition().toShortString(),
                        precheck.verdict(), precheck.surfaceY(), precheck.rise());
                fail("cannot plan an honest route to " + bx + "," + by + "," + bz
                        + TravelUphillPrecheck.advice(precheck, blockTarget), FailureType.NO_PATH);
                return;
            }
        }
        startWalkingNav();
    }

    /** 包任务可见的预检入口，供回归直接核对闸门判定；不做任何世界改动。 */
    TravelUphillPrecheck.Result uphillPrecheck() {
        return r.kind == MoveToTaskRecord.Kind.BLOCK && r.mayAlterTerrain
                && by > feet().getY() && !targetCellSolid()
                ? TravelUphillPrecheck.evaluate(player.level(), blockTarget, feet().getY())
                : TravelUphillPrecheck.Result.ALLOW;
    }

    /** 这次 goto 的地形许可:模型点头了才开路,否则只走不改。四处建导航都从这儿取。 */
    private PlayerNav.ContextProvider terrain() {
        return r.mayAlterTerrain ? PlayerNav.ContextProvider.TERRAFORM
                : r.allowLandingAssists ? PlayerNav.ContextProvider.LANDING_ONLY
                : r.allowWaterBucketFall ? PlayerNav.ContextProvider.WATER_ONLY : PlayerNav.ContextProvider.DEFAULT;
    }

    /**
     * 步行段的启动:预算、租约、建导航。开工时走它,行船段靠岸后接力也走它——
     * 两个入口一份逻辑。
     */
    private void startWalkingNav() {
        // 初始预算按直线距离估算（此处还无法得知地形难度）；旅程开始后改由下方的进度期限续期。
        long extra = Math.min(MAX_EXTRA_TICKS, 600 + (long) (repDistance() * TICKS_PER_BLOCK));
        r.extendDeadlineTo(player.level().getGameTime() + extra);
        // 完整坐标与只给水平坐标使用各自的到达范围；BLOCK 在此表示坐标目标，不是 FIND 的方块类型搜索。
        nav = navigationOptions(r.kind == MoveToTaskRecord.Kind.BLOCK
                ? PlayerNav.to(player, this::blockCompiled, WALK_SPEED, this::reached, terrain())
                : PlayerNav.toGoal(player, this::goal, WALK_SPEED, this::reached, terrain()));
        Constants.LOG.info(
                "[maicraft-task] goto start kind={} target={},{},{} solid={}",
                r.kind, bx, by, bz,
                r.kind == MoveToTaskRecord.Kind.BLOCK && targetCellSolid());
    }

    private PlayerNav navigationOptions(PlayerNav candidate) {
        // 保留实际行走和可用交通；局部交互站位不再为了诊断“假如能挖路”而阻塞父级的其他站位。
        candidate.withTransportMode(r.transportMode);
        return r.skipTerrainProbe?candidate:candidate.withTerrainProbe();
    }

    /** 将任务单里的目的地要求交给导航；找方块时用已经选出的候选位置。 */
    private NavGoal goal() {
        return switch (r.kind) {
            case BLOCK -> blockGoal();
            case COLUMN -> r.coordinateGoal();
            case YLEVEL -> NavGoal.yLevel(by);
            case FIND -> finder.contract() == null ? null : finder.contract().goal();
        };
    }

    /** 规划和实时到达检查共用同一坐标范围。 */
    private NavGoal blockGoal() {
        return blockCompiled().goal();
    }

    /** 内部精确站位与允许容差的公开目的地分别使用各自的成员判定。 */
    private GoalCompiler.Compiled blockCompiled() {
        return new GoalCompiler.Compiled(
                r.coordinateGoal(), LongSets.emptySet());
    }

    /** 目标格是否有碰撞形状占据，导致角色脚位不能进入？ */
    private boolean targetCellSolid() {
        return !player.level().getBlockState(blockTarget)
                .getCollisionShape(player.level(), blockTarget).isEmpty();
    }

    /** 返回识别半砖后的脚位节点，而非原始 blockPosition；站在下半砖上时按规划器约定视作处于其上方格。 */
    private BlockPos feet() {
        return BlockHelper.playerFeet(
                player.level(), player.getX(), player.getY(), player.getZ());
    }

    /** 到达不能只看脚刚碰到目标格：还要看脚下支撑是否也在目标范围内，避免起跳到最高点就停下导致坠落。 */
    private boolean reached() {
        boolean supportedGoalMembership = inGoalCell(feet())
                && inGoalCell(Movement.pathStart(player));
        // 精确站位还要真的落地；非精确移动允许在水中到达，但不把普通空中经过当作已到达。
        return supportedGoalMembership && (player.onGround() || !r.requiresStrictStance() && player.isInWater());
    }

    /** 已进入准确目标格、只是还没落地时继续等重力落下；游泳、攀爬、飞行或悬浮不能当成同一种落地过程。 */
    private boolean strictLandingInProgress() {
        if (!r.requiresStrictStance() || player.onGround() || !inGoalCell(feet())) {
            return false;
        }
        if (!strictTargetLandable()) {
            return false;
        }
        return !player.isInWater()
                && !player.isInLava()
                && !player.isSwimming()
                && !player.onClimbable()
                && !player.isPassenger()
                && !player.isFallFlying()
                && !player.isNoGravity()
                && !player.hasEffect(MobEffects.LEVITATION)
                && !player.isSpectator()
                && !player.getAbilities().flying;
    }

    private boolean strictTargetLandable() {
        return BlockHelper.isStandable(
                player.level(), blockTarget);
    }

    /** 每种目标只保留一套成员判定并与搜索共用：BLOCK 按到达模式要求脚位格等于目标，COLUMN 比较 x/z，YLEVEL 比较 y 并要求角色落地。 */
    private boolean inGoalCell(BlockPos cell) {
        return switch (r.kind) {
            case BLOCK -> blockGoal().isAt(cell);
            case COLUMN -> r.coordinateGoal().isAt(cell);
            case YLEVEL -> cell.getY() == by && player.onGround();
            case FIND -> finder.contract() != null && finder.contract().goal().isAt(cell);
        };
    }

    @Override
    protected TaskState onTick() {
        // 先处理已经开始的交通和导航，再判断是否到达；飞行刚碰地、电梯刚到楼层，都可能还没完成收尾。
        observeLanding();
        // 到达后的垫柱回收阶段：导航已停，只剩逐格下拆；终态在这里落定。
        if (pillarRecovery != null) {
            return tickPillarRecovery();
        }
        // 现有导航必须先消费其原生完成回执，之后任务清理才可停止它；仅仅碰到船舱地板或喷气背包触地，不代表退出或模式恢复已经完成。
        if (nav == null && reached()) return successAtBody();
        // 只有本次移动开始前明确退出旧页面；后续导航换装备或交通准备自己的菜单不被每刻抢关。
        if (!worldViewPrepared) {
            var context = ClientRuntime.requireContext(player);
            if (!context.menus().ensureWorldVisible(context)) return TaskState.RUNNING;
            worldViewPrepared = true;
        }
        if (boatLeg != null) {
            return tickBoatLeg();
        }
        if(player.onGround() && !reached() && !TransportRuntime.occupied()
                && (r.transportMode==TransportMode.GROUND
                    || r.transportMode==TransportMode.AUTO)) {
            var context=ClientRuntime.requireContext(player);
            if(!groundFlight.prepare(context)) {
                context.body().releaseAll();
                if(groundFlight.failed()) { fail(groundFlight.diagnostics().toString(),FailureType.UNKNOWN); return TaskState.FAILED; }
                return TaskState.RUNNING;
            }
        }
        if (r.kind == MoveToTaskRecord.Kind.FIND && nav == null) {
            TaskState pre = tickFindDiscovery();
            if (pre != null) {
                return pre;
            }
        }
        if (nav == null) {
            fail(blockedMessage("no path"), FailureType.NO_PATH);
            return TaskState.FAILED;
        }
        // 绕湖或长距离算路可能暂时没有距离缩短，按新计算事实和真实身体进展共同续期。
        // 已到目标格、只等落地时不无限续期，避免身体悬着不落也永远不超时。
        boolean awaitingStrictLanding = strictLandingInProgress();
        boolean planning = nav.planningInFlight();
        long now = player.level().getGameTime();
        boolean stalled = planningBudget.observeCounter(now,
                awaitingStrictLanding ? 0 : nav.lastVerifiedProgressTick());
        if (planning && stalled) {
            fail("planning_stall: route planning made no verified progress for about "
                    + PLANNING_IDLE_TICKS / 20 + " active seconds; no no-path conclusion was established; "
                    + nav.outcomeSummary(), FailureType.PLANNING_STALL);
            return TaskState.FAILED;
        }
        // 另外记录身体是否仍在靠近，例如水中自然下沉；越接近就重新开始计算“等待稳定”的时间。
        double d = repDistance();
        if (d < bestDist - 0.1) {
            bestDist = d;
            lastApproachTick = now;
            settleTicks = 0;
        } else {
            settleTicks++;
        }
        // 无进展预算管不住“持续有产出的搜索”：地下挖洞寻路能一直展开节点却永不接近目标。
        // 累计工作量越过熔断线且最近距离长时间无收敛时，把这次规划判成病态并给出明确失败口径，
        // 已探索的搜索事实随任务回执一并交付，让调用方决定换路线还是放弃。
        planningWorkHighWater = Math.max(planningWorkHighWater, nav.planningProgressUnits());
        if (planning
                && planningWorkHighWater > PLANNING_WORK_FUSE_UNITS
                && lastApproachTick != Long.MIN_VALUE
                && now - lastApproachTick >= PLANNING_CONVERGENCE_WINDOW_TICKS) {
            // 规划从原站位不收敛时，先试一次已知格中转：例如本会话亲自挖出并站立过的井底格，
            // 从那里恢复原目标比在远处扩展搜索有增量得多。无候选（未交付已知格）时自然落空。
            if (tryKnownCellWaypointLeg("planning did not converge")) return TaskState.RUNNING;
            // 已知格走不通再给一次预算内的降级行程（同型教训：目标在脚下竖井底时，搜索从
            // 原站位出发的所有下降边都被准入闸门诚实拒绝，绕行楼梯的空间它自己走不完）：
            // 先站到目标柱旁两格的「楼梯头」，再用全新搜索恢复原目标——起点离开井口柱后，
            // 楼梯下掘不再与被拒的直降前沿竞争。只试一次，降级行程单独计量熔断工作量。
            if (!degradedShaftLegTried && r.kind == MoveToTaskRecord.Kind.BLOCK
                    && by <= feet().getY() - 3 && r.mayAlterTerrain) {
                degradedShaftLegTried = true;
                degradedShaftLegActive = true;
                planningWorkHighWater = 0;
                lastApproachTick = now;
                BlockPos head = staircaseHeadCell();
                Constants.LOG.info(
                        "[maicraft-task] goto 规划不收敛，先走井口旁降级行程 head={} 目标={},{},{}",
                        head.toShortString(), bx, by, bz);
                stopNav();
                r.extendDeadlineTo(now + PROGRESS_LEASE_TICKS);
                NavGoal headGoal = NavGoal.exact(head);
                nav = navigationOptions(PlayerNav.to(player,
                        () -> new GoalCompiler.Compiled(headGoal, LongSets.emptySet()),
                        WALK_SPEED, () -> false, terrain()));
                return TaskState.RUNNING;
            }
            fail("planning did not converge: the dig-route search has banked " + planningWorkHighWater
                    + " work units while the closest approach stayed " + String.format("%.1f", bestDist)
                    + " blocks from the target for about " + (PLANNING_CONVERGENCE_WINDOW_TICKS / 20)
                    + " seconds with no improvement; " + nav.outcomeSummary()
                    + belowTargetEgressAdvice()
                    + " Pick a nearer waypoint, approach the target from another direction,"
                    + " or abandon this destination." + knownCellLegSummary(),
                    FailureType.PLANNING_STALL);
            return TaskState.FAILED;
        }
        return switch (nav.tick()) {
            case RUNNING -> TaskState.RUNNING;
            case ARRIVED -> {
                // 中转段走到已知格后不在此判到达：站稳即停掉该段导航，用全新搜索恢复原目标。
                if (knownCellLegTarget != null) {
                    if (!nav.isSafeToCancel()) yield TaskState.RUNNING;
                    Constants.LOG.info(
                            "[maicraft-task] goto 已知格中转段完成 waypoint={} 恢复原目标 {},{},{}",
                            knownCellLegTarget.toShortString(), bx, by, bz);
                    knownCellLegTarget = null;
                    stopNav();
                    startWalkingNav();
                    yield TaskState.RUNNING;
                }
                if (!nav.isSafeToCancel()) yield TaskState.RUNNING;
                // 降级行程到头：站上楼梯头后用全新搜索恢复原目标；新一段的熔断工作量单独计量。
                if (degradedShaftLegActive) {
                    degradedShaftLegActive = false;
                    stopNav();
                    planningWorkHighWater = 0;
                    lastApproachTick = player.level().getGameTime();
                    startWalkingNav();
                    yield TaskState.RUNNING;
                }
                // 导航说路线到头了，还要按玩家实际身体检查；exact 任务不能用附近的落脚点替代。
                if (r.requiresStrictStance()) {
                    if (reached()) {
                        if (beginPillarRecovery()) yield TaskState.RUNNING;
                        yield successAtBody();
                    }
                    if (strictLandingInProgress()) {
                        yield TaskState.RUNNING;
                    }
                    Constants.LOG.info(
                            "[maicraft-task] goto end kind={} result=failed type=NO_PATH"
                                    + " feet={} reason=route ended without the exact grounded stance",
                            r.kind, player.blockPosition().toShortString());
                    fail(blockedMessage(
                                    "the route ended without occupying the exact grounded stance"),
                            FailureType.NO_PATH);
                    yield TaskState.FAILED;
                }
                if (!reached()) {
                    if (!player.onGround() && !player.isInWater()) yield TaskState.RUNNING;
                    fail(blockedMessage("the route ended outside the supported destination region"), FailureType.NO_PATH);
                    yield TaskState.FAILED;
                }
                if (beginPillarRecovery()) yield TaskState.RUNNING;
                yield successAtBody();
            }
            case FAILED -> {
                // 交通已经可能产生副作用而结果不明时，不因“已经很近”就当成功，也不自动换目标重试。
                if (nav.failType() == FailureType.UNKNOWN) {
                    fail(blockedMessage(nav.failReason()), nav.failType());
                    yield TaskState.FAILED;
                }
                // 降级行程自身打不通：不再绕路，按原目标直接收场并给出场景化出路。
                if (degradedShaftLegActive) {
                    String reason = nav.failReason();
                    stopNav();
                    Constants.LOG.info(
                            "[maicraft-task] goto end kind={} result=failed type=NO_PATH"
                                    + " feet={} reason=degraded shaft-leg approach failed",
                            r.kind, player.blockPosition().toShortString());
                    fail(blockedMessage("the degraded approach beside the target shaft also failed: "
                            + reason) + belowTargetEgressAdvice(), FailureType.NO_PATH);
                    yield TaskState.FAILED;
                }
                // FIND:打不通就近候选 -> 除名,朝余下候选重开导航
                if (r.kind == MoveToTaskRecord.Kind.FIND && finder.rotateAfterFailure()) {
                    stopNav();
                    nav = navigationOptions(PlayerNav.to(player, finder::contract, WALK_SPEED, this::reached, terrain()));
                    yield TaskState.RUNNING;
                }
                // 水中可能还会自然漂近或下沉，非精确移动再等一小段时间；一直没有靠近就继续处理失败。
                if (!r.requiresStrictStance()
                        && player.isInWater() && settleTicks < MAX_SETTLE_TICKS) {
                    yield TaskState.RUNNING;
                }
                // 否则按地形允许的最近位置处理，再判断是否满足教学性成功或必须失败。
                if (!r.requiresStrictStance() && closeEnoughToSucceed()) {
                    if (beginPillarRecovery()) yield TaskState.RUNNING;
                    yield successAtBody();
                }
                // 旧兼容逻辑允许水平误差为零的非精确目标再试一次附近三格；这可能放宽调用者明确给出的零误差。
                if (!r.requiresStrictStance() && r.horizontalRadius == 0 && !nearRetried && !player.isInWater()
                        && r.kind != MoveToTaskRecord.Kind.YLEVEL
                        && r.kind != MoveToTaskRecord.Kind.FIND) {
                    nearRetried = true;
                    stopNav();
                    NavGoal retry = nearRetryGoal();
                    nav = navigationOptions(PlayerNav.toGoal(player, () -> retry, WALK_SPEED, this::closeEnoughToSucceed,terrain()));
                    yield TaskState.RUNNING;
                }
                // 精确站位确认无路时也试一次已知格中转：既有矿道入口旁的已知格会让恢复搜索
                // 从通道内部继续，而不是再次从洞外整体规划被洞口准入拒绝。
                if (r.requiresStrictStance() && nav.failType() == FailureType.NO_PATH
                        && tryKnownCellWaypointLeg("no path to the exact target")) {
                    yield TaskState.RUNNING;
                }
                String also = nearRetried
                        ? " (also retried accepting anywhere within "
                                + (int) NEAR_SUCCESS_RADIUS + " blocks — no path either)"
                        : "";
                Constants.LOG.info(
                        "[maicraft-task] goto end kind={} result=failed type={} feet={} reason={}",
                        r.kind, nav.failType(), player.blockPosition().toShortString(),
                        nav.failReason());
                fail(blockedMessage(nav.failReason() + also) + knownCellLegSummary(), nav.failType());
                yield TaskState.FAILED;
            }
        };
    }

    /**
     * 到达后进入垫柱回收阶段：从旅程放置账里认领脚下连续自有柱，交给逐格下拆驱动。
     * 没有垫过柱（或脚下不是自有柱）时返回 false，收尾照旧。回收尽力而为：
     * 拆不动时按 RESIDUE 声明剩余垫块，到达事实不被回收成败否决。
     */
    private boolean beginPillarRecovery() {
        if (pillarRecovery != null) return false;
        if (!r.mayAlterTerrain || !player.onGround()) return false;
        var forbidden = terrain().embeddedForbiddenBodyCells();
        stopNav();   // 先把最后一段导航的地形账并入旅程账，再据此认领柱列。
        var column = PillarRecovery.ownPillarUnder(journeyPlacedCells(), feet(), Integer.MAX_VALUE);
        if (column.isEmpty()) return false;
        pillarRecovery = new PillarRecovery(player, column,
                at -> player.level().isLoaded(at),
                forbidden,
                terrain());
        Constants.LOG.info(
                "[maicraft-task] goto 到达后回收自有垫柱 column_top={} blocks={}",
                column.getFirst().toShortString(), column.size());
        return true;
    }

    /** 回收阶段的一刻：续期截止时间并驱动下拆，终态后带注记按成功收场。 */
    private TaskState tickPillarRecovery() {
        r.extendDeadlineTo(player.level().getGameTime() + PROGRESS_LEASE_TICKS);
        var status = pillarRecovery.tick();
        if (status == PillarRecovery.Status.RUNNING) return TaskState.RUNNING;
        Constants.LOG.info(
                "[maicraft-task] goto 垫柱回收收场 status={} feet={}",
                status, player.blockPosition().toShortString());
        return successAtBody();
    }

    /**
     * 行船段的一刻:驾船朝目标推进,终态(靠岸或搁浅)都走同一条接力——到不了目标的
     * 水路不算失败,只是"这段行船到此为止",剩下的路归步行段(步行导航起步自会下船)。
     * 目标就在水上时她留在船里,不往水里跳。船留在原地,那是她的船,不是垃圾。
     */
    private TaskState tickBoatLeg() {
        // 船走不下去时可以停船后换步行继续；整项移动是否成功，仍要按总目的地判断。
        // 行船段的续约与步行段同一制式:还在消耗航线就把期限保持在租约窗口里
        if (boatLeg.progressing()) {
            long now = player.level().getGameTime();
            r.extendDeadlineTo(now + PROGRESS_LEASE_TICKS);
        }
        var status = boatLeg.tick();
        if (status == BoatNav.Status.RUNNING) {
            return TaskState.RUNNING;
        }
        String how = status == BoatNav.Status.ARRIVED
                ? "靠岸" : boatLeg.failReason();
        boatLeg.stop();
        boatLeg = null;
        if (reached()) {
            Constants.LOG.info(
                    "[maicraft-task] 行船段结束({}),目标已在船下 feet={}", how,
                    player.blockPosition().toShortString());
            return successAtBody();
        }
        Constants.LOG.info(
                "[maicraft-task] 行船段结束({}),接步行 feet={}", how,
                player.blockPosition().toShortString());
        startWalkingNav();
        return TaskState.RUNNING;
    }

    /** 失败后的再试目标：完整坐标仍用原范围，只给 x/z 时改成附近三格。 */
    private NavGoal nearRetryGoal() {
        if (r.kind == MoveToTaskRecord.Kind.BLOCK) {
            return blockGoal();
        }
        // COLUMN 在任意高度按水平半径判断；NavGoal.near 是三维球体，需要此目标没有的 Y 值，因此复用柱列自身的估价和中心点。
        final int targetX = bx;
        final int targetZ = bz;
        final NavGoal column = NavGoal.column(targetX, targetZ);
        final double radiusSqr = NEAR_SUCCESS_RADIUS * NEAR_SUCCESS_RADIUS;
        return new NavGoal() {
            @Override public boolean isAt(BlockPos feet) {
                double dx = feet.getX() - targetX;
                double dz = feet.getZ() - targetZ;
                return dx * dx + dz * dz <= radiusSqr;
            }
            @Override public double heuristic(BlockPos from) {
                return column.heuristic(from);
            }
            @Override public BlockPos center() {
                return column.center();
            }
        };
    }

    /**
     * 已知格中转段：从任务单交付的已知可站立位置里挑「离目标最近且比当前站位有明显增量」的一格，
     * 先走到那里，再用全新搜索恢复原目标。井底是本会话亲自挖出并站立过的格子时，规划器不必把它
     * 当陌生埋藏点从头搜。只试一次；这段打不通就诚实失败，不自动换格重试。
     */
    private boolean tryKnownCellWaypointLeg(String reason) {
        if (knownCellLegTried || r.knownStandableCells().isEmpty()) return false;
        BlockPos from = feet();
        double fromDistance = cellDistanceToTarget(from);
        BlockPos best = null;
        double bestDistance = fromDistance;
        for (BlockPos cell : r.knownStandableCells()) {
            // 测试桩的 level 没有区块源，isLoaded 沿高度评估链必然 NPE：跳过已加载过滤（生产 Level 恒有区块源，语义不变）。
            boolean chunkFilter = player.level().getChunkSource() != null;
            if (cell.equals(from) || inGoalCell(cell) || chunkFilter && !player.level().isLoaded(cell)) continue;
            double distance = cellDistanceToTarget(cell);
            if (distance + KNOWN_CELL_WAYPOINT_MARGIN < bestDistance) {
                best = cell;
                bestDistance = distance;
            }
        }
        if (best == null) return false;
        knownCellLegTried = true;
        knownCellLegTarget = best;
        stopNav();
        long extra = Math.min(MAX_EXTRA_TICKS, 600 + (long) (repDistance() * TICKS_PER_BLOCK));
        r.extendDeadlineTo(player.level().getGameTime() + extra);
        BlockPos waypoint = best;
        nav = navigationOptions(PlayerNav.toGoal(player, () -> NavGoal.exact(waypoint), WALK_SPEED,
                () -> waypointReached(waypoint), terrain()));
        Constants.LOG.info(
                "[maicraft-task] goto 已知格中转段启动 reason={} waypoint={} from={}",
                reason, waypoint.toShortString(), from.toShortString());
        return true;
    }

    /** 中转段的到达判定：脚确实落在已知格且有支撑，与任务目标无关。 */
    private boolean waypointReached(BlockPos waypoint) {
        return player.onGround() && waypoint.equals(feet());
    }

    /** 方块格到目标格的中心距离，供中转段比较增量。 */
    private double cellDistanceToTarget(BlockPos cell) {
        double dx = bx - cell.getX();
        double dy = by - cell.getY();
        double dz = bz - cell.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** 失败回执里对中转段的交代：试过什么、走到了哪一步。 */
    private String knownCellLegSummary() {
        if (!knownCellLegTried) return "";
        return " A known standable cell waypoint leg"
                + (knownCellLegTarget == null
                        ? " reached its cell, but the restored search still failed to converge."
                        : " to " + knownCellLegTarget.toShortString() + " also failed to plan.")
                + " Travel to a verified underground cell first (or pick a nearer waypoint) and retry from there.";
    }

    /** 找路失败后是否仍可接受当前落脚点；COLUMN 的零半径会在这里按三格算，高度目标也额外接受一格偏差。 */
    private boolean closeEnoughToSucceed() {
        if (!player.onGround() && !player.isInWater()) {
            return false;
        }
        return switch (r.kind) {
            case BLOCK -> blockGoal().isAt(feet());
            case COLUMN -> r.horizontalRadius > 0 ? r.coordinateGoal().isAt(feet())
                    : horizontalDistSqr(bx, bz) <= NEAR_SUCCESS_RADIUS * NEAR_SUCCESS_RADIUS;
            case YLEVEL -> Math.abs(feet().getY() - by) <= 1;
            // FIND 候选众多,失败梯已在候选间轮换过,不设贴近成功档
            case FIND -> false;
        };
    }

    private double horizontalDistSqr(int cellX, int cellZ) {
        double dx = (cellX + 0.5) - player.getX();
        double dz = (cellZ + 0.5) - player.getZ();
        return dx * dx + dz * dz;
    }

    /**
     * 到达后保存实际位置，供后续引用。自动落地保护未验证或已失败时，到达事实仍成立：
     * 终态按到达交付，只附带 landing_protection_unverified 注记让调用方自行复检，
     * 不再把「身体已在目的地」整体否决成失败。
     */
    private TaskState successAtBody() {
        observeLanding();
        landingProtectionUnverified = landingProtectionUnverified(landingFacts);
        if (landingProtectionUnverified) {
            Constants.LOG.info(
                    "[maicraft-task] goto end kind={} result=success feet={} requested={}"
                            + " landing_protection_unverified=true",
                    r.kind, player.blockPosition().toShortString(), blockTarget.toShortString());
        }
        BlockPos body = player.blockPosition();
        // 到达即留痕:最终脚位与请求格同框——"报 exact 成功却站在别处"这类悬案,
        // 下一轮实机的第一现场就在这一行。
        Constants.LOG.info(
                "[maicraft-task] goto end kind={} result=success feet={} requested={}",
                r.kind, body.toShortString(), blockTarget.toShortString());
        r.retainVerifiedPosition(new InternalPositionReceipt.Position(
                body.getX(), body.getY(), body.getZ(),
                player.level().dimension().location().toString()));
        return TaskState.SUCCESS;
    }

    /** 落地保护事实表明这轮自动保护已收场且失败/未确认；到达事实不受它否决。 */
    static boolean landingProtectionUnverified(Map<String, Object> facts) {
        return Boolean.TRUE.equals(facts.get("complete")) && Boolean.TRUE.equals(facts.get("failed"));
    }

    /** 目的地已知（x/z 均给出）时计算到达分级；FIND、纯高度目标没有固定格中心可比。 */
    private ArrivalVerdict arrivalVerdict() {
        if (r.x == null || r.z == null) return null;
        return ArrivalVerdict.of(player.getX(), player.getY(), player.getZ(),
                bx, by, bz, r.y != null);
    }

    /** 到达成立但安全层未验证时的注记；调用方据此自行复检脚下支撑。 */
    private String landingNote() {
        return landingProtectionUnverified
                ? " Note: the automatic landing protection ended unverified or failed after arrival;"
                  + " the body did stand at the destination, recheck ground support if the next"
                  + " action depends on it."
                : "";
    }

    /** 到达分级的可读后缀：等级 + 剩余水平距离与方向。 */
    private String arrivalGradeNote() {
        ArrivalVerdict verdict = arrivalVerdict();
        if (verdict == null) return "";
        return "; arrival " + verdict.grade()
                + " (" + String.format(java.util.Locale.ROOT, "%.1f", verdict.remainingHorizontal())
                + " blocks " + verdict.direction() + ")";
    }

    private void observeLanding() {
        var observation = LandingAssistPolicy.observation();
        if (observation.revision() > landingBaseline) landingFacts = observation.facts();
    }

    /** 返回截止时间估算所用的代表性剩余距离，单位为方块。 */
    private double repDistance() {
        return switch (r.kind) {
            case BLOCK -> Math.sqrt(player.distanceToSqr(bx + 0.5, by, bz + 0.5));
            case COLUMN -> Math.sqrt(horizontalDistSqr(bx, bz));
            case YLEVEL -> Math.abs(player.getY() - by);
            case FIND -> {
                BlockPos n = finder == null ? null : finder.nearest();
                yield n == null ? NearestBlockFinder.BUDGET_BLOCKS
                        : Math.sqrt(player.distanceToSqr(n.getX() + 0.5, n.getY() + 0.5, n.getZ() + 0.5));
            }
        };
    }

    // ==================== FIND（最近方块）驱动 ====================

    /**
     * 候选发现期(导航尚未建立)推进一步:收割扫描 -> 有候选即建导航
     * (返回 null 表示落入正常驱动),扫完仍无候选 -> 失败,否则继续等。
     */
    private TaskState tickFindDiscovery() {
        finder.drain();
        if (finder.hasCandidates()) {
            nav = navigationOptions(PlayerNav.to(player, finder::contract, WALK_SPEED, this::reached, terrain()));
            return null;
        }
        if (finder.exhausted()) {
            fail("no " + r.block + " found in the loaded area around me — explore"
                    + " closer to one, or give exact coordinates (scan_blocks/locate can find some).",
                    FailureType.NO_PATH);
            return TaskState.FAILED;
        }
        return TaskState.RUNNING;
    }

    @Override
    protected Map<String, Object> resultData() {
        // 记录最终身体位置和途中落地保护情况；哪些坐标能公开给模型由外层结果整理决定。
        int gy = player.blockPosition().getY();
        Map<String, Object> data = new HashMap<>();
        data.put("final_x", player.getX());
        data.put("final_y", player.getY());
        data.put("final_z", player.getZ());
        data.put("ground_y", gy);
        // 目标带高度提示时交付实际站位与提示的对照；调用方靠它察觉"仍在坑底/坡下"这类容差内却不够用的到达。
        if (r.y != null) {
            data.put("target_y_hint", by);
            data.put("y_hint_delta", gy - by);
        }
        data.put("planning_idle_budget", planningBudget.diagnostics(player.level().getGameTime()));
        data.put("planning_budget_unit", "active_game_ticks");
        // 搜索预算消耗与收敛趋势：调用方据此判断这条路线是“还在接近”还是“规划已病态”。
        data.put("planning_work_units", planningWorkHighWater);
        data.put("planning_work_fuse_units", PLANNING_WORK_FUSE_UNITS);
        data.put("degraded_shaft_leg_tried", degradedShaftLegTried);
        // 已知格中转的对账：任务单交付了多少已知格、中转段是否启用、启用了哪一格。
        data.put("known_standable_cells_supplied", r.knownStandableCells().size());
        if (knownCellLegTried) data.put("known_cell_waypoint_tried", true);
        if (knownCellLegTarget != null) {
            data.put("known_cell_waypoint", knownCellLegTarget.toShortString());
        }
        if (bestDist != Double.MAX_VALUE) {
            data.put("best_distance_blocks", Math.round(bestDist * 10) / 10.0);
        }
        data.put("ground_flight_mode",groundFlight.diagnostics());
        // 任务终局直接交付导航证据，避免模型为一次无路结果另开多轮观察。
        if (!finalNavigationEvidence.isEmpty()) data.put("navigation", finalNavigationEvidence);
        observeLanding();
        data.put("landing_assist_observed",!landingFacts.isEmpty());
        if (!landingFacts.isEmpty()) data.put("landing_assist",landingFacts);
        return data;
    }

    /** 到达成立时的分级交付：等级、剩余水平/垂直距离与方向，以及安全层注记。 */
    @Override
    protected Map<String, Object> resultData(TaskState finalState) {
        Map<String, Object> data = resultData();
        if (finalState != TaskState.SUCCESS) return data;
        ArrivalVerdict verdict = arrivalVerdict();
        if (verdict != null) {
            data.put("arrival_grade", verdict.grade());
            data.put("remaining_horizontal_blocks", verdict.remainingHorizontal());
            if (verdict.hasVerticalHint()) data.put("remaining_vertical_blocks", verdict.remainingVertical());
            data.put("arrival_direction", verdict.direction());
        }
        if (landingProtectionUnverified) data.put("landing_protection_unverified", true);
        // 垫柱回收对账：回收了哪些格、还剩哪些格；调用方靠它复验登顶柱没有留在世界里。
        if (pillarRecovery != null) {
            data.put("pillar_recovery", pillarRecovery.evidence());
        }
        return data;
    }

    /** 与请求高度提示的对照说明；提示与站位同高时不必解释。 */
    private String heightHintNote(int gy) {
        if (r.y == null) return "";
        int delta = gy - by;
        return " The requested height hint was y=" + by
                + (delta == 0 ? ", matching the standing height."
                        : "; standing " + Math.abs(delta) + " block(s) "
                        + (delta > 0 ? "above" : "below") + " it, within the vertical tolerance.");
    }

    /** 按目标类型说明到达结果；此处是内部文字，外层还可能删去具体坐标。 */
    @Override
    protected String successMessage() {
        int gy = player.blockPosition().getY();
        // 垫柱回收注记跟在到达事实之后：回收成立报净变化，残留时点名列出坐标与去向。
        String recovery = pillarRecovery == null ? "" : pillarRecovery.note();
        return switch (r.kind) {
            case BLOCK -> r.requiresStrictStance()
                    ? "reached the exact cell " + bx + "," + by + "," + bz + "." + landingNote()
                    : "arrived within " + r.horizontalRadius + " blocks horizontally and " + r.verticalTolerance
                            + " blocks vertically of the destination; supported at y=" + gy + "."
                            + arrivalGradeNote() + heightHintNote(gy) + landingNote();
            case COLUMN -> "arrived at location x=" + bx + " z=" + bz
                    + (player.isInWater() ? ", in water at y=" : ", standing on the ground at y=") + gy + "."
                    + arrivalGradeNote() + landingNote();
            case YLEVEL -> "reached elevation y=" + gy
                    + (gy == by ? "." : " (requested y=" + by + ").") + landingNote();
            case FIND -> {
                BlockPos n = finder.nearest();
                yield n == null
                        ? "arrived beside the target block." + landingNote()
                        : "arrived beside " + r.block + " at " + n.getX() + "," + n.getY()
                                + "," + n.getZ() + " — within reach to use." + landingNote();
            }
        } + recovery;
    }

    @Override
    protected String timeoutMessage() {
        int gy = player.blockPosition().getY();
        double remaining = repDistance();
        // 行走超时时把是否进入搜索、是否在备料一起写入日志，不能仅凭剩余距离推断地形无路。
        return "timed out " + String.format("%.1f", remaining) + " blocks from target (now at "
                + bx(gy) + "); verified route progress stopped long enough for the progress lease"
                + " to expire. Reassess the obstruction or continue from this position."
                + (nav == null ? "" : "; " + nav.outcomeSummary());
    }

    @Override
    protected String cancelledMessage() {
        return "cancelled before reaching target";
    }

    private String bx(int gy) {
        return String.format("%.0f,%d,%.0f", player.getX(), gy, player.getZ());
    }

    /** 结束后停导航、取消还没完成的找方块扫描，并停止当前船只控制。 */
    @Override
    protected void cleanup() {
        // 父类会释放导航引用；先保存失败时冻结的证据，再结束路线和原生按键。
        if (nav != null) finalNavigationEvidence = nav.transportDiagnostics();
        super.cleanup();
        if (finder != null) {
            finder.cancelScan();
        }
        if (boatLeg != null) {
            boatLeg.stop();   // 中途被取消/让位:收桨,别让船带着按下的前进键漂走
            boatLeg = null;
        }
        // 回收阶段被抢占或取消时停掉进行中的下拆；已确认的拆除由驱动结算交账，剩余垫块按残留声明。
        if (pillarRecovery != null) {
            pillarRecovery.stop();
        }
    }

    /** 规划失败且未接近到可视为到达时使用的放弃消息。必须在失败位置、导航尚未释放时捕获，
     *  这样才能在父类 {@code cleanup()} 停止导航前读取 {@code failReason}。 */
    /** 行走时如实显示剩余距离和绕路变化；尚未迈步的规划阶段另交付已确认计算量。 */
    @Override
    public Map<String, Object> progress() {
        double distance = repDistance();
        // 剩余按 16 格量化档向上取整：半档不再提前报 0，「剩余 0」与真实到达同刻成立。
        // 口径随事件声明（remaining_unit），调用方拿到的计数不再是无口径的裸格数。
        int remaining = AbstractCompanionTask.quantizedRemaining(distance);
        if (initialRemaining < 0 || remaining > initialRemaining) initialRemaining = remaining;
        boolean planning = nav != null && nav.planningInFlight();
        var result = new HashMap<String, Object>();
        result.putAll(Map.of("task", name(), "phase", planning ? "planning" : "moving",
                "remaining", remaining, "initial", initialRemaining,
                "remaining_unit", "straight_line_blocks_quantized_" + DISTANCE_QUANTUM_BLOCKS));
        result.put("planning_idle_budget", planningBudget.diagnostics(player.level().getGameTime()));
        result.put("planning_budget_unit", "active_game_ticks");
        if (planning) {
            result.put("done", nav.planningProgressUnits());
            result.put("progress_unit", "verified_planning_work_units");
            // 心跳：搜索零新进展时 done 停在原地，尝试次数仍单调增长，事件流不会在停滞期完全静默。
            result.put("calc", nav.planningCalcAttempts());
            // 收敛趋势：曾达到的最近距离按同一量化档交付；它不再缩小说明搜索在空转，可以提前取消。
            result.put("best_remaining", bestDist == Double.MAX_VALUE
                    ? Integer.MAX_VALUE
                    : AbstractCompanionTask.quantizedRemaining(bestDist));
        }
        return Map.copyOf(result);
    }

    /** 面板行动行的一句话汇报；目的地来自任务单，剩余距离按导航现场的量化档。 */
    @Override
    public String describeCurrentAction() {
        String destination;
        if (blockTarget != null) {
            destination = "(" + blockTarget.getX() + "," + blockTarget.getY() + "," + blockTarget.getZ() + ")";
        } else if (r.block != null) {
            destination = "最近的 " + r.block;
        } else if (r.kind == MoveToTaskRecord.Kind.YLEVEL) {
            destination = "y=" + (r.y == null ? "?" : r.y.intValue()) + " 层";
        } else if (r.x != null && r.z != null) {
            destination = "(" + r.x.intValue() + "," + r.z.intValue() + ")";
        } else {
            destination = "目标地点";
        }
        boolean planning = nav != null && nav.planningInFlight();
        return (planning ? "正在规划路线，之后前往 " : "正在前往 ") + destination
                + "，剩余约 " + AbstractCompanionTask.quantizedRemaining(repDistance()) + " 格";
    }

    /** 降级行程的楼梯头：目标柱旁两格、当前脚位高度；起点离开井口柱后，楼梯下掘的扩展前沿不再被直降拒绝支配。 */
    private BlockPos staircaseHeadCell() {
        int offX = offsetAxisTowardPlayer(player.getX(), bx);
        int offZ = offsetAxisTowardPlayer(player.getZ(), bz);
        if (offX == 0 && offZ == 0) offX = 2;   // 就站在目标柱上时固定向东偏移，保证离开井口柱。
        return new BlockPos(bx + offX, feet().getY(), bz + offZ);
    }

    /** 朝角色所在方向偏移两格；角色与目标同柱时返回 0，由另一轴提供偏移。 */
    private static int offsetAxisTowardPlayer(double playerCoord, int targetCoord) {
        double delta = playerCoord - (targetCoord + 0.5);
        if (Math.abs(delta) < 1.0) return 0;
        return delta > 0 ? 2 : -2;
    }

    /** 目标在脚下时的收场出路：直降竖井需要着陆授权或下方缓冲，否则从井口旁挖楼梯或分段路点。 */
    private String belowTargetEgressAdvice() {
        if (by >= feet().getY() - 3) return "";
        return " The target lies below the current stance: a one-drop descent into an open shaft"
                + " is only planned with landing-assist authorization (allow_landing_assists) or"
                + " water below; otherwise authorize terrain alteration and dig a staircase route"
                + " starting beside the shaft, or travel to a remembered standable cell inside the"
                + " shaft first and continue from there.";
    }

    private String blockedMessage(String failReason) {        int gy = player.blockPosition().getY();
        double remaining = repDistance();
        String where = switch (r.kind) {
            case BLOCK, COLUMN -> "location x=" + bx + " z=" + bz;
            case YLEVEL -> "elevation y=" + by;
            case FIND -> "the nearest " + r.block;
        };
        // 地形封路的验尸自带下一步,不再叠几何建议;策略挡路时出路是授权或垫料,
        // 其余无路才是几何问题:换近一点的路点或扫描。
        String advice = "";
        if (r.transportMode == TransportMode.JETPACK
                || r.transportMode == TransportMode.ELEVATOR) {
            advice = " Inspect the reported transport failure and destination support before choosing another transport goal.";
        } else if (nav.failType() == FailureType.TERRAIN_BLOCKED) {
            advice = " This route is only walkable with terrain alteration permitted"
                    + " (may_alter_terrain), or with scaffolding blocks to pillar or bridge up.";
        } else {
            advice = r.mayAlterTerrain && nav.failType() == FailureType.NO_MATERIAL
                    ? ScaffoldMaterials.shortageAdvice(player) : null;
            if (advice == null) {
                advice = " Inspect the destination support and nearby obstacles before selecting a new route.";
            }
        }
        return "blocked: got within " + String.format("%.1f", remaining) + " blocks of " + where
                + " (now " + (player.onGround() ? "grounded" : "not grounded")
                + " at y=" + gy + "). " + failReason + "." + advice;
    }
}
