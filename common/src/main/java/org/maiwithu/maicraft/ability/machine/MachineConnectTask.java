// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.ability.machine.spi.LineMaterials;
import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.ability.machine.spi.MachineType;
import org.maiwithu.maicraft.ability.machine.spi.NetworkKind;
import org.maiwithu.maicraft.ability.machine.spi.NetworkReader;
import org.maiwithu.maicraft.ability.machine.spi.NetworkSummary;
import org.maiwithu.maicraft.behavior.construction.ConstructionServices;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.kernel.child.ChildTaskRunner;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.ResultDetails;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

/**
 * 把机器接进网络的任务：认两端 → 已经接着就不再动（转速为零、过载也照实说接上了）→
 * 没接上就规划一条线路（直线优先、少拐弯、远处用链条这类跨越）→ 写成机器蓝图交给施工铺好 →
 * 再核对两端是不是同一张网、机器转不转。线材由那种网的联动给；不会接线的网如实说铺不了。
 *
 * <p>线路只铺在空格里，挨着别的机器会被一起接上的格不放；铺好了两端还不同网就照实说，
 * 已经放下的线材不拆。接上了但过载、转速为零不是接线的失败，读数原样交给 LLM。
 */
final class MachineConnectTask extends PhasedTask<MachineConnectTask.Phase> {

    /** 认两端 → 没给来源就扫一片找 → 核对 → 规划线路 → 施工 → 再核对。 */
    enum Phase { RESOLVE, FIND_SOURCE, VERDICT, PLAN, BUILD, CHECK }

    private final MachineConnectInput input;
    private final MachineServices services;
    private final MachineArchives archives;
    private final WorldMemory memory;
    private final Function<Permissions, ConstructionServices> construction;
    private final MachineTargets targets;
    private final LineRoutes routes;
    private NetworkReader reader;
    private NetworkKind kind;
    private BlockPos targetCell;
    private String targetDimension;
    private BlockPos sourceCell;
    /** 找来源的扫描账：见过的候选格与它们的网络编号，扫完挑最近的；规划线路时同网的都能当起点。 */
    private final Map<BlockPos, String> candidates = new LinkedHashMap<>();
    private boolean preferredRunning;
    /** 规划出的线路；没走到规划为 null。 */
    private RoutePlanner.Plan route;
    private ChildTaskRunner runner;

    MachineConnectTask(MachineConnectInput input, MachineServices services, MachineArchives archives,
            WorldMemory memory, Function<Permissions, ConstructionServices> construction) {
        super("接网络", Phase.RESOLVE, new ProgressTracker(1200, 216000));
        this.input = input;
        this.services = services;
        this.archives = archives;
        this.memory = memory;
        this.construction = construction;
        this.targets = new MachineTargets(services);
        this.routes = new LineRoutes(services, archives);
    }

    @Override protected Action enter(Phase phase) {
        // 施工那一步是子任务，其余阶段只做判断与读数。
        return null;
    }

    @Override protected Next<Phase> tick(Phase phase, TickContext context) {
        return switch (phase) {
            case RESOLVE -> resolve();
            case FIND_SOURCE -> findSource();
            case VERDICT -> verdict();
            case PLAN -> plan();
            case BUILD -> build(context);
            case CHECK -> check();
        };
    }

    // 认两端：网络种类要有读取器认；目标与给定的来源落实成格子；目标在档案里时维度先对一遍。
    private Next<Phase> resolve() {
        for (NetworkReader candidate : services.networkReaders()) {
            if (candidate.kind().id().equals(input.kind())) {
                reader = candidate;
                kind = candidate.kind();
            }
        }
        if (reader == null) {
            return Next.fail(Problem.of(Problem.Kind.INVALID_PARAMETER,
                    "没有登记 " + input.kind() + " 这种网络的读取器", "可选值用 lookup 查这个能力"));
        }
        MachineTargets.Resolution target = resolveTarget();
        if (target instanceof MachineTargets.Resolution.DeadEnd dead) {
            return Next.fail(dead.problem());
        }
        MachineTargets.Resolution.Found found = (MachineTargets.Resolution.Found) target;
        targetCell = found.cell();
        targetDimension = found.dimension();
        if (services.world().stateAt(targetCell).isEmpty()) {
            return Next.fail(Problem.of(Problem.Kind.UNREACHABLE, "目标那一格还没加载，看不了机器", "先走近一点再试"));
        }
        if (input.source().isPresent()) {
            MachineTargets.Resolution source = targets.resolve(input.source().get());
            if (source instanceof MachineTargets.Resolution.DeadEnd dead) {
                return Next.fail(dead.problem());
            }
            sourceCell = ((MachineTargets.Resolution.Found) source).cell();
            return Next.go(Phase.VERDICT, "两端都认好了，核对网络");
        }
        return Next.go(Phase.FIND_SOURCE, "没给来源，在 " + input.radius() + " 格内找挂着这种网的格子");
    }

    /** 目标落实：档案名先对档案（挑档案里挂着这种网、或能接这种线的那一格），其余交给机器目标解析。 */
    private MachineTargets.Resolution resolveTarget() {
        if (input.archive() != null) {
            if (services.world().playerSpot().isPresent()
                    && !services.world().playerSpot().get().dimension().equals(input.archive().dimension())) {
                return new MachineTargets.Resolution.DeadEnd(Problem.of(Problem.Kind.UNREACHABLE,
                        "档案「" + input.archive().name() + "」在另一个维度（" + input.archive().dimension() + "）"));
            }
            return new MachineTargets.Resolution.Found(representativeCell(), null, input.archive().dimension());
        }
        return targets.resolve(input.target());
    }

    // 档案的代表格：先挑已经挂在这种网上的，再挑能接这种线的，都没有就用锚点。
    private BlockPos representativeCell() {
        List<BlockPos> cells = LineRoutes.cellsOf(input.archive());
        for (BlockPos cell : cells) {
            if (reader.membership(cell).isPresent()) return cell;
        }
        Optional<LineMaterials> materials = reader.lineMaterials();
        if (materials.isPresent()) {
            for (BlockPos cell : cells) {
                if (!routes.attaches(List.of(cell), materials.get()).isEmpty()) return cell;
            }
        }
        return input.archive().anchor();
    }

    // 找来源：分刻扫一片，把挂着这种网的格子记下，机器在转的优先；扫完挑最近的，一个都没有照实说。
    private Next<Phase> findSource() {
        if (services.world().playerSpot().isEmpty()) {
            return Next.fail(Problem.of(Problem.Kind.UNREACHABLE, "角色现在不在世界里，看不了现场", "先回到世界"));
        }
        ReadsMachineArea.Round round = services.area().scan(targetDimension, targetCell, input.radius());
        if (round.worldChanged()) {
            return Next.fail(Problem.of(Problem.Kind.UNREACHABLE,
                    "扫的时候角色离开了这个世界，这一眼作废", "回到世界再来一次"));
        }
        for (ReadsMachineArea.Cell cell : round.cells()) {
            reader.membership(cell.pos()).ifPresent(networkId -> {
                candidates.put(cell.pos(), networkId);
                if (isRunning(cell.pos(), cell.state())) preferredRunning = true;
            });
        }
        recordProgress("找来源：看过了 " + candidates.size() + " 个挂网的格子");
        if (!round.complete()) {
            return Next.stay();
        }
        BlockPos chosen = chooseSource();
        if (chosen == null) {
            return Next.fail(Problem.of(Problem.Kind.NOT_FOUND,
                    input.radius() + " 格内没有挂着 " + kind.id() + " 网的格子",
                    "把 radius 调大，或用 source 指一台在网的机器"));
        }
        sourceCell = chosen;
        return Next.go(Phase.VERDICT, "找到了在网的一格，核对网络");
    }

    /** 挑来源：在转的优先，再按离目标远近；登记的网络读取器读不出运行状态时按远近。 */
    private BlockPos chooseSource() {
        List<BlockPos> running = new ArrayList<>();
        List<BlockPos> others = new ArrayList<>();
        for (BlockPos pos : candidates.keySet()) {
            (isRunning(pos, services.world().stateAt(pos).orElse(null)) ? running : others).add(pos);
        }
        if (running.isEmpty()) {
            return nearest(others);
        }
        BlockPos best = nearest(running);
        preferredRunning = true;
        return best != null ? best : nearest(others);
    }

    private BlockPos nearest(List<BlockPos> cells) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : cells) {
            double distance = pos.distSqr(targetCell);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = pos;
            }
        }
        return best;
    }

    /** 这一格的机器读数是不是在转；没有机器类型认领或读不到都按不知道对待。 */
    private boolean isRunning(BlockPos at, BlockState state) {
        if (state == null) {
            return false;
        }
        MachineType type = services.claiming(state);
        if (type == null) {
            return false;
        }
        return type.state(at).activity() == MachineState.Activity.RUNNING;
    }

    // 核对：已经同一张网就不再动；没接上时这种网会接线就去规划，不会接线的如实说铺不了。
    private Next<Phase> verdict() {
        MachineJoined.Verdict outcome = judge();
        if (outcome.joined()) {
            return finishJoined(outcome);
        }
        if (reader.lineMaterials().isEmpty()) {
            recordProgress(outcome.note());
            return Next.fail(Problem.of(Problem.Kind.UNSUPPORTED, outcome.note() + "（" + where()
                    + "）；" + kind.id() + " 这种网的联动没有给出接线的方块，铺不了线路",
                    "装了会接这种网的联动后再试，或自己按蓝图用 machine_build 铺"));
        }
        return Next.go(Phase.PLAN, outcome.note() + "，规划一条线路");
    }

    // 规划：来源网里同网的格都能当起点，已有的链条输送机这类能当跨越的一头；目标的接线面是终点。
    private Next<Phase> plan() {
        LineMaterials materials = reader.lineMaterials().orElseThrow();
        List<BlockPos> sourceCells = sourceNetworkCells();
        List<BlockPos> targetCells = input.archive() != null ? LineRoutes.cellsOf(input.archive()) : List.of(targetCell);
        RoutePlanner.Result result = RoutePlanner.plan(routes.attaches(sourceCells, materials),
                routes.spanEnds(sourceCells, materials), routes.attaches(targetCells, materials), materials,
                routes.ground(), routes.spanAllowed(materials, input.archive()));
        if (result instanceof RoutePlanner.NotFound notFound) {
            return Next.fail(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, notFound.why() + "（" + where() + "）",
                    "清出一条空路、换一个 source，或自己写蓝图用 machine_build 铺"));
        }
        route = ((RoutePlanner.Found) result).plan();
        // 线路的终点就是要接的那一格：之后核对、读数都看它。
        targetCell = routeEndpoint(targetCells);
        recordProgress("线路：" + route.cells().size() + " 格、拐 " + route.turns() + " 个弯"
                + route.span().map(span -> "、一段 " + span.kind()).orElse(""));
        return Next.go(Phase.BUILD, "按线路施工");
    }

    /** 来源网里能当起点的格：给了来源就是它；找来的话是扫到的、和来源同一张网的全部格。 */
    private List<BlockPos> sourceNetworkCells() {
        if (input.source().isPresent()) return List.of(sourceCell);
        Optional<String> network = reader.membership(sourceCell);
        return network.map(id -> LineRoutes.sameNetwork(candidates, id)).orElse(List.of(sourceCell));
    }

    // 线路最后一格挨着的那台机器格就是接上的那一格；挑不出时保持原来的目标格。
    private BlockPos routeEndpoint(List<BlockPos> targetCells) {
        BlockPos last = route.cells().getLast().pos();
        for (BlockPos cell : targetCells) {
            if (cell.distManhattan(last) == 1) return cell;
        }
        return targetCell;
    }

    // 施工：线路写成机器蓝图（目标有档案就并进去），交给按蓝图施工的同一套任务；它的结局原样接过来。
    private Next<Phase> build(TickContext context) {
        if (runner == null) {
            runner = new ChildTaskRunner(ChildTaskRunner.NO_LIMIT);
            runner.begin(new MachineBuildTask(routes.buildInput(route, reader.lineMaterials().orElseThrow(), targetCell,
                    targetDimension, input.archive(), input.permissions()), services, archives, memory, construction),
                    context);
            return Next.stay();
        }
        if (runner.tick(context) instanceof TickResult.Finished finished) {
            runner = null;
            copyFacts(finished.result());
            if (finished.result().status() == TaskResult.Status.FAILED) {
                return Next.fail(finished.result().problem());
            }
            return Next.go(Phase.CHECK, "线路铺完，核对两端");
        }
        // 铺线路的施工引擎有真实进展就算接网络有进展：和建机器一样，外层收不到会被 60 秒卡住判定掐掉。
        runner.progressedNow().ifPresent(this::recordProgress);
        return Next.stay();
    }

    // 再核对：同一张网了就算接上（转不转照实说）；铺完还不同网就按部分完成交代，已放的线材不拆。
    private Next<Phase> check() {
        MachineJoined.Verdict outcome = judge();
        if (outcome.joined()) {
            return finishJoined(outcome);
        }
        return Next.done(TaskResult.builder(TaskResult.Status.PARTIAL, "线路铺了，" + outcome.note())
                .problem(Problem.of(Problem.Kind.STUCK, outcome.note() + "（" + where() + "）",
                        "machine_inspect 看看线路哪里断了")).build());
    }

    private MachineJoined.Verdict judge() {
        return MachineJoined.judge(reader.membership(targetCell), reader.membership(sourceCell), machineState());
    }

    private Next<Phase> finishJoined(MachineJoined.Verdict outcome) {
        Optional<String> network = reader.membership(targetCell);
        NetworkSummary summary = network.map(reader::summary)
                .orElse(NetworkSummary.unreadable("none", "目标不在这张网上"));
        if (input.archive() != null && network.isPresent()) {
            rememberJoined(network.get());
        }
        String line = outcome.note() + describeSummary(summary)
                + (preferredRunning ? "；来源挑了在转的机器旁那格" : "");
        return Next.done(TaskResult.done(line));
    }

    private String where() {
        return targetCell.toShortString() + " 与 " + sourceCell.toShortString();
    }

    private String describeSummary(NetworkSummary summary) {
        if (!summary.readable()) {
            return "；网络读数读不到（" + summary.note() + "）";
        }
        List<String> readings = new ArrayList<>();
        summary.readings().forEach((name, value) -> readings.add(name + "=" + value));
        return readings.isEmpty() ? "" : "；网络：" + String.join("，", readings);
    }

    /** 目标机器此刻的读数；没有机器类型认领就按读不到对待，不猜。 */
    private MachineState machineState() {
        BlockState state = services.world().stateAt(targetCell).orElse(null);
        MachineType type = state == null ? null : services.claiming(state);
        return type == null ? MachineState.unknown("没有机器类型认领这一格，读不到运行状态")
                : type.state(targetCell);
    }

    /** 接上了把这张网记进档案：种类 → 网络编号，下次看档案知道它挂在哪。 */
    private void rememberJoined(String networkId) {
        Map<String, String> joined = new LinkedHashMap<>();
        joined.put(kind.id(), networkId);
        MachineArchive updated = archives.find(input.archive().name()).orElse(input.archive()).joinedTo(joined);
        if (!archives.save(updated)) {
            recordAttempt("记档案", "档案「" + updated.name() + "」没存进去，接线不受影响");
        }
    }

    /** 把施工子任务已经确认的事实接到本任务的结果里：放了的方块、没确认的交互、试过的办法。 */
    private void copyFacts(TaskResult result) {
        result.changes().forEach(this::recordChange);
        result.unconfirmed().forEach(this::recordUnconfirmed);
        result.attempts().forEach(attempt -> recordAttempt(attempt.tried(), attempt.result()));
    }

    @Override protected ResultDetails details() {
        if (reader == null || targetCell == null) {
            // 还没认出两端就结束的（种类不认、目标没落实）：没有两端可核对。
            return new MachineConnectDetails(kind, null, null, false, null,
                    NetworkSummary.unreadable("none", "还没走到核对这一步"), List.of(), "");
        }
        WorldPosition target = new WorldPosition(targetCell.getX(), targetCell.getY(), targetCell.getZ(),
                targetDimension);
        WorldPosition source = sourceCell == null ? null
                : new WorldPosition(sourceCell.getX(), sourceCell.getY(), sourceCell.getZ(), targetDimension);
        MachineJoined.Verdict outcome = MachineJoined.judge(reader.membership(targetCell),
                sourceCell == null ? Optional.empty() : reader.membership(sourceCell), machineState());
        NetworkSummary summary = reader.membership(targetCell).map(reader::summary)
                .orElse(NetworkSummary.unreadable("none", "目标不在这张网上"));
        return new MachineConnectDetails(kind, target, source, outcome.joined(), outcome.running(), summary,
                route == null ? List.of() : LineRoutes.describe(route), outcome.note());
    }

    @Override protected List<String> remaining() {
        return List.of("把机器接进 " + input.kind() + " 网");
    }

    @Override protected String describePhase(Phase value) {
        return switch (value) {
            case RESOLVE -> "认两端";
            case FIND_SOURCE -> "找挂着这种网的格子";
            case VERDICT -> "核对网络";
            case PLAN -> "规划线路";
            case BUILD -> "按线路施工";
            case CHECK -> "铺完再核对";
        };
    }
}
