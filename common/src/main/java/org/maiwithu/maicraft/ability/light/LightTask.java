// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.light;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.construction.Blueprint;
import org.maiwithu.maicraft.behavior.construction.ConstructionDetails;
import org.maiwithu.maicraft.behavior.construction.ConstructionInput;
import org.maiwithu.maicraft.behavior.construction.ConstructionServices;
import org.maiwithu.maicraft.behavior.construction.ConstructionTask;
import org.maiwithu.maicraft.behavior.construction.PlannedCell;
import org.maiwithu.maicraft.behavior.lighting.HandlesOwnLighting;
import org.maiwithu.maicraft.behavior.lighting.LampItem;
import org.maiwithu.maicraft.behavior.lighting.LampSiteChooser;
import org.maiwithu.maicraft.behavior.lighting.LightRegion;
import org.maiwithu.maicraft.behavior.lighting.LightSample;
import org.maiwithu.maicraft.behavior.lighting.LightSurveyor;
import org.maiwithu.maicraft.behavior.lighting.LightingWorld;
import org.maiwithu.maicraft.behavior.permission.PermissionCheck;
import org.maiwithu.maicraft.game.world.ReadsLightLevels;
import org.maiwithu.maicraft.kernel.child.ChildTaskRunner;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.Change;
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
 * 区域补光的任务：量光（采样范围内的可行走格或作物与耕地，分几刻扫完）→ 备光源（默认火把；身上不够就
 * 如实 partial 写明还缺多少，采购不发生在 light 里，LLM 让它 obtain 再下达一次）→ 选灯位（资格与贪心）
 * → 转蓝图交施工引擎放置 → 等几刻复测，没达标且这轮有真实进展就再来一轮；同一批候选原样重现、
 * 或选中集为空，就按穷尽如实收场。
 *
 * <p>本任务声明"自己管照明"：太暗这项生存需求看到就让位，不往验收范围里插野火把。
 * 放灯、许可、临时方块、受保护格提问、每格结局全是施工引擎的事，这里只管量光、选灯位与亮没亮；
 * 照明不声明要清空的格子，蓝图没写的格子不要求为空。施工交代没做完的部分（没收回的临时方块）照样并进结果。
 */
final class LightTask extends PhasedTask<LightTask.Phase> implements HandlesOwnLighting {

    /** 定范围 → 量光 → 选灯位 → 放置 → 等光稳定 → 复测。 */
    enum Phase { REGION, MEASURE, CHOOSE, PLACE, WAIT, RECHECK }

    /** 放灯后等几刻再量：放置光源后光要过几刻才同步稳定。 */
    static final long LIGHT_SETTLE_TICKS = 10;

    /** most 档要求的达标比率：九成。 */
    static final double MOST_RATIO = 0.9;

    private final LightInput input;
    private final ConstructionServices services;
    private final ReadsLightLevels lights;
    private final LightingWorld world;

    /** 分刻部件：范围泛洪、要量的列、采样扫描、复测。 */
    private LightRegion.Flood flood;
    private List<BlockPos> columns = List.of();
    private LightSurveyor.SampleScan scan;
    private LightSurveyor.Recheck recheck;

    /** 量到的现场：冻结的样本与最近一次量到的光（复测会覆盖初始值）。 */
    private Observation obs;
    /** 没加载、要先走过去让它加载的列。 */
    private final Deque<BlockPos> pendingWalks = new ArrayDeque<>();
    /** 下一刻要推进的动作：走到没加载的那一列。 */
    private Action prepared;
    /** 正在走去的那一列：走到（或没走到）后连它一起重量。 */
    private BlockPos walkingTo;
    /** 走过或试过走的没加载的列：走到后仍没加载、或走不到的，不再反复去。 */
    private final Set<BlockPos> walkTried = new HashSet<>();

    /** 这次用的光源：第一轮定下后锁定沿用，不每轮重新挑。 */
    private LampItem lamp;
    /** 身上的灯不够照完这片时还缺多少支：结论里写明缺口，采购不发生在 light 里。 */
    private int lampShortfall;
    /** 施工子任务交代没做完的部分（例如没收回的临时方块）：并进本任务的结果，不悄悄消失。 */
    private List<String> constructionRemainder = List.of();
    /** 放灯轮次的账。 */
    private final Rounds rounds = new Rounds();
    /** 正在推进的施工子任务、它的结果与复测前的等待。 */
    private Placing placing;

    LightTask(LightInput input, ConstructionServices services, ReadsLightLevels lights, LightingWorld world) {
        super("补光", Phase.REGION, new ProgressTracker(2400, 216000));
        this.input = input;
        this.services = services;
        this.lights = lights;
        this.world = world;
    }

    /** 放灯轮次：第几轮、这轮放了几支、总共几支、上一轮选中的灯位（穷尽判断用）。 */
    private static final class Rounds {
        int count;
        int placedTotal;
        int placedThisRound;
        Set<BlockPos> previousChosen = Set.of();
    }

    /** 施工子任务与复测前的等待。 */
    private static final class Placing {
        final ChildTaskRunner runner = new ChildTaskRunner(ChildTaskRunner.NO_LIMIT);
        TaskResult result;
        long settleAt;
    }

    /** 量到的现场：样本冻结，方块光每次量完更新。 */
    private static final class Observation {
        private LightSurveyor.LightSurvey survey;
        final Map<BlockPos, Integer> lights;

        Observation(LightSurveyor.LightSurvey survey) {
            this.survey = survey;
            this.lights = new LinkedHashMap<>(survey.lights());
        }

        LightSurveyor.LightSurvey survey() {
            return survey;
        }

        /** 重扫过的列并回来：样本与光以新值替换；这回重扫过的列从没加载名单里摘掉，仍没加载的照新名单留着。 */
        void merge(LightSurveyor.LightSurvey fresh, List<BlockPos> rescanned) {
            Map<BlockPos, LightSample> byPosition = new LinkedHashMap<>();
            for (LightSample sample : survey.samples()) byPosition.put(sample.pos(), sample);
            for (LightSample sample : fresh.samples()) byPosition.put(sample.pos(), sample);
            Set<BlockPos> sensitive = new LinkedHashSet<>(survey.sensitive());
            sensitive.addAll(fresh.sensitive());
            for (Map.Entry<BlockPos, Integer> entry : fresh.lights().entrySet()) {
                lights.put(entry.getKey(), entry.getValue());
            }
            // 没加载名单记的是列：按这回重扫过的列摘掉（不按样本格摘，样本的高度和列的基准高度对不上），
            // 重扫时仍没加载的照新名单留着。
            Set<BlockPos> unloaded = new LinkedHashSet<>(survey.unloaded());
            unloaded.removeAll(rescanned);
            unloaded.addAll(fresh.unloaded());
            survey = new LightSurveyor.LightSurvey(new ArrayList<>(byPosition.values()),
                    Map.copyOf(lights), sensitive, unloaded);
        }
    }

    @Override protected Action enter(Phase phase) {
        Action next = prepared;
        prepared = null;
        return next;
    }

    @Override protected Next<Phase> tick(Phase phase, TickContext context) {
        return switch (phase) {
            case REGION -> region(context);
            case MEASURE -> measure(context);
            case CHOOSE -> choose(context);
            case PLACE -> place(context);
            case WAIT -> waitOut(context);
            case RECHECK -> recheck(context);
        };
    }

    // 定范围：radius 给了按圆心取圆内各列；不给从起点泛洪连通可行走空间，到没加载的边界先走过去看看。
    private Next<Phase> region(TickContext context) {
        if (action() != null) {
            // 走过去了（或没走到）：从头再泛洪一遍，刚加载的那片连进来。
            return walkThen(context, () -> Next.go(Phase.REGION, "走过去了，接着量范围"));
        }
        if (input.radius() != null) {
            columns = LightRegion.circleColumns(center(), input.radius());
            return Next.go(Phase.MEASURE, "按半径 " + input.radius() + " 格取圆内各列");
        }
        if (flood == null) {
            flood = new LightRegion.Flood(world, center());
        }
        if (!flood.tick()) return Next.stay();
        if (queueUnloadedWalks(flood.unloadedFrontier()) && startWalk()) {
            flood = null;
            return Next.go(Phase.REGION, "有边界没加载，走过去看看");
        }
        LightRegion.Flood done = flood;
        flood = null;
        columns = done.columns();
        scan = newScan(columns);
        return Next.go(Phase.MEASURE,
                done.capped() ? "连通空间大到量到上限为止" : "连通可行走空间量完了");
    }

    // 量光：分几刻扫样本；扫完发现整列没加载，就先走过去让它加载，走不到的照实留在没加载名单里。
    private Next<Phase> measure(TickContext context) {
        if (action() != null) {
            return walkThen(context, () -> {
                // 走去的那一列连同还没去的几列一起重量：走近了它们多半已经加载。
                List<BlockPos> again = new ArrayList<>();
                again.add(walkingTo);
                again.addAll(pendingWalks);
                columns = List.copyOf(again);
                scan = newScan(columns);
                return Next.go(Phase.MEASURE, "走过去了，重量这些列");
            });
        }
        if (scan == null) {
            scan = newScan(columns);
        }
        if (!scan.tick()) {
            recordProgress("量光中");
            return Next.stay();
        }
        if (obs == null) {
            obs = new Observation(scan.survey());
        } else {
            obs.merge(scan.survey(), columns);
        }
        scan = null;
        if (queueUnloadedWalks(obs.survey().unloaded()) && startWalk()) {
            return Next.go(Phase.MEASURE, "有样本没加载，走过去让它加载");
        }
        if (obs.survey().samples().isEmpty()) {
            return Next.fail(Problem.of(Problem.Kind.NOT_FOUND,
                    "查过的范围里没有能站住验收亮度的样本（走了一整遍，整片都站不进或没加载走不到）",
                    "换一个 target 或给一个小一点的 radius 再试"));
        }
        List<BlockPos> dark = darkSamples();
        if (dark.isEmpty() && input.coverage() != LightInput.Coverage.MOST && !obs.survey().unloaded().isEmpty()) {
            // 量得到的都够亮，可有几列没加载也走不到：没量到的不能算达标，如实写明分母。
            return Next.done(TaskResult.builder(TaskResult.Status.PARTIAL,
                            "量得到的 " + checkedCount() + " 格开始时都够亮，一支灯没插；另有 "
                                    + unloadedCount() + " 格没加载走不到，没量到")
                    .details(details(null, litCount())).build());
        }
        if (dark.isEmpty()) {
            return Next.done(TaskResult.done("开始时已满足，一支灯都不用插"));
        }
        lamp = resolveLamp();
        if (lamp == null) {
            return Next.fail(Problem.of(Problem.Kind.NEED_ITEM, "弄不到发射量够 " + input.minimumLight()
                            + " 的光源（默认火把是 14）",
                    "先 obtain 火把，或指定发射量够的 item 再下达一次"));
        }
        return Next.go(Phase.CHOOSE, "量完了：样本 " + obs.survey().samples().size() + " 格，暗 " + dark.size() + " 格");
    }

    // 选灯位：围绕暗格按资格与贪心挑一批；身上的灯有几支就提交几处，一支都交不出去或跟上一轮
    // 原样重现，就按穷尽收场。
    private Next<Phase> choose(TickContext context) {
        LampSiteChooser.Selection selection = LampSiteChooser.choose(darkSamples(), world, obs.survey().sensitive(),
                protectedAt(), nearFeet(context), lamp, input.minimumLight());
        List<LampSiteChooser.LampSite> sites = sitesOnHand(selection);
        Set<BlockPos> chosen = new LinkedHashSet<>();
        sites.forEach(site -> chosen.add(site.pos()));
        if (chosen.isEmpty() || chosen.equals(rounds.previousChosen)) {
            return concludeUnmet(selection, rounds.placedTotal == 0);
        }
        rounds.previousChosen = chosen;
        rounds.placedThisRound = 0;
        rounds.count++;
        placing = new Placing();
        // 灯位清单转蓝图（绝对坐标，BLOCK 格）：许可、临时方块、受保护格提问、每格结局全按施工的规矩；
        // 灯就是身上这些，施工不再去备料。
        placing.runner.begin(new ConstructionTask(new ConstructionInput(
                        new Blueprint(dimension(), chosen.iterator().next(), cells(sites)),
                        input.permissions(), "照明"),
                services), context);
        recordProgress("第 " + rounds.count + " 轮选中 " + sites.size() + " 处灯位");
        return Next.go(Phase.PLACE, "交给施工引擎插灯");
    }

    /** 这一次插得出去的灯位：身上的灯有几支就提交几处，不够的差额记下来在结论里写明。 */
    private List<LampSiteChooser.LampSite> sitesOnHand(LampSiteChooser.Selection selection) {
        List<LampSiteChooser.LampSite> chosen = selection.chosen();
        int carried = services.site().carried(lamp.itemId());
        if (chosen.size() <= carried) {
            lampShortfall = 0;
            return chosen;
        }
        lampShortfall = chosen.size() - carried;
        // 贪心的次序就是覆盖从大到小：只交出手上够用的前几处，不为了插灯去弄料。
        recordAttempt("备光源", "身上只有 " + carried + " 支 " + lamp.itemId()
                + "，先插这些，还缺 " + lampShortfall + " 支");
        return chosen.subList(0, carried);
    }

    // 放置：整段交给施工引擎这个子任务；它失败按它的问题结束，已放的不回滚。
    private Next<Phase> place(TickContext context) {
        if (placing.runner.tick(context) instanceof TickResult.Finished finished) {
            placing.result = finished.result();
            copyFacts(placing.result);
            copyRemainder(placing.result);
            if (placing.result.status() == TaskResult.Status.FAILED) {
                return Next.fail(placing.result.problem());
            }
            rounds.placedThisRound = placedCount();
            rounds.placedTotal += rounds.placedThisRound;
            if (rounds.placedThisRound == 0 && placing.result.problem() != null) {
                // 许可这类一步都没走成的：把引擎的问题原样交出去，不静默变成"灯位不足"。
                return Next.fail(placing.result.problem());
            }
            recordProgress("放下了 " + rounds.placedThisRound + " 支灯");
            return Next.go(Phase.WAIT, "等几刻让光稳定");
        }
        // 引擎在干活：它有真实进展就算这个任务还有进展，父任务的卡住判定不被施工时长骗到。
        placing.runner.currentProgress()
                .filter(progress -> progress.ticksSinceProgress() == 0)
                .ifPresent(progress -> recordProgress(progress.lastProgress()));
        return Next.stay();
    }

    // 等几刻：放完灯光要过几刻才同步稳定，复测前的等待归在亮度读取的使用纪律里。
    private Next<Phase> waitOut(TickContext context) {
        if (placing.settleAt == 0) {
            placing.settleAt = context.gameTick() + LIGHT_SETTLE_TICKS;
            return Next.stay();
        }
        if (context.gameTick() < placing.settleAt) return Next.stay();
        recheck = new LightSurveyor.Recheck(lights, obs.survey().samples());
        return Next.go(Phase.RECHECK, "开始复测");
    }

    // 复测：在冻结的样本上逐格实测；达标按验收范围档算，比率按能验收的样本算并写明分母。
    private Next<Phase> recheck(TickContext context) {
        if (!recheck.tick()) {
            recordProgress("复测中");
            return Next.stay();
        }
        int lit = 0;
        int checked = 0;
        for (LightSurveyor.Measured cell : recheck.result()) {
            if (cell.light() == null) continue;
            checked++;
            obs.lights.put(cell.pos(), cell.light());
            if (cell.light() >= input.minimumLight()) lit++;
        }
        recheck = null;
        recordProgress("复测：" + lit + "/" + checked + " 格达标");
        if (satisfied(lit, checked)) {
            return Next.done(TaskResult.builder(TaskResult.Status.DONE,
                    rounds.placedTotal == 0 ? "开始时已满足" : "放了 " + rounds.placedTotal + " 支灯，"
                            + lit + "/" + checked + " 格达标")
                    .remaining(constructionRemainder)
                    .build());
        }
        // 还有暗格且这轮有真实进展 → 再来一轮；候选原样重现或没进展时，选灯位那一步会按穷尽收场。
        if (rounds.placedThisRound > 0) {
            return Next.go(Phase.CHOOSE, "还有暗格，再来一轮");
        }
        return concludeUnmet(LampSiteChooser.choose(darkSamples(), world, obs.survey().sensitive(),
                protectedAt(), nearFeet(context), lamp, input.minimumLight()), rounds.placedTotal == 0);
    }

    /** 施工子任务交代没做完的部分（例如没收回的临时方块）接到本任务的结果里：垫出去的要记账，收不回的说清留下几块。 */
    private void copyRemainder(TaskResult result) {
        if (result.remaining().isEmpty()) return;
        constructionRemainder = List.copyOf(result.remaining());
    }

    /** 没达标收场："一支都没放出去"与"放了 N 支仍有 M 格暗"是两种话术，不能混成一句。 */
    private Next<Phase> concludeUnmet(LampSiteChooser.Selection selection, boolean nothingPlaced) {
        // 身上的灯不够照完这片：采购不发生在 light 里，如实写明还缺多少，LLM 让它 obtain 再来。
        if (lampShortfall > 0) return concludeShortOfLamps(selection);
        int lit = litCount();
        int checked = checkedCount();
        int unlitLoaded = checked - lit;
        TaskResult.Builder builder;
        if (!nothingPlaced && unlitLoaded == 0) {
            // 能验收的全亮了，只剩走不到的：如实列为未验收，比率写明分母，不给"全部达标"的结论。
            builder = TaskResult.builder(TaskResult.Status.PARTIAL,
                    "放了 " + rounds.placedTotal + " 支灯，能验收的 " + lit + "/" + checked + " 格达标，另有 "
                            + unloadedCount() + " 格没加载走不到")
                    .details(details(selection, lit));
            if (!selection.onlyProtected().isEmpty()) {
                builder.problem(Problem.of(Problem.Kind.NEED_APPROVAL,
                        "有 " + selection.onlyProtected().size() + " 格暗处周围的灯位都在受保护的方块上",
                        "确认可以动它们再下达一次，或改用小范围分片补光"));
            }
            return Next.done(builder.build());
        }
        String summary = nothingPlaced
                ? "一支都没放出去：查过的范围里" + whyNowhere(selection)
                : "放了 " + rounds.placedTotal + " 支灯，仍有 " + Math.max(unlitLoaded, 0) + " 格能验收的样本暗着";
        builder = TaskResult.builder(
                nothingPlaced ? TaskResult.Status.FAILED : TaskResult.Status.PARTIAL, summary)
                .details(details(selection, lit));
        if (!selection.onlyProtected().isEmpty()) {
            builder.problem(Problem.of(Problem.Kind.NEED_APPROVAL,
                    "有 " + selection.onlyProtected().size() + " 格暗处周围的灯位都在受保护的方块上",
                    "确认可以动它们再下达一次，或改用小范围分片补光"));
        } else if (nothingPlaced) {
            builder.problem(Problem.of(Problem.Kind.NOT_FOUND, whyNowhere(selection), null));
        } else {
            builder.problem(Problem.of(Problem.Kind.STUCK, "同一批候选再来一遍还是暗，先如实停在这里", null));
        }
        return Next.done(builder.build());
    }

    private String whyNowhere(LampSiteChooser.Selection selection) {
        if (!selection.onlyProtected().isEmpty()) return "够得着的灯位都在受保护的方块上";
        if (!obs.survey().unloaded().isEmpty()) return "有 " + obs.survey().unloaded().size() + " 列没加载也走不到";
        return "找不到能放灯具的格（要空气或草丛加结实支撑，还不挡窄道）";
    }

    // 灯不够的收场：手上的灯都用上了还有暗格，如实写明还缺多少支；一支都没插出去按失败交出去。
    private Next<Phase> concludeShortOfLamps(LampSiteChooser.Selection selection) {
        int lit = litCount();
        int checked = checkedCount();
        boolean placedSome = rounds.placedTotal > 0;
        String summary = placedSome
                ? "放了 " + rounds.placedTotal + " 支灯，身上的 " + lamp.itemId() + " 用完了，还有 "
                        + Math.max(checked - lit, 0) + " 格验收样本暗着，还缺 " + lampShortfall + " 支"
                : "身上没有 " + lamp.itemId() + "，一支灯都插不出去；这片要 " + selection.chosen().size()
                        + " 支才能照完";
        return Next.done(TaskResult.builder(
                        placedSome ? TaskResult.Status.PARTIAL : TaskResult.Status.FAILED, summary)
                .details(details(selection, lit))
                .remaining(constructionRemainder)
                .problem(Problem.of(Problem.Kind.NEED_ITEM,
                        "还缺 " + lampShortfall + " 支 " + lamp.itemId() + "（发射量够 " + input.minimumLight() + "）",
                        "照明不自己去弄灯：先 obtain 再下达一次，会从还暗的格接着补"))
                .build());
    }

    /** 蓝图：选中的灯位转成计划格（BLOCK 格，没有 AIR 格）。 */
    private List<PlannedCell> cells(List<LampSiteChooser.LampSite> sites) {
        return sites.stream()
                .map(site -> PlannedCell.block(site.pos(), site.state(), Set.of()))
                .toList();
    }

    /** 没加载的列排队走过去；走过、试过的不再去，走不到的照实留在没加载名单里，比率按能验收的样本算。 */
    private boolean queueUnloadedWalks(Set<BlockPos> unloaded) {
        boolean queued = false;
        for (BlockPos column : unloaded) {
            if (!pendingWalks.contains(column) && !walkTried.contains(column)) {
                pendingWalks.addLast(column);
                queued = true;
            }
        }
        return queued;
    }

    /** 起身走向下一列没加载的列；这一列给不出走法（走不到）就记下试过，换下一列。 */
    private boolean startWalk() {
        prepared = null;
        if (services.travel() == null) return false;
        while (!pendingWalks.isEmpty()) {
            BlockPos column = pendingWalks.pollFirst();
            walkTried.add(column);
            Optional<Action> walk = services.travel().toward(
                    new WorldPosition(column.getX(), column.getY(), column.getZ(), dimension()), input.permissions());
            if (walk.isPresent()) {
                walkingTo = column;
                prepared = walk.get();
                return true;
            }
        }
        return false;
    }

    // 走向没加载的那一列：走到了接着量；没走到如实记一笔，那一列留在没加载名单里，照样接着量能量的，不整单失败。
    private Next<Phase> walkThen(TickContext context, Supplier<Next<Phase>> after) {
        ActionStatus status = runAction(context);
        if (status instanceof ActionStatus.Running) return Next.stay();
        if (status instanceof ActionStatus.Failed failed) {
            recordAttempt("走到没加载的一列（" + walkingTo.getX() + ", " + walkingTo.getZ() + "）",
                    "没走到：" + failed.problem().message());
        }
        return after.get();
    }

    /** 这一次能用的光源：item 指定了就用它，没指定默认火把；发射量达不到验收亮度的不选。 */
    private LampItem resolveLamp() {
        return LampItem.resolve(input.item() == null ? "minecraft:torch" : input.item(), input.minimumLight())
                .orElse(null);
    }

    private LightSurveyor.SampleScan newScan(List<BlockPos> columns) {
        return new LightSurveyor.SampleScan(world, lights, columns, input.coverage() == LightInput.Coverage.CROPS);
    }

    /** 达标判断：all 与 crops 要求全部达标（有没加载的列就不下"全部达标"的结论），most 九成。 */
    private boolean satisfied(int lit, int checked) {
        if (input.coverage() == LightInput.Coverage.MOST) {
            return checked > 0 && lit >= Math.ceil(checked * MOST_RATIO);
        }
        return obs.survey().unloaded().isEmpty() && checked > 0 && lit == checked;
    }

    private List<BlockPos> darkSamples() {
        List<BlockPos> dark = new ArrayList<>();
        for (LightSample sample : obs.survey().samples()) {
            Integer light = obs.lights.get(sample.pos());
            if (light != null && light < input.minimumLight()) dark.add(sample.pos());
        }
        return dark;
    }

    private List<BlockPos> unloadedSamples() {
        List<BlockPos> unloaded = new ArrayList<>();
        for (LightSample sample : obs.survey().samples()) {
            if (obs.lights.get(sample.pos()) == null) unloaded.add(sample.pos());
        }
        // 采样时整列没加载的那些列没有样本，但它们是"没量到"的部分，一并如实列出。
        unloaded.addAll(obs.survey().unloaded());
        return unloaded;
    }

    private int unloadedCount() {
        return unloadedSamples().size();
    }

    private int litCount() {
        int lit = 0;
        for (LightSample sample : obs.survey().samples()) {
            Integer light = obs.lights.get(sample.pos());
            if (light != null && light >= input.minimumLight()) lit++;
        }
        return lit;
    }

    /** 能验收的分母：样本都在冻结清单上，量到的都有光（没加载的列本来就没有样本）。 */
    private int checkedCount() {
        return obs.survey().samples().size();
    }

    /** 灯位资格里的保护闸：能不能动由许可检查点回答，照明不自己另列保护名单。 */
    private Predicate<BlockPos> protectedAt() {
        return pos -> services.guards()
                .allows(PermissionCheck.WorldAction.PLACE_BLOCK, pos, null)
                .isPresent();
    }

    private BlockPos nearFeet(TickContext context) {
        var player = context.player();
        if (player == null || player.localPlayer() == null) return center();
        return player.localPlayer().blockPosition();
    }

    private BlockPos center() {
        WorldPosition at = input.center();
        return new BlockPos(at.x(), at.y(), at.z());
    }

    private String dimension() {
        return input.center().dimension();
    }

    /** 施工子任务已经确认的事实接到本任务的结果里：放下的灯、没能确认的交互、试过的办法。 */
    private void copyFacts(TaskResult result) {
        for (Change change : result.changes()) recordChange(change);
        for (Change change : result.unconfirmed()) recordUnconfirmed(change);
        for (var attempt : result.attempts()) recordAttempt(attempt.tried(), attempt.result());
    }

    /** 这轮施工放了多少支灯：按每格结局里的 placed 数；读不到就按放下方块的变化数。 */
    private int placedCount() {
        if (placing.result != null && placing.result.details() instanceof ConstructionDetails details) {
            Integer placed = details.cells().get("placed");
            if (placed != null) return placed;
        }
        int counted = 0;
        if (placing.result != null) {
            for (Change change : placing.result.changes()) {
                if (change.kind() == Change.Kind.BLOCK_PLACED) counted += change.count();
            }
        }
        return counted;
    }

    @Override protected ResultDetails details() {
        if (obs == null) return ResultDetails.NONE;
        return details(null, litCount());
    }

    /** 没做完的部分：施工交代没收回的临时方块这类尾巴，随结果带给调用方。 */
    @Override protected List<String> remaining() {
        return constructionRemainder;
    }

    /** 结果细节：光源、样本与达标数、实测覆盖率（分母是能验收的样本）、轮数灯数、没达标的按原因分组。 */
    private LightDetails details(LampSiteChooser.Selection selection, int lit) {
        int checked = checkedCount();
        List<LightDetails.Group> groups = new ArrayList<>();
        if (selection != null) {
            appendGroup(groups, LightDetails.NO_SITE, unlitNoSite(selection));
            appendGroup(groups, LightDetails.PROTECTED, List.copyOf(selection.onlyProtected()));
        }
        appendGroup(groups, LightDetails.UNLOADED, unloadedSamples());
        double ratio = checked == 0 ? 0.0 : (double) lit / checked;
        String source = lamp == null
                ? (input.item() == null ? "minecraft:torch" : input.item()) : lamp.itemId();
        return new LightDetails(source, obs.survey().samples().size(), lit, ratio,
                rounds.count, rounds.placedTotal, groups);
    }

    /** 没达标又不在受保护名单里的暗格：找不到灯位的那部分。 */
    private List<BlockPos> unlitNoSite(LampSiteChooser.Selection selection) {
        List<BlockPos> rest = new ArrayList<>();
        for (BlockPos cell : selection.uncovered()) {
            if (!selection.onlyProtected().contains(cell)) rest.add(cell);
        }
        return rest;
    }

    private static void appendGroup(List<LightDetails.Group> groups, String reason, List<BlockPos> cells) {
        if (cells.isEmpty()) return;
        groups.add(LightDetails.Group.of(reason, cells.stream().map(BlockPos::toShortString).toList()));
    }

    @Override protected String describePhase(Phase value) {
        return switch (value) {
            case REGION -> "定要照的范围";
            case MEASURE -> "量哪里暗";
            case CHOOSE -> "选灯位";
            case PLACE -> "插灯";
            case WAIT -> "等光稳定";
            case RECHECK -> "复测";
        };
    }
}
