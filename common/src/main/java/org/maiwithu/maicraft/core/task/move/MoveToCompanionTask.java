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

/**
 * 执行一次移动：到坐标附近、准确站到某格、改变高度，或找到某种方块走到旁边。
 * 具体路线由 PlayerNav 负责；这里决定何时开始、找不到路怎么办，以及玩家真的到达后怎样报告结果。
 * x/y/z 都有也不一定要求精确站位，是否精确由 exact 与到达误差共同决定。
 */
public final class MoveToCompanionTask extends AbstractCompanionTask<MoveToTaskRecord> {

    private static final long TICKS_PER_BLOCK = 20;
    private static final long MAX_EXTRA_TICKS = 5 * 60 * 20;
    /** 行进期间将截止时间保持在此期限之后，使健康的多分钟挖掘路线不会半途超时；若路线卡住，也会在一个期限内结束并归还角色控制。 */
    private static final long PROGRESS_LEASE_TICKS = 30 * 20;
    /** 进展必须在多近的时间内发生，才会续期。预留一段较宽松的间隔以容纳缓慢但合法的移动；长时间徒手挖掘会将执行器进度时钟保持为 0，
     *  此间隔主要覆盖放置动作和路线重规划空档。 */
    private static final int PROGRESS_GRACE_TICKS = 100;
    /** 规划器无法抵达精确目标时，若停在请求柱列附近此距离内，仍视为到达（教学性成功，避免反复抖动）。这是唯一容差；真正的精确到达仍按原目标判定。 */
    private static final double WALK_SPEED = 1.0;
    private static final double NEAR_SUCCESS_RADIUS = 3.0;
    /** 规划器无法再接近时（例如停在水下目标上方的水面），等待此数量的无进展 tick 再放弃：
     *  时间足以让角色被水流带到可达的水下目标附近，也能及时放弃水面上方不可达的目标。 */
    private static final int MAX_SETTLE_TICKS = 60;

    private final int bx;
    private final int by;
    private final int bz;
    private final BlockPos blockTarget;   // 仅在 BLOCK 目标类型下有意义。

    private double bestDist = Double.MAX_VALUE;   // 到目标曾达到的最近距离。
    private int settleTicks = 0;                  // 规划器放弃后无进展的 tick 数。
    /** 唯一一次近距离重试恢复阶梯已用完；此阶梯状态会在挂起期间保留。 */
    private boolean nearRetried;
    private long landingBaseline = Long.MAX_VALUE;
    private Map<String,Object> landingFacts = Map.of();
    private final JetpackGroundMode groundFlight=
            new JetpackGroundMode();
    /** FIND(就近方块)子系统:扫描/入册/契约/轮换全在组件里,此处只驱动。 */
    private NearestBlockFinder finder;

    /** 船腿:开工时坐在船上就先驾船,靠岸(或搁浅)后接步行。null = 没有/已交棒。 */
    private BoatNav boatLeg;

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
        landingBaseline = LandingAssistPolicy.observation().revision();
        // 载具处置:坐在船上且有明确去处,先驾船——船腿走到离目标最近的水格,
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
        // 初始预算按直线距离估算（此处还无法得知地形难度）；旅程开始后改由下方的进度期限续期。
        long extra = Math.min(MAX_EXTRA_TICKS, 600 + (long) (repDistance() * TICKS_PER_BLOCK));
        r.extendDeadlineTo(player.level().getGameTime() + extra);
        // 完整坐标与只给水平坐标使用各自的到达范围；BLOCK 在此表示坐标目标，不是 FIND 的方块类型搜索。
        nav = (r.kind == MoveToTaskRecord.Kind.BLOCK
                ? PlayerNav.to(player, this::blockCompiled, WALK_SPEED, this::reached, terrain())
                : PlayerNav.toGoal(player, this::goal, WALK_SPEED, this::reached, terrain()))
                .withTransportMode(r.transportMode).withTerrainProbe();
        Constants.LOG.info(
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
        // 现有导航必须先消费其原生完成回执，之后任务清理才可停止它；仅仅碰到船舱地板或喷气背包触地，不代表退出或模式恢复已经完成。
        if (nav == null && reached()) return successAtBody();
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
                // 否则按地形允许的最近位置处理，再判断是否满足教学性成功或必须失败。
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
                Constants.LOG.info(
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
        if (status == BoatNav.Status.RUNNING) {
            return TaskState.RUNNING;
        }
        String how = status == BoatNav.Status.ARRIVED
                ? "靠岸" : boatLeg.failReason();
        boatLeg.stop();
        boatLeg = null;
        if (reached()) {
            Constants.LOG.info(
                    "[maicraft-task] 船腿结束({}),目标已在船下 feet={}", how,
                    player.blockPosition().toShortString());
            return successAtBody();
        }
        Constants.LOG.info(
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
        Constants.LOG.info(
                "[maicraft-task] goto end kind={} result=success feet={} requested={}",
                r.kind, body.toShortString(), blockTarget.toShortString());
        r.retainVerifiedPosition(new InternalPositionReceipt.Position(
                body.getX(), body.getY(), body.getZ(),
                player.level().dimension().location().toString()));
        return TaskState.SUCCESS;
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
                BlockPos n = finder.nearest();
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

    /** 规划失败且未接近到可视为到达时使用的放弃消息。必须在失败位置、导航尚未释放时捕获，
     *  这样才能在父类 {@code cleanup()} 停止导航前读取 {@code failReason}。 */
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
