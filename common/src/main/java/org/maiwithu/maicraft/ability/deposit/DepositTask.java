// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.deposit;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.behavior.approach.InteractionTarget;
import org.maiwithu.maicraft.behavior.inventory.ContainerChooser;
import org.maiwithu.maicraft.behavior.interaction.FirstPersonScene;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuSession;
import org.maiwithu.maicraft.behavior.menu.MoveConfirmation;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.behavior.menu.TransferSettlement;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 存东西的任务：挑好容器、走过去点开、认下界面、把要存的东西一笔一笔搬进去、关上；
 * 一只满了换下一只，都满了就带着还剩多少结束。
 *
 * <p>只记确认过的量：每笔快速移动都按角色背包这一侧精确减少、容器那一侧精确增加来核对，
 * 对不上的不凑数。界面开着时被打断先关界面再走，恢复后重新打开接着存。
 */
final class DepositTask extends PhasedTask<DepositTask.Phase> {

    /** 任务的推进：挑容器 → 挖开盖子 → 靠近 → 点开 → 认界面 → 搬 → 关上 → 换下一只。 */
    enum Phase { CHOOSE, DIG, APPROACH, OPEN, TRANSFER, CLOSE, NEXT }

    /** 等界面打开并同步完的期限（刻）。 */
    private static final int MENU_WAIT_LIMIT = 40;
    /** 一笔搬运等确认的期限（刻）；对不上的不无限等。 */
    private static final int CONFIRM_WAIT_LIMIT = 10;

    private final DepositInput input;
    private final DepositServices services;
    private final Deque<ContainerChooser.Entry> candidates = new ArrayDeque<>();
    /** 这次要存的东西，按背包格排好；存掉一件少一件。 */
    private final List<DepositDecider.ToDeposit> plan = new ArrayList<>();
    private final TransferSettlement settlement = new TransferSettlement();
    private final Map<String, Integer> storedByContainer = new LinkedHashMap<>();
    private final List<String> containerLines = new ArrayList<>();
    private ContainerChooser.Entry current;
    private int menuWaited;
    private Action currentAction;
    private TransferPlanState transfer;

    DepositTask(DepositInput input, DepositServices services, BackpackView backpack) {
        super("存东西", Phase.CHOOSE, new ProgressTracker(200, 20L * 60 * 10));
        this.input = input;
        this.services = services;
        // 存什么的判定在开工前一次算好：背包在存的过程中只会变少，不会再多出新东西。
        plan.addAll(DepositDecider.choose(backpack.stacks(), input.itemIds(), input.count(),
                (String itemId, String tag) -> false));
    }

    @Override protected Action enter(Phase phase) {
        currentAction = switch (phase) {
            case DIG -> services.digs() == null || current == null || !current.digLidFirst()
                    ? null
                    : services.digs().dig(lidCell()).orElse(null);
            case APPROACH -> current == null ? null
                    : services.close().toward(InteractionTarget.ofBlock(containerPos()), input.permissions());
            default -> null;
        };
        return currentAction;
    }

    @Override protected Next<Phase> tick(Phase phase, TickContext context) {
        return switch (phase) {
            case CHOOSE -> chooseContainers(context);
            case DIG -> digStep(context);
            case APPROACH -> runActionThen(context, () -> Next.go(Phase.OPEN, "到容器跟前了"));
            case OPEN -> openMenu(context);
            case TRANSFER -> transfer(context);
            case CLOSE -> closeStep(context);
            case NEXT -> nextContainer();
        };
    }

    // 挑容器：点名了只用那一个；没点名在这片地方radius内挑，加上世界记忆里的（到场核对）。
    private Next<Phase> chooseContainers(TickContext context) {
        if (plan.isEmpty()) {
            return Next.done(TaskResult.done("身上没有要存的东西"));
        }
        boolean mayDig = services.digs() != null
                && input.permissions().changeBlocks() != Permissions.BlockChanges.NONE;
        ContainerChooser.Pick pick;
        if (input.target() != null) {
            pick = chooseNamed();
        } else {
            BlockPos center = feetOf(context);
            List<ContainerChooser.Candidate> found = services.spots().around(center, (int) input.radius());
            if (found.isEmpty() && !services.spots().scanComplete()) {
                recordProgress("正在扫附近 " + input.radius() + " 格内的容器");
                return Next.stay();
            }
            pick = ContainerChooser.choose(found, representativeItem(), mayDig);
        }
        return switch (pick) {
            case ContainerChooser.Pick.Chosen chosen -> {
                candidates.addAll(chosen.ordered());
                yield Next.go(candidates.getFirst().digLidFirst() ? Phase.DIG : Phase.APPROACH,
                        "挑好了容器，先去第一只");
            }
            case ContainerChooser.Pick.NeedApproval approval -> Next.fail(approvalProblem(approval));
            case ContainerChooser.Pick.NotFound notFound ->
                Next.fail(Problem.of(Problem.Kind.NOT_FOUND, notFound.searched(), null));
        };
    }

    // 点名的容器：位置落实成一只候选；落实不了按目标没了说。
    private ContainerChooser.Pick chooseNamed() {
        WorldPosition at = positionOf(input.target());
        if (at == null) {
            return new ContainerChooser.Pick.NotFound("目标对象不是一格容器，要用观察编号或坐标点名容器");
        }
        Optional<ContainerChooser.Candidate> candidate = services.spots().at(at);
        return candidate.<ContainerChooser.Pick>map(c -> new ContainerChooser.Pick.Chosen(
                List.of(new ContainerChooser.Entry(c, false))))
                .orElseGet(() -> new ContainerChooser.Pick.NotFound(
                        "(" + at.x() + ", " + at.y() + ", " + at.z() + ") 那里没有容器"));
    }

    // 挖开压住盖子的天然方块；挖不了时按超出许可问，写明是哪一格。
    private Next<Phase> digStep(TickContext context) {
        if (currentAction == null) {
            return Next.fail(Problem.of(Problem.Kind.NEED_APPROVAL,
                    "(" + lidCell().toShortString() + ") 压住了容器盖子，许可不允许挖或挖的接缝没接上",
                    "允许挖天然方块，或让人去把那一格清掉"));
        }
        return switch (currentAction.tick(context)) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Done done -> Next.go(Phase.APPROACH, "盖子清开了");
            case ActionStatus.Failed failed -> Next.fail(failed.problem());
        };
    }

    // 等界面打开并同步完；同步完才认界面，不把没同步的格子当空的。
    private Next<Phase> openMenu(TickContext context) {
        Optional<MenuContent.Reading> reading = services.menus().current();
        if (reading.isEmpty()) {
            if (menuWaited == 0) {
                Action open = services.interactions().useBlock(containerPos(),
                        InteractionConfirmation.menuChanged(context.player().localPlayer().containerMenu.containerId));
                open.tick(context);
                open.close();
            }
            if (++menuWaited > MENU_WAIT_LIMIT) {
                menuWaited = 0;
                recordAttempt("打开容器", "界面迟迟没开：锁着、上面坐着猫，或被服务器拒绝");
                return Next.go(Phase.NEXT, "这只开不了，换下一只");
            }
            return Next.stay();
        }
        menuWaited = 0;
        recordProgress("界面打开并同步完了");
        return Next.go(Phase.TRANSFER, "界面认下了，开始搬");
    }

    // 搬：逐堆快速移动，每笔按两侧精确增减核对；对不上的保留现场、不再凑数。
    private Next<Phase> transfer(TickContext context) {
        Optional<MenuContent.Reading> reading = services.menus().current();
        if (reading.isEmpty()) {
            recordAttempt("搬运", "界面中途没了，这一只存到哪算哪");
            return Next.go(Phase.NEXT, "换下一只容器");
        }
        if (transfer == null) transfer = new TransferPlanState(reading.get());
        ActionStatus status = transfer.step(context, reading.get());
        return switch (status) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Done done -> Next.go(Phase.CLOSE, "这一只存完了");
            case ActionStatus.Failed failed -> {
                settlement.fail(failed.problem());
                yield Next.go(Phase.CLOSE, "这一只出了岔子，先关上");
            }
        };
    }

    // 关上界面：光标上的东西先放回原来的格子，再等界面真的消失。
    private Next<Phase> closeStep(TickContext context) {
        Optional<MenuContent.Reading> reading = services.menus().current();
        if (reading.isEmpty()) return Next.go(Phase.NEXT, "界面已经关上了");
        MenuSession.Claim claim = MenuSession.claim(reading.get().channel());
        if (claim instanceof MenuSession.Claim.Refused refused) {
            recordAttempt("关上界面", refused.problem().message());
            return Next.go(Phase.NEXT, "关不了界面，先继续");
        }
        var closing = ((MenuSession.Claim.Owned) claim).session()
                .closeNow(reading.get().channel(), context.player().clientTick());
        if (closing instanceof MenuSession.Closing.Closed) return Next.go(Phase.NEXT, "界面关上了");
        if (closing instanceof MenuSession.Closing.Failed failed) {
            settlement.fail(failed.problem());
            return Next.go(Phase.NEXT, "界面关不上，先继续");
        }
        return Next.stay();
    }

    // 换下一只：还存得进就继续，都满了或用尽了就带着已确认的量收尾。
    private Next<Phase> nextContainer() {
        long deposited = settlement.confirmedByItem().values().stream().mapToLong(Integer::longValue).sum();
        long wanted = plan.stream().mapToLong(DepositDecider.ToDeposit::amount).sum();
        boolean allStored = deposited >= wanted;
        ContainerChooser.Entry next = candidates.poll();
        if (allStored || next == null) {
            return finish();
        }
        current = next;
        transfer = null;
        recordProgress("换下一只容器");
        return Next.go(next.digLidFirst() ? Phase.DIG : Phase.APPROACH, "换下一只容器");
    }

    // 收尾：存完算完成；没存完算部分完成，问题写明附近的容器都满了、还剩什么没存。
    private Next<Phase> finish() {
        List<Change> stored = new ArrayList<>();
        settlement.confirmedByItem().forEach((itemId, amount) ->
                stored.add(new Change(Change.Kind.ITEM_STORED, itemId, amount,
                        "存进了" + String.join("、", storedByContainer.keySet()))));
        String containerSummary = storedByContainer.isEmpty() ? "没有存进任何容器"
                : "存进了 " + storedByContainer.size() + " 只容器";
        long deposited = settlement.confirmedByItem().values().stream().mapToLong(Integer::longValue).sum();
        long wanted = plan.stream().mapToLong(DepositDecider.ToDeposit::amount).sum();
        TaskResult.Builder builder;
        if (deposited >= wanted) {
            builder = TaskResult.builder(TaskResult.Status.DONE, containerSummary + "，要存的都存进去了");
        } else {
            builder = TaskResult.builder(TaskResult.Status.PARTIAL,
                    containerSummary + "，还有 " + (wanted - deposited) + " 件没存下")
                    .problem(Problem.of(Problem.Kind.NOT_FOUND, "附近的容器都满了", null))
                    .remaining("还有 " + (wanted - deposited) + " 件没存下");
        }
        settlement.unconfirmedFacts().forEach(fact ->
                builder.unconfirmed(new Change(Change.Kind.OTHER, "搬运", 1, fact)));
        stored.forEach(builder::change);
        List<String> lines = new ArrayList<>();
        storedByContainer.forEach((name, amount) -> lines.add(name + "（存入 " + amount + " 件）"));
        return Next.done(builder.details(new DepositDetails(lines)).build());
    }

    private String depositedSummary() {
        List<String> parts = new ArrayList<>();
        settlement.confirmedByItem().forEach((itemId, amount) -> parts.add(itemId + " ×" + amount));
        return parts.isEmpty() ? "没有存进东西" : String.join("、", parts);
    }

    private Problem approvalProblem(ContainerChooser.Pick.NeedApproval approval) {
        String fact = String.join("；", approval.others()) + String.join("；", approval.blockedLids());
        return Problem.of(Problem.Kind.NEED_APPROVAL,
                "附近只挑得到这些容器：" + fact, "点名其中的容器，或允许动它们");
    }

    private BlockPos containerPos() {
        return new BlockPos(current.candidate().at().x(), current.candidate().at().y(),
                current.candidate().at().z());
    }

    private BlockPos lidCell() {
        return containerPos().above();
    }

    private BlockPos feetOf(TickContext context) {
        var eye = FirstPersonScene.of(context.player()).eyePosition();
        return BlockPos.containing(eye.x, eye.y - 1, eye.z);
    }

    private WorldPosition positionOf(Target target) {
        if (target instanceof Target.Position position) {
            return new WorldPosition(position.x(), position.y() == null ? 0 : position.y(), position.z(),
                    position.dimension());
        }
        return null;
    }

    // 给挑容器认"已经放着同种东西"用的代表物品：这次要存的第一样。
    private String representativeItem() {
        return plan.isEmpty() ? null : plan.getFirst().stack().itemId();
    }

    /**
     * 一只容器里的搬运状态：还剩哪些堆要搬、正在等哪一笔的确认。
     * 计划在界面内容读清后按背包侧的快照对号，界面被别人动过就按还剩的重新对。
     */
    /** 一个槽位快照里物品的注册 ID；对号与核对都按它。 */
    private static String idOf(SlotSnapshot snapshot) {
        return BuiltInRegistries.ITEM.getKey(snapshot.stack().getItem()).toString();
    }

    /** 背包侧快照里的物品注册 ID。 */
    private static String idOfStack(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    /** 一只容器里的搬运状态。 */
    private final class TransferPlanState {
        private final Deque<DepositDecider.ToDeposit> pending = new ArrayDeque<>(plan);
        private SlotSnapshot sourceBefore;
        private SlotSnapshot targetBefore;
        private int sourceSlotId = -1;
        private int targetSlotId = -1;
        private int amount;
        private String itemId;
        private int waited;

        TransferPlanState(MenuContent.Reading reading) {
            rematch(reading);
        }

        // 按此刻背包侧的快照把计划里的堆对回槽位；对不上的（被别人动过）跳过。
        private void rematch(MenuContent.Reading reading) {
            pending.clear();
            for (DepositDecider.ToDeposit toDeposit : plan) {
                long already = settlement.confirmedByItem().getOrDefault(toDeposit.stack().itemId(), 0);
                long wanted = plan.stream().filter(item -> item.stack().itemId().equals(toDeposit.stack().itemId()))
                        .mapToLong(DepositDecider.ToDeposit::amount).sum();
                long left = wanted - already;
                if (left <= 0) continue;
                reading.playerSlotIds().stream()
                        .filter(slotId -> matches(reading, slotId, toDeposit.stack()))
                        .findFirst()
                        .ifPresent(slotId -> pending.add(new DepositDecider.ToDeposit(toDeposit.stack(),
                                (int) Math.min(left, toDeposit.stack().count()))));
            }
        }

        private boolean matches(MenuContent.Reading reading, int slotId, BackpackStack stack) {
            int index = reading.playerSlotIds().indexOf(slotId);
            SlotSnapshot snapshot = reading.playerSnapshots().get(index);
            return !snapshot.isEmpty() && idOf(snapshot).equals(stack.itemId());
        }

        ActionStatus step(TickContext context, MenuContent.Reading reading) {
            if (pending.isEmpty()) return ActionStatus.done();
            if (sourceSlotId < 0) {
                DepositDecider.ToDeposit next = pending.poll();
                Optional<int[]> slots = locate(reading, next);
                if (slots.isEmpty()) return ActionStatus.done();
                sourceSlotId = slots.get()[0];
                targetSlotId = slots.get()[1];
                amount = next.amount();
                itemId = next.stack().itemId();
                sourceBefore = snapshotOf(reading, sourceSlotId, true);
                targetBefore = snapshotOf(reading, targetSlotId, false);
                waited = 0;
                services.quickMoves().quickMove(sourceSlotId);
                return ActionStatus.progressed();
            }
            SlotSnapshot sourceNow = snapshotOf(reading, sourceSlotId, true);
            SlotSnapshot targetNow = snapshotOf(reading, targetSlotId, false);
            var verdict = MoveConfirmation.normal(sourceBefore, targetBefore, sourceNow, targetNow, amount);
            return switch (verdict) {
                case CONFIRMED -> {
                    settlement.confirm(itemId, amount);
                    recordChange(new Change(Change.Kind.ITEM_STORED, itemId, amount,
                            "存进了 " + current.candidate().name()));
                    storedByContainer.merge(current.candidate().name(), amount, Integer::sum);
                    sourceSlotId = -1;
                    yield ActionStatus.progressed();
                }
                case DIVERGED -> {
                    settlement.unconfirmed("从 " + sourceSlotId + " 搬往 " + targetSlotId + " 的 "
                            + amount + " 件 " + itemId + " 对不上账：界面内容与计划不一致");
                    sourceSlotId = -1;
                    rematch(reading);
                    yield ActionStatus.progressed();
                }
                case WAITING -> {
                    if (++waited > CONFIRM_WAIT_LIMIT) {
                        settlement.unconfirmed("从 " + sourceSlotId + " 搬往 " + targetSlotId + " 的 "
                                + amount + " 件 " + itemId + " 到期限也没确认");
                        sourceSlotId = -1;
                        yield ActionStatus.progressed();
                    }
                    yield ActionStatus.running();
                }
            };
        }

        // 找这一堆的源槽位（背包侧）与目标槽位（容器侧：先并同种，再占空格）。
        private Optional<int[]> locate(MenuContent.Reading reading, DepositDecider.ToDeposit toDeposit) {
            int source = -1;
            for (int i = 0; i < reading.playerSlotIds().size(); i++) {
                SlotSnapshot snapshot = reading.playerSnapshots().get(i);
                if (!snapshot.isEmpty() && idOf(snapshot).equals(toDeposit.stack().itemId())) {
                    source = reading.playerSlotIds().get(i);
                    break;
                }
            }
            if (source < 0) return Optional.empty();
            int merge = -1;
            int free = -1;
            for (int i = 0; i < reading.containerSlotIds().size(); i++) {
                SlotSnapshot snapshot = reading.containerSnapshots().get(i);
                if (snapshot.isEmpty()) {
                    if (free < 0) free = reading.containerSlotIds().get(i);
                } else if (merge < 0 && idOf(snapshot).equals(toDeposit.stack().itemId())) {
                    merge = reading.containerSlotIds().get(i);
                }
            }
            int target = merge >= 0 ? merge : free;
            return target < 0 ? Optional.empty() : Optional.of(new int[]{source, target});
        }

        private SlotSnapshot snapshotOf(MenuContent.Reading reading, int slotId, boolean playerSide) {
            List<Integer> ids = playerSide ? reading.playerSlotIds() : reading.containerSlotIds();
            List<SlotSnapshot> snapshots = playerSide ? reading.playerSnapshots() : reading.containerSnapshots();
            int index = ids.indexOf(slotId);
            return index < 0 ? SlotSnapshot.empty() : snapshots.get(index);
        }
    }

    @Override protected List<String> remaining() {
        long deposited = settlement.confirmedByItem().values().stream().mapToLong(Integer::longValue).sum();
        long wanted = plan.stream().mapToLong(DepositDecider.ToDeposit::amount).sum();
        return deposited >= wanted ? List.of() : List.of("还有 " + (wanted - deposited) + " 件没存下");
    }

    @Override protected String describePhase(Phase value) {
        return switch (value) {
            case CHOOSE -> "挑容器";
            case DIG -> "清开盖子上方的方块";
            case APPROACH -> "走近容器";
            case OPEN -> "打开容器";
            case TRANSFER -> "把东西搬进去";
            case CLOSE -> "关上界面";
            case NEXT -> "换下一只容器";
        };
    }
}
