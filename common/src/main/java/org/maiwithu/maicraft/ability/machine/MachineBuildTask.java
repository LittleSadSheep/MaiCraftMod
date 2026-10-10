// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.ability.machine.spi.Installation;
import org.maiwithu.maicraft.ability.machine.spi.MachineType;
import org.maiwithu.maicraft.ability.machine.spi.PartCell;
import org.maiwithu.maicraft.behavior.construction.Blueprint;
import org.maiwithu.maicraft.behavior.construction.BlueprintCheck;
import org.maiwithu.maicraft.behavior.construction.CellState;
import org.maiwithu.maicraft.behavior.construction.ConstructionDetails;
import org.maiwithu.maicraft.behavior.construction.ConstructionInput;
import org.maiwithu.maicraft.behavior.construction.ConstructionServices;
import org.maiwithu.maicraft.behavior.construction.ConstructionTask;
import org.maiwithu.maicraft.behavior.construction.PlannedCell;
import org.maiwithu.maicraft.behavior.construction.ReadsBlocks;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryKind;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.kernel.child.ChildTaskRunner;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.ResultDetails;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

/**
 * 按机器蓝图施工的任务：先看档案是否已满足，普通格与流体交给施工引擎，之后装安装段、装部件、
 * 改装后设置（用 machine_configure 的同一套任务），最后整机比对并把补丁并进档案。
 *
 * <p>安装段与部件的格在蓝图里标成"由机器放"：引擎不放、不清障、核对时出"由机器核对"，
 * 由认领它们的机器类型用模组自己的方式装、按最终结构核对。拆整台是把档案范围换成全清空
 * 交给同一个引擎逐格挖。施工子任务失败按它的问题结束，已放的不回滚；机器条目做不成的不
 * 拦住其余部分，各记各的结局。
 */
final class MachineBuildTask extends PhasedTask<MachineBuildTask.Phase> {

    /** 看是否已满足 → 施工 → 装安装段 → 装部件 → 改设置 → 整机比对并落档案。 */
    enum Phase { PRECHECK, CONSTRUCT, INSTALL, MOUNT, CONFIGURE, SETTLE }

    private static final String BUILD_PURPOSE = "按机器蓝图施工";
    private static final String REMOVE_PURPOSE = "拆机器";

    private final MachineBuildInput input;
    private final MachineServices services;
    private final MachineArchives archives;
    private final WorldMemory memory;
    private final Function<Permissions, ConstructionServices> constructionServices;

    /** 施工要落的那份蓝图：安装段的格标成由机器放；拆整台是档案范围的全清空。 */
    private final Blueprint site;
    /** 整机比对用的蓝图：安装段的格保留声明，核对时出"由机器核对"，不冒充逐格放好。 */
    private final Blueprint target;
    private final BlockPos anchor;

    /** 下一刻要推进的动作：上一刻按现场备好，原地重进阶段时交给基类。 */
    private Action prepared;
    /** 正在推进的子任务（施工或一项设置）；一次一个。 */
    private ChildTaskRunner runner;
    private TaskResult constructionResult;
    private Problem carriedProblem;

    private final Deque<MachineBlueprint.Segment> toInstall = new ArrayDeque<>();
    private final Deque<MachineBlueprint.Part> toMount = new ArrayDeque<>();
    private final Deque<MachineBlueprint.Setting> toConfigure = new ArrayDeque<>();
    private final List<MachineBuildDetails.SegmentResult> segmentResults = new ArrayList<>();
    private final List<MachineBuildDetails.PartResult> partResults = new ArrayList<>();
    private final List<MachineBuildDetails.SettingResult> settingResults = new ArrayList<>();
    /** 正在装的一段、一个部件或正在改的一条：动作与子任务收场后按它核对记账。 */
    private MachineBlueprint.Segment installing;
    private MachineBlueprint.Part mounting;
    private MachineBlueprint.Setting configuring;
    private BlueprintCheck.Result check;

    MachineBuildTask(MachineBuildInput input, MachineServices services, MachineArchives archives,
            WorldMemory memory, Function<Permissions, ConstructionServices> constructionServices) {
        super("按机器蓝图施工", Phase.PRECHECK, new ProgressTracker(1200, 216000));
        this.input = input;
        this.services = services;
        this.archives = archives;
        this.memory = memory;
        this.constructionServices = constructionServices;
        this.anchor = new BlockPos(input.anchor().x(), input.anchor().y(), input.anchor().z());
        this.site = input.remove() ? removalPlan() : sitePlan();
        this.target = input.remove() ? site : targetPlan();
        if (!input.remove()) {
            toInstall.addAll(input.blueprint().installations());
            toMount.addAll(input.blueprint().parts());
            toConfigure.addAll(input.blueprint().settings());
        }
    }

    @Override protected Action enter(Phase phase) {
        Action next = prepared;
        prepared = null;
        return next;
    }

    @Override protected Next<Phase> tick(Phase phase, TickContext context) {
        return switch (phase) {
            case PRECHECK -> precheck();
            case CONSTRUCT -> construct(context);
            case INSTALL -> install(context);
            case MOUNT -> mount(context);
            case CONFIGURE -> configure(context);
            case SETTLE -> settle();
        };
    }

    // 已满足：目标是已有档案、补丁没带来任何变化，且结构与机器条目都对 → 一个动作都不做。
    private Next<Phase> precheck() {
        if (input.remove()) {
            return Next.go(Phase.CONSTRUCT, "按档案范围逐格拆");
        }
        if (input.archive() != null && !MachinePatch.changed(input.blueprint(), input.archive().blueprint())) {
            check = compareNow();
            if (check.allMatch() && nothingPending()) {
                recordProgress("开始时已和蓝图一致，一个动作都没做");
                return Next.go(Phase.SETTLE, "开始时已和蓝图一致");
            }
            return Next.go(Phase.CONSTRUCT, "有没对上的地方，从施工走一遍");
        }
        return Next.go(Phase.CONSTRUCT, "开工：普通格与流体先交给施工引擎");
    }

    // 施工：普通格与流体整段交给施工引擎这个子任务；它的核对、备料、清障、倒桶、验收、收临时方块都在里面。
    private Next<Phase> construct(TickContext context) {
        if (runner == null) {
            runner = new ChildTaskRunner(ChildTaskRunner.NO_LIMIT);
            runner.begin(new ConstructionTask(new ConstructionInput(site, input.permissions(), purpose()),
                    constructionServices.apply(input.permissions())), context);
            return Next.stay();
        }
        if (runner.tick(context) instanceof TickResult.Finished finished) {
            constructionResult = finished.result();
            copyFacts(constructionResult);
            runner = null;
            if (constructionResult.status() == TaskResult.Status.FAILED) {
                return Next.fail(constructionResult.problem());
            }
            if (constructionResult.status() == TaskResult.Status.PARTIAL) {
                carriedProblem = constructionResult.problem();
            }
            return Next.go(nextWorkPhase(), "现场做完，" + (nothingLeft() ? "整机比对" : "装机器自己的部分"));
        }
        return Next.stay();
    }

    // 安装段：已经是要求的样子就过；不然交给机器类型的装法（两端的轴就位后用模组自己的方式连），按最终结构核对。
    private Next<Phase> install(TickContext context) {
        if (action() != null) {
            return switch (runAction(context)) {
                case ActionStatus.Running running -> Next.stay();
                case ActionStatus.Done done -> segmentFinished("");
                case ActionStatus.Failed failed -> segmentFinished(failed.problem().message());
            };
        }
        MachineBlueprint.Segment next = toInstall.poll();
        if (next == null) {
            return Next.go(nextWorkPhase(), "安装段做完");
        }
        installing = next;
        Installation installation = absolute(next);
        if (typeInstalled(installation).isPresent()) {
            return segmentFinished("");
        }
        // 问遍机器类型，肯装的第一家给的动作直接用；都不肯就照实记下这一段没装成。
        for (MachineType type : services.machineTypes()) {
            Optional<Action> laying = type.install(installation, input.permissions());
            if (laying.isEmpty()) {
                continue;
            }
            prepared = laying.get();
            return Next.go(Phase.INSTALL, "装 " + next.kind() + " 这一段");
        }
        return segmentFinished(noTypeFor(installation.kind()));
    }

    // 一段收场：装没装成以机器类型按最终结构核对的结论为准，动作的失败原因带在说明里。
    private Next<Phase> segmentFinished(String why) {
        Installation installation = absolute(installing);
        boolean done = typeInstalled(installation).isPresent();
        segmentResults.add(new MachineBuildDetails.SegmentResult(installing.kind(),
                installation.cells().stream().map(BlockPos::toShortString).toList(), done, why));
        if (done) {
            recordProgress("装好了 " + installing.kind());
        } else {
            recordAttempt("装 " + installing.kind(), why);
        }
        installing = null;
        return Next.go(toInstall.isEmpty() ? nextWorkPhase() : Phase.INSTALL,
                toInstall.isEmpty() ? "安装段做完" : "装好了这段，接着下一段");
    }

    // 部件：宿主就位后手持部件右键那一面；装上没有以部件出现在那一面为准。
    private Next<Phase> mount(TickContext context) {
        if (action() != null) {
            return switch (runAction(context)) {
                case ActionStatus.Running running -> Next.stay();
                case ActionStatus.Done done -> partFinished("");
                case ActionStatus.Failed failed -> partFinished(failed.problem().message());
            };
        }
        MachineBlueprint.Part next = toMount.poll();
        if (next == null) {
            return Next.go(nextWorkPhase(), "部件做完");
        }
        mounting = next;
        PartCell part = absolute(next);
        Optional<BlockState> host = services.world().stateAt(part.host());
        if (host.isEmpty()) {
            return partFinished("宿主那一格还没加载");
        }
        for (MachineType type : services.machineTypes()) {
            if (type.partProblem(part, host.get()).isPresent()) {
                continue;
            }
            Optional<Action> willing = type.mount(part, input.permissions());
            if (willing.isEmpty()) {
                continue;
            }
            prepared = willing.get();
            return Next.go(Phase.MOUNT, "把 " + next.itemId() + " 装到宿主的" + next.side().getName() + "面");
        }
        return partFinished(noTypeForPart());
    }

    private Next<Phase> partFinished(String why) {
        PartCell part = absolute(mounting);
        boolean done;
        if (!why.isEmpty()) {
            done = false;
        } else {
            done = mountedSomewhere(part);
            if (!done) {
                why = "动作做完了，部件没有出现在那一面";
            }
        }
        partResults.add(new MachineBuildDetails.PartResult(mounting.itemId(), part.host().toShortString(),
                part.side().getName(), done, why));
        if (done) {
            recordProgress("装上了部件 " + mounting.itemId());
        } else {
            recordAttempt("装部件 " + mounting.itemId(), why);
        }
        mounting = null;
        return Next.go(toMount.isEmpty() ? nextWorkPhase() : Phase.MOUNT,
                toMount.isEmpty() ? "部件做完" : "装好了这个，接着下一个");
    }

    // 装后设置：逐条交给 machine_configure 的同一套任务（认机器、循环切换、读回核对），结果收回来记账。
    private Next<Phase> configure(TickContext context) {
        if (runner == null) {
            MachineBlueprint.Setting next = toConfigure.poll();
            if (next == null) {
                return Next.go(Phase.SETTLE, "设置改完，整机比对");
            }
            configuring = next;
            BlockPos at = anchor.offset(next.offset());
            runner = new ChildTaskRunner(ChildTaskRunner.NO_LIMIT);
            runner.begin(new MachineConfigureTask(new MachineConfigureInput(
                    new Target.Position(at.getX(), at.getY(), at.getZ(), null),
                    Map.of(next.key(), next.value()), input.permissions()), services), context);
            return Next.stay();
        }
        if (runner.tick(context) instanceof TickResult.Finished finished) {
            runner = null;
            MachineBlueprint.Setting done = configuring;
            configuring = null;
            boolean applied = finished.result().status() == TaskResult.Status.DONE;
            String readback = readBack(anchor.offset(done.offset()), done.key());
            settingResults.add(new MachineBuildDetails.SettingResult(done.key(),
                    anchor.offset(done.offset()).toShortString(), applied, readback));
            if (applied) {
                recordProgress("「" + done.key() + "」改好了，读回 " + readback);
            } else {
                recordAttempt("改设置 " + done.key(), finished.result().summary());
                carriedProblem = carriedProblem != null ? carriedProblem : finished.result().problem();
            }
            copyFacts(finished.result());
        }
        return Next.stay();
    }

    // 整机比对与落档：结构对不对、装没装成、档案存没存住三件事分开说，互不冒充。
    private Next<Phase> settle() {
        if (check == null) {
            check = compareNow();
            recordProgress("整机比对：" + describeCheck());
        }
        boolean structureOk = check.allMatch();
        boolean entriesOk = segmentResults.stream().allMatch(MachineBuildDetails.SegmentResult::installed)
                && partResults.stream().allMatch(MachineBuildDetails.PartResult::mounted)
                && settingResults.stream().allMatch(MachineBuildDetails.SettingResult::applied);
        saveArchive(structureOk);
        boolean allOk = structureOk && entriesOk && constructionDone();
        String summary = "机器「" + input.name() + "」"
                + (allOk ? (input.remove() ? "拆完了" : "建好了") : "做到一半：结构与机器条目详见结果细节");
        return allOk ? Next.done(TaskResult.done(summary))
                : Next.done(TaskResult.builder(TaskResult.Status.PARTIAL, summary)
                        .problem(problem()).build());
    }

    /** 档案更新：补丁并入或标拆除；存不进只记问题，不改判施工。 */
    private void saveArchive(boolean structureOk) {
        Instant now = Instant.now();
        MachineArchive stored;
        if (input.remove()) {
            stored = existingArchive().markRemoved();
            // 拆掉的机器从记忆里抹掉：位置上已经没有这台机器，留着旧记录只会再骗一次。
            memory.forget(MemoryKind.MACHINE, input.anchor());
        } else {
            stored = (input.archive() != null ? input.archive()
                    : MachineArchive.fresh(input.name(), input.anchor().dimension(), anchor, input.blueprint(), null))
                    .withBlueprint(input.blueprint(), null, now, checkNote(structureOk));
            memory.rememberMachine(input.anchor(), input.name(), now);
        }
        if (!archives.save(stored)) {
            recordAttempt("存档案", "档案「" + input.name() + "」没存进去，施工结果不受影响");
        }
    }

    private MachineArchive existingArchive() {
        return input.archive() != null ? input.archive() : archives.find(input.name()).orElseThrow(
                () -> new IllegalStateException("拆机器却找不到档案：" + input.name()));
    }

    private String checkNote(boolean structureOk) {
        return structureOk ? "整机与蓝图一致" : "还有 " + unsettledCells() + " 格没对上";
    }

    /** 组装问题：先说施工没做完的原因，再说机器条目哪几件没做成。 */
    private Problem problem() {
        if (carriedProblem != null) {
            return carriedProblem;
        }
        List<String> undone = new ArrayList<>();
        countUndone(segmentResults.stream().filter(result -> !result.installed()).count(), "段安装段", undone);
        countUndone(partResults.stream().filter(result -> !result.mounted()).count(), "个部件", undone);
        countUndone(settingResults.stream().filter(result -> !result.applied()).count(), "条设置", undone);
        if (unsettledCells() > 0) {
            undone.add(unsettledCells() + " 格没对上");
        }
        if (undone.isEmpty()) {
            return Problem.of(Problem.Kind.STUCK, "没有全部做成，原因见结果细节", null);
        }
        return Problem.of(Problem.Kind.UNSUPPORTED, "还有 " + String.join("、", undone) + " 没做成",
                "缺的联动装上后，再下达一次同样的目标会接着做");
    }

    private void countUndone(long count, String unit, List<String> into) {
        if (count > 0) {
            into.add(count + " " + unit);
        }
    }

    @Override protected List<String> remaining() {
        List<String> left = new ArrayList<>();
        if (unsettledCells() > 0) {
            left.add("还有 " + unsettledCells() + " 格没对上");
        }
        for (MachineBlueprint.Segment segment : toInstall) {
            left.add("还没装安装段 " + segment.kind());
        }
        for (MachineBlueprint.Part part : toMount) {
            left.add("还没装部件 " + part.itemId());
        }
        for (MachineBlueprint.Setting setting : toConfigure) {
            left.add("还没改设置 " + setting.key());
        }
        return left;
    }

    @Override protected ResultDetails details() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (check != null) {
            for (CellState state : CellState.values()) {
                int count = check.count(state);
                if (count > 0) {
                    counts.put(state.name().toLowerCase(Locale.ROOT), count);
                }
            }
        }
        List<String> notes = new ArrayList<>();
        if (check != null && !check.complete()) {
            notes.add("有格没加载，整机比对下不了结论");
        }
        return new MachineBuildDetails(input.name(), input.anchor(), input.remove(),
                check == null ? null : check.complete(), counts, constructionCells(),
                segmentResults, partResults, settingResults, String.join("；", notes));
    }

    /** 施工子任务结果里的每格结局计数；没跑到施工（例如已满足提前收场）给空表。 */
    private Map<String, Integer> constructionCells() {
        if (constructionResult == null
                || !(constructionResult.details() instanceof ConstructionDetails construction)) {
            return Map.of();
        }
        return construction.cells();
    }

    @Override protected String describePhase(Phase value) {
        return switch (value) {
            case PRECHECK -> "看是否已满足";
            case CONSTRUCT -> "施工：普通格与流体";
            case INSTALL -> "装安装段";
            case MOUNT -> "装部件";
            case CONFIGURE -> "改装后设置";
            case SETTLE -> "整机比对与落档";
        };
    }

    private String purpose() {
        return input.remove() ? REMOVE_PURPOSE : BUILD_PURPOSE;
    }

    /** 还剩哪一步：施工之后按安装段 → 部件 → 设置 → 比对的顺序挑下一个有人要做的阶段。 */
    private Phase nextWorkPhase() {
        if (!toInstall.isEmpty()) {
            return Phase.INSTALL;
        }
        if (!toMount.isEmpty()) {
            return Phase.MOUNT;
        }
        if (!toConfigure.isEmpty()) {
            return Phase.CONFIGURE;
        }
        return Phase.SETTLE;
    }

    private boolean nothingLeft() {
        return toInstall.isEmpty() && toMount.isEmpty() && toConfigure.isEmpty();
    }

    /** 施工要落的那份蓝图：偏移落锚点，安装段占的格标成由机器放（引擎不放、不清、核对交给机器）。 */
    private Blueprint sitePlan() {
        Blueprint laid = Blueprint.at(input.anchor().dimension(), anchor, Rotation.NONE, input.blueprint().cells());
        List<PlannedCell> adjusted = new ArrayList<>();
        Set<BlockPos> machineCells = installationCells();
        for (PlannedCell cell : laid.cells()) {
            adjusted.add(machineCells.remove(cell.pos()) ? cell.byMachine() : cell);
        }
        for (BlockPos left : machineCells) {
            // 蓝图没逐格声明的安装段格：照样标给机器，引擎不碰，核对时由机器认。
            adjusted.add(PlannedCell.air(left).byMachine());
        }
        return new Blueprint(laid.dimension(), anchor, adjusted);
    }

    /** 比对用的蓝图：安装段占的格标成由机器核对，保留原声明，不冒充逐格放好。 */
    private Blueprint targetPlan() {
        Blueprint laid = Blueprint.at(input.anchor().dimension(), anchor, Rotation.NONE, input.blueprint().cells());
        List<PlannedCell> adjusted = new ArrayList<>();
        for (PlannedCell cell : laid.cells()) {
            adjusted.add(installationCells().contains(cell.pos()) ? cell.byMachine() : cell);
        }
        return new Blueprint(laid.dimension(), anchor, adjusted);
    }

    private Set<BlockPos> installationCells() {
        Set<BlockPos> cells = new LinkedHashSet<>();
        for (MachineBlueprint.Segment segment : input.blueprint().installations()) {
            for (BlockPos offset : segment.offsets()) {
                cells.add(anchor.offset(offset));
            }
        }
        return cells;
    }

    /** 拆整台的施工蓝图：档案范围逐格换成清空（含安装段占的格），交给施工引擎挖。 */
    private Blueprint removalPlan() {
        MachineBlueprint from = existingArchive().blueprint();
        Set<BlockPos> declared = new LinkedHashSet<>();
        List<PlannedCell> cells = new ArrayList<>();
        for (PlannedCell cell : from.cells()) {
            cells.add(PlannedCell.air(anchor.offset(cell.pos())));
            declared.add(anchor.offset(cell.pos()));
        }
        for (MachineBlueprint.Segment segment : from.installations()) {
            for (BlockPos offset : segment.offsets()) {
                BlockPos at = anchor.offset(offset);
                if (declared.add(at)) {
                    cells.add(PlannedCell.air(at));
                }
            }
        }
        return new Blueprint(input.anchor().dimension(), anchor, cells);
    }

    private BlueprintCheck.Result compareNow() {
        return BlueprintCheck.compare(target, worldView, Set.of());
    }

    /** 没对上的格数：要施工引擎动手的，加没加载下不了结论的。 */
    private long unsettledCells() {
        return check == null ? 0 : check.states().values().stream()
                .filter(state -> state.needsWork() || state == CellState.UNKNOWN).count();
    }

    private String describeCheck() {
        StringBuilder line = new StringBuilder();
        for (CellState state : CellState.values()) {
            int count = check.count(state);
            if (count > 0) {
                line.append(line.isEmpty() ? "" : "，").append(state.name().toLowerCase(Locale.ROOT)).append(" ").append(count);
            }
        }
        return line.toString();
    }

    /** 施工子任务做全了没有：没跑到施工（已满足提前收场）或全部做成算过。 */
    private boolean constructionDone() {
        return constructionResult == null || constructionResult.status() != TaskResult.Status.PARTIAL;
    }

    /** 还有没有没做成的机器条目：已满足判断用。 */
    private boolean nothingPending() {
        for (MachineBlueprint.Segment segment : input.blueprint().installations()) {
            if (typeInstalled(absolute(segment)).isEmpty()) {
                return false;
            }
        }
        for (MachineBlueprint.Part part : input.blueprint().parts()) {
            PartCell cell = absolute(part);
            if (services.world().stateAt(cell.host()).isEmpty() || !mountedSomewhere(cell)) {
                return false;
            }
        }
        for (MachineBlueprint.Setting setting : input.blueprint().settings()) {
            if (!settingAlready(anchor.offset(setting.offset()), setting)) {
                return false;
            }
        }
        return true;
    }

    private boolean settingAlready(BlockPos at, MachineBlueprint.Setting wanted) {
        return wanted.value().equals(readBack(at, wanted.key()));
    }

    /** 读回一格机器的一项设置现值；那格没加载、没有机器类型认领或没有这一项都给空串，按"读不到"继续。 */
    private String readBack(BlockPos at, String key) {
        BlockState state = services.world().stateAt(at).orElse(null);
        MachineType type = state == null ? null : services.claiming(state);
        if (type == null) {
            return "";
        }
        return type.settings(at).stream()
                .filter(setting -> setting.key().equals(key))
                .map(setting -> setting.value() == null ? "" : setting.value())
                .findFirst()
                .orElse("");
    }

    /** 这段安装段现在是不是按要求装成了（按最终结构核对）。 */
    private Optional<MachineType> typeInstalled(Installation installation) {
        for (MachineType type : services.machineTypes()) {
            if (type.installed(installation)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }

    private boolean mountedSomewhere(PartCell part) {
        for (MachineType type : services.machineTypes()) {
            if (type.mounted(part)) {
                return true;
            }
        }
        return false;
    }

    private Installation absolute(MachineBlueprint.Segment segment) {
        return new Installation(segment.kind(), segment.offsets().stream().map(anchor::offset).toList());
    }

    private PartCell absolute(MachineBlueprint.Part part) {
        return new PartCell(anchor.offset(part.offset()), part.side(), part.itemId());
    }

    private String noTypeFor(String kind) {
        return services.machineTypes().isEmpty()
                ? "这个实例没有登记任何机器联动，认不出安装段 " + kind
                : "没有机器类型认安装段 " + kind;
    }

    private String noTypeForPart() {
        return services.machineTypes().isEmpty()
                ? "这个实例没有登记任何机器联动，认不出这个部件"
                : "没有机器类型肯把这个部件装在宿主的这一面";
    }

    /** 把子任务（施工、改设置）已经确认的事实接到本任务的结果里：放了的方块、没确认的交互、试过的办法。 */
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

    /** 读现场给整机比对：一格有没有加载看现场视图给没给状态。 */
    private final ReadsBlocks worldView = new ReadsBlocks() {
        @Override public boolean loaded(BlockPos pos) {
            return services.world().stateAt(pos).isPresent();
        }

        @Override public BlockState state(BlockPos pos) {
            return services.world().stateAt(pos).orElse(Blocks.AIR.defaultBlockState());
        }
    };
}
