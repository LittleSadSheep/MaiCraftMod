// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.ability.machine.spi.MachineType;
import org.maiwithu.maicraft.ability.machine.spi.NetworkKind;
import org.maiwithu.maicraft.ability.machine.spi.NetworkReader;
import org.maiwithu.maicraft.ability.machine.spi.NetworkSummary;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.ResultDetails;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 把机器接进网络的任务：认两端 → 已接着就不再动（转速为零、过载也照实说接上了）→
 * 没接上时如实交代差哪一段。只读现场与网络读数，不替角色规划线路：线路要铺哪种方块
 * 由各网络的联动说，现在还没有登记的途径能给出，铺线路这一步等联动补上。
 */
final class MachineConnectTask extends PhasedTask<MachineConnectTask.Phase> {

    /** 认两端 → 没给来源就扫一片找 → 核对结论。 */
    enum Phase { RESOLVE, FIND_SOURCE, VERDICT }

    private final MachineConnectInput input;
    private final MachineServices services;
    private final MachineArchives archives;
    private final MachineTargets targets;
    private NetworkReader reader;
    private NetworkKind kind;
    private BlockPos targetCell;
    private String targetDimension;
    private BlockPos sourceCell;
    /** 找来源的扫描账：见过的候选格与它们的网络编号，扫完挑最近的。 */
    private final Map<BlockPos, String> candidates = new LinkedHashMap<>();
    private boolean preferredRunning;

    MachineConnectTask(MachineConnectInput input, MachineServices services, MachineArchives archives) {
        super("接网络", Phase.RESOLVE, new ProgressTracker(600, 3600));
        this.input = input;
        this.services = services;
        this.archives = archives;
        this.targets = new MachineTargets(services);
    }

    @Override protected Action enter(Phase phase) {
        // 三个阶段都只做判断与读数，不带动手的动作。
        return null;
    }

    @Override protected Next<Phase> tick(Phase phase, TickContext context) {
        return switch (phase) {
            case RESOLVE -> resolve();
            case FIND_SOURCE -> findSource(context);
            case VERDICT -> verdict();
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
        if (found.state() == null) {
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

    /** 目标落实：档案名先对档案（锚点即目标格），其余交给机器目标解析。 */
    private MachineTargets.Resolution resolveTarget() {
        if (input.archive() != null) {
            if (services.world().playerSpot().isPresent()
                    && !services.world().playerSpot().get().dimension().equals(input.archive().dimension())) {
                return new MachineTargets.Resolution.DeadEnd(Problem.of(Problem.Kind.UNREACHABLE,
                        "档案「" + input.archive().name() + "」在另一个维度（" + input.archive().dimension() + "）"));
            }
            return new MachineTargets.Resolution.Found(input.archive().anchor(), null, input.archive().dimension());
        }
        return targets.resolve(input.target());
    }

    // 找来源：分刻扫一片，把挂着这种网的格子记下，机器在转的优先；扫完挑最近的，一个都没有照实说。
    private Next<Phase> findSource(TickContext context) {
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
                if (isRunning(cell.pos(), cell.state())) {
                    preferredRunning = true;
                }
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

    // 核对：两端在不在同一张网、接上了在不在转，结论与读数如实交付。
    private Next<Phase> verdict() {
        Optional<String> targetNetwork = reader.membership(targetCell);
        Optional<String> sourceNetwork = reader.membership(sourceCell);
        MachineState state = machineState();
        MachineJoined.Verdict outcome = MachineJoined.judge(targetNetwork, sourceNetwork, state);
        NetworkSummary summary = targetNetwork.map(reader::summary)
                .orElse(NetworkSummary.unreadable("none", "目标不在这张网上"));
        if (!outcome.joined()) {
            recordProgress(outcome.note());
            return Next.fail(Problem.of(Problem.Kind.UNSUPPORTED, describeGap(outcome),
                    "把对应模组的联动补上、它能给出这种网的线材与铺法之后，接线路这一步才能动手"));
        }
        if (input.archive() != null && targetNetwork.isPresent()) {
            rememberJoined(targetNetwork.get());
        }
        String line = outcome.note() + describeSummary(summary)
                + (preferredRunning ? "；来源挑了在转的机器旁那格" : "");
        return Next.done(TaskResult.done(line));
    }

    // 没接上时的差距说明：差的是线路，铺线路要的方块知识现在没有登记的来源。
    private String describeGap(MachineJoined.Verdict outcome) {
        String where = targetCell.toShortString() + " 与 " + sourceCell.toShortString();
        return outcome.note() + "（" + where + "）；铺线路要哪种方块、怎么铺，"
                + "现在没有登记的联动能给出，接线路动不了手";
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
        MachineArchive updated = input.archive().joinedTo(joined);
        if (!archives.save(updated)) {
            recordAttempt("记档案", "档案「" + updated.name() + "」没存进去，接线不受影响");
        }
    }

    @Override protected ResultDetails details() {
        if (reader == null || targetCell == null) {
            // 还没认出两端就结束的（种类不认、目标没落实）：没有两端可核对。
            return new MachineConnectDetails(kind, null, null, false, null,
                    NetworkSummary.unreadable("none", "还没走到核对这一步"), "");
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
                outcome.note());
    }

    @Override protected List<String> remaining() {
        return List.of("把机器接进 " + input.kind() + " 网");
    }

    @Override protected String describePhase(Phase value) {
        return switch (value) {
            case RESOLVE -> "认两端";
            case FIND_SOURCE -> "找挂着这种网的格子";
            case VERDICT -> "核对网络";
        };
    }
}
