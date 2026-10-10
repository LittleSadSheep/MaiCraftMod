// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.ability.machine.spi.ExchangePoint;
import org.maiwithu.maicraft.ability.machine.spi.MachineType;
import org.maiwithu.maicraft.ability.machine.spi.NetworkReader;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.ResultDetails;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 机器查看的任务：分几刻把范围里的格子读一遍，认领的机器方块按相邻与同网分成一台台，
 * 读每台的组成、进出口、运行状态与挂着的网络，一次给全，不截断。只读，不动角色。
 *
 * <p>"看着像机器但没有机器类型认领"的格（有方块实体、又不是原版方块）按方块 ID 归并着报，
 * 写明缺哪个联动；没加载的格只数个数，不按空气算。这一片没有档案可比（档案随施工接入），
 * 现状就是全部结论。
 */
final class MachineInspectTask extends PhasedTask<MachineInspectTask.Phase> {

    /** 分刻读格子 → 分台 → 交付。 */
    enum Phase { SCAN, DELIVER }

    /** 一个方块 ID 的没认领格先列几个位置当例子；总数照实给，不悄悄截断。 */
    private static final int SAMPLE_POSITIONS = 8;

    private final MachineInspectInput input;
    private final MachineServices services;
    private final MachineTargets targets;
    /** 落实下来的扫描中心与维度。 */
    private BlockPos center;
    private String dimension;
    /** 到目前为止认领的机器方块与它们的方块状态。 */
    private final Map<BlockPos, BlockState> claimedStates = new LinkedHashMap<>();
    /** 没有机器类型认领的格，按方块 ID 归并。 */
    private final Map<String, UnrecognizedAccumulator> unrecognized = new LinkedHashMap<>();
    private int unloadedSoFar;
    private boolean scanComplete;
    /** 交付阶段建好的机器清单；没走到交付为空。 */
    private List<MachineGrouping.Machine> machines = List.of();

    MachineInspectTask(MachineInspectInput input, MachineServices services) {
        super("看机器", Phase.SCAN, new ProgressTracker(400, 2400));
        this.input = input;
        this.services = services;
        this.targets = new MachineTargets(services);
    }

    @Override protected Action enter(Phase phase) {
        // 两个阶段都只做判断，不带动手的动作。
        return null;
    }

    @Override protected Next<Phase> tick(Phase phase, TickContext context) {
        return switch (phase) {
            case SCAN -> scanTick();
            case DELIVER -> deliver();
        };
    }

    // 第一刻先落实目标对象，之后每刻读一小段；读完了去交付。
    private Next<Phase> scanTick() {
        if (center == null) {
            MachineTargets.Resolution resolved = targets.resolve(input.target());
            if (resolved instanceof MachineTargets.Resolution.DeadEnd dead) {
                return Next.fail(dead.problem());
            }
            center = ((MachineTargets.Resolution.Found) resolved).cell();
            dimension = ((MachineTargets.Resolution.Found) resolved).dimension();
        }
        ReadsMachineArea.Round round = services.area().scan(dimension, center, input.radius());
        if (round.worldChanged()) {
            return Next.fail(Problem.of(Problem.Kind.UNREACHABLE,
                    "看到一半角色离开了这个世界，这一眼作废", "回到世界再看一遍"));
        }
        absorb(round);
        recordProgress("读机器区：" + describeCells(round));
        if (!round.complete()) {
            return Next.stay();
        }
        scanComplete = true;
        return Next.go(Phase.DELIVER, "范围读完了，整理看到的东西");
    }

    // 把这一轮读出的格子分拣：认领的记下来，没认领的按方块 ID 归并，未加载的记总数。
    private void absorb(ReadsMachineArea.Round round) {
        unloadedSoFar = round.unloadedCells();
        for (ReadsMachineArea.Cell cell : round.cells()) {
            MachineType claiming = services.claiming(cell.state());
            if (claiming != null) {
                claimedStates.put(cell.pos(), cell.state());
                continue;
            }
            String blockId = BuiltInRegistries.BLOCK.getKey(cell.state().getBlock()).toString();
            if (!looksLikeUnclaimedMachine(cell.hasBlockEntity(), blockId)) {
                // 没有方块实体的格与原版方块不算"看着像机器"：原版方块归原版能力管。
                continue;
            }
            unrecognized.computeIfAbsent(blockId, key -> new UnrecognizedAccumulator())
                    .add(cell.pos());
        }
    }

    // 交付：分组、读组成与状态、挂网，一次给全。
    private Next<Phase> deliver() {
        List<MachineGrouping.Claimed> claimed = claimedStates.entrySet().stream()
                .map(entry -> new MachineGrouping.Claimed(entry.getKey(),
                        services.claiming(entry.getValue())))
                .toList();
        machines = MachineGrouping.group(claimed, at -> MachineGrouping.membershipsOf(services, at));
        recordProgress("分成 " + machines.size() + " 台机器");
        return Next.done(TaskResult.done(summary()));
    }

    private String summary() {
        StringBuilder line = new StringBuilder();
        if (machines.isEmpty()) {
            line.append("这一片没有认得的机器");
        } else {
            line.append("看到 ").append(machines.size()).append(" 台机器");
        }
        if (!unrecognized.isEmpty()) {
            line.append("；有 ").append(unrecognized.values().stream().mapToInt(value -> value.cells).sum())
                    .append(" 格看着像机器但没有机器类型认领");
        }
        if (unloadedSoFar > 0) {
            line.append("；").append(unloadedSoFar).append(" 格没加载，不知道是什么");
        }
        line.append("。机器能不能转以各台的运行状态为准，光看组成说不准");
        return line.toString();
    }

    /**
     * 看着像机器、但还没有机器类型认领的格：有方块实体（机器都要存东西），又不是原版方块
     * （原版没有"缺联动"一说，箱子与熔炉归原版能力管）。纯函数，输入就是方块 ID。
     */
    static boolean looksLikeUnclaimedMachine(boolean hasBlockEntity, String blockId) {
        return hasBlockEntity && !blockId.startsWith("minecraft:");
    }

    private String describeCells(ReadsMachineArea.Round round) {
        return "本轮读出 " + round.cells().size() + " 个非空格，认领了 " + claimedStates.size() + " 格";
    }

    @Override protected ResultDetails details() {
        String missing = "装了它所属模组的联动、且该联动认领这种机器时才能认出来";
        return new MachineDetails(machines.stream().map(this::view).toList(), unrecognized.entrySet().stream()
                .map(entry -> new MachineDetails.UnrecognizedBlock(entry.getKey(), entry.getValue().cells,
                        entry.getValue().samples, missing)).toList(),
                unloadedSoFar, scanComplete, note());
    }

    private String note() {
        if (!scanComplete) {
            return "范围没有读完，列出的只是已经看到的部分";
        }
        return "";
    }

    // 一台机器的查看视图：组成按角色分组，进出口并起来，运行状态每种机器读一格，网络逐张汇总。
    private MachineDetails.MachineView view(MachineGrouping.Machine machine) {
        List<MachineDetails.RoleGroup> roles = rolesOf(machine);
        List<ExchangePoint> ports = new ArrayList<>();
        for (MachineGrouping.Claimed cell : cellsOf(machine)) {
            ports.addAll(cell.type().exchangePoints(claimedStates.get(cell.pos()), cell.pos()));
        }
        List<MachineDetails.TypedState> running = new ArrayList<>();
        Set<String> reported = new LinkedHashSet<>();
        for (MachineGrouping.Claimed cell : cellsOf(machine)) {
            if (reported.add(cell.type().id())) {
                running.add(new MachineDetails.TypedState(cell.type().id(), cell.pos(),
                        cell.type().state(cell.pos())));
            }
        }
        List<MachineDetails.NetworkView> networks = networksOf(machine);
        return new MachineDetails.MachineView("m" + machine.number(), null, roles, ports, running, networks);
    }

    // 分组结果里存的是格子的位置，方块状态从认领账里拿回来。
    private List<MachineGrouping.Claimed> cellsOf(MachineGrouping.Machine machine) {
        return machine.cells().stream()
                .map(pos -> new MachineGrouping.Claimed(pos, services.claiming(claimedStates.get(pos))))
                .toList();
    }

    private List<MachineDetails.RoleGroup> rolesOf(MachineGrouping.Machine machine) {
        Map<String, List<String>> byRole = new LinkedHashMap<>();
        for (MachineGrouping.Claimed cell : cellsOf(machine)) {
            byRole.computeIfAbsent(cell.type().role().name().toLowerCase(Locale.ROOT),
                    key -> new ArrayList<>()).add(cell.type().id() + " (" + cell.pos().toShortString() + ")");
        }
        return byRole.entrySet().stream()
                .map(entry -> new MachineDetails.RoleGroup(entry.getKey(), entry.getValue()))
                .toList();
    }

    private List<MachineDetails.NetworkView> networksOf(MachineGrouping.Machine machine) {
        Map<String, String> memberships = new LinkedHashMap<>();
        for (BlockPos pos : machine.cells()) {
            memberships.putAll(MachineGrouping.membershipsOf(services, pos));
        }
        List<MachineDetails.NetworkView> networks = new ArrayList<>();
        for (NetworkReader reader : services.networkReaders()) {
            String id = memberships.get(reader.kind().id());
            if (id != null) {
                networks.add(new MachineDetails.NetworkView(reader.kind(), id, reader.summary(id)));
            }
        }
        return networks;
    }

    @Override protected String describePhase(Phase value) {
        return switch (value) {
            case SCAN -> "读范围内的格子";
            case DELIVER -> "整理看到的机器";
        };
    }

    /** 没认领格的归并账：共几格，前几个位置当例子；方块 ID 是归并的键，放在清单条目上。 */
    private static final class UnrecognizedAccumulator {
        final List<BlockPos> samples = new ArrayList<>();
        int cells;

        void add(BlockPos pos) {
            cells++;
            if (samples.size() < SAMPLE_POSITIONS) {
                samples.add(pos);
            }
        }
    }
}
