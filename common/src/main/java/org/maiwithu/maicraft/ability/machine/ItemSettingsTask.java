// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.ability.machine.spi.ItemSettings;
import org.maiwithu.maicraft.ability.machine.spi.MachineSetting;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.ResultDetails;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.TaskInput;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 看与改身上物品自己的设置（machine_inspect / machine_configure 给 item 时）：过滤器、属性过滤器写规则。
 * 看：身上每一件这种物品分别列出在哪一格、现在是什么规则。改：挑一件（给了 inventory_slot 就是那一格，
 * 不给就是第一件）→ 不认识的项一次报全、已经对的不动 → 交给物品设置一整段改（拿到主手、开界面、改、关上）→
 * 关上界面后读主手那件的数据核对，写明改的是原来哪一格的那件、现在在主手。
 */
final class ItemSettingsTask extends PhasedTask<ItemSettingsTask.Phase> {

    /** 认物品 → 改 → 读回。 */
    enum Phase { RESOLVE, CHANGE }

    /**
     * 看或改身上物品设置的输入。
     *
     * @param item          物品 ID
     * @param inventorySlot 改哪一格那件（0~35，40 副手）；不给为 null，改第一件
     * @param settings      要改成什么；只看时为空
     */
    record Input(String item, Integer inventorySlot, Map<String, String> settings, Permissions permissions)
            implements TaskInput {
        Input {
            Objects.requireNonNull(item, "item");
            settings = settings == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(settings));
            Objects.requireNonNull(permissions, "permissions");
        }

        @Override public String describe() {
            return settings.isEmpty() ? "看 " + item + " 的设置" : "改 " + item + " 的设置（" + settings.size() + " 项）";
        }
    }

    /**
     * 结果细节：看的时候每一件一行；改的时候还有每项的改前与读回。
     *
     * @param item    物品 ID
     * @param stacks  身上每一件这种物品：在哪一格、现在的设置
     * @param changed 改的是原来哪一格那件（改完在主手）；只看时为 null
     * @param applied 每项设置：改前、改后读回
     */
    record Details(String item, List<Stack> stacks, Integer changed, List<MachineConfigureDetails.Applied> applied)
            implements ResultDetails {
        Details {
            stacks = List.copyOf(stacks);
            applied = List.copyOf(applied);
        }
    }

    /** 身上的一件：在哪一格（0~35，40 副手）、现在的设置。 */
    record Stack(int inventorySlot, Map<String, String> settings) {
        Stack {
            settings = Map.copyOf(settings);
        }
    }

    private static final int OFFHAND = 40;

    private final Input input;
    private final MachineServices services;
    private final List<Stack> stacks = new ArrayList<>();
    private final List<MachineConfigureDetails.Applied> applied = new ArrayList<>();
    private ItemSettings settings;
    private Integer changed;
    private Map<String, String> before = Map.of();
    private Action prepared;

    ItemSettingsTask(Input input, MachineServices services) {
        super(input.settings().isEmpty() ? "看物品设置" : "改物品设置", Phase.RESOLVE, new ProgressTracker(600, 2400));
        this.input = input;
        this.services = services;
    }

    @Override protected Action enter(Phase phase) {
        Action next = prepared;
        prepared = null;
        return next;
    }

    @Override protected Next<Phase> tick(Phase phase, TickContext context) {
        return switch (phase) {
            case RESOLVE -> resolve();
            case CHANGE -> change(context);
        };
    }

    // 列出身上每一件；只看就到此为止；要改就挑一件、核对设置项，已经对的不动。
    private Next<Phase> resolve() {
        PlayerContext current = services.context().get();
        if (current == null || current.localPlayer() == null) {
            return Next.fail(Problem.of(Problem.Kind.WRONG_TIME, "角色不在世界里，看不了身上的东西"));
        }
        LocalPlayer self = current.localPlayer();
        Optional<Integer> first = Optional.empty();
        for (int slot : slotsOf(self)) {
            ItemStack stack = stackAt(self, slot);
            if (!idOf(stack).equals(input.item())) continue;
            ItemSettings owner = settingsFor(stack);
            if (owner == null) {
                return Next.fail(Problem.of(Problem.Kind.UNSUPPORTED, input.item() + " 没有登记的物品设置（缺对应模组的联动）"));
            }
            settings = owner;
            stacks.add(new Stack(slot, asMap(owner.settings(stack))));
            if (first.isEmpty()) first = Optional.of(slot);
        }
        if (stacks.isEmpty()) {
            return Next.fail(Problem.of(Problem.Kind.NEED_ITEM, "身上没有 " + input.item()));
        }
        if (input.settings().isEmpty()) {
            return Next.done(TaskResult.done("身上有 " + stacks.size() + " 件 " + input.item()));
        }
        changed = input.inventorySlot() != null ? input.inventorySlot() : first.get();
        Optional<Stack> chosen = stacks.stream().filter(stack -> stack.inventorySlot() == changed).findFirst();
        if (chosen.isEmpty()) {
            return Next.fail(Problem.of(Problem.Kind.INVALID_PARAMETER, "第 " + changed + " 格不是 " + input.item()));
        }
        before = chosen.get().settings();
        ItemStack target = stackAt(current.localPlayer(), changed);
        List<String> unknown = input.settings().keySet().stream().filter(key -> !before.containsKey(key)).toList();
        if (!unknown.isEmpty()) {
            return Next.fail(Problem.of(Problem.Kind.INVALID_PARAMETER, input.item() + " 不认识这些设置项："
                    + String.join("、", unknown) + "；它认的是：" + String.join("、", before.keySet())));
        }
        Map<String, String> pending = new LinkedHashMap<>();
        input.settings().forEach((key, value) -> {
            if (!settings.holds(target, key, value)) pending.put(key, value);
        });
        if (pending.isEmpty()) {
            input.settings().forEach((key, value) -> applied.add(new MachineConfigureDetails.Applied(key, value, value)));
            return Next.done(TaskResult.done(input.item() + " 的设置本来就是要求的样子"));
        }
        Optional<Action> work = settings.change(changed, pending, input.permissions());
        if (work.isEmpty()) {
            return Next.fail(Problem.of(Problem.Kind.UNSUPPORTED, input.item() + " 改不了这些设置项"));
        }
        prepared = work.get();
        return Next.go(Phase.CHANGE, "开它的界面改 " + pending.size() + " 项");
    }

    // 改完（界面已关上）读主手那件核对：一项一项比，对上的算改好。
    private Next<Phase> change(TickContext context) {
        return switch (runAction(context)) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Done done -> settle(null);
            case ActionStatus.Failed failed -> settle(failed.problem());
        };
    }

    private Next<Phase> settle(Problem problem) {
        PlayerContext current = services.context().get();
        ItemStack held = current == null || current.localPlayer() == null ? ItemStack.EMPTY : current.localPlayer().getMainHandItem();
        Map<String, String> after = idOf(held).equals(input.item()) ? asMap(settings.settings(held)) : Map.of();
        List<String> wrong = new ArrayList<>();
        boolean inHand = idOf(held).equals(input.item());
        input.settings().forEach((key, value) -> {
            String now = after.getOrDefault(key, "");
            applied.add(new MachineConfigureDetails.Applied(key, before.get(key), now));
            if (!inHand || !settings.holds(held, key, value)) wrong.add(key + " 读回 " + now);
        });
        if (wrong.isEmpty()) {
            recordChange(new Change(Change.Kind.OTHER, input.item(), 1, "改了设置：原来第 " + changed + " 格那件，现在在主手"));
            return Next.done(TaskResult.done(input.item() + " 的 " + input.settings().size() + " 项设置都改好了"));
        }
        Problem why = problem != null ? problem
                : Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, "关上界面后读回对不上：" + String.join("；", wrong));
        return Next.done(TaskResult.builder(TaskResult.Status.PARTIAL, input.item() + " 的设置没改全").problem(why).build());
    }

    private ItemSettings settingsFor(ItemStack stack) {
        for (ItemSettings candidate : services.itemSettings()) {
            if (candidate.covers(stack)) return candidate;
        }
        return null;
    }

    private static List<Integer> slotsOf(LocalPlayer self) {
        List<Integer> slots = new ArrayList<>();
        for (int slot = 0; slot < self.getInventory().items.size(); slot++) slots.add(slot);
        slots.add(OFFHAND);
        return slots;
    }

    private static ItemStack stackAt(LocalPlayer self, int slot) {
        return slot == OFFHAND ? self.getOffhandItem() : self.getInventory().items.get(slot);
    }

    private static Map<String, String> asMap(List<MachineSetting> list) {
        Map<String, String> out = new LinkedHashMap<>();
        for (MachineSetting setting : list) out.put(setting.key(), setting.value());
        return out;
    }

    private static String idOf(ItemStack stack) {
        return stack.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    @Override protected ResultDetails details() {
        return new Details(input.item(), stacks, changed, applied);
    }

    @Override protected List<String> remaining() {
        return List.of();
    }

    @Override protected String describePhase(Phase value) {
        return switch (value) {
            case RESOLVE -> "认物品、对设置项";
            case CHANGE -> "开界面改、关上后读回";
        };
    }
}
