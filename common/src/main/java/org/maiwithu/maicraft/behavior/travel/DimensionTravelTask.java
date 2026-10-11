// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import java.util.Set;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;

import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.behavior.navigation.WalkReport;
import org.maiwithu.maicraft.behavior.navigation.WalkRun;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.kernel.child.ChildTaskRunner;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.ResultDetails;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

/**
 * 跨维度段的任务：路由 → 找活门 → 走到门口 → 直走进门 → 等维度交接 → 先离门口再结算；
 * 附近没门时按许可建门（下界）或去要塞激活末地门（末地），带坐标的目标到了新维度接着步行。
 *
 * <p>每段步行沿用出行任务的全部保证：走近门口与离门口的走交给走到，自己不算路。
 * 到达以维度为准：已在目标维度才算跨维度段完成，不数秒冒充到达；结算前先走出门口，
 * 不站在门里依赖重入冷却。扫描没扫完不下"没找到"结论；接近或进门失败只排除已试的
 * 入口，同一扇门的其他入口仍在候选里，窗口内候选全部试败才扩大扫描范围。
 */
public final class DimensionTravelTask extends PhasedTask<DimensionTravelTask.Phase> {

    /** 跨维度段的阶段。 */
    enum Phase {
        ROUTE, FIND_PORTAL, APPROACH, RECHECK, ENTER, CROSS, WALK_OUT, BUILD_PORTAL, END_ROOM, WALK_ON
    }

    /** 直走进门最多等多久（刻，20 秒）：下界门站着等几秒才传，20 秒进不去说明这扇门有问题。 */
    private static final int MAX_ENTRY_TICKS = 20 * 20;
    /** 跨维度整段没有真实进展多久算卡住：找门扫描每刻都在动，走路段由走到自己报进展。 */
    private static final long STUCK_AFTER_TICKS = 20L * 60;
    /** 整段没给时限时最多做多久：一段到不了的门不该占着一刻钟以上。 */
    private static final long MAX_TICKS = 20L * 60 * 20;

    /** 这趟行程的给定输入：去哪个维度、到了接着去哪、找门范围、时限与许可。 */
    public record Journey(String targetDimension, TravelDestination destination, int searchDistance,
            int maxSeconds, Permissions permissions) {
    }

    private final Journey journey;
    private final DimensionTravelServices services;

    private DimensionRoute.Decision route;
    private WalkRun walk;
    private ChildTaskRunner runner;
    private Action prepared;
    private final Set<Long> attemptedEntrances = new LongOpenHashSet();
    private BlockPos entrance;
    private Set<Long> portalCells = Set.of();
    private int entryTicks;
    private String startDimension;
    private BlockPos startFeet;
    private DimensionTravelDetails settlement;

    public DimensionTravelTask(Journey journey, DimensionTravelServices services) {
        super("跨维度出行", Phase.ROUTE, new ProgressTracker(STUCK_AFTER_TICKS,
                journey.maxSeconds() > 0 ? journey.maxSeconds() * 20L : MAX_TICKS));
        this.journey = journey;
        this.services = services;
    }

    @Override
    protected Action enter(Phase phase) {
        Action next = prepared;
        prepared = null;
        if (next != null) {
            return next;
        }
        return switch (phase) {
            case APPROACH -> startWalk(GoalCompiler.interact(entrance));
            case ENTER -> new WalksIntoPortal(services.world(), services.context(), portalBlock(), entrance, portalCells);
            case WALK_OUT -> {
                BlockPos aside = services.world().stepAsideCell(portalCells).orElse(null);
                walk = aside == null ? null : startWalk(GoalCompiler.standOn(aside));
                yield walk;
            }
            default -> null;
        };
    }

    @Override
    protected Next<Phase> tick(Phase phase, TickContext context) {
        return switch (phase) {
            case ROUTE -> decideRoute();
            case FIND_PORTAL -> findPortal();
            case APPROACH -> approach(context);
            case RECHECK -> recheck();
            case ENTER -> enterPortal(context);
            case CROSS -> cross();
            case WALK_OUT -> walkOut(context);
            case BUILD_PORTAL, END_ROOM -> runPreparation(context);
            case WALK_ON -> walkOn(context);
        };
    }

    @Override
    public Interruptibility interruptibility(TickContext context) {
        // 正在过门（身体沾着门格等传送）停下不安全：半个人在门里被切碎，接着走会被重入冷却卡住。
        if ((phase() == Phase.ENTER || phase() == Phase.CROSS)
                && portalBlock() != null
                && services.world().touchesPortal(portalBlock(), portalCells)) {
            return Interruptibility.UNSAFE_TO_STOP;
        }
        return super.interruptibility(context);
    }

    /** 路由：同维度不出跨维度段；末地的回程不归出行；其余按维度对认门。 */
    private Next<Phase> decideRoute() {
        startDimension = services.world().currentDimension();
        startFeet = services.world().feet();
        if (startDimension == null) {
            return Next.fail(Problem.of(Problem.Kind.INTERNAL_ERROR, "角色不在世界里，跨不了维度"));
        }
        if (startDimension.equals(journey.targetDimension())) {
            return sameDimension("出发时已经在目标维度");
        }
        route = DimensionRoute.decide(startDimension, journey.targetDimension());
        return switch (route.way()) {
            case SAME_DIMENSION -> sameDimension("出发时已经在目标维度");
            case NETHER_PORTAL -> Next.go(Phase.FIND_PORTAL, "走下界传送门");
            case END_PORTAL -> {
                // 末地的回程门要打败末影龙才出现，归战斗域；站在末地里去主世界不是这趟行程做得了的。
                if (DimensionRoute.END.equals(startDimension)) {
                    yield Next.fail(Problem.of(Problem.Kind.UNSUPPORTED,
                            "末地的回程要走打败末影龙后才出现的出口门，归战斗域，出行不代劳"));
                }
                yield Next.go(Phase.FIND_PORTAL, "走末地传送门");
            }
            case NO_DIRECT_PORTAL, UNKNOWN_DIMENSION -> Next.fail(route.problem());
        };
    }

    private Next<Phase> sameDimension(String why) {
        if (journey.destination() == null) {
            settlement = new DimensionTravelDetails(journey.targetDimension(), null, null, -1, false);
            return Next.done(TaskResult.done("跨维度出行：" + why));
        }
        return Next.go(Phase.WALK_ON, why + "，接着走到坐标");
    }

    /** 找活门：分刻扫描，没扫完不下"没找到"；门方块还在的整扇门按列底部挑入口。 */
    private Next<Phase> findPortal() {
        PortalFinder.Round round = services.world().scanPortals(portalBlock(), journey.searchDistance(), attemptedEntrances);
        if (round.hasEntrance()) {
            PortalFinder.Entrance chosen = round.entrances().get(0);
            entrance = chosen.cell();
            portalCells = chosen.portalCells();
            recordProgress("找到一扇活门，先走过去");
            return Next.go(Phase.APPROACH, "找到一扇活门，走过去");
        }
        if (!round.complete()) {
            return Next.stay();
        }
        // 扫完了确实一个入口都没有：下界按许可建门，末地去要塞激活门。
        if (route.way() == DimensionRoute.Way.NETHER_PORTAL) {
            if (journey.permissions().changeBlocks() == Permissions.BlockChanges.NONE) {
                return Next.fail(Problem.of(Problem.Kind.NOT_FOUND,
                        noPortalFoundText() + "；建门要摆黑曜石，这次 change_blocks=none 动不了地形",
                        "把 change_blocks 提到 natural 再试，或先走到有门的地方"));
            }
            return Next.go(Phase.BUILD_PORTAL, "已加载区没有活门，按许可建一扇");
        }
        return Next.go(Phase.END_ROOM, "已加载区没有开着的末地门，去要塞找门房间激活");
    }

    private String noPortalFoundText() {
        return "查了 " + journey.searchDistance() + " 格已加载区，没有要去的维度的活门；更远的地方还没加载";
    }

    /** 走到门口：站到入口格旁边；走不过去只排除这一个入口，同一扇门的其他侧还在候选里。 */
    private Next<Phase> approach(TickContext context) {
        ActionStatus status = runAction(context);
        WalkReport report = walk.report();
        if (report.state() == WalkReport.State.STOPPED) {
            return Next.go(Phase.APPROACH, "上一段走到已停稳，从原地接着走向门口");
        }
        return switch (status) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Done done -> Next.go(Phase.RECHECK, "到门口了，进门前再看一眼门还在不在");
            case ActionStatus.Failed failed -> {
                excludeEntrance("走近门口失败：" + failed.problem().message());
                yield Next.go(Phase.FIND_PORTAL, "这个入口走不过去，换下一个候选");
            }
        };
    }

    /** 进门前复核：门没了按目标没了换下一候选，不把坏门点一遍。 */
    private Next<Phase> recheck() {
        if (!services.world().portalAlive(portalBlock(), entrance)) {
            excludeEntrance("进门前复核：门方块已经不在了");
            return Next.go(Phase.FIND_PORTAL, "门没了，换下一个候选");
        }
        entryTicks = 0;
        return Next.go(Phase.ENTER, "门还在，直走进去");
    }

    /** 直走进门：20 秒进不去排除这扇门；门在途中没了换下一候选。 */
    private Next<Phase> enterPortal(TickContext context) {
        if (++entryTicks > MAX_ENTRY_TICKS) {
            excludeEntrance("直走了 " + MAX_ENTRY_TICKS / 20 + " 秒没能走进门格");
            return Next.go(Phase.FIND_PORTAL, "这扇门进不去，换下一个候选");
        }
        ActionStatus status = runAction(context);
        return switch (status) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Done done -> Next.go(Phase.CROSS, "身体进了门格，等维度真的换过去");
            case ActionStatus.Failed failed -> {
                excludeEntrance("进门失败：" + failed.problem().message());
                yield Next.go(Phase.FIND_PORTAL, "这扇门进不去，换下一个候选");
            }
        };
    }

    /** 等维度交接：以维度为准，不数秒；身体被挤出门格而维度没变就再走进去。 */
    private Next<Phase> cross() {
        if (journey.targetDimension().equals(services.world().currentDimension())) {
            return Next.go(Phase.WALK_OUT, "到 " + shortDimension(journey.targetDimension()) + " 了，先离门口再结算");
        }
        if (!services.world().touchesPortal(portalBlock(), portalCells)) {
            prepared = new WalksIntoPortal(services.world(), services.context(), portalBlock(), entrance, portalCells);
            return Next.go(Phase.ENTER, "还没传过去人已离开门格，再走进去");
        }
        return Next.stay();
    }

    /** 离门口：走到落脚格；走不动也结算——跨维度段以维度为准，离门是纪律不是达成条件。 */
    private Next<Phase> walkOut(TickContext context) {
        if (walk == null) {
            // 起步时就没有落脚格（落点四面是渊或水）：不再挑，就地如实结算。
            recordAttempt("找离门口的落脚格", "门口附近没有干燥能站的格子，就地结算");
            return settleCrossing(null);
        }
        ActionStatus status = runAction(context);
        WalkReport report = walk.report();
        if (report.state() == WalkReport.State.STOPPED) {
            return Next.go(Phase.WALK_OUT, "从原地接着离门");
        }
        return switch (status) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Done done -> settleCrossing(null);
            case ActionStatus.Failed failed -> settleCrossing("离门口的几步没走成（" + failed.problem().message() + "）");
        };
    }

    /** 跨维度段结算：实测落点、换算预期与偏差；带坐标的目标接着走步行段。 */
    private Next<Phase> settleCrossing(String note) {
        BlockPos feet = services.world().feet();
        String now = services.world().currentDimension();
        // 换算预期按出发时的位置算：从主世界哪儿过门，预期落在下界的换算点上。
        WorldPosition expected = startFeet == null ? null
                : DimensionScale.expectedSpot(startDimension, startFeet, journey.targetDimension());
        int offset = startFeet == null ? -1 : DimensionScale.horizontalOffset(expected, feet);
        settlement = new DimensionTravelDetails(now,
                entrance == null ? null
                        : new WorldPosition(entrance.getX(), entrance.getY(), entrance.getZ(), startDimension),
                expected, offset, route != null && route.way() == DimensionRoute.Way.END_PORTAL);
        if (!journey.targetDimension().equals(now)) {
            return Next.fail(Problem.of(Problem.Kind.UNREACHABLE,
                    "跨维度没成：还在 " + shortDimension(now == null ? "未知" : now)
                            + (note == null ? "" : "；" + note)));
        }
        if (journey.destination() == null) {
            return Next.done(TaskResult.done(crossSummary(feet, note)));
        }
        return Next.go(Phase.WALK_ON, "到了目标维度" + (note == null ? "" : "（" + note + "）") + "，接着走到坐标");
    }

    /** 继续步行段：把目的地交给出行任务跑完最后一段，它的事实并进这趟行程。 */
    private Next<Phase> walkOn(TickContext context) {
        if (runner == null) {
            runner = new ChildTaskRunner(ChildTaskRunner.NO_LIMIT);
            runner.begin(new TravelTask(journey.destination(), journey.maxSeconds(), TerrainPermit.of(journey.permissions()),
                    services.walks(), services.placedBlocks(), services.progressListener()), context);
            return Next.stay();
        }
        if (runner.tick(context) instanceof TickResult.Finished finished) {
            copyFacts(finished.result());
            TaskResult result = finished.result();
            runner = null;
            String summary = "先到 " + shortDimension(journey.targetDimension()) + "，" + result.summary();
            if (result.status() == TaskResult.Status.FAILED) {
                return Next.fail(result.problem());
            }
            if (result.status() == TaskResult.Status.PARTIAL) {
                return Next.done(TaskResult.builder(TaskResult.Status.PARTIAL, summary)
                        .problem(result.problem()).build());
            }
            return Next.done(TaskResult.done(summary));
        }
        return Next.stay();
    }

    /** 建门或末地门房间：子任务做完门活了回找门；门没活按各自的收场结束，不循环。 */
    private Next<Phase> runPreparation(TickContext context) {
        if (runner == null) {
            runner = new ChildTaskRunner(ChildTaskRunner.NO_LIMIT);
            runner.begin(phase() == Phase.BUILD_PORTAL
                    ? new PortalBuilder(journey.permissions(), journey.searchDistance(), services)
                    : new EndPortalRoomTask(journey.permissions(), journey.searchDistance(), services), context);
            return Next.stay();
        }
        if (runner.tick(context) instanceof TickResult.Finished finished) {
            copyFacts(finished.result());
            TaskResult result = finished.result();
            runner = null;
            if (result.status() == TaskResult.Status.FAILED) {
                return Next.fail(result.problem());
            }
            if (phase() == Phase.BUILD_PORTAL) {
                // 施工做完门框没成型就是建门没成：整个目标就此结束，保留施工事实，不循环。
                return Next.done(TaskResult.builder(TaskResult.Status.PARTIAL,
                                "建门做完但没验出活门，" + result.summary())
                        .problem(Problem.of(Problem.Kind.NOT_FOUND, "施工结束后门口没有出现下界传送门方块",
                                "看看试过的办法里差在哪，补齐门框或换个地方再建")).build());
            }
            return Next.go(Phase.FIND_PORTAL, "末地门激活了，回去找开着的门");
        }
        return Next.stay();
    }

    private WalkRun startWalk(GoalCompiler.Compiled target) {
        // 跨维度段没有途中要额外保护的方块：走近的门格由目标编译自己进保护名单。
        walk = services.walks().start(target, TerrainPermit.of(journey.permissions()));
        return walk;
    }

    private void excludeEntrance(String why) {
        if (entrance != null) {
            attemptedEntrances.add(entrance.asLong());
            recordAttempt("试门口 (" + entrance.getX() + ", " + entrance.getY() + ", " + entrance.getZ() + ")", why);
        }
        entrance = null;
        portalCells = Set.of();
    }

    private void copyFacts(TaskResult result) {
        for (var change : result.changes()) {
            recordChange(change);
        }
        for (var change : result.unconfirmed()) {
            recordUnconfirmed(change);
        }
        for (var attempt : result.attempts()) {
            recordAttempt(attempt.tried(), attempt.result());
        }
    }

    private String crossSummary(BlockPos feet, String note) {
        String place = "实测落点 " + (feet == null ? "未知" : "(" + feet.getX() + ", " + feet.getY() + ", "
                + feet.getZ() + ")");
        String expectation = settlement.expectedLanding() == null ? ""
                : "；按 1:8 换算预期 (" + settlement.expectedLanding().x() + ", " + settlement.expectedLanding().z()
                + ")，偏差 " + settlement.landingOffset() + " 格";
        String oneWay = settlement.oneWay() ? "；末地门是单程，回程要打败末影龙（归战斗域）" : "";
        return "已到 " + shortDimension(journey.targetDimension()) + "，" + place + expectation
                + (note == null ? "" : "；" + note) + oneWay;
    }

    private Block portalBlock() {
        return route == null ? null : route.portalBlock();
    }

    private static String shortDimension(String dimension) {
        return dimension == null ? "未知维度" : dimension.replace("minecraft:", "");
    }

    @Override
    protected ResultDetails details() {
        return settlement == null ? ResultDetails.NONE : settlement;
    }

    @Override
    protected String describePhase(Phase value) {
        return switch (value) {
            case ROUTE -> "认门：看这趟走哪种门";
            case FIND_PORTAL -> "找活门：扫已加载区的门方块";
            case APPROACH -> "走到门口";
            case RECHECK -> "进门前复核门还在";
            case ENTER -> "直走进门";
            case CROSS -> "等维度交接";
            case WALK_OUT -> "先离门口再结算";
            case BUILD_PORTAL -> "建门：勘察、备料、施工、点火";
            case END_ROOM -> "去要塞激活末地门";
            case WALK_ON -> "接着走到坐标";
        };
    }
}
