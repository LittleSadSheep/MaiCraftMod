// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.maiwithu.maicraft.behavior.inventory.spi.CarriedBackpack;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.game.player.BackpackView;

/**
 * 背包空间：捡东西、合成取出、交易、开箱取物、卸下装备之前，统一来这里腾格子，任务自己不再各自处理"背包满了"。
 *
 * <p>腾挪照真人玩家的做法一步步来：合并散堆 → 放进随身背包 → 存进附近记得的容器 → 丢最不值钱的；
 * 要动到贵重一级才停下来问。每一步都是一个真实的动作（并散堆、存箱子用的是存东西能力同一份存放动作，
 * 丢东西用的是丢弃能力同一份丢东西动作），确认的变化与没能确认的交互直接记进发起任务的结果。
 *
 * <p>腾一次就是一个动作（{@link #makeRoom}）：每做完一步重新看背包、重新排计划，腾够了算做完；
 * 一步做不成就换计划里的下一步，做不成的容器、东西这次不再试。随身背包、已知容器、存放与丢弃的
 * 部件没接上时，对应步骤自动跳过。
 */
public final class InventorySpace {

    /** 记得多少格以内的容器算"附近"：附近有容器时优先存放，不往地上扔。 */
    public static final int NEARBY_CONTAINER_RANGE = 16;

    private final BackpackView backpack;
    private final Optional<StackMerger> stackMerger;
    private final Optional<CarriedBackpack> carriedBackpack;
    private final Optional<KnownContainers> knownContainers;
    private final Optional<StoresInContainer.Parts> storing;
    private final Optional<DropsItems.Parts> dropping;

    /**
     * @param backpack        背包视图：腾挪的每一步都以它读到的现场为准
     * @param stackMerger     合并散堆的接缝；没接上传 {@code Optional.empty()}
     * @param carriedBackpack 随身背包接缝；没接上传 {@code Optional.empty()}
     * @param knownContainers 附近已知容器接缝；没接上传 {@code Optional.empty()}
     * @param storing         存放的现场部件（与存东西能力共用）；没接上传 {@code Optional.empty()}
     * @param dropping        丢东西的现场部件（与丢弃能力共用）；没接上传 {@code Optional.empty()}
     */
    public InventorySpace(
            BackpackView backpack,
            Optional<StackMerger> stackMerger,
            Optional<CarriedBackpack> carriedBackpack,
            Optional<KnownContainers> knownContainers,
            Optional<StoresInContainer.Parts> storing,
            Optional<DropsItems.Parts> dropping) {
        this.backpack = Objects.requireNonNull(backpack, "backpack");
        this.stackMerger = Objects.requireNonNull(stackMerger, "stackMerger");
        this.carriedBackpack = Objects.requireNonNull(carriedBackpack, "carriedBackpack");
        this.knownContainers = Objects.requireNonNull(knownContainers, "knownContainers");
        this.storing = Objects.requireNonNull(storing, "storing");
        this.dropping = Objects.requireNonNull(dropping, "dropping");
    }

    /**
     * 腾出若干格空位的动作：腾够了算做完；要动贵重品才腾得出时以 NEED_APPROVAL 失败（问题里写明动哪一堆），
     * 连贵重品都算上也不够、或办法都用不上时以 INVENTORY_FULL 失败。
     *
     * @param slotsNeeded 要腾出的空格数
     * @param purpose     拿这些空位做什么，例如"捡起掉落的铁锭"；写进存放、丢弃的变化备注，让 LLM 看到为什么动了它
     * @param keep        这次要留着的物品注册 ID（例如正要拿的东西），不往外挪
     * @param permissions 这次任务的许可：存进容器要走路，走过去能动多少地形按它来
     * @param records     发起任务的记账口
     */
    public Action makeRoom(int slotsNeeded, String purpose, Set<String> keep, Permissions permissions,
            TaskRecords records) {
        if (slotsNeeded < 0) {
            throw new IllegalArgumentException("要腾出的格数不能为负：" + slotsNeeded);
        }
        return new MakingRoom(slotsNeeded, purpose, Set.copyOf(keep), Objects.requireNonNull(permissions, "permissions"),
                Objects.requireNonNull(records, "records"));
    }

    /** 一次腾挪：看背包 → 排计划 → 做第一步做得了的 → 再看背包，直到腾够、要问或腾不出。 */
    private final class MakingRoom implements Action {

        private final int slotsNeeded;
        private final String purpose;
        private final Permissions permissions;
        private final TaskRecords records;
        /** 不往外挪的东西：任务要留着的，加上这次挪不动的（丢不出去、存哪都存不进）。 */
        private final Set<String> unmovable;
        /** 这次存不进去的容器：点不开、放不下的，不再往里塞。 */
        private final Set<WorldPosition> failedContainers = new HashSet<>();
        private boolean mergeBroken;
        private boolean carriedBroken;
        private SpaceMove doing;
        private Action step;
        /** 正在做的存箱子那一步，和这一步确认存进了几件：做完没存进或放不下了，这只就不再试。 */
        private StoresInContainer storingStep;
        private int storedThisStep;

        MakingRoom(int slotsNeeded, String purpose, Set<String> keep, Permissions permissions, TaskRecords records) {
            this.slotsNeeded = slotsNeeded;
            this.purpose = purpose;
            this.unmovable = new HashSet<>(keep);
            this.permissions = permissions;
            this.records = records;
        }

        @Override
        public ActionStatus tick(TickContext context) {
            // 正在做的一步先做完（界面开着、投掷在等确认时不能转头做别的），再回来看背包。
            if (step != null) {
                switch (step.tick(context)) {
                    case ActionStatus.Running running -> {
                        return running;
                    }
                    case ActionStatus.Done done -> {
                        settleStore();
                        endStep();
                    }
                    case ActionStatus.Failed failed -> {
                        giveUpOn(doing, failed.problem());
                        endStep();
                    }
                }
                return ActionStatus.progressed();
            }
            if (backpack.freeSlots() >= slotsNeeded) {
                return ActionStatus.done();
            }
            SpacePlanner.Plan plan = SpacePlanner.plan(scene());
            // 按计划顺序做第一步做得了的；部件没接上的这一步就做不了，换下一步。
            for (SpaceMove move : plan.moves()) {
                Optional<Action> started = start(move);
                if (started.isPresent()) {
                    doing = move;
                    step = started.get();
                    return ActionStatus.progressed();
                }
                giveUpOn(move, null);
            }
            if (plan.question() != null) {
                // 要动贵重品才腾得出：停下来问，问题原样写明动哪一堆。
                return ActionStatus.failed(Problem.of(Problem.Kind.NEED_APPROVAL, plan.question().text(),
                        "同意动贵重品，或先整理背包"));
            }
            if (plan.moves().isEmpty()) {
                return ActionStatus.failed(plan.problem());
            }
            // 计划里每一步都做不了（部件没接上、容器不在了……）：如实说腾不出，不悄悄空转。
            return ActionStatus.failed(Problem.of(Problem.Kind.INVENTORY_FULL,
                    "腾地方的办法都用不上，背包腾不出 " + slotsNeeded + " 格"));
        }

        // 一次腾挪要看的现场：这次做不成的合并、随身背包、容器与东西都从计划里拿掉，免得原地重试。
        private SpacePlanner.Scene scene() {
            List<KnownContainer> containers = knownContainers
                    .map(known -> known.within(NEARBY_CONTAINER_RANGE)).orElse(List.of()).stream()
                    .filter(container -> !failedContainers.contains(container.at()))
                    .toList();
            return new SpacePlanner.Scene(
                    backpack.stacks(),
                    backpack.freeSlots(),
                    slotsNeeded,
                    Set.copyOf(unmovable),
                    stackMerger.isPresent() && !mergeBroken,
                    carriedBroken ? 0 : carriedBackpack.map(CarriedBackpack::freeSlots).orElse(0),
                    storing.isPresent() ? containers : List.of());
        }

        // 把计划里的一步变成动作；部件没接上就给空。
        private Optional<Action> start(SpaceMove move) {
            return switch (move) {
                case SpaceMove.MergeStacks merge -> stackMerger.map(merger -> merger.mergeOne(records));
                case SpaceMove.ToCarriedBackpack(var stack) -> carriedBackpack.flatMap(pack -> pack.store(stack, records));
                case SpaceMove.ToKnownContainer(var container, var stack) -> storing.map(parts -> {
                    storingStep = new StoresInContainer(container, List.of(stack.itemId()), storeLedger(stack),
                            permissions, parts);
                    storedThisStep = 0;
                    return storingStep;
                });
                case SpaceMove.DropStack(var stack) -> dropping.map(parts -> new DropsItems(stack.itemId(),
                        stack.count(), () -> carriedInBackpack(stack.itemId()), parts, records,
                        "腾地方（" + purpose + "）"));
            };
        }

        // 存进容器的账：只存计划里这一堆的件数；存进的记变化，没能确认的、试过的照实记。
        private StoresInContainer.Ledger storeLedger(BackpackStack stack) {
            return new StoresInContainer.Ledger() {
                @Override public int left(String itemId) {
                    return itemId.equals(stack.itemId()) ? stack.count() - storedThisStep : 0;
                }

                @Override public void stored(String itemId, int amount) {
                    storedThisStep += amount;
                    records.change(new Change(Change.Kind.ITEM_STORED, itemId, amount,
                            "腾地方（" + purpose + "）：存进了 " + nameOf(doing)));
                }

                @Override public void unconfirmed(String fact) {
                    records.unconfirmed(new Change(Change.Kind.OTHER, "腾背包", 1, fact));
                }

                @Override public void attempt(String tried, String whatHappened) {
                    records.attempt("腾地方：" + tried, whatHappened);
                }
            };
        }

        // 一步做不成：这次不再试同一条路——合并坏了不再合，随身背包不再塞，容器不再存，丢不出的东西不再挪。
        private void giveUpOn(SpaceMove move, Problem problem) {
            switch (move) {
                case SpaceMove.MergeStacks merge -> mergeBroken = true;
                case SpaceMove.ToCarriedBackpack carried -> carriedBroken = true;
                case SpaceMove.ToKnownContainer(var container, var stack) -> failedContainers.add(container.at());
                case SpaceMove.DropStack(var stack) -> unmovable.add(stack.itemId());
            }
            // 存箱子自己记了试过什么；其余的做不成在这里记一笔，部件没接上的不算试过。
            if (problem != null && !(move instanceof SpaceMove.ToKnownContainer)) {
                records.attempt("腾地方：" + describeMove(move), problem.message());
            }
        }

        // 存箱子那一步做完了：一件没存进、或这只已经放不下了，这次不再往这只里塞，免得原地反复开关。
        private void settleStore() {
            if (doing instanceof SpaceMove.ToKnownContainer(var container, var stack)
                    && (storedThisStep == 0 || !storingStep.full().isEmpty())) {
                failedContainers.add(container.at());
            }
        }

        private void endStep() {
            step.close();
            step = null;
            doing = null;
            storingStep = null;
        }

        private int carriedInBackpack(String itemId) {
            return backpack.stacks().stream().filter(stack -> stack.itemId().equals(itemId))
                    .mapToInt(BackpackStack::count).sum();
        }

        @Override
        public void pause() {
            if (step != null) step.pause();
        }

        @Override
        public void close() {
            if (step != null) endStep();
        }

        @Override
        public Interruptibility interruptibility() {
            return step == null ? Interruptibility.WORKING : step.interruptibility();
        }

        @Override
        public String describe() {
            return step == null ? "看背包还差几格（" + purpose + "）" : "腾地方：" + step.describe();
        }
    }

    private static String nameOf(SpaceMove move) {
        return move instanceof SpaceMove.ToKnownContainer(var container, var stack) ? container.name() : "容器";
    }

    // 一步腾挪给人看的说法，例如"丢掉 64 个 minecraft:cobblestone"。
    private static String describeMove(SpaceMove move) {
        return switch (move) {
            case SpaceMove.MergeStacks merge -> "合并散着的 " + merge.itemId();
            case SpaceMove.ToCarriedBackpack(var stack) -> "把 " + noteOf(stack) + " 放进随身背包";
            case SpaceMove.ToKnownContainer(var container, var stack) -> "把 " + noteOf(stack) + " 存进 " + container.name();
            case SpaceMove.DropStack(var stack) -> "丢掉 " + noteOf(stack);
        };
    }

    private static String noteOf(BackpackStack stack) {
        return stack.count() + " 个 " + stack.itemId();
    }
}
