// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.kernel.goal.Question;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 背包空间：捡东西、合成取出、交易、开箱取物之前，统一来这里腾格子，任务自己不再各自处理"背包满了"。
 *
 * <p>腾挪照真人玩家的做法一步步来：合并散堆 → 放进随身背包 → 存进附近记得的容器 → 丢最不值钱的；
 * 要动到贵重一级才停下来问。每次存放、丢弃都记进结果的变化里，事实不丢账。
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
     * 任务结束时并进任务结果，丢掉、存进什么都说得清。
     */
    public record Result(State state, List<Change> changes, Problem problem, Question question) {

        public Result {
            changes = List.copyOf(changes);
            if (state == State.NEED_ASK && question == null) {
                throw new IllegalArgumentException("要问的处境必须带上问题");
            }
            if (state == State.IMPOSSIBLE && problem == null) {
                throw new IllegalArgumentException("腾不出来的处境必须写明问题");
            }
        }

        /** 已经够了，或这一步做成了。 */
        public static Result of(State state, List<Change> changes) {
            return new Result(state, changes, null, null);
        }
    }

    /**
     * 腾出若干格空位。用途只写进丢弃的变化备注，让 LLM 看到"为什么丢了它"。
     *
     * @param slotsNeeded 要腾出的空格数
     * @param purpose     拿这些空位做什么，例如"捡起掉落的铁锭"
     */
    public Result ensureFree(int slotsNeeded, String purpose) {
        if (slotsNeeded < 0) {
            throw new IllegalArgumentException("要腾出的格数不能为负：" + slotsNeeded);
        }
        if (backpack.freeSlots() >= slotsNeeded) {
            return Result.of(State.FREE, List.of());
        }

        SpacePlanner.Plan plan = SpacePlanner.plan(new SpacePlanner.Scene(
                backpack.stacks(),
                backpack.freeSlots(),
                slotsNeeded,
                Set.of(),
                stackMerger.isPresent(),
                carriedBackpack.isPresent(),
                knownContainers.map(containers -> containers.within(NEARBY_CONTAINER_RANGE)).orElse(List.of())));

        List<Change> done = new ArrayList<>();
        // 执行计划里能执行的第一步：接缝没接上或现场变了就跳过，跳过的下一步重算。
        for (SpaceMove move : plan.moves()) {
            Optional<Change> change = execute(move, purpose);
            if (change.isPresent()) {
                done.add(change.get());
                return Result.of(State.PROGRESS, done);
            }
        }
        if (!plan.moves().isEmpty()) {
            // 计划里的动作一步都没确认：可能正点击到一半，下一刻重看现场，任务自己的进度跟踪兜底卡住。
            return Result.of(State.PROGRESS, done);
        }

        if (plan.question() != null) {
            return new Result(State.NEED_ASK, done, null, plan.question());
        }
        return new Result(State.IMPOSSIBLE, done, plan.problem(), null);
    }

    // 执行一步腾挪；接缝没接上、东西已经不在或还没得到确认都返回空，下一刻重看现场。
    private Optional<Change> execute(SpaceMove move, String purpose) {
        if (move instanceof SpaceMove.MergeStacks ignored) {
            // 散堆合并一步只并一堆；合并本身不丢东西，记一笔普通变化让账对得上。
            return stackMerger.flatMap(merger -> merger.mergeOne());
        }
        if (move instanceof SpaceMove.ToCarriedBackpack(var stack)) {
            return carriedBackpack.flatMap(pack -> pack.store(stack));
        }
        if (move instanceof SpaceMove.ToKnownContainer(var container, var stack)) {
            return containerDeposits.flatMap(deposits -> deposits.deposit(container, stack));
        }
        if (move instanceof SpaceMove.DropStack(var stack)) {
            return itemDropper.flatMap(dropper -> dropper.drop(stack.itemId(), stack.count()))
                    .map(change -> new Change(Change.Kind.ITEM_DROPPED, change.what(), change.count(),
                            "腾地方（" + purpose + "）：" + noteOf(stack)));
        }
        return Optional.empty();
    }

    private static String noteOf(BackpackStack stack) {
        return stack.count() + " 个 " + stack.itemId();
    }
}
