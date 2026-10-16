// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.behavior.navigation.WalkReport;
import org.maiwithu.maicraft.behavior.navigation.WalkRun;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.behavior.travel.spi.TravelVia;
import org.maiwithu.maicraft.game.world.EndExitPortal;
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
import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 跨维度段的任务：路由 → 找活门 → 走到门口 → 直走进门 → 等维度交接 → 先离门口再结算；
 * 附近没门时按许可建门（下界）或去要塞激活末地门（末地），带坐标的目标到了新维度接着步行。
 * 在末地回主世界只走主岛中心那扇出口门：门亮了就走过去跳进门洞，第一次放的终末之诗像按 Esc 一样关掉；
 * 落点是重生点，没有门，不离落点直接结算；重生点在下界时从那里接着走下界门回主世界。
 * 人在末地外岛看不见主岛中心时，先只用折跃门送一段回主岛，等那一片加载出来再看出口门。
 *
 * <p>跨维度那一程前后、在同一个维度里的那段（到了接着去坐标、外岛先回主岛）交给同维度出行的取舍：
 * 那个维度有用得上的出行方式就是行程（末地里隔着虚空先对着折跃门扔珍珠过去），没有就只走路。
 *
 * <p>交给子任务做的阶段（接着去坐标、外岛回主岛、建门、去要塞激活末地门）都按动作推进：
 * 整趟被生存需求打断时它跟着暂停，先到安全处再停、提前结束时的收尾、此刻能不能打断都转给它。
 *
 * <p>每段步行沿用出行任务的全部保证：走近门口与离门口的走交给走到，自己不算路。
 * 到达以维度为准：已在目标维度才算跨维度段完成，不数秒冒充到达；结算前先走出门口，
 * 不站在门里依赖重入冷却。扫描没扫完不下"没找到"结论；接近或进门失败只排除已试的
 * 入口，同一扇门的其他入口仍在候选里，窗口内候选全部试败才扩大扫描范围。
 */
public final class DimensionTravelTask extends PhasedTask<DimensionTravelTask.Phase> {

    /** 跨维度段的阶段。 */
    enum Phase {
        ROUTE, TO_MAIN_ISLAND, AWAIT_CENTER, FIND_PORTAL, APPROACH, RECHECK, ENTER, CROSS, WALK_OUT, BUILD_PORTAL,
        END_ROOM, WALK_ON
    }

    /** 直走进门最多等多久（刻，20 秒）：下界门站着等几秒才传，20 秒进不去说明这扇门有问题。 */
    private static final int MAX_ENTRY_TICKS = 20 * 20;
    /** 跨维度整段没有真实进展多久算卡住：找门扫描每刻都在动，走路段由走到自己报进展。 */
    private static final long STUCK_AFTER_TICKS = 20L * 60;
    /** 整段没给时限时最多做多久：一段到不了的门不该占着一刻钟以上。 */
    private static final long MAX_TICKS = 20L * 60 * 20;
    /** 关了终末之诗过这么久（刻，1 秒）界面还在，就再关一次：原版关界面当刻就发重生请求，还在说明那一下没生效。 */
    private static final int POEM_RETRY_TICKS = 20;
    /**
     * 过折跃门回到主岛后，最多等多久（刻，10 秒）主岛中心那一片加载出来：传送后服务端一圈一圈地发区块，
     * 落点离中心约 96 格要等一会儿。类别 F。
     */
    private static final int CENTER_WAIT_TICKS = 20 * 10;

    /** 这趟行程的给定输入：去哪个维度、到了接着去哪、找门范围、时限与许可。 */
    public record Journey(String targetDimension, TravelDestination destination, int searchDistance,
            int maxSeconds, Permissions permissions) {
    }

    private final Journey journey;
    private final DimensionTravelServices services;
    private final SameDimensionTravel sameDimension;
    /** 附近没有下界门时建一扇的子任务怎么建。 */
    private final Supplier<Task> portalBuilder;
    /** 附近没有开着的末地门时去要塞插眼激活的子任务怎么建。 */
    private final Supplier<Task> endPortalRoom;

    private DimensionRoute.Decision route;
    private WalkRun walk;
    private Action prepared;
    private final Set<Long> attemptedEntrances = new LongOpenHashSet();
    private BlockPos entrance;
    private Set<Long> portalCells = Set.of();
    private int entryTicks;
    /** 这趟已经建过一扇门：建好后只回去找它走，不再建第二扇。 */
    private boolean portalBuilt;
    /** 这趟建的门是怎么建的（摆黑曜石、浇筑）；没建为 null，结果细节里原样给出。 */
    private String portalBuiltHow;
    private String startDimension;
    private BlockPos startFeet;
    private DimensionTravelDetails settlement;
    /** 这一程是在末地走出口门回去：门格直接从出口门认，不扫描；到了不离落点。 */
    private boolean exitRoute;
    /** 出口门门洞那一层连成一片的门格；不走出口门时为空。 */
    private Set<Long> exitCells = Set.of();
    /** 这趟放过终末之诗、被关掉了：结果里写明。 */
    private boolean poemSkipped;
    private int poemTicks;
    /** 出口门先把人送到了别的维度（重生锚在下界）：从那里接着走时，结果里先交代这一段。 */
    private String detour;
    /** 这一阶段交给子任务做的那件事（接着去坐标、外岛回主岛、建门、去要塞激活末地门）；没在做为 null。 */
    private ChildTaskAction child;
    /** 外岛先过折跃门回到了主岛：那一段自己的一句（过的哪扇门、落在哪），结果里先交代；没过为 null。 */
    private String mainIslandReturn;
    /** 回到主岛后等了几刻主岛中心加载出来。 */
    private int centerTicks;
    /** 已经跨进目标维度：剩下那段没走成时，原因前面说清人已经在哪个维度。 */
    private boolean crossedInto;

    /**
     * @param sameDimension 跨维度那一程前后、在同一个维度里的那段怎么建：出行能力给的那一份和下达同维度出行同一个取舍；
     *                      跟着别人过门用只走路的那一份
     */
    public DimensionTravelTask(Journey journey, DimensionTravelServices services, SameDimensionTravel sameDimension) {
        this(journey, services, sameDimension,
                () -> new PortalBuilder(journey.permissions(), journey.searchDistance(), services),
                () -> new EndPortalRoomTask(journey.permissions(), journey.searchDistance(), services));
    }

    /**
     * @param portalBuilder 附近没有下界门时建一扇的子任务怎么建；离线测试换成替身
     * @param endPortalRoom 附近没有开着的末地门时去要塞激活的子任务怎么建；离线测试换成替身
     */
    DimensionTravelTask(Journey journey, DimensionTravelServices services, SameDimensionTravel sameDimension,
            Supplier<Task> portalBuilder, Supplier<Task> endPortalRoom) {
        super("跨维度出行", Phase.ROUTE, new ProgressTracker(STUCK_AFTER_TICKS,
                journey.maxSeconds() > 0 ? journey.maxSeconds() * 20L : MAX_TICKS));
        this.journey = journey;
        this.services = services;
        this.sameDimension = sameDimension;
        this.portalBuilder = portalBuilder;
        this.endPortalRoom = endPortalRoom;
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
            // 到了目标维度接着去坐标：交给同维度出行的取舍，隔着虚空的那段由行程先过折跃门。
            case WALK_ON -> child = new ChildTaskAction(sameDimension.toward(journey.destination(),
                    journey.permissions(), journey.maxSeconds()), records());
            // 外岛回主岛那一段在认路时就建好了（建不出来当场如实结束）。
            case TO_MAIN_ISLAND -> child;
            // 附近没门：下界按许可建一扇，末地去要塞插眼激活；吃饭、自卫插进来时砌框、走路跟着停下松手。
            case BUILD_PORTAL -> child = new ChildTaskAction(portalBuilder.get(), records());
            case END_ROOM -> child = new ChildTaskAction(endPortalRoom.get(), records());
            default -> null;
        };
    }

    @Override
    protected Next<Phase> tick(Phase phase, TickContext context) {
        return switch (phase) {
            case ROUTE -> decideRoute();
            case TO_MAIN_ISLAND -> backToMainIsland(context);
            case AWAIT_CENTER -> awaitIslandCenter();
            case FIND_PORTAL -> findPortal();
            case APPROACH -> approach(context);
            case RECHECK -> recheck();
            case ENTER -> enterPortal(context);
            case CROSS -> cross();
            case WALK_OUT -> walkOut(context);
            case BUILD_PORTAL, END_ROOM -> afterPreparation(context);
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
        // 终末之诗放着时人已经过了门、还没落地：这时插进别的事只会在界面后面乱按，等它关掉、人回去再说。
        if (exitRoute && phase() == Phase.CROSS && services.world().endPoemShowing()) {
            return Interruptibility.UNSAFE_TO_STOP;
        }
        // 交给子任务的阶段能不能停看子任务手上的动作：扔出珍珠等传送、悬空搭桥、砌框等游戏确认放方块时停不得。
        if (action() instanceof ChildTaskAction running) {
            return running.interruptibility(context);
        }
        return super.interruptibility(context);
    }

    /** 路由：同维度不出跨维度段；在末地回主世界走出口门；其余按维度对认门。 */
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
                // 站在末地里去主世界：只有主岛中心那扇出口门通回去，龙死后才亮。
                if (DimensionRoute.END.equals(startDimension)) {
                    yield exitPortalRoute();
                }
                // 去末地外岛的坐标也直接走末地门：到了对着折跃门扔珍珠过去，珍珠是过门用的，不看 use_rare_items。
                yield Next.go(Phase.FIND_PORTAL, "走末地传送门");
            }
            case NO_DIRECT_PORTAL, UNKNOWN_DIMENSION -> Next.fail(route.problem());
        };
    }

    /**
     * 末地回程：先看主岛中心的出口门。看不见门（那一片没加载）时，人在外岛就先过折跃门回主岛再看，人在主岛就说走近中心；
     * 门没亮是龙还活着，要先打龙，不去找别的门也不代打；门亮了就把门洞那一层连成一片的门格当这扇门，走过去跳进去。
     */
    private Next<Phase> exitPortalRoute() {
        EndExitPortal.Sighting exit = services.world().exitPortal();
        if (exit == null || !exit.loaded()) {
            // 人在外岛：中间隔着虚空走不回去，先只用折跃门送一段回主岛，回到主岛再看出口门。
            if (startFeet != null && EndIslands.sideOf(startFeet) == EndIslands.Side.OUTER_ISLANDS) {
                return toMainIsland();
            }
            return Next.fail(Problem.of(Problem.Kind.NOT_FOUND, centerUnloadedText(),
                    "走近主岛中心（离 0, 0 一百多格内）再出行回主世界"));
        }
        return useExitPortal(exit);
    }

    private static String centerUnloadedText() {
        return "主岛中心 (" + EndExitPortal.CENTER_X + ", " + EndExitPortal.CENTER_Z + ") 那一片没加载，看不见出口门亮没亮";
    }

    /** 外岛回主岛：只用折跃门送一段、不往别处去（过最近的一扇门）；只走路的那一份过不了门，就如实说要先过门。 */
    private Next<Phase> toMainIsland() {
        Optional<Task> crossing = sameDimension.only(PearlCrossing.VIA, journey.permissions(), journey.maxSeconds());
        if (crossing.isEmpty()) {
            return Next.fail(Problem.of(Problem.Kind.NOT_FOUND, centerUnloadedText(),
                    "人在外岛：先用 travel 给 via=" + PearlCrossing.VIA.name()
                            + "（不给 target）过折跃门回主岛，再出行回主世界"));
        }
        child = new ChildTaskAction(crossing.get(), records());
        return Next.go(Phase.TO_MAIN_ISLAND, "人在外岛，看不见主岛中心，先过折跃门回主岛");
    }

    /**
     * 过折跃门回主岛那一段：过去了就等主岛中心加载出来再看出口门；没过成照那一段的原因结束，
     * 原因前面说清人还在外岛，不改走别的。
     */
    private Next<Phase> backToMainIsland(TickContext context) {
        if (runAction(context) instanceof ActionStatus.Running) {
            return Next.stay();
        }
        TaskResult result = child.settle();
        if (result.status() != TaskResult.Status.DONE) {
            Problem why = result.problem() != null ? result.problem()
                    : Problem.of(Problem.Kind.UNREACHABLE, result.summary());
            return Next.fail(Problem.of(why.kind(), "人在外岛，回主岛的折跃门没过成：" + why.message(),
                    "照原因里说的办好（放开许可、备好珍珠、走近折跃门），再出行回主世界"));
        }
        mainIslandReturn = lastRide(result).orElse("过折跃门回到了主岛");
        centerTicks = 0;
        recordProgress("过折跃门回到主岛，接着看出口门");
        return Next.go(Phase.AWAIT_CENTER, "回到主岛了，等主岛中心那一片加载出来再看出口门");
    }

    /** 回到主岛后看出口门：主岛中心那一片加载出来就照常认门；10 秒还没加载就如实说看不见，不再过门。 */
    private Next<Phase> awaitIslandCenter() {
        EndExitPortal.Sighting exit = services.world().exitPortal();
        if (exit != null && exit.loaded()) {
            return useExitPortal(exit);
        }
        if (++centerTicks < CENTER_WAIT_TICKS) {
            return Next.stay();
        }
        BlockPos feet = services.world().feet();
        String where = feet == null ? "" : " (" + feet.getX() + ", " + feet.getZ() + ")";
        return Next.fail(Problem.of(Problem.Kind.NOT_FOUND,
                "已经过折跃门回到主岛" + where + "，等了 " + CENTER_WAIT_TICKS / 20
                        + " 秒主岛中心那一片还没加载（视距太小），看不见出口门亮没亮",
                "往 0, 0 走近些再出行回主世界"));
    }

    /** 出口门看得见了：没亮是龙还活着，要先打龙；亮了就把门洞那一层连成一片的门格当这扇门。 */
    private Next<Phase> useExitPortal(EndExitPortal.Sighting exit) {
        Set<Long> cells = exit.lit() ? exitPortalCells(exit.center()) : Set.of();
        if (cells.isEmpty()) {
            // 刚过折跃门回到主岛的，说明里先交代人已经在主岛：接着打龙就行，不用再过门。
            return Next.fail(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                    (mainIslandReturn == null ? "" : "已经过折跃门回到主岛，但")
                            + "出口门还没亮：要先打败末影龙，门才会亮",
                    "先用 fight 打败末影龙，看到出口门亮了再出行回主世界"));
        }
        exitRoute = true;
        exitCells = cells;
        return Next.go(Phase.FIND_PORTAL, "出口门亮着，走过去跳进门洞");
    }

    /** 出口门的门格：从门心旁亮着的那一格起，同一层连成一片的末地传送门方块。 */
    private Set<Long> exitPortalCells(BlockPos center) {
        for (BlockPos ring : EndExitPortal.ringCells(center)) {
            if (PortalFinder.aliveOf(services.world()::stateAt, ring, Blocks.END_PORTAL)) {
                return PortalFinder.connectedCells(services.world()::stateAt, ring, PortalFinder.MAX_CONNECTED_CELLS);
            }
        }
        return Set.of();
    }

    private Next<Phase> sameDimension(String why) {
        if (journey.destination() == null) {
            settlement = new DimensionTravelDetails(journey.targetDimension(), null, null, -1, false, null);
            return Next.done(TaskResult.done("跨维度出行：" + why));
        }
        return Next.go(Phase.WALK_ON, why + "，接着走到坐标");
    }

    /** 找活门：分刻扫描，没扫完不下"没找到"；门方块还在的整扇门按列底部挑入口。 */
    private Next<Phase> findPortal() {
        if (exitRoute) {
            return pickExitEntrance();
        }
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
            if (portalBuilt) {
                // 刚建好的门也没有走得进的入口：不再建第二扇，把试过的入口交代清楚。
                return Next.fail(Problem.of(Problem.Kind.UNREACHABLE,
                        "刚建好的下界门找不到走得进去的入口（试过的入口见试过的办法）",
                        "看看门口是不是被挡住，清出一条路再出行"));
            }
            if (journey.permissions().changeBlocks() == Permissions.BlockChanges.NONE) {
                return Next.fail(Problem.of(Problem.Kind.NOT_FOUND,
                        noPortalFoundText() + "；建门要摆黑曜石，这次 change_blocks=none 动不了地形",
                        "把 change_blocks 提到 natural 再试，或先走到有门的地方"));
            }
            return Next.go(Phase.BUILD_PORTAL, "已加载区没有活门，按许可建一扇");
        }
        return Next.go(Phase.END_ROOM, "已加载区没有开着的末地门，去要塞找门房间激活");
    }

    /** 出口门挑入口：门格已经认好了，不扫描、不管找门范围；按离人近到远挑一格没试过的。 */
    private Next<Phase> pickExitEntrance() {
        BlockPos feet = services.world().feet();
        List<BlockPos> left = PortalFinder.entranceColumns(exitCells, attemptedEntrances,
                feet == null ? BlockPos.ZERO : feet);
        if (left.isEmpty()) {
            // 门洞边一格一格都试过了：不去找别的门（末地通主世界的只有这一扇），把每格的原因交代清楚。
            return Next.fail(Problem.of(Problem.Kind.UNREACHABLE,
                    "出口门的 " + exitCells.size() + " 个门格都试过了，走不进去（每格的原因见试过的办法）",
                    "看看门洞边是不是被挡住，清出一条路再出行"));
        }
        entrance = left.get(0);
        portalCells = exitCells;
        recordProgress("挑好出口门的入口，走过去");
        return Next.go(Phase.APPROACH, "走到出口门边");
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
        if (exitRoute && services.world().endPoemShowing()) {
            // 终末之诗已经放起来了：人已经过了门，交给等维度交接那一步去关它。
            return Next.go(Phase.CROSS, "进了出口门，原版放起了终末之诗");
        }
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
        String now = services.world().currentDimension();
        if (journey.targetDimension().equals(now)) {
            if (exitRoute) {
                // 出口门把人送到重生点，那里没有门，不用离门口：直接取实测落点结算。
                return settleCrossing(null);
            }
            return Next.go(Phase.WALK_OUT, "到 " + shortDimension(journey.targetDimension()) + " 了，先离门口再结算");
        }
        if (exitRoute && now != null && !now.equals(startDimension)) {
            return landedElsewhere(now);
        }
        if (exitRoute && services.world().endPoemShowing()) {
            return skipEndPoem();
        }
        if (!services.world().touchesPortal(portalBlock(), portalCells)) {
            prepared = new WalksIntoPortal(services.world(), services.context(), portalBlock(), entrance, portalCells);
            return Next.go(Phase.ENTER, "还没传过去人已离开门格，再走进去");
        }
        return Next.stay();
    }

    /**
     * 终末之诗：像玩家按 Esc 一样关掉，原版随即把人送回去；一秒后还在就再关一次。
     * 关的那一下算真实进展，界面一直关不掉才会被判卡住。
     */
    private Next<Phase> skipEndPoem() {
        if (!poemSkipped) {
            poemSkipped = true;
            recordAttempt("过出口门", "原版放起了终末之诗，像按 Esc 一样跳过");
        }
        if (poemTicks++ % POEM_RETRY_TICKS == 0) {
            services.world().closeEndPoem();
            recordProgress("关掉终末之诗，等原版把人送回去");
        }
        return Next.stay();
    }

    /**
     * 出口门把人送到了重生点所在的别的维度（充了能的重生锚在下界）：没到目标维度，
     * 从那里按维度路由接着跨一次（下界走下界门），结果里先交代这一段。
     */
    private Next<Phase> landedElsewhere(String now) {
        detour = "出口门先把人送到了重生点所在的 " + shortDimension(now) + "（重生锚设在那里）";
        recordAttempt("走出口门回 " + shortDimension(journey.targetDimension()), detour);
        exitRoute = false;
        exitCells = Set.of();
        route = null;
        entrance = null;
        portalCells = Set.of();
        // 试过的入口是末地的坐标，换了维度不再作数。
        attemptedEntrances.clear();
        recordProgress(detour);
        return Next.go(Phase.ROUTE, detour + "，从这里接着回 " + shortDimension(journey.targetDimension()));
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
                // 单程只说主世界进末地那扇门：走出口门回来的这一程不再提"回程要打龙"。
                expected, offset, route != null && route.way() == DimensionRoute.Way.END_PORTAL && !exitRoute,
                portalBuiltHow);
        if (!journey.targetDimension().equals(now)) {
            return Next.fail(Problem.of(Problem.Kind.UNREACHABLE,
                    "跨维度没成：还在 " + shortDimension(now == null ? "未知" : now)
                            + (note == null ? "" : "；" + note)));
        }
        crossedInto = true;
        if (journey.destination() == null) {
            return Next.done(TaskResult.done(crossSummary(feet, note)));
        }
        return Next.go(Phase.WALK_ON, "到了目标维度" + (note == null ? "" : "（" + note + "）") + "，接着去坐标");
    }

    /**
     * 接着去坐标：同维度出行跑完剩下那段（隔着虚空先过折跃门），它的事实并进这趟行程；
     * 路上过的折跃门用那一段自己的一句写进总结。没走成照它的原因结束，原因前面说清人已经在哪个维度。
     */
    private Next<Phase> walkOn(TickContext context) {
        if (runAction(context) instanceof ActionStatus.Running) {
            return Next.stay();
        }
        TaskResult result = child.settle();
        if (result.status() == TaskResult.Status.FAILED) {
            Problem why = result.problem() != null ? result.problem()
                    : Problem.of(Problem.Kind.UNREACHABLE, result.summary());
            return Next.fail(crossedInto ? alreadyThere(why) : why);
        }
        String summary = "先到 " + shortDimension(journey.targetDimension()) + crossingNotes()
                + lastRide(result).map(ride -> "；" + ride).orElse("") + "，" + result.summary();
        return switch (result.status()) {
            case PARTIAL -> Next.done(TaskResult.builder(TaskResult.Status.PARTIAL, summary)
                    .problem(result.problem()).build());
            case CANCELLED -> Next.done(TaskResult.cancelled(summary));
            default -> Next.done(TaskResult.done(summary));
        };
    }

    /** 跨过来了、剩下那段没走成：末地门是单程，回去要走出口门，所以原因前面先说人已经在哪个维度。 */
    private Problem alreadyThere(Problem why) {
        String where = "已经到了 " + shortDimension(journey.targetDimension())
                + (DimensionRoute.END.equals(journey.targetDimension()) ? "（末地门是单程）" : "")
                + "，剩下那段没走成：";
        return Problem.of(why.kind(), where + why.message(), why.suggestion());
    }

    /** 那一程里最后坐的别的方式（过折跃门）那一段自己的一句：过的哪扇门、落在哪；只走了路为空。 */
    private static Optional<String> lastRide(TaskResult result) {
        if (!(result.details() instanceof JourneyDetails journey)) {
            return Optional.empty();
        }
        String ride = null;
        for (JourneyDetails.Leg leg : journey.legs()) {
            if (!TravelVia.WALK.name().equals(leg.via())) {
                ride = leg.result();
            }
        }
        return Optional.ofNullable(ride);
    }

    /** 建门或末地门房间：子任务做完门活了回找门；门没活按各自的收场结束，不循环。 */
    private Next<Phase> afterPreparation(TickContext context) {
        // 子任务还在做（走路、砌框、插眼）：它的真实进展已由动作报给整趟。
        if (runAction(context) instanceof ActionStatus.Running) {
            return Next.stay();
        }
        TaskResult result = child.settle();
        if (result.status() == TaskResult.Status.FAILED) {
            return Next.fail(result.problem());
        }
        if (phase() == Phase.BUILD_PORTAL) {
            // 建门怎么建的（摆黑曜石、浇筑）随结果带回来，写进这趟的结果细节。
            if (result.details() instanceof PortalBuildDetails built) {
                portalBuiltHow = built.portalBuiltHow();
                settlement = new DimensionTravelDetails(services.world().currentDimension(), null, null, -1,
                        false, portalBuiltHow);
            }
            if (result.status() == TaskResult.Status.DONE) {
                // 门点着了、门面验出来了：回去找这扇门走进去；只建这一次，再找不到入口就如实结束。
                portalBuilt = true;
                return Next.go(Phase.FIND_PORTAL, "门建好点着了，回去找这扇门走进去");
            }
            // 施工做完门框没成型就是建门没成：整个目标就此结束，保留施工事实，不循环。
            return Next.done(TaskResult.builder(TaskResult.Status.PARTIAL,
                            "建门做完但没验出活门，" + result.summary())
                    .problem(Problem.of(Problem.Kind.NOT_FOUND, "施工结束后门口没有出现下界传送门方块",
                            "看看试过的办法里差在哪，补齐门框或换个地方再建")).build());
        }
        return Next.go(Phase.FIND_PORTAL, "末地门激活了，回去找开着的门");
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

    private String crossSummary(BlockPos feet, String note) {
        String place = "实测落点 " + (feet == null ? "未知" : "(" + feet.getX() + ", " + feet.getY() + ", "
                + feet.getZ() + ")");
        String expectation = settlement.expectedLanding() == null ? ""
                : "；按 1:8 换算预期 (" + settlement.expectedLanding().x() + ", " + settlement.expectedLanding().z()
                + ")，偏差 " + settlement.landingOffset() + " 格";
        String oneWay = settlement.oneWay()
                ? "；末地门是单程：回来要先打败末影龙（fight），出口门亮了再用 travel 回主世界" : "";
        return "已到 " + shortDimension(journey.targetDimension()) + "，" + place + expectation
                + (note == null ? "" : "；" + note) + oneWay + crossingNotes();
    }

    /** 走出口门这一路上要交代的：外岛先过折跃门回主岛、落点是重生点、放过终末之诗、先被送到了别的维度。 */
    private String crossingNotes() {
        StringBuilder notes = new StringBuilder();
        if (mainIslandReturn != null) {
            notes.append("；人在外岛，先回主岛：").append(mainIslandReturn);
        }
        if (exitRoute) {
            notes.append("；出口门送回重生点（床边，没床是世界出生点）");
        }
        if (poemSkipped) {
            notes.append("；第一次过出口门放了终末之诗，跳过了");
        }
        if (detour != null) {
            notes.append("；").append(detour).append("，又从那里走过来");
        }
        return notes.toString();
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
            case TO_MAIN_ISLAND -> "人在外岛，先过折跃门回主岛";
            case AWAIT_CENTER -> "回到主岛，等主岛中心加载出来看出口门";
            case FIND_PORTAL -> "找活门：扫已加载区的门方块";
            case APPROACH -> "走到门口";
            case RECHECK -> "进门前复核门还在";
            case ENTER -> "直走进门";
            case CROSS -> "等维度交接";
            case WALK_OUT -> "先离门口再结算";
            case BUILD_PORTAL -> "建门：勘察、备料、施工、点火";
            case END_ROOM -> "去要塞激活末地门";
            case WALK_ON -> "接着去坐标";
        };
    }

    /** 本来就在挪地方：被临时任务带离原地后从新地点接着走。 */
    @Override
    public boolean resumesAnywhere() {
        return true;
    }
}
