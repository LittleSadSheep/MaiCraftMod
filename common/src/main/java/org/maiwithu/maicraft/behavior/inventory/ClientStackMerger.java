// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.game.menu.MenuActions;
import org.maiwithu.maicraft.game.menu.MenuConfirmation;
import org.maiwithu.maicraft.game.menu.PendingMenuAction;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 合并接缝的读端：在背包界面里把同一种物品散在两格的东西并成一堆，腾出一格。
 *
 * <p>一个动作并一对：把数量小的那堆拿起（整份到光标上），点在还有余量的那堆上并进去，
 * 每下点击都等游戏确认（光标拿到的、源格空了的）才走下一步，并完把界面关上。
 * 配对挑"最小的并进装得下它的最大堆"，与腾地方计划里"能并出几格"的算法一致，计划才不空头。
 * 点击点出去了却没等到结果，照实记进没能确认，不盲目再点一次；做不成、被打断、被收尾时
 * 请游戏关上界面，光标上的东西由关闭流程放回背包。
 */
public final class ClientStackMerger implements StackMerger {

    /** 等一步确认的期限；到点按没能确认收场，不盲目重做。 */
    private static final int STEP_TIMEOUT_TICKS = 60;
    /** 一次合并的总期限（刻）：开界面加两下点击加关界面用不了这么久，到点按做不了收场。 */
    private static final int ATTEMPT_BUDGET_TICKS = 300;

    private final Supplier<PlayerContext> contexts;

    public ClientStackMerger(Supplier<PlayerContext> contexts) {
        this.contexts = Objects.requireNonNull(contexts, "contexts");
    }

    @Override
    public Action mergeOne(TaskRecords records) {
        return new MergeOne(contexts, Objects.requireNonNull(records, "records"));
    }

    /** 合并一对散堆：挑对 → 开背包界面 → 拿起小的那堆 → 并进另一格 → 关上。 */
    private static final class MergeOne implements Action {

        private enum Stage { PICK, OPENING, PICKING, PLACING, CLOSING, FINISHED }

        private final Supplier<PlayerContext> contexts;
        private final TaskRecords records;
        private Stage stage = Stage.PICK;
        private int sourceMenuSlot;
        private int targetMenuSlot;
        /** 动手前源格里那份的样子：确认条件与"拿到的对不对"都以它为基准。 */
        private ItemStack sourceBefore = ItemStack.EMPTY;
        private int sourceCount;
        private String itemId = "";
        private PendingMenuAction pending;
        private long startTick = -1;

        MergeOne(Supplier<PlayerContext> contexts, TaskRecords records) {
            this.contexts = contexts;
            this.records = records;
        }

        @Override
        public ActionStatus tick(TickContext tick) {
            PlayerContext player = tick.player();
            if (player == null || player.localPlayer() == null) {
                return ActionStatus.running();
            }
            if (startTick < 0) startTick = tick.gameTick();
            if (stage != Stage.CLOSING && stage != Stage.FINISHED
                    && tick.gameTick() - startTick > ATTEMPT_BUDGET_TICKS) {
                return giveUp(player, Problem.of(Problem.Kind.STUCK, "合并散堆超时，界面可能没就绪"));
            }
            return switch (stage) {
                case PICK -> pick(player);
                case OPENING -> opening(player);
                case PICKING, PLACING -> advancingClick(player);
                case CLOSING -> closing(player);
                case FINISHED -> ActionStatus.done();
            };
        }

        // 挑一对能并的散堆，定下从哪格并进哪格；挑不出来就是并不了。
        private ActionStatus pick(PlayerContext player) {
            Optional<MergePair> found = findMergeablePair(filledSlots(player.localPlayer()));
            if (found.isEmpty()) {
                stage = Stage.FINISHED;
                return ActionStatus.failed(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, "没有能并成一堆的散堆"));
            }
            MergePair pair = found.get();
            sourceMenuSlot = menuSlot(pair.sourceSlot());
            targetMenuSlot = menuSlot(pair.targetSlot());
            sourceBefore = pair.stack().copy();
            sourceCount = pair.stack().getCount();
            itemId = pair.itemId();
            stage = Stage.OPENING;
            return ActionStatus.progressed();
        }

        // 打开背包界面；开好了就点第一下——把源格整份拿到光标上。
        private ActionStatus opening(PlayerContext player) {
            MenuActions actions = player.menuActions();
            if (!actions.ensureVisible(player)) return ActionStatus.running();
            pending = actions.click(player, sourceMenuSlot, 0, ClickType.PICKUP, pickedUpConfirmation(), STEP_TIMEOUT_TICKS);
            stage = Stage.PICKING;
            return ActionStatus.progressed();
        }

        // 推进拿起或并进去的那一下：确认了走下一步；没确认的记一笔、关上界面按做不了收场。
        private ActionStatus advancingClick(PlayerContext player) {
            if (!pending.terminal()) {
                pending = player.menuActions().poll(player, pending);
                return ActionStatus.running();
            }
            boolean picking = stage == Stage.PICKING;
            String what = picking ? "拿起" : "并进另一格";
            switch (pending.status()) {
                case CONFIRMED_APPLIED -> {
                    if (picking) {
                        pending = player.menuActions().click(player, targetMenuSlot, 0, ClickType.PICKUP,
                                placedConfirmation(), STEP_TIMEOUT_TICKS);
                        stage = Stage.PLACING;
                    } else {
                        // 并进去了：这一格腾出来了，记一笔；关界面利不利索不改写这件事。
                        records.change(new Change(Change.Kind.OTHER, itemId, sourceCount,
                                "把散着的 " + itemId + " 并成整堆，腾出一格"));
                        pending = player.menuActions().close(player, STEP_TIMEOUT_TICKS);
                        stage = Stage.CLOSING;
                    }
                    return ActionStatus.progressed();
                }
                case CONFIRMED_NOT_APPLIED -> {
                    pending = null;
                    return giveUp(player, Problem.of(Problem.Kind.REFUSED_BY_GAME,
                            "把 " + itemId + " " + what + "没能做成：游戏没有接受这次点击"));
                }
                default -> {
                    records.unconfirmed(new Change(Change.Kind.OTHER, "腾背包", 1,
                            "合并散堆（" + itemId + "）：" + what + "的那一下点出去了，没等到确认结果"));
                    pending = null;
                    return giveUp(player, Problem.of(Problem.Kind.STUCK,
                            "把 " + itemId + " " + what + "没能确认，不盲目再点"));
                }
            }
        }

        // 等背包界面关上；剩下的关闭由菜单入口自己推进，合并本身已经确认过。
        private ActionStatus closing(PlayerContext player) {
            if (pending != null && !pending.terminal()) {
                pending = player.menuActions().poll(player, pending);
                if (pending != null && !pending.terminal()) return ActionStatus.running();
            }
            stage = Stage.FINISHED;
            return ActionStatus.done();
        }

        // 做不成就收场：开着的界面请游戏关上，光标上的东西由关闭流程放回背包。
        private ActionStatus giveUp(PlayerContext player, Problem problem) {
            closeInventory(player);
            stage = Stage.FINISHED;
            return ActionStatus.failed(problem);
        }

        private void closeInventory(PlayerContext player) {
            try {
                player.menuActions().closeForTaskBoundary(player, STEP_TIMEOUT_TICKS, "腾地方收尾");
            } catch (RuntimeException busy) {
                // 界面入口此刻不接受关闭：留给下一步动作的"先关旧界面"处理，这里不硬抢。
            }
        }

        // 被生存需求打断：点出去没等到的照实记，关上界面；恢复后从挑对重新来。
        @Override
        public void pause() {
            stopMidway();
            if (stage != Stage.FINISHED) stage = Stage.PICK;
            startTick = -1;
        }

        // 不再做了：点出去没等到的照实记，关上界面。
        @Override
        public void close() {
            stopMidway();
            stage = Stage.FINISHED;
        }

        private void stopMidway() {
            if (stage == Stage.PICK || stage == Stage.CLOSING || stage == Stage.FINISHED) return;
            if (pending != null && !pending.terminal()) {
                records.unconfirmed(new Change(Change.Kind.OTHER, "腾背包", 1,
                        "合并散堆（" + itemId + "）：停下时还有一下点击没等到确认结果"));
            }
            pending = null;
            // 停下时没有本刻的上下文：取当前角色请游戏关上界面。
            PlayerContext player = contexts.get();
            if (player != null && player.localPlayer() != null) closeInventory(player);
        }

        // 小的那堆拿在光标上时停不得：撒手会把东西留在界面外的流程里。
        @Override
        public Interruptibility interruptibility() {
            return stage == Stage.PLACING ? Interruptibility.UNSAFE_TO_STOP : Interruptibility.WORKING;
        }

        @Override
        public String describe() {
            return switch (stage) {
                case PICK -> "挑一对能并的散堆";
                case OPENING -> "打开背包界面";
                case PICKING -> "拿起散着的 " + itemId;
                case PLACING -> "把 " + itemId + " 并进另一格";
                case CLOSING -> "关上背包界面";
                case FINISHED -> "合并完了";
            };
        }

        // 确认条件：光标上拿到的正是源格那一份。
        private MenuConfirmation pickedUpConfirmation() {
            return (context, pending) -> {
                var menu = context.localPlayer().containerMenu;
                if (menu.containerId != pending.containerId()) return MenuConfirmation.Verdict.PENDING;
                ItemStack carried = menu.getCarried();
                if (carried.isEmpty()) return MenuConfirmation.Verdict.PENDING;
                return carried.getCount() == sourceCount && ItemStack.isSameItemSameComponents(carried, sourceBefore)
                        ? MenuConfirmation.Verdict.APPLIED : MenuConfirmation.Verdict.DIVERGED;
            };
        }

        // 确认条件：源格空了、光标也空了——整份并进了另一格。
        private MenuConfirmation placedConfirmation() {
            return (context, pending) -> {
                var menu = context.localPlayer().containerMenu;
                if (menu.containerId != pending.containerId() || sourceMenuSlot >= menu.slots.size()) {
                    return MenuConfirmation.Verdict.PENDING;
                }
                if (!menu.getSlot(sourceMenuSlot).getItem().isEmpty()) {
                    // 源格还没空：光标上还是那份就是同步没到，变了别的东西就是点岔了。
                    ItemStack carried = menu.getCarried();
                    return carried.getCount() == sourceCount && ItemStack.isSameItemSameComponents(carried, sourceBefore)
                            ? MenuConfirmation.Verdict.PENDING : MenuConfirmation.Verdict.DIVERGED;
                }
                return menu.getCarried().isEmpty()
                        ? MenuConfirmation.Verdict.APPLIED : MenuConfirmation.Verdict.DIVERGED;
            };
        }
    }

    /** 背包界面里的槽位号：快捷栏 0–8 在 36–44，主背包 9–35 原号不变。 */
    private static int menuSlot(int inventorySlot) {
        return inventorySlot <= 8 ? 36 + inventorySlot : inventorySlot;
    }

    // 主背包与快捷栏里非空的格子，按格号顺序。
    private static List<SlotStack> filledSlots(LocalPlayer player) {
        List<SlotStack> filled = new ArrayList<>();
        var inventory = player.getInventory();
        for (int slot = 0; slot <= 35; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty()) filled.add(new SlotStack(slot, stack));
        }
        return filled;
    }

    /**
     * 挑一对能并成一堆的散堆：同一种物品同一副组件、并起来不超过最大堆叠。
     * 数量小的先找去处，并进还有余量的最大那堆——这样能并出的格子最多，与计划里"能并几格"的算法一致。
     */
    static Optional<MergePair> findMergeablePair(List<SlotStack> filled) {
        List<SlotStack> sorted = new ArrayList<>(filled);
        sorted.sort((a, b) -> a.stack().getCount() != b.stack().getCount()
                ? Integer.compare(a.stack().getCount(), b.stack().getCount())
                : Integer.compare(a.slot(), b.slot()));
        for (int i = 0; i < sorted.size(); i++) {
            SlotStack small = sorted.get(i);
            SlotStack best = null;
            for (int j = 0; j < sorted.size(); j++) {
                if (j == i) continue;
                SlotStack other = sorted.get(j);
                if (!ItemStack.isSameItemSameComponents(small.stack(), other.stack())) continue;
                int combined = small.stack().getCount() + other.stack().getCount();
                if (combined > other.stack().getMaxStackSize()) continue;
                // 挑余量最大的一堆放进去；同余量取格号小的，顺序稳定可复现。
                if (best == null || room(other) > room(best)
                        || (room(other) == room(best) && other.slot() < best.slot())) {
                    best = other;
                }
            }
            if (best != null) {
                return Optional.of(new MergePair(small.slot(), best.slot(), small.stack(),
                        BuiltInRegistries.ITEM.getKey(small.stack().getItem()).toString()));
            }
        }
        return Optional.empty();
    }

    private static int room(SlotStack stack) {
        return stack.stack().getMaxStackSize() - stack.stack().getCount();
    }

    /** 背包里非空的一格：格号加这一格的样子。 */
    record SlotStack(int slot, ItemStack stack) {}

    /** 选中的一对：source 整份并进 target，腾出 source 那格。 */
    record MergePair(int sourceSlot, int targetSlot, ItemStack stack, String itemId) {}
}
