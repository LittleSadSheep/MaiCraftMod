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

/**
 * 执行一次移动：到坐标附近、准确站到某格、改变高度，或找到某种方块走到旁边。
 * 具体路线由 PlayerNav 负责；这里决定何时开始、找不到路怎么办，以及玩家真的到达后怎样报告结果。
 * x/y/z 都有也不一定要求精确站位，是否精确由 exact 与到达误差共同决定。
 */
public final class MoveToCompanionTask extends AbstractCompanionTask<MoveToTaskRecord> {

    private static final long TICKS_PER_BLOCK = 20;
    private static final long MAX_EXTRA_TICKS = 5 * 60 * 20;
    /** Progress lease: while the journey is consuming its plan, the deadline is kept
     *  this far ahead — a healthy multi-minute dig route never times out mid-stride,
     *  and a stalled one still returns the body within one lease. */
    private static final long PROGRESS_LEASE_TICKS = 30 * 20;
    /** How recent "progress" must be to renew the lease. Generous enough to span one
     *  slow legitimate move (a long bare-hand dig holds the executor's progress clock
     *  at 0 anyway; this covers place maneuvers and replan gaps). */
    private static final int PROGRESS_GRACE_TICKS = 100;
    /** When the planner CAN'T reach the exact goal, a stop within this of the
     *  requested column still counts as "got there" (a teaching success, not a
     *  thrash). This is the only tolerance — arrival itself is exact. */
    private static final double WALK_SPEED = 1.0;
    private static final double NEAR_SUCCESS_RADIUS = 3.0;
    /** Once the planner can't get closer (e.g. it stopped at the water surface above an
     *  underwater goal), keep the task alive this many ticks of NO progress before giving
     *  up — long enough for the body to passively drift onto a reachable underwater target,
     *  short enough to bail under an out-of-reach above-water one. */
    private static final int MAX_SETTLE_TICKS = 60;

    private final int bx;
    private final int by;
    private final int bz;
    private final BlockPos blockTarget;   // only meaningful for BLOCK kind

    private double bestDist = Double.MAX_VALUE;   // closest we've gotten to the goal
    private int settleTicks = 0;                  // ticks of no progress after the planner gave up
    /** The one near-retry recovery rung has been consumed (ladder state — survives suspend). */
    private boolean nearRetried;
    private long landingBaseline = Long.MAX_VALUE;
    private Map<String,Object> landingFacts = Map.of();
    private final org.maiwithu.maicraft.core.integration.jetpack.JetpackGroundMode groundFlight=
            new org.maiwithu.maicraft.core.integration.jetpack.JetpackGroundMode();
    /** FIND(就近方块)子系统:扫描/入册/契约/轮换全在组件里,此处只驱动。 */
    private NearestBlockFinder finder;

    /** 船腿:开工时坐在船上就先驾船,靠岸(或搁浅)后接步行。null = 没有/已交棒。 */
    private org.maiwithu.maicraft.core.pathing.execute.BoatNav boatLeg;

    public MoveToCompanionTask(LocalPlayer player, MoveToTaskRecord record) {
        super(player, record);
        this.bx = record.x != null ? (int) Math.floor(record.x) : 0;
        this.by = record.y != null ? (int) Math.floor(record.y) : 0;
        this.bz = record.z != null ? (int) Math.floor(record.z) : 0;
        this.blockTarget = new BlockPos(bx, by, bz);
    }

    @Override
    protected void onStart() {
        // 已经坐船且目标适合驾船时先走水路；找某种方块则先扫描，其他目标直接准备导航。
        landingBaseline = org.maiwithu.maicraft.core.pathing.baritone.landing.LandingAssistPolicy.observation().revision();
        // 载具处置:坐在船上且有明确去处,先驾船——船腿走到离目标最近的水格,
        // 靠岸后接步行(见 tickBoatLeg)。其余情况(矿车没有舵、马的寻路仍按步行
        // 物理算、FIND 要先扫描)直接走步行段;下座驾是步行导航自己的事(PlayerNav)。
        if (player.isPassenger()
                && (r.transportMode == org.maiwithu.maicraft.core.pathing.transport.TransportMode.AUTO
                    || r.transportMode == org.maiwithu.maicraft.core.pathing.transport.TransportMode.GROUND)
                && player.getVehicle() instanceof net.minecraft.world.entity.vehicle.Boat
                && (r.kind == MoveToTaskRecord.Kind.BLOCK || r.kind == MoveToTaskRecord.Kind.COLUMN)
                && !reached()) {
            boatLeg = new org.maiwithu.maicraft.core.pathing.execute.BoatNav(player, blockTarget);
            long extra = Math.min(MAX_EXTRA_TICKS,
                    600 + (long) (repDistance() * TICKS_PER_BLOCK));
            r.extendDeadlineTo(player.level().getGameTime() + extra);
            org.maiwithu.maicraft.core.Constants.LOG.info(
                    "[maicraft-task] goto start kind={} target={},{},{} 驾船先行",
                    r.kind, bx, by, bz);
            return;
        }
        if (r.kind == MoveToTaskRecord.Kind.FIND) {
            // 就近方块:解析 id → 离线扫描附近候选;导航等首批候选到手再建
            var id = net.minecraft.resources.ResourceLocation.tryParse(r.block);
            var b = id == null ? null : net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(id);
            if (b == null || b == net.minecraft.world.level.block.Blocks.AIR) {
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
            org.maiwithu.maicraft.core.Constants.LOG.info(
                    "[maicraft-task] goto start kind=FIND block={}", r.block);
            return;
        }
        // 已经符合到达条件时不用新建导航，下一次 tick 就可以报告成功。
        if (reached()) return;
        startWalkingNav();
    }

    /** 这次 goto 的地形许可:模型点头了才开路,否则只走不改。四处建导航都从这儿取。 */
    private PlayerNav.ContextProvider terrain() {
        return r.mayAlterTerrain ? PlayerNav.ContextProvider.TERRAFORM
                : r.allowLandingAssists ? PlayerNav.ContextProvider.LANDING_ONLY
                : r.allowWaterBucketFall ? PlayerNav.ContextProvider.WATER_ONLY : PlayerNav.ContextProvider.DEFAULT;
    }

    /**
     * 步行段的启动:预算、租约、建导航。开工时走它,船腿靠岸后接力也走它——
     * 两个入口一份逻辑。
     */
    private void startWalkingNav() {
        // Initial budget from straight-line distance (terrain difficulty is unknowable
        // here — the progress lease below takes over once the journey is under way).
        long extra = Math.min(MAX_EXTRA_TICKS, 600 + (long) (repDistance() * TICKS_PER_BLOCK));
        r.extendDeadlineTo(player.level().getGameTime() + extra);
        // 完整坐标与只给水平坐标使用各自的到达范围；BLOCK 在此表示坐标目标，不是 FIND 的方块类型搜索。
        nav = (r.kind == MoveToTaskRecord.Kind.BLOCK
                ? PlayerNav.to(player, this::blockCompiled, WALK_SPEED, this::reached, terrain())
                : PlayerNav.toGoal(player, this::goal, WALK_SPEED, this::reached, terrain()))
                .withTransportMode(r.transportMode).withTerrainProbe();
        org.maiwithu.maicraft.core.Constants.LOG.info(
                "[maicraft-task] goto start kind={} target={},{},{} solid={}",
                r.kind, bx, by, bz,
                r.kind == MoveToTaskRecord.Kind.BLOCK && targetCellSolid());
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

    /** The same coordinate region drives planning and live arrival. */
    private NavGoal blockGoal() {
        return blockCompiled().goal();
    }

    /** Exact internal stances and tolerant public destinations keep their own membership. */
    private org.maiwithu.maicraft.core.pathing.goal.GoalCompiler.Compiled blockCompiled() {
        return new org.maiwithu.maicraft.core.pathing.goal.GoalCompiler.Compiled(
                r.coordinateGoal(), it.unimi.dsi.fastutil.longs.LongSets.emptySet());
    }

    /** Does a collision shape occupy the target cell (feet can't go there)? */
    private boolean targetCellSolid() {
        return !player.level().getBlockState(blockTarget)
                .getCollisionShape(player.level(), blockTarget).isEmpty();
    }

    /** Slab-aware feet cell — the pathing node, not raw blockPosition (standing on a
     *  bottom slab counts as the cell above it, like the planner sees it). */
    private BlockPos feet() {
        return org.maiwithu.maicraft.core.pathing.util.BlockHelper.playerFeet(
                player.level(), player.getX(), player.getY(), player.getZ());
    }

    /** 到达不能只看脚刚碰到目标格：还要看脚下支撑是否也在目标范围内，避免起跳到最高点就停下导致坠落。 */
    private boolean reached() {
        boolean supportedGoalMembership = inGoalCell(feet())
                && inGoalCell(org.maiwithu.maicraft.core.pathing.moves.Movement.pathStart(player));
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
                && !player.hasEffect(net.minecraft.world.effect.MobEffects.LEVITATION)
                && !player.isSpectator()
                && !player.getAbilities().flying;
    }

    private boolean strictTargetLandable() {
        return org.maiwithu.maicraft.core.pathing.util.BlockHelper.isStandable(
                player.level(), blockTarget);
    }

    /** ONE membership definition per kind, shared with the search:
     *  BLOCK (cell == target per arrival mode), COLUMN (x/z match),
     *  YLEVEL (y match + on the ground). */
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
        // An existing nav must consume its native completion before task cleanup can stop it.
        // Cabin floor contact or jetpack touchdown alone does not finish exit/mode restoration.
        if (nav == null && reached()) return successAtBody();
        if (boatLeg != null) {
            return tickBoatLeg();
        }
        if(player.onGround() && !reached() && !org.maiwithu.maicraft.core.pathing.transport.TransportRuntime.occupied()
                && (r.transportMode==org.maiwithu.maicraft.core.pathing.transport.TransportMode.GROUND
                    || r.transportMode==org.maiwithu.maicraft.core.pathing.transport.TransportMode.AUTO)) {
            var context=org.maiwithu.maicraft.client.runtime.ClientRuntime.requireContext(player);
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
        // 路线仍有实际进展或正在后台算路就延长时间；绕湖可能暂时离目标更远，不能只用距离缩短判断进展。
        // 已到目标格、只等落地时不无限续期，避免身体悬着不落也永远不超时。
        boolean awaitingStrictLanding = strictLandingInProgress();
        if (!awaitingStrictLanding && (nav.planningInFlight()
                || nav.hasRecentPhysicalProgress(PROGRESS_GRACE_TICKS))) {
            long now = player.level().getGameTime();
            r.extendDeadlineTo(now + PROGRESS_LEASE_TICKS);
        }
        // 另外记录身体是否仍在靠近，例如水中自然下沉；越接近就重新开始计算“等待稳定”的时间。
        double d = repDistance();
        if (d < bestDist - 0.1) {
            bestDist = d;
            settleTicks = 0;
        } else {
            settleTicks++;
        }
        return switch (nav.tick()) {
            case RUNNING -> TaskState.RUNNING;
            case ARRIVED -> {
                if (!nav.isSafeToCancel()) yield TaskState.RUNNING;
                // 导航说路线到头了，还要按玩家实际身体检查；exact 任务不能用附近的落脚点替代。
                if (r.requiresStrictStance()) {
                    if (reached()) {
                        yield successAtBody();
                    }
                    if (strictLandingInProgress()) {
                        yield TaskState.RUNNING;
                    }
                    org.maiwithu.maicraft.core.Constants.LOG.info(
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
                yield successAtBody();
            }
            case FAILED -> {
                // 交通已经可能产生副作用而结果不明时，不因“已经很近”就当成功，也不自动换目标重试。
                if (nav.failType() == FailureType.UNKNOWN) {
                    fail(blockedMessage(nav.failReason()), nav.failType());
                    yield TaskState.FAILED;
                }
                // FIND:打不通就近候选 -> 除名,朝余下候选重开导航
                if (r.kind == MoveToTaskRecord.Kind.FIND && finder.rotateAfterFailure()) {
                    stopNav();
                    nav = PlayerNav.to(player, finder::contract, WALK_SPEED, this::reached, terrain())
                            .withTransportMode(r.transportMode).withTerrainProbe();
                    yield TaskState.RUNNING;
                }
                // 水中可能还会自然漂近或下沉，非精确移动再等一小段时间；一直没有靠近就继续处理失败。
                if (!r.requiresStrictStance()
                        && player.isInWater() && settleTicks < MAX_SETTLE_TICKS) {
                    yield TaskState.RUNNING;
                }
                // Otherwise: as close as the terrain allows → (teaching) success or fail.
                if (!r.requiresStrictStance() && closeEnoughToSucceed()) {
                    yield successAtBody();
                }
                // 旧兼容逻辑允许水平误差为零的非精确目标再试一次附近三格；这可能放宽调用者明确给出的零误差。
                if (!r.requiresStrictStance() && r.horizontalRadius == 0 && !nearRetried && !player.isInWater()
                        && r.kind != MoveToTaskRecord.Kind.YLEVEL
                        && r.kind != MoveToTaskRecord.Kind.FIND) {
                    nearRetried = true;
                    stopNav();
                    NavGoal retry = nearRetryGoal();
                    nav = PlayerNav.toGoal(player, () -> retry, WALK_SPEED, this::closeEnoughToSucceed,
                            terrain()).withTransportMode(r.transportMode).withTerrainProbe();
                    yield TaskState.RUNNING;
                }
                String also = nearRetried
                        ? " (also retried accepting anywhere within "
                                + (int) NEAR_SUCCESS_RADIUS + " blocks — no path either)"
                        : "";
                org.maiwithu.maicraft.core.Constants.LOG.info(
                        "[maicraft-task] goto end kind={} result=failed type={} feet={} reason={}",
                        r.kind, nav.failType(), player.blockPosition().toShortString(),
                        nav.failReason());
                fail(blockedMessage(nav.failReason() + also), nav.failType());
                yield TaskState.FAILED;
            }
        };
    }

    /**
     * 船腿的一刻:驾船朝目标推进,终态(靠岸或搁浅)都走同一条接力——到不了目标的
     * 水路不算失败,只是"这条腿到此为止",剩下的路归步行段(步行导航起步自会下船)。
     * 目标就在水上时她留在船里,不往水里跳。船留在原地,那是她的船,不是垃圾。
     */
    private TaskState tickBoatLeg() {
        // 船走不下去时可以停船后换步行继续；整项移动是否成功，仍要按总目的地判断。
        // 船腿的续约与步行段同一制式:还在消耗航线就把期限保持在租约窗口里
        if (boatLeg.progressing()) {
            long now = player.level().getGameTime();
            r.extendDeadlineTo(now + PROGRESS_LEASE_TICKS);
        }
        var status = boatLeg.tick();
        if (status == org.maiwithu.maicraft.core.pathing.execute.BoatNav.Status.RUNNING) {
            return TaskState.RUNNING;
        }
        String how = status == org.maiwithu.maicraft.core.pathing.execute.BoatNav.Status.ARRIVED
                ? "靠岸" : boatLeg.failReason();
        boatLeg.stop();
        boatLeg = null;
        if (reached()) {
            org.maiwithu.maicraft.core.Constants.LOG.info(
                    "[maicraft-task] 船腿结束({}),目标已在船下 feet={}", how,
                    player.blockPosition().toShortString());
            return successAtBody();
        }
        org.maiwithu.maicraft.core.Constants.LOG.info(
                "[maicraft-task] 船腿结束({}),接步行 feet={}", how,
                player.blockPosition().toShortString());
        startWalkingNav();
        return TaskState.RUNNING;
    }

    /** 失败后的再试目标：完整坐标仍用原范围，只给 x/z 时改成附近三格。 */
    private NavGoal nearRetryGoal() {
        if (r.kind == MoveToTaskRecord.Kind.BLOCK) {
            return blockGoal();
        }
        // COLUMN: within the radius HORIZONTALLY at any height (NavGoal.near is 3D and
        // needs a Y this kind doesn't have; heuristic/center reuse the column's own).
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

    /** 到达后保存实际位置，供后续引用；如果途中落地保护已经确认失败，则不能只因到达就报告成功。 */
    private TaskState successAtBody() {
        observeLanding();
        if (Boolean.TRUE.equals(landingFacts.get("complete")) && Boolean.TRUE.equals(landingFacts.get("failed"))) {
            fail("destination reached after unverified or failed automatic landing protection",FailureType.UNKNOWN);
            return TaskState.FAILED;
        }
        BlockPos body = player.blockPosition();
        // 到达即留痕:最终脚位与请求格同框——"报 exact 成功却站在别处"这类悬案,
        // 下一轮实机的第一现场就在这一行。
        org.maiwithu.maicraft.core.Constants.LOG.info(
                "[maicraft-task] goto end kind={} result=success feet={} requested={}",
                r.kind, body.toShortString(), blockTarget.toShortString());
        r.retainVerifiedPosition(new org.maiwithu.maicraft.task.InternalPositionReceipt.Position(
                body.getX(), body.getY(), body.getZ(),
                player.level().dimension().location().toString()));
        return TaskState.SUCCESS;
    }

    private void observeLanding() {
        var observation = org.maiwithu.maicraft.core.pathing.baritone.landing.LandingAssistPolicy.observation();
        if (observation.revision() > landingBaseline) landingFacts = observation.facts();
    }

    /** Representative remaining distance (blocks) for the deadline estimate. */
    private double repDistance() {
        return switch (r.kind) {
            case BLOCK -> Math.sqrt(player.distanceToSqr(bx + 0.5, by, bz + 0.5));
            case COLUMN -> Math.sqrt(horizontalDistSqr(bx, bz));
            case YLEVEL -> Math.abs(player.getY() - by);
            case FIND -> {
                BlockPos n = finder.nearest();
                yield n == null ? NearestBlockFinder.BUDGET_BLOCKS
                        : Math.sqrt(player.distanceToSqr(n.getX() + 0.5, n.getY() + 0.5, n.getZ() + 0.5));
            }
        };
    }

    // ==================== FIND(就近方块)驱动 ====================

    /**
     * 候选发现期(导航尚未建立)推进一步:收割扫描 -> 有候选即建导航
     * (返回 null 表示落入正常驱动),扫完仍无候选 -> 失败,否则继续等。
     */
    private TaskState tickFindDiscovery() {
        finder.drain();
        if (finder.hasCandidates()) {
            nav = PlayerNav.to(player, finder::contract, WALK_SPEED, this::reached, terrain())
                    .withTransportMode(r.transportMode).withTerrainProbe();
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
        data.put("ground_flight_mode",groundFlight.diagnostics());
        observeLanding();
        data.put("landing_assist_observed",!landingFacts.isEmpty());
        if (!landingFacts.isEmpty()) data.put("landing_assist",landingFacts);
        return data;
    }

    /** 按目标类型说明到达结果；此处是内部文字，外层还可能删去具体坐标。 */
    @Override
    protected String successMessage() {
        int gy = player.blockPosition().getY();
        return switch (r.kind) {
            case BLOCK -> r.requiresStrictStance() ? "reached the exact cell " + bx + "," + by + "," + bz + "."
                    : "arrived within " + r.horizontalRadius + " blocks horizontally and " + r.verticalTolerance
                            + " blocks vertically of the destination; supported at y=" + gy + ".";
            case COLUMN -> "arrived at location x=" + bx + " z=" + bz
                    + (player.isInWater() ? ", in water at y=" : ", standing on the ground at y=") + gy + ".";
            case YLEVEL -> "reached elevation y=" + gy
                    + (gy == by ? "." : " (requested y=" + by + ").");
            case FIND -> {
                BlockPos n = finder.nearest();
                yield n == null
                        ? "arrived beside the target block."
                        : "arrived beside " + r.block + " at " + n.getX() + "," + n.getY()
                                + "," + n.getZ() + " — within reach to use.";
            }
        };
    }

    @Override
    protected String timeoutMessage() {
        int gy = player.blockPosition().getY();
        double remaining = repDistance();
        return "timed out " + String.format("%.1f", remaining) + " blocks from target (now at "
                + bx(gy) + "); verified route progress stopped long enough for the progress lease"
                + " to expire. Reassess the obstruction or continue from this position.";
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
        super.cleanup();
        if (finder != null) {
            finder.cancelScan();
        }
        if (boatLeg != null) {
            boatLeg.stop();   // 中途被取消/让位:收桨,别让船带着按下的前进键漂走
            boatLeg = null;
        }
    }

    /** The give-up message for a planner failure that wasn't close enough to count as arrival.
     *  Captured at the fail site (nav still alive) so its {@code failReason} is readable before
     *  the base's {@code cleanup()} releases the nav. */
    private String blockedMessage(String failReason) {
        int gy = player.blockPosition().getY();
        double remaining = repDistance();
        String where = switch (r.kind) {
            case BLOCK, COLUMN -> "location x=" + bx + " z=" + bz;
            case YLEVEL -> "elevation y=" + by;
            case FIND -> "the nearest " + r.block;
        };
        // 地形封路的验尸自带下一步,不再叠几何建议;策略挡路时出路是授权或垫料,
        // 其余无路才是几何问题:换近一点的路点或扫描。
        String advice = "";
        if (r.transportMode == org.maiwithu.maicraft.core.pathing.transport.TransportMode.JETPACK
                || r.transportMode == org.maiwithu.maicraft.core.pathing.transport.TransportMode.ELEVATOR) {
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
