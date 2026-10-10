// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

import org.maiwithu.maicraft.behavior.inventory.spi.CarriedBackpack;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.Question;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.ReportsUnconfirmed;
import org.maiwithu.maicraft.kernel.task.TickContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 背包空间：捡东西、合成取出、交易、开箱取物、卸下装备之前，统一来这里腾格子，任务自己不再各自处理"背包满了"。
 *
 * <p>腾挪照真人玩家的做法一步步来：合并散堆 → 放进随身背包 → 存进附近记得的容器 → 丢最不值钱的；
 * 要动到贵重一级才停下来问。每次存放、丢弃都记进结果的变化里，事实不丢账；
 * 点出去了却没能确认结果的交互，也一句一条带回去，进任务结果的 unconfirmed，不冒充做成了。
 *
 * <p>腾挪动作靠真实点击与丢弃，一步要一刻：每次调用执行一步，没腾够就返回
 * {@code PROGRESS}，调用方下一刻带着新背包视图再调一次，直到 {@code FREE}、要问或确实腾不出。
 * 随身背包、已知容器与存取、丢弃的接缝没接上时，对应步骤自动跳过。
 */
public final class InventorySpace {

    /** 记得多少格以内的容器算"附近"：附近有容器时优先存放，不往地上扔。 */
    public static final int NEARBY_CONTAINER_RANGE = 16;

    private final BackpackView backpack;
    private final Optional<StackMerger> stackMerger;
    private final Optional<CarriedBackpack> carriedBackpack;
    private final Optional<KnownContainers> knownContainers;
    private final Optional<ContainerDeposits> containerDeposits;
    private final Optional<ItemDropper> itemDropper;
    /** 各执行接缝已经交回过几句没能确认的交互：收尾时再交一次也不重复。 */
    private final int[] forwardedFacts = new int[Seam.values().length];

    /** 四条腾挪路各对应一个执行接缝；没能确认的事实按它去接缝里取。 */
    private enum Seam { MERGER, CARRIED, DEPOSITS, DROPPER }

    /**
     * @param backpack           背包视图：腾挪的每一步都以它读到的现场为准
     * @param stackMerger        合并散堆的执行接缝；没接上传 {@code Optional.empty()}
     * @param carriedBackpack    随身背包接缝；没接上传 {@code Optional.empty()}
     * @param knownContainers    附近已知容器接缝；没接上传 {@code Optional.empty()}
     * @param containerDeposits  往容器存东西的执行接缝；没接上传 {@code Optional.empty()}
     * @param itemDropper        丢弃的执行接缝；没接上传 {@code Optional.empty()}
     */
    public InventorySpace(
            BackpackView backpack,
            Optional<StackMerger> stackMerger,
            Optional<CarriedBackpack> carriedBackpack,
            Optional<KnownContainers> knownContainers,
            Optional<ContainerDeposits> containerDeposits,
            Optional<ItemDropper> itemDropper) {
        this.backpack = Objects.requireNonNull(backpack, "backpack");
        this.stackMerger = Objects.requireNonNull(stackMerger, "stackMerger");
        this.carriedBackpack = Objects.requireNonNull(carriedBackpack, "carriedBackpack");
        this.knownContainers = Objects.requireNonNull(knownContainers, "knownContainers");
        this.containerDeposits = Objects.requireNonNull(containerDeposits, "containerDeposits");
        this.itemDropper = Objects.requireNonNull(itemDropper, "itemDropper");
    }

    /** 一次腾挪后的处境。 */
    public enum State {
        /** 已经腾够了，可以捡东西、取物了。 */
        FREE,
        /** 动了一步还没腾够：changes 里是这一步确认的变化，下一刻再调。 */
        PROGRESS,
        /** 要动贵重品才腾得出来：停下来问，LLM 点头了才动。 */
        NEED_ASK,
        /** 腾不出来：连贵重品都算上也不够，这次少拿。 */
        IMPOSSIBLE
    }

    /**
     * 腾挪的中间结果：没腾够时 problem 或 question 恰有一个；changes 是本次确认的变化，
     * unconfirmed 是本次"点出去了却没能确认结果"的交互，任务结束时一并并进任务结果。
     */
    public record Result(State state, List<Change> changes, List<String> unconfirmed,
            Problem problem, Question question) {

        private static final Result FREE_EMPTY = new Result(State.FREE, List.of(), List.of(), null, null);

        public Result {
            changes = List.copyOf(changes);
            unconfirmed = List.copyOf(unconfirmed);
            if (state == State.NEED_ASK && question == null) {
                throw new IllegalArgumentException("要问的处境必须带上问题");
            }
            if (state == State.IMPOSSIBLE && problem == null) {
                throw new IllegalArgumentException("腾不出来的处境必须写明问题");
            }
        }
    }

    /**
     * 腾出若干格空位。用途只写进丢弃的变化备注，让 LLM 看到"为什么丢了它"。
     *
     * @param slotsNeeded 要腾出的空格数
     * @param purpose     拿这些空位做什么，例如"捡起掉落的铁锭"
     * @param permissions 这次任务的许可：存进容器要走路，走过去能动多少地形按它来
     * @param context     本刻的上下文：腾挪的一步就在这一刻里推进
     */
    public Result ensureFree(int slotsNeeded, String purpose, Permissions permissions, TickContext context) {
        if (slotsNeeded < 0) {
            throw new IllegalArgumentException("要腾出的格数不能为负：" + slotsNeeded);
        }
        if (backpack.freeSlots() >= slotsNeeded) {
            return Result.FREE_EMPTY;
        }

        SpacePlanner.Plan plan = SpacePlanner.plan(new SpacePlanner.Scene(
                backpack.stacks(),
                backpack.freeSlots(),
                slotsNeeded,
                Set.of(),
                stackMerger.isPresent(),
                carriedBackpack.map(CarriedBackpack::freeSlots).orElse(0),
                knownContainers.map(containers -> containers.within(NEARBY_CONTAINER_RANGE)).orElse(List.of())));

        // 按计划顺序做第一步能做的：做成了记账，还在做就等它（不转头做下一步），做不了才换下一步。
        for (SpaceMove move : plan.moves()) {
            StepEnding outcome = execute(move, purpose, permissions, context);
            if (outcome.step() instanceof SpaceStepResult.Done made) {
                return new Result(State.PROGRESS, List.of(made.change()), outcome.facts(), null, null);
            }
            if (outcome.step() instanceof SpaceStepResult.Working) {
                return new Result(State.PROGRESS, List.of(), outcome.facts(), null, null);
            }
        }
        if (plan.question() != null) {
            return new Result(State.NEED_ASK, List.of(), List.of(), null, plan.question());
        }
        if (plan.moves().isEmpty()) {
            return new Result(State.IMPOSSIBLE, List.of(), List.of(), plan.problem(), null);
        }
        // 计划里每一步都做不了（接缝没接上、容器不在了……）：如实说腾不出，不悄悄空转。
        return new Result(State.IMPOSSIBLE, List.of(), List.of(),
                plan.problem() != null ? plan.problem()
                        : Problem.of(Problem.Kind.INVENTORY_FULL, "腾地方的办法都用不上，背包腾不出 " + slotsNeeded + " 格"),
                null);
    }

    /**
     * 中途撒手：任务收尾或换目标时，正在走的腾挪一步不再等它——
     * 开着的界面请游戏关上、没等到确认的交互照实留在各读端里，下次交回。
     */
    public void abandonStep() {
        stackMerger.filter(AbandonsStep.class::isInstance)
                .ifPresent(seam -> ((AbandonsStep) seam).abandonStep());
        containerDeposits.filter(AbandonsStep.class::isInstance)
                .ifPresent(seam -> ((AbandonsStep) seam).abandonStep());
        itemDropper.filter(AbandonsStep.class::isInstance)
                .ifPresent(seam -> ((AbandonsStep) seam).abandonStep());
    }

    /** 一步执行后的 outcome：这一步的结局，加上接缝新交回的没能确认的交互。 */
    private record StepEnding(SpaceStepResult step, List<String> facts) {}

    // 执行一步腾挪；接缝没接上就是做不了。没能确认的交互从对应接缝一句一条取新交回的。
    private StepEnding execute(SpaceMove move, String purpose, Permissions permissions, TickContext context) {
        if (move instanceof SpaceMove.MergeStacks ignored) {
            // 散堆合并不丢东西，记一笔普通变化让账对得上。
            SpaceStepResult step = stackMerger.isPresent()
                    ? stackMerger.get().mergeOne(context)
                    : SpaceStepResult.cannotDo("合并散堆的接缝没接上");
            return new StepEnding(step, drainFacts(Seam.MERGER, factsOf(stackMerger)));
        }
        if (move instanceof SpaceMove.ToCarriedBackpack(var stack)) {
            return new StepEnding(
                    carriedBackpack.map(pack -> pack.store(stack))
                            .orElseGet(() -> SpaceStepResult.cannotDo("没有随身背包")),
                    List.of());
        }
        if (move instanceof SpaceMove.ToKnownContainer(var container, var stack)) {
            SpaceStepResult step = containerDeposits.isPresent()
                    ? containerDeposits.get().deposit(container, stack, permissions, context)
                    : SpaceStepResult.cannotDo("存箱子的接缝没接上");
            return new StepEnding(step, drainFacts(Seam.DEPOSITS, factsOf(containerDeposits)));
        }
        if (move instanceof SpaceMove.DropStack(var stack)) {
            SpaceStepResult step = itemDropper.isPresent()
                    ? itemDropper.get().drop(stack.itemId(), stack.count(), context)
                    : SpaceStepResult.cannotDo("丢东西的接缝没接上");
            if (step instanceof SpaceStepResult.Done made) {
                step = SpaceStepResult.done(new Change(Change.Kind.ITEM_DROPPED, made.change().what(),
                        made.change().count(), "腾地方（" + purpose + "）：" + noteOf(stack)));
            }
            return new StepEnding(step, drainFacts(Seam.DROPPER, factsOf(itemDropper)));
        }
        return new StepEnding(SpaceStepResult.cannotDo("不认识的腾挪：" + move), List.of());
    }

    // 腾挪读端把没能确认的交互照 behavior.acquire.spi 的同一条路交回；不实现它的接缝没有事实可取。
    private static ReportsUnconfirmed factsOf(Optional<?> seam) {
        return seam.filter(ReportsUnconfirmed.class::isInstance)
                .map(ReportsUnconfirmed.class::cast)
                .orElse(null);
    }

    // 从接缝里取新交回的事实：只取没交过的，收尾时再问一遍也不重复。
    private List<String> drainFacts(Seam seam, ReportsUnconfirmed seamInstance) {
        if (seamInstance == null) return List.of();
        List<String> all = seamInstance.unconfirmedFacts();
        if (forwardedFacts[seam.ordinal()] >= all.size()) return List.of();
        List<String> fresh = new ArrayList<>(all.subList(forwardedFacts[seam.ordinal()], all.size()));
        forwardedFacts[seam.ordinal()] = all.size();
        return fresh;
    }

    private static String noteOf(BackpackStack stack) {
        return stack.count() + " 个 " + stack.itemId();
    }
}
