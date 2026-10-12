// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.deposit;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiPredicate;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.inventory.ContainerChooser;
import org.maiwithu.maicraft.behavior.inventory.KnownContainer;
import org.maiwithu.maicraft.behavior.inventory.StoresInContainer;
import org.maiwithu.maicraft.behavior.inventory.spi.NetworkStorage;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.Target;
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
import org.maiwithu.maicraft.kernel.task.TaskRecords;

/**
 * 存东西的任务：挑好容器、清开压住盖子的方块，再交给玩家行为层的存放动作（走过去点开、一笔笔搬进去、
 * 记下里面有什么、关上）；一只满了换下一只，都满了就带着还剩多少结束。
 *
 * <p>存放动作和腾地方存进记得的箱子是同一份：被打断时开着的界面先关上，恢复后重新点开接着存；
 * 被取消时界面也关上。只记确认过的量：每一笔按背包少了几件、容器多了几件对上才记账，对不上的进未确认，不凑数。
 */
final class DepositTask extends PhasedTask<DepositTask.Phase> {

    /** 挑容器 → 清开盖子 → 存进去（走过去点开、搬、关上）→ 换下一只；存进网络时一样样存进去。 */
    enum Phase { CHOOSE, DIG, STORE, NEXT, NETWORK }

    private final DepositInput input;
    private final DepositServices services;
    private final BackpackView backpack;
    private final Deque<ContainerChooser.Entry> candidates = new ArrayDeque<>();
    /** 每样东西一共要存几件，按存什么的顺序。 */
    private final Map<String, Integer> wanted = new LinkedHashMap<>();
    private final Map<String, Integer> confirmed = new LinkedHashMap<>();
    /** 放进去就被容器下面的溜槽、漏斗抽走的件数：离开了背包，没留在容器里。 */
    private final Map<String, Integer> passed = new LinkedHashMap<>();
    /** 每只容器存进了什么：容器的叫法 → 物品 → 件数。 */
    private final Map<String, Map<String, Integer>> storedIn = new LinkedHashMap<>();
    private final List<Problem> openFailures = new ArrayList<>();
    /** 存进网络时已经交给网络存储试过的东西：存不进的不再试。 */
    private final Set<String> networkTried = new HashSet<>();
    /** 存进网络没成的原因；没有为 null。 */
    private Problem networkFailure;
    private boolean planned;
    private Action prepared;
    private ContainerChooser.Entry current;
    private StoresInContainer storing;

    DepositTask(DepositInput input, DepositServices services, BackpackView backpack) {
        super("存东西", Phase.CHOOSE, new ProgressTracker(200, 20L * 60 * 10));
        this.input = input;
        this.services = services;
        this.backpack = backpack;
    }

    @Override protected Action enter(Phase phase) {
        Action next = prepared;
        prepared = null;
        return next;
    }

    @Override protected Next<Phase> tick(Phase phase, TickContext context) {
        return switch (phase) {
            case CHOOSE -> choose();
            case DIG -> dig(context);
            case STORE -> store(context);
            case NEXT -> nextContainer();
            case NETWORK -> network(context);
        };
    }

    // 挑容器：先按身上的东西算好存什么；点名的容器只用它，一片地方就在那里 radius 内挑。
    private Next<Phase> choose() {
        if (!planned) {
            plan();
        }
        if (wanted.isEmpty()) {
            return Next.done(TaskResult.done(input.itemIds().isEmpty() ? "身上没有要存的东西"
                    : "身上没有要存的 " + String.join("、", input.itemIds())));
        }
        if (input.network()) return nextToNetwork();
        Optional<ContainerChooser.Pick> pick = pick();
        if (pick.isEmpty()) {
            recordProgress("正在扫附近 " + input.radius() + " 格内的容器");
            return Next.stay();
        }
        return switch (pick.get()) {
            case ContainerChooser.Pick.Chosen chosen -> {
                candidates.addAll(chosen.ordered());
                yield nextContainer();
            }
            case ContainerChooser.Pick.NeedApproval approval -> Next.fail(Problem.of(Problem.Kind.NEED_APPROVAL,
                    "只挑得到这些容器：" + String.join("；", concat(approval.others(), approval.blockedLids())),
                    "点名其中的容器，或允许动它们"));
            case ContainerChooser.Pick.NotFound notFound ->
                Next.fail(Problem.of(Problem.Kind.NOT_FOUND, notFound.searched(), null));
        };
    }

    // 存什么在开工时按身上的东西算一次：之后背包只会变少；标签经标签接缝判断。
    private void plan() {
        planned = true;
        // 标签判断直接用玩家行为层的读法：物品挂着的标签里有没有这一个；没接上时什么标签都不挂。
        BiPredicate<String, String> taggedIn = services.tags() == null
                ? (itemId, tagId) -> false : (itemId, tagId) -> services.tags().tagsOf(itemId).contains(tagId);
        for (DepositDecider.ToDeposit item : DepositDecider.choose(backpack.stacks(), input.itemIds(),
                input.count(), taggedIn)) {
            wanted.merge(item.stack().itemId(), item.amount(), Integer::sum);
        }
    }

    // 目标对象分两种：一格容器（b#、坐标）只用它；一片地方（f#、地标、脚边、不给）在那里挑。还在扫给空。
    private Optional<ContainerChooser.Pick> pick() {
        return switch (input.target()) {
            case null -> area(services.places().feet());
            case Target.Here here -> area(services.places().feet());
            case Target.Seen seen -> services.places().seen(seen.id())
                    .map(at -> seen.id().startsWith("f") ? area(cellOf(at)) : Optional.of(named(at)))
                    .orElseGet(() -> Optional.of(new ContainerChooser.Pick.NotFound(
                            "观察编号 " + seen.id() + " 对应的东西已经不在了")));
            case Target.Position position -> Optional.of(position.y() == null
                    ? new ContainerChooser.Pick.NotFound("点名容器要给全坐标，包括 y")
                    : named(new WorldPosition(position.x(), position.y(), position.z(), position.dimension())));
            case Target.Landmark landmark -> services.places().landmark(landmark.name())
                    .map(at -> area(cellOf(at)))
                    .orElseGet(() -> Optional.of(new ContainerChooser.Pick.NotFound(
                            "没有记住叫「" + landmark.name() + "」的地点")));
            default -> Optional.of(new ContainerChooser.Pick.NotFound("存东西不接受这种目标：" + input.target().kind()));
        };
    }

    // 一片地方：radius 内的容器加上世界记忆里记过的，按"已放着同种 > 有空位 > 近"排；没扫完给空。
    private Optional<ContainerChooser.Pick> area(BlockPos center) {
        if (center == null) {
            return Optional.of(new ContainerChooser.Pick.NotFound("这一刻角色不在世界里，找不了容器"));
        }
        List<ContainerChooser.Candidate> found = services.spots().around(center, (int) input.radius());
        if (found.isEmpty() && !services.spots().scanComplete()) {
            return Optional.empty();
        }
        return Optional.of(ContainerChooser.choose(found, wanted.keySet().iterator().next(), mayDigLid()));
    }

    // 点名的容器：LLM 点名就算允许，别人的也用；界面认不出、坐着猫、盖子压住照样按挑容器的规矩判。
    private ContainerChooser.Pick named(WorldPosition at) {
        return services.spots().at(at)
                .map(found -> ContainerChooser.choose(List.of(new ContainerChooser.Candidate(found.name(),
                        found.blockTypeId(), found.at(), found.knownContents(), found.hasFreeSpace(), found.walkCost(),
                        false, found.layoutKnown(), found.lid(), found.catSitting())),
                        wanted.keySet().iterator().next(), mayDigLid()))
                .orElseGet(() -> new ContainerChooser.Pick.NotFound(
                        "（" + at.x() + ", " + at.y() + ", " + at.z() + "）那里没有容器"));
    }

    // 挖开压住盖子的方块要许可允许挖天然方块（只垫不挖的档不算）。
    private boolean mayDigLid() {
        Permissions.BlockChanges changes = input.permissions().changeBlocks();
        return services.digs() != null
                && (changes == Permissions.BlockChanges.NATURAL || changes == Permissions.BlockChanges.ANY);
    }

    // 存进网络：一样样交给网络存储（拿出终端打开、快速移动进去、关上），存进的按背包少了几件记账；
    // 一样存不进（没连上、放不下）就不再试别的，带着还剩多少收尾。
    private Next<Phase> nextToNetwork() {
        Optional<NetworkStorage> storage = services.network().get();
        if (storage.isEmpty() || !storage.get().available()) {
            return Next.fail(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, "身上没有绑了访问点的无线终端，存不进网络",
                    "带上绑好访问点的无线终端，或改存进箱子（into=container）"));
        }
        for (Map.Entry<String, Integer> entry : wanted.entrySet()) {
            int left = entry.getValue() - confirmed.getOrDefault(entry.getKey(), 0);
            if (left <= 0 || networkTried.contains(entry.getKey())) continue;
            networkTried.add(entry.getKey());
            Optional<Action> store = storage.get().store(new BackpackStack(entry.getKey(), left, 64, false, false,
                    false, false), "存东西", networkLedger());
            if (store.isEmpty()) continue;
            return goWith(Phase.NETWORK, store.get(), "把 " + entry.getKey() + " 存进网络");
        }
        return finish();
    }

    private Next<Phase> network(TickContext context) {
        return switch (runAction(context)) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Done done -> nextToNetwork();
            case ActionStatus.Failed failed -> {
                recordAttempt("存进网络", failed.problem().message());
                networkFailure = failed.problem();
                yield finish();
            }
        };
    }

    // 网络存储记的账：存进的一笔并进这次的确认与"存进了哪"，其余照转。
    private TaskRecords networkLedger() {
        TaskRecords records = records();
        return new TaskRecords() {
            @Override public void change(Change change) {
                if (change.kind() == Change.Kind.ITEM_STORED) {
                    confirmed.merge(change.what(), change.count(), Integer::sum);
                    storedIn.computeIfAbsent("ME 网络", ignored -> new LinkedHashMap<>())
                            .merge(change.what(), change.count(), Integer::sum);
                }
                records.change(change);
            }

            @Override public void unconfirmed(Change change) {
                records.unconfirmed(change);
            }

            @Override public void attempt(String tried, String whatHappened) {
                records.attempt(tried, whatHappened);
            }
        };
    }

    // 换下一只：都存完了或候选用尽就收尾；盖子被压住先挖开，再走过去点开。
    private Next<Phase> nextContainer() {
        current = allStored() ? null : candidates.poll();
        if (current == null) {
            return finish();
        }
        if (current.digLidFirst()) {
            Optional<Action> dig = services.digs() == null ? Optional.empty()
                    : services.digs().dig(lidCell(), input.permissions());
            if (dig.isEmpty()) {
                recordAttempt("清开" + name() + "盖子上的方块", "挖不了，换下一只");
                return Next.go(Phase.NEXT, "这只的盖子清不开，换下一只");
            }
            return goWith(Phase.DIG, dig.get(), "先清开" + name() + "盖子上的方块");
        }
        return storeInCurrent();
    }

    private Next<Phase> dig(TickContext context) {
        return switch (runAction(context)) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Done done -> storeInCurrent();
            case ActionStatus.Failed failed -> {
                recordAttempt("清开" + name() + "盖子上的方块", failed.problem().message());
                yield Next.go(Phase.NEXT, "这只的盖子清不开，换下一只");
            }
        };
    }

    // 交给存放动作：走过去点开、按存什么的顺序搬、记下里面有什么、关上；界面中途被关掉它自己重新点开。
    private Next<Phase> storeInCurrent() {
        ContainerChooser.Candidate candidate = current.candidate();
        storing = new StoresInContainer(new KnownContainer(candidate.name(), candidate.at(), candidate.blockTypeId()),
                List.copyOf(wanted.keySet()), ledger(), input.permissions(), services.storing());
        return goWith(Phase.STORE, storing, "走过去点开" + name());
    }

    // 这只存完了（放不下的已经记了一笔）就换下一只；开不了的原因留着，一件都没存进时拿它说明。
    private Next<Phase> store(TickContext context) {
        return switch (runAction(context)) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Done done -> Next.go(Phase.NEXT, name() + "存完了");
            case ActionStatus.Failed failed -> {
                storing.openFailure().ifPresent(openFailures::add);
                yield Next.go(Phase.NEXT, "这只存不进去，换下一只");
            }
        };
    }

    // 收尾：存完算完成；存进了一些算部分完成；一件都没存进按失败，原因是开不了就说开不了，否则是都满了。
    private Next<Phase> finish() {
        long deposited = confirmed.values().stream().mapToLong(Integer::longValue).sum();
        long drained = passed.values().stream().mapToLong(Integer::longValue).sum();
        long total = wanted.values().stream().mapToLong(Integer::longValue).sum();
        // 要存的都离开了背包：一部分留在容器里，一部分被下面的溜槽、漏斗抽走了，分开说清。
        String drainedNote = drained == 0 ? "" : "；另有 " + drained + " 件放进去就被下面的溜槽或漏斗抽走了，没留在容器里";
        if (deposited + drained >= total) {
            return Next.done(TaskResult.done("存进了 " + storedIn.size() + " 只容器，要存的都离开了背包" + drainedNote));
        }
        deposited += drained;
        Problem why = networkFailure != null ? networkFailure
                : deposited == 0 && !openFailures.isEmpty() ? openFailures.getFirst()
                : Problem.of(Problem.Kind.NOT_FOUND, "附近的容器都满了，或都开不了", "走远一点再存，或点名一只有空位的容器");
        if (deposited == 0) {
            return Next.fail(why);
        }
        return Next.done(TaskResult.builder(TaskResult.Status.PARTIAL,
                "存进了 " + deposited + " 件，还有 " + (total - deposited) + " 件没存下").problem(why).build());
    }

    // 存放的账：确认一笔记一笔变化，对不上的进未确认，试过的办法进 attempts。
    private StoresInContainer.Ledger ledger() {
        return new StoresInContainer.Ledger() {
            @Override public int left(String itemId) {
                return wanted.getOrDefault(itemId, 0) - confirmed.getOrDefault(itemId, 0) - passed.getOrDefault(itemId, 0);
            }

            // 放进去就被下面的溜槽、漏斗抽走的：离开了背包，不再当还要存；去向照实记进没能确认。
            @Override public void passedThrough(String itemId, int amount, String fact) {
                passed.merge(itemId, amount, Integer::sum);
                recordUnconfirmed(new Change(Change.Kind.OTHER, "搬运", amount, fact));
            }

            @Override public void stored(String itemId, int amount) {
                confirmed.merge(itemId, amount, Integer::sum);
                storedIn.computeIfAbsent(name(), ignored -> new LinkedHashMap<>()).merge(itemId, amount, Integer::sum);
                recordChange(new Change(Change.Kind.ITEM_STORED, itemId, amount, "存进了" + name()));
            }

            @Override public void unconfirmed(String fact) {
                recordUnconfirmed(new Change(Change.Kind.OTHER, "搬运", 1, fact));
            }

            @Override public void attempt(String tried, String whatHappened) {
                recordAttempt(tried, whatHappened);
            }
        };
    }

    private boolean allStored() {
        return wanted.entrySet().stream()
                .allMatch(entry -> confirmed.getOrDefault(entry.getKey(), 0) >= entry.getValue());
    }

    private Next<Phase> goWith(Phase phase, Action action, String why) {
        prepared = action;
        return Next.go(phase, why);
    }

    private String name() {
        return current.candidate().name();
    }

    private BlockPos lidCell() {
        return cellOf(current.candidate().at()).above();
    }

    private static BlockPos cellOf(WorldPosition at) {
        return new BlockPos(at.x(), at.y(), at.z());
    }

    private static List<String> concat(List<String> first, List<String> second) {
        List<String> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }

    @Override protected ResultDetails details() {
        List<String> lines = new ArrayList<>();
        storedIn.forEach((container, items) -> {
            List<String> parts = new ArrayList<>();
            items.forEach((itemId, amount) -> parts.add(itemId + " ×" + amount));
            lines.add(container + "：" + String.join("、", parts));
        });
        return new DepositDetails(lines);
    }

    @Override protected List<String> remaining() {
        List<String> left = new ArrayList<>();
        wanted.forEach((itemId, amount) -> {
            int missing = amount - confirmed.getOrDefault(itemId, 0) - passed.getOrDefault(itemId, 0);
            if (missing > 0) left.add(itemId + " 还有 " + missing + " 件没存下");
        });
        return left;
    }

    @Override protected String describePhase(Phase value) {
        return switch (value) {
            case CHOOSE -> "挑容器";
            case DIG -> "清开盖子上的方块";
            case STORE -> "走过去点开容器、搬进去、关上";
            case NEXT -> "换下一只容器";
            case NETWORK -> "拿出无线终端存进网络";
        };
    }
}
