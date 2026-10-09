// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.equip;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.maiwithu.maicraft.behavior.inventory.GearChanges;
import org.maiwithu.maicraft.behavior.inventory.InventorySpace;
import org.maiwithu.maicraft.game.player.GearSlotName;
import org.maiwithu.maicraft.game.player.ReadsEquipment;
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

/**
 * 穿卸装备的任务：穿上时交给穿卸接缝做完再核对栏位；卸下时先腾出一格地方，
 * 再逐格取下放包。每一个栏位都分开确认：取下了记一条变化，没等到确认记进
 * unconfirmed，还在身上的记进 still_worn——卸了三格、剩一格穿着，就是部分完成，
 * 不再冒充全部卸完。一个栏位只动手一次，没卸下来也不原地反复拽。
 */
final class EquipTask extends PhasedTask<EquipTask.Phase> {

    /** 任务的进度：卸下时"腾地方 → 取下一格"交替进行；穿上只有动手一步。 */
    enum Phase { FREE_SPACE, HANDLING }

    private final EquipInput input;
    private final ReadsEquipment equipment;
    private final Optional<InventorySpace> space;
    private final Optional<GearChanges> gear;
    /** 这次已经处理过的栏位。 */
    private final Set<GearSlotName> processed = new HashSet<>();
    /** 动手了但没能确认结果的栏位。 */
    private final List<String> unconfirmedSlots = new ArrayList<>();
    /** 确认完成了的栏位数。 */
    private int settled;
    /** 当前动手的栏位，以及动手前栏位里是什么（卸下记账用）。 */
    private GearSlotName currentSlot;
    private String wornAtStart;

    EquipTask(EquipInput input, ReadsEquipment equipment, Optional<InventorySpace> space,
            Optional<GearChanges> gear) {
        super(input.unequip() ? "卸下装备" : "穿上装备",
                input.unequip() ? Phase.FREE_SPACE : Phase.HANDLING,
                new ProgressTracker(100, 20L * 60 * 2));
        this.input = input;
        this.equipment = Objects.requireNonNull(equipment, "equipment");
        this.space = Objects.requireNonNull(space, "space");
        this.gear = Objects.requireNonNull(gear, "gear");
    }

    @Override
    protected Action enter(Phase phase) {
        if (phase == Phase.FREE_SPACE) return null;
        currentSlot = nextUnprocessedSlot().orElse(null);
        if (currentSlot == null) return null;
        // 动手前抄下栏位里原来是什么，取下成功后变化记录有账可对。
        wornAtStart = equipment.slot(currentSlot)
                .map(stack -> stack.itemId())
                .orElse(currentSlot.paramName());
        return gear.flatMap(changes -> input.unequip()
                        ? changes.takeOff(currentSlot)
                        : changes.wear(input.itemId(), currentSlot))
                .orElse(null);
    }

    @Override
    protected Next<Phase> tick(Phase phase, TickContext context) {
        if (phase == Phase.FREE_SPACE) {
            return makeRoom();
        }
        if (currentSlot == null) {
            return settle();
        }
        if (action() == null) {
            return Next.fail(new Problem(Problem.Kind.UNSUPPORTED,
                    (input.unequip() ? "取下装备" : "穿上装备") + "的界面操作还没接上，做不了", null));
        }
        GearSlotName slot = currentSlot;
        return switch (runAction(context)) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Done done -> afterHandled(slot);
            case ActionStatus.Failed failed -> {
                processed.add(slot);
                recordAttempt((input.unequip() ? "取下 " : "穿上 ") + slot.paramName(),
                        failed.problem().message());
                yield nextSlotOrSettle();
            }
        };
    }

    // 卸下前先保证有一格空位放取下的东西；腾挪的每一步变化都如实记账。
    private Next<Phase> makeRoom() {
        if (space.isEmpty()) {
            // 腾地方的模型没接上：直接试着取下，装不装得下由游戏自己结算、由核对如实报告。
            return Next.go(Phase.HANDLING, "没有腾背包的模型可用，直接试着取下");
        }
        InventorySpace.Result result = space.get().ensureFree(1, "卸下装备");
        result.changes().forEach(this::recordChange);
        return switch (result.state()) {
            case FREE -> Next.go(Phase.HANDLING, "腾出地方了");
            case PROGRESS -> {
                recordProgress("在为卸下装备腾背包");
                yield Next.stay();
            }
            // 腾不动要动贵重品：把事实如实带回，不悄悄丢玩家的东西。
            case NEED_ASK, IMPOSSIBLE -> Next.fail(new Problem(Problem.Kind.INVENTORY_FULL,
                    "背包腾不出放卸下装备的地方：" + (result.problem() != null
                            ? result.problem().message() : result.question().text()),
                    "同意动贵重品，或先整理背包"));
        };
    }

    // 一个栏位动手完了：核对栏位现在的内容，卸下的看空没空，穿上的看是不是这件。
    private Next<Phase> afterHandled(GearSlotName slot) {
        processed.add(slot);
        boolean empty = equipment.slot(slot).isEmpty();
        if (input.unequip()) {
            if (empty) {
                settled++;
                recordChange(new Change(Change.Kind.ITEM_GAINED, wornAtStart, 1,
                        "从 " + slot.paramName() + " 取下放进了背包"));
            } else {
                // 动作"做完"了栏位还有东西：没等到确认，收尾时按仍穿着如实列出。
                unconfirmedSlots.add(slot.paramName());
                recordUnconfirmed(new Change(Change.Kind.OTHER, wornAtStart, 1,
                        "从 " + slot.paramName() + " 取下的操作没能确认"));
            }
        } else if (!empty && equipment.slot(slot).get().itemId().equals(input.itemId())) {
            settled++;
            recordChange(new Change(Change.Kind.OTHER, input.itemId(), 1,
                    "穿在了 " + slot.paramName()));
        } else {
            // 穿的动作做完了，栏位里却不是这件：没等到确认，不冒充穿上了。
            unconfirmedSlots.add(slot.paramName());
            recordUnconfirmed(new Change(Change.Kind.OTHER, input.itemId(), 1,
                    "穿上 " + slot.paramName() + " 的操作没能确认"));
        }
        return nextSlotOrSettle();
    }

    private Next<Phase> nextSlotOrSettle() {
        return nextUnprocessedSlot().isPresent()
                ? Next.go(input.unequip() ? Phase.FREE_SPACE : Phase.HANDLING, "下一个栏位")
                : settle();
    }

    private Next<Phase> settle() {
        List<String> worn = stillWornNow();
        String label = input.unequip() ? "卸下" : "穿上";
        if (unconfirmedSlots.isEmpty() && worn.isEmpty()) {
            return Next.done(TaskResult.done(label + "完成（" + settled + " 个栏位）"));
        }
        if (worn.isEmpty()) {
            return Next.done(TaskResult.builder(TaskResult.Status.PARTIAL,
                            label + "的操作没能全部确认，做过什么见 unconfirmed")
                    .remaining(unconfirmedSlots.stream()
                            .map(s -> "确认 " + s + " 的" + label + "结果").toList())
                    .build());
        }
        TaskResult.Builder builder = TaskResult.builder(TaskResult.Status.PARTIAL,
                label + "做了一部分：还穿着 " + String.join("、", worn));
        builder.remaining(worn.stream().map(s -> "仍穿着 " + s).toList());
        builder.details(new EquipDetails(worn));
        return Next.done(builder.build());
    }

    // 这次没卸完、现在还穿在身上的栏位。
    private List<String> stillWornNow() {
        List<String> worn = new ArrayList<>();
        if (input.unequip()) {
            for (GearSlotName slot : input.slots()) {
                if (equipment.slot(slot).isPresent()) worn.add(slot.paramName());
            }
        }
        return worn;
    }

    private Optional<GearSlotName> nextUnprocessedSlot() {
        return input.slots().stream()
                .filter(slot -> !processed.contains(slot))
                // 卸下时已经空着的栏位不用动手：整套卸下时中间那几格本来就是空的。
                .filter(slot -> !input.unequip() || equipment.slot(slot).isPresent())
                .findFirst();
    }

    /** 没卸下来、仍穿着的栏位；只有卸下且部分完成时非空。 */
    record EquipDetails(List<String> stillWorn) implements ResultDetails {
        public EquipDetails {
            stillWorn = List.copyOf(stillWorn);
        }
    }
}
