// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.sleep;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import java.util.List;

import org.maiwithu.maicraft.behavior.acquire.CollectsBlocks;
import org.maiwithu.maicraft.behavior.acquire.OffhandContents;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.permission.ReadsRememberedPlaces;
import org.maiwithu.maicraft.behavior.survival.NightfallNeed;
import org.maiwithu.maicraft.behavior.inventory.ClientMovesToMainhand;
import org.maiwithu.maicraft.behavior.travel.DestinationResolver;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.world.BlockScanService;
import org.maiwithu.maicraft.game.world.WorldTime;
import org.maiwithu.maicraft.kernel.ability.AbilityDoc;
import org.maiwithu.maicraft.kernel.ability.AbilityModule;
import org.maiwithu.maicraft.kernel.ability.AbilitySpec;
import org.maiwithu.maicraft.kernel.ability.ExecutionMode;
import org.maiwithu.maicraft.kernel.ability.Listing;
import org.maiwithu.maicraft.kernel.goal.Goal;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.TargetKind;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.param.ParamSpecs;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.task.TaskFactories;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 睡觉能力：找一张能用的床躺下，躺下即完成，不保证睡到天亮。白天不干等：先把今晚的床备好
 * （附近没有、身上没有，就走拿到物品引擎去弄一张），然后以"现在睡不了"结束；
 * 夜里附近没床时放下自带的床或现做一张。下界与末地的床会爆炸，直接结束不尝试。
 *
 * <p>能力是薄的：选床、靠近、放床、拿床都组合玩家行为层已有的模型；这里的知识只有
 * 睡觉特有的判断——备不备床、现在睡不睡得、床从哪来。夜间自动休息（生存需求）复用
 * 同一套选床与躺下流程，从这里拿判断与任务。
 */
public final class SleepModule implements AbilityModule, NightfallNeed.NightRestMoves {

    private final Supplier<PlayerContext> context;
    private final LiveBedFinder beds;
    private final PlacesBed placer;
    private final ItemNeeds obtain;
    private final BringsPlayerClose approaches;
    private final CollectsBlocks collects;
    private final ReadsRememberedPlaces rememberedPlaces;
    private final DestinationResolver destinations;
    private final Supplier<Optional<String>> refusalTexts;
    private final Interactions interactions;

    public SleepModule(Supplier<PlayerContext> context, BlockScanService scans, Protection protection,
            BackpackView backpack, OffhandContents offhand,
            Interactions interactions, BringsPlayerClose approaches, CollectsBlocks collects,
            ReadsRememberedPlaces rememberedPlaces, DestinationResolver destinations,
            ItemNeeds obtain, Supplier<Optional<String>> refusalTexts) {
        this.context = Objects.requireNonNull(context, "context");
        this.beds = new LiveBedFinder(context, scans, protection, backpack, offhand);
        this.interactions = Objects.requireNonNull(interactions, "interactions");
        this.placer = new ClientBedPlacer(interactions,
                new ClientMovesToMainhand(context), context);
        this.obtain = Objects.requireNonNull(obtain, "obtain");
        this.approaches = Objects.requireNonNull(approaches, "approaches");
        this.collects = Objects.requireNonNull(collects, "collects");
        this.rememberedPlaces = Objects.requireNonNull(rememberedPlaces, "rememberedPlaces");
        this.destinations = Objects.requireNonNull(destinations, "destinations");
        this.refusalTexts = Objects.requireNonNull(refusalTexts, "refusalTexts");
    }

    @Override
    public AbilitySpec spec() {
        return new AbilitySpec("maicraft:sleep", "找一张床躺下睡觉，白天会先备好今晚的床再报告睡不了",
                AbilityDoc.forAbility("sleep"),
                ParamSpecs.of(),
                Set.of(TargetKind.HERE, TargetKind.LANDMARK, TargetKind.POSITION, TargetKind.SEEN),
                ExecutionMode.CONTROLS_PLAYER,
                Set.of(), List.of(), Listing.LISTED);
    }

    @Override
    public StepDecision decide(StepContext step) {
        PlayerContext current = context.get();
        if (current == null || current.level() == null) {
            return StepDecision.NOT_READY;
        }
        // 在下界、末地睡会爆炸：不尝试，直接结束并说明，不提问。
        if (SleepRules.bedsExplodeHere(current.level())) {
            return finished(TaskResult.builder(TaskResult.Status.FAILED, "睡不了：这个维度的床会爆炸")
                    .problem(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, "这个维度的床会爆炸，睡不了", null))
                    .build());
        }
        // 白天被叫去睡不干等：先把今晚的床备好，然后报告"现在睡不了"，天黑后夜间自动休息自己去睡。
        if (!WorldTime.canAttemptSleep(current.level())) {
            return decideForDaytime(step, current);
        }
        Optional<WorldPosition> area = bedArea(step.goal());
        if (area.isEmpty()) {
            return finished(bedAreaUnclear(step));
        }
        return new StepDecision.Run(new SleepInput(area.orElseThrow()));
    }

    // 白天的两步：先备床（跑一次备床任务），备好了或备不成，都以"现在睡不了"收场并交代床备好没有。
    private StepDecision decideForDaytime(StepContext step, PlayerContext current) {
        long minutes = SleepRules.minutesUntilSleepable(current.level());
        String notYet = "现在睡不了，约 " + minutes + " 分钟后可睡";
        boolean bedReady = beds.bedReady(step.tick());
        if (bedReady) {
            return finished(daytimeResult(notYet, true, null));
        }
        if (step.taskResults().isEmpty()) {
            // 还没备过床：先跑备床，跑完回来重新决定这一步。
            return new StepDecision.Run(PrepareBedInput.INSTANCE, true);
        }
        // 备床已经跑过：成了就报备好，不成带着备床给的原因结束。
        TaskResult prepare = step.taskResults().get(step.taskResults().size() - 1);
        if (prepare.status() == TaskResult.Status.DONE && beds.bedReady(step.tick())) {
            return finished(daytimeResult(notYet, true, null));
        }
        return finished(daytimeResult("现在睡不了，也没弄到今晚要睡的床", false,
                prepare.problem() != null ? prepare.problem()
                        : Problem.of(Problem.Kind.NEED_ITEM, "没有床，做床还缺材料", null)));
    }

    // 白天的收场：以时间不对结束，remaining 写明入睡，细节交代今晚的床备好没有。
    private TaskResult daytimeResult(String summary, boolean bedReady, Problem problem) {
        TaskResult.Builder builder = TaskResult.builder(TaskResult.Status.PARTIAL, summary)
                .remaining("入睡")
                .details(new SleepDetails(false, SleepDetails.BedSource.WORLD, bedReady, false, false));
        if (problem != null) {
            builder.problem(problem);
        }
        return builder.build();
    }

    // 目标对象落成床区：给了就在这片里选床，没给全范围找。
    private Optional<WorldPosition> bedArea(Goal goal) {
        if (goal.target() == null) {
            return Optional.empty();
        }
        var resolution = destinations.resolve(goal.target(), BedChooser.BED_AREA_RADIUS_BLOCKS);
        if (resolution instanceof DestinationResolver.Resolution.Ready ready) {
            return Optional.of(ready.destination().position());
        }
        if (resolution instanceof DestinationResolver.Resolution.AlreadyThere there) {
            return Optional.of(there.spot());
        }
        return Optional.empty();
    }

    // 目标对象说不清（地标没记过、观察编号失效）：如实结束，换个说法重新下达。
    private TaskResult bedAreaUnclear(StepContext step) {
        return TaskResult.failed("睡觉的地方说不清",
                Problem.of(Problem.Kind.NOT_FOUND, "目标对象解析不出在哪：用坐标、地点名或观察编号说明在哪片床区睡",
                        "重新下达并给 target"));
    }

    private StepDecision finished(TaskResult result) {
        return new StepDecision.Finish(result);
    }

    @Override
    public void registerTasks(TaskFactories factories) {
        factories.register(SleepInput.class, input ->
                new SleepTask(input, currentPermissions(), beds, placer, obtain, approaches,
                        interactions::useBlock, refusalTexts, beds::carriedBed));
        factories.register(PrepareBedInput.class, input ->
                new PrepareBedTask(obtain, currentPermissions(), beds::carriedBed));
    }

    // 任务的许可用默认值：睡觉自己不读许可参数，保护与战斗档位由各模型按默认许可把关。
    private Permissions currentPermissions() {
        return Permissions.DEFAULT;
    }

    /** 夜间自动休息怎么落地：读床、放床、拿床、收床与回站位都从这里组合。 */
    @Override
    public Task nightRest(TickContext tick) {
        return new NightRestTask(new SleepInput(null), currentPermissions(), beds, placer, obtain, approaches,
                interactions::useBlock, refusalTexts, beds::carriedBed, collects, rememberedPlaces,
                moment -> moment.player() != null && moment.player().localPlayer() != null
                        && moment.player().localPlayer().isSleeping());
    }

    /** 夜晚这项生存需求读的"今晚有没有床"：附近有能用的床或身上带着床。 */
    public NightfallNeed.ReadsBedAvailability bedAvailability() {
        return beds;
    }
}
