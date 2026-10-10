// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.construction;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.BlockPos;
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
 * 施工：把一份蓝图变成现实。对图核一遍 → 缺料去拿 → 清障 → 从低到高砌 → 倒桶 → 再核一遍（有限修补）→ 收回临时方块。
 * 开始时就全对的一个动作都不做；材料不够就建到材料尽头如实停下；受保护的格一次列出全部不动；
 * 做不了的格记进结果照建其余。全部对了是完成，否则部分完成带问题。
 */
public final class ConstructionTask extends PhasedTask<ConstructionTask.Phase> {

    /** 核对 → 备料 → 清障 → 砌筑 → 倒桶 → 验收 → 收回临时方块。 */
    public enum Phase { CHECK, SUPPLY, CLEAR, PLACE, POUR, VERIFY, CLEANUP }

    /** 验收不过最多再修几轮；同一批错误重复出现就停，不无限拆建。 */
    private static final int MAX_REPAIR_ROUNDS = 2;

    private final ConstructionInput input;
    private final ConstructionServices services;
    private final ConstructionSite site;
    private Action prepared;
    private boolean started;
    private boolean travelled;
    private int repairRounds;
    private Set<BlockPos> lastNeedingWork = Set.of();
    private CleanupWork cleanup;
    private List<BlockPos> temporariesLeft = List.of();

    public ConstructionTask(ConstructionInput input, ConstructionServices services) {
        super("施工", Phase.CHECK, new ProgressTracker(20L * 60, 20L * 60 * 120));
        this.input = input;
        this.services = services;
        this.site = new ConstructionSite(input.blueprint());
    }

    @Override protected Action enter(Phase phase) {
        Action next = prepared;
        prepared = null;
        return next;
    }

    @Override protected Next<Phase> tick(Phase phase, TickContext context) {
        return switch (phase) {
            case CHECK -> check(context);
            case SUPPLY -> runActionThen(context, this::toClear);
            case CLEAR -> runActionThen(context, this::toPlace);
            case PLACE -> runActionThen(context, this::toPour);
            case POUR -> runActionThen(context, () -> Next.go(Phase.VERIFY, "倒完了，再对一遍图"));
            case VERIFY -> action() == null ? verify() : runActionThen(context, this::verify);
            case CLEANUP -> action() == null ? finish() : runActionThen(context, this::finish);
        };
    }

    // 对图核一遍：有格没加载就先走过去一次；全对就结束；挖不动的记做不了；然后去备料。
    private Next<Phase> check(TickContext context) {
        if (action() != null) return runActionThen(context, () -> Next.go(Phase.CHECK, "走到了，重新核对"));
        List<BlockPos> unloaded = site.check(services.site());
        if (!started) {
            started = true;
            services.ledger().siteStarted(input.blueprint());
        }
        if (!unloaded.isEmpty() && !travelled && services.travel() != null) {
            travelled = true;
            BlockPos first = unloaded.getFirst();
            var walk = services.travel().toward(new WorldPosition(first.getX(), first.getY(), first.getZ(), services.site().dimension()), input.permissions());
            if (walk.isPresent()) {
                prepared = walk.get();
                return Next.go(Phase.CHECK, "有格没加载，先走过去让它加载出来");
            }
        }
        for (BlockPos pos : unloaded) site.unreachable(pos, "那一片没加载，看不到");
        recordProgress("对图核完：还有 " + site.unsettled() + " 格要做");
        if (site.allSettled()) return Next.done(result("开始时已满足，一个动作都没做"));
        for (PlannedCell cell : input.blueprint().cells()) {
            if (site.needsWork(cell.pos()) && services.site().loaded(cell.pos()) && services.site().unbreakable(cell.pos())) {
                site.exclude(cell.pos(), CellEnding.IMPOSSIBLE, "挖不动（基岩或世界边界）");
            }
        }
        return toSupply();
    }

    private Next<Phase> toSupply() {
        Map<String, Integer> missing = site.missingMaterials(services.site());
        if (missing.isEmpty() || services.needs() == null) return toClear();
        prepared = new SupplyWork(services, site, records(), input.permissions(), input.purpose(), missing);
        return Next.go(Phase.SUPPLY, "缺 " + missing.size() + " 种料，先去拿");
    }

    private Next<Phase> toClear() {
        List<PlannedCell> cells = site.pendingClear(services.site());
        if (cells.isEmpty()) return toPlace();
        prepared = new ClearingWork(services, site, records(), input.permissions(), cells);
        return Next.go(Phase.CLEAR, "有 " + cells.size() + " 格挡着，先清掉");
    }

    private Next<Phase> toPlace() {
        List<PlannedCell> cells = site.pendingPlace();
        if (cells.isEmpty()) return toPour();
        prepared = new PlacingWork(services, site, records(), input.permissions(), input.purpose(), cells);
        return Next.go(Phase.PLACE, "开始砌，还有 " + cells.size() + " 格");
    }

    private Next<Phase> toPour() {
        List<BlockPos> strays = site.straySources(services.site());
        List<PlannedCell> pours = site.pendingPour();
        if (strays.isEmpty() && pours.isEmpty()) return toVerify();
        prepared = new PouringWork(services, site, records(), input.permissions(), strays, pours);
        return Next.go(Phase.POUR, "实心格砌完了，处理流体");
    }

    private Next<Phase> toVerify() {
        return Next.go(Phase.VERIFY, "再对一遍图");
    }

    // 验收：再核一遍；点名了开关的门按一次；还有错的格有限修补，同一批错误重复出现就停。
    private Next<Phase> verify() {
        site.check(services.site());
        List<PlannedCell> doors = new ArrayList<>();
        Set<BlockPos> needing = new HashSet<>();
        for (PlannedCell cell : input.blueprint().cells()) {
            if (!site.needsWork(cell.pos()) || !services.site().loaded(cell.pos())) continue;
            if (AdjustingWork.adjustableDoor(cell, services.site().state(cell.pos()))) doors.add(cell);
            else needing.add(cell.pos());
        }
        if (!doors.isEmpty() && repairRounds < MAX_REPAIR_ROUNDS) {
            repairRounds++;
            prepared = new AdjustingWork(services, site, records(), input.permissions(), doors);
            return Next.go(Phase.VERIFY, "有 " + doors.size() + " 扇门开关不对，去按一下");
        }
        if (!needing.isEmpty() && repairRounds < MAX_REPAIR_ROUNDS && !needing.equals(lastNeedingWork)) {
            repairRounds++;
            lastNeedingWork = needing;
            recordAttempt("验收", "有 " + needing.size() + " 格不对，再修一轮");
            return toClear();
        }
        for (BlockPos pos : needing) site.problem(endingReason(site.ending(pos)), pos);
        return toCleanup();
    }

    private static String endingReason(CellEnding ending) {
        return switch (ending) {
            case WRONG_BLOCK -> "结束时仍是别的方块";
            case WRONG_STATE -> "方块对了，点名的属性对不上，施工调不了";
            case MISSING -> "结束时仍是空的";
            default -> "结束时没对上";
        };
    }

    private Next<Phase> toCleanup() {
        if (services.ledger().temporaries().isEmpty()) return Next.go(Phase.CLEANUP, "没有临时方块要收");
        cleanup = new CleanupWork(services, site, records(), input.permissions());
        prepared = cleanup;
        return Next.go(Phase.CLEANUP, "收回临时方块");
    }

    private Next<Phase> finish() {
        temporariesLeft = cleanup == null ? services.ledger().temporaries() : cleanup.left();
        if (site.allSettled() && temporariesLeft.isEmpty()) return Next.done(result("按图建好了"));
        Problem problem = problem();
        return Next.done(TaskResult.builder(TaskResult.Status.PARTIAL, "按图建了一部分：" + site.unsettled() + " 格没做成")
                .problem(problem).remaining(remaining()).details(details()).build());
    }

    // 问题按轻重挑一个说：受保护的格要同意；缺料；够不着；其余按不支持。
    private Problem problem() {
        if (site.protectedCount() > 0) {
            return Problem.of(Problem.Kind.NEED_APPROVAL,
                    "有 " + site.protectedCount() + " 格是玩家的东西或在玩家的地盘里，没动：" + positions(CellEnding.PROTECTED),
                    "同意拆改的话，把 change_blocks 设为 any 再下达一次同样的目标");
        }
        for (var group : site.problemGroups()) {
            if (group.reason().startsWith("缺 ")) {
                return Problem.of(Problem.Kind.NEED_ITEM, "材料用完了：" + group.reason().substring(2) + " 还缺 " + group.count() + " 格的量",
                        "拿到材料后再下达一次同样的目标，会从没建完的地方接着做");
            }
        }
        for (var group : site.problemGroups()) {
            if (group.reason().startsWith("够不着")) {
                return Problem.of(Problem.Kind.UNREACHABLE, group.count() + " 格够不着：" + group.reason(), null);
            }
        }
        if (!temporariesLeft.isEmpty()) {
            return Problem.of(Problem.Kind.STUCK, "有 " + temporariesLeft.size() + " 块临时方块没收回", "下一次施工会接着收");
        }
        return Problem.of(Problem.Kind.UNSUPPORTED, "有 " + site.unsettled() + " 格没对上，原因见结果细节", null);
    }

    private String positions(CellEnding ending) {
        List<String> out = new ArrayList<>();
        for (PlannedCell cell : input.blueprint().cells()) {
            if (site.ending(cell.pos()) == ending) out.add(cell.pos().toShortString());
        }
        return String.join("、", out);
    }

    private TaskResult result(String summary) {
        return TaskResult.builder(TaskResult.Status.DONE, summary).details(details()).build();
    }

    @Override protected ResultDetails details() {
        return site.details(services.site().dimension(), temporariesLeft, input.fixturesSkipped());
    }

    @Override protected List<String> remaining() {
        List<String> left = new ArrayList<>();
        int unsettled = site.unsettled();
        if (unsettled > 0) left.add("还有 " + unsettled + " 格没砌");
        if (!temporariesLeft.isEmpty()) left.add("还有 " + temporariesLeft.size() + " 块临时方块没收回");
        return left;
    }

    @Override protected String describePhase(Phase value) {
        return switch (value) {
            case CHECK -> "对图核对";
            case SUPPLY -> "备料";
            case CLEAR -> "清障";
            case PLACE -> "砌筑";
            case POUR -> "倒桶";
            case VERIFY -> "验收";
            case CLEANUP -> "收回临时方块";
        };
    }
}
