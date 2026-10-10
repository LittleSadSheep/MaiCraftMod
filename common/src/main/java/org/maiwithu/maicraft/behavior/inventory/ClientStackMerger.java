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
import org.maiwithu.maicraft.kernel.task.ReportsUnconfirmed;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 合并散堆执行接缝的读端：在背包界面里把同一种物品散在两格的东西并成一堆，腾出一格。
 *
 * <p>一次调用并一对：把数量小的那堆拿起（整份到光标上），点在还有余量的那堆上并进去，
 * 每下点击都等游戏确认（光标拿到的、源格空了的）才走下一步，并完把界面关上。
 * 配对挑"最小的并进装得下它的最大堆"，与腾地方计划里"能并出几格"的算法一致，计划才不空头。
 * 点击点出去了却没等到结果，照交回接缝原样交回，不盲目再点一次；收尾时界面还开着就请游戏关上，
 * 光标上的东西由关闭流程还回背包。
 */
public final class ClientStackMerger implements StackMerger, ReportsUnconfirmed, AbandonsStep {

    /** 等一步确认的期限；到点按没能确认收场，不盲目重做。 */
    private static final int STEP_TIMEOUT_TICKS = 60;
    /** 一次合并的总预算（刻）：开界面加两下点击加关界面用不了这么久，到点按做不了收场。 */
    private static final int ATTEMPT_BUDGET_TICKS = 300;
    /** 两次调用隔了这么久就算"这一步没人管了"：现场可能变了，撒手旧的、重新看背包。 */
    private static final long STALE_AFTER_TICKS = 200;

    /** 合并的先后顺序。 */
    private enum Stage { IDLE, OPENING, PICKING, PLACING, CLOSING }

    private final Supplier<PlayerContext> contexts;

    private Stage stage = Stage.IDLE;
    private int sourceInventorySlot;
    private int sourceMenuSlot;
    private int targetMenuSlot;
    /** 动手前源格里那份的样子：确认条件与"拿到的对不对"都以它为基准。 */
    private ItemStack sourceBefore = ItemStack.EMPTY;
    private int sourceCount;
    private String itemId = "";
    private PendingMenuAction pending;
    /** 这一次合并的起点刻，算总预算用。 */
    private long attemptStartTick;
    /** 上一次推进的时刻，隔太久就撒手重来。 */
    private long lastDrivenTick = Long.MIN_VALUE;
    /** 这一次做不了的原因；返回一次就清掉，下一次调用重新看现场。 */
    private String failure;
    /** 点出去了却没能确认结果的交互，一句一条。 */
    private final List<String> unconfirmed = new ArrayList<>();

    public ClientStackMerger(Supplier<PlayerContext> contexts) {
        this.contexts = Objects.requireNonNull(contexts, "contexts");
    }

    @Override
    public SpaceStepResult mergeOne(TickContext tick) {
        if (failure != null) {
            String why = failure;
            resetAttempt();
            return SpaceStepResult.cannotDo(why);
        }
        PlayerContext player = tick.player();
        if (player == null || player.localPlayer() == null) {
            return SpaceStepResult.cannotDo("现在没有角色，合并不了散堆");
        }
        // 隔了太久才被再次调用：中间现场可能变了（被打断、界面被别人动过），撒手旧的重新来。
        if (lastDrivenTick != Long.MIN_VALUE && tick.gameTick() - lastDrivenTick > STALE_AFTER_TICKS) {
            abandonStep();
        }
        lastDrivenTick = tick.gameTick();
        if (stage == Stage.IDLE) {
            startAttempt(player, tick.gameTick());
            if (failure != null) {
                String why = failure;
                resetAttempt();
                return SpaceStepResult.cannotDo(why);
            }
        }
        if (tick.gameTick() - attemptStartTick > ATTEMPT_BUDGET_TICKS) {
            return giveUp("合并散堆超时，界面可能没就绪");
        }
        return switch (stage) {
            case IDLE -> SpaceStepResult.WORKING;
            case OPENING -> opening(player);
            case PICKING, PLACING -> advancingClick(player);
            case CLOSING -> closing(player);
        };
    }

    // 挑一对能并的散堆，定下从哪格并进哪格。
    private void startAttempt(PlayerContext player, long gameTick) {
        Optional<MergePair> found = findMergeablePair(filledSlots(player.localPlayer()));
        if (found.isEmpty()) {
            failure = "没有能并成一堆的散堆";
            return;
        }
        MergePair pair = found.get();
        sourceInventorySlot = pair.sourceSlot();
        sourceMenuSlot = menuSlot(pair.sourceSlot());
        targetMenuSlot = menuSlot(pair.targetSlot());
        sourceBefore = pair.stack().copy();
        sourceCount = pair.stack().getCount();
        itemId = pair.itemId();
        attemptStartTick = gameTick;
        stage = Stage.OPENING;
    }

    // 打开背包界面；开好了就点第一下——把源格整份拿到光标上。
    private SpaceStepResult opening(PlayerContext player) {
        MenuActions actions = player.menuActions();
        if (!actions.ensureVisible(player)) return SpaceStepResult.WORKING;
        pending = actions.click(player, sourceMenuSlot, 0, ClickType.PICKUP, pickedUpConfirmation(), STEP_TIMEOUT_TICKS);
        stage = Stage.PICKING;
        return SpaceStepResult.WORKING;
    }

    // 推进拿起或并进去的那一下：确认了走下一步，没确认的交回一句、按做不了收场。
    private SpaceStepResult advancingClick(PlayerContext player) {
        if (!pending.terminal()) {
            pending = player.menuActions().poll(player, pending);
            return SpaceStepResult.WORKING;
        }
        boolean picking = stage == Stage.PICKING;
        String what = picking ? "拿起" : "并进另一格";
        String why;
        switch (pending.status()) {
            case CONFIRMED_APPLIED -> {
                pending = picking
                        ? player.menuActions().click(player, targetMenuSlot, 0, ClickType.PICKUP,
                                placedConfirmation(), STEP_TIMEOUT_TICKS)
                        : player.menuActions().close(player, STEP_TIMEOUT_TICKS);
                stage = picking ? Stage.PLACING : Stage.CLOSING;
                return SpaceStepResult.WORKING;
            }
            case CONFIRMED_NOT_APPLIED -> why = "把 " + itemId + " " + what + "没能做成：游戏没有接受这次点击";
            default -> {
                unconfirmed.add("合并散堆（" + itemId + "）：" + what + "的那一下点出去了，没等到确认结果");
                why = "把 " + itemId + " " + what + "没能确认，不盲目再点";
            }
        }
        resetAttempt();
        return SpaceStepResult.cannotDo(why);
    }

    // 等背包界面关上；关上了这一格就腾出来了。
    private SpaceStepResult closing(PlayerContext player) {
        if (!pending.terminal()) {
            pending = player.menuActions().poll(player, pending);
            return SpaceStepResult.WORKING;
        }
        // 合并本身已经确认过：界面关没关利索不挡腾格子，剩下的关闭由菜单入口自己推进。
        return SpaceStepResult.done(new Change(Change.Kind.OTHER, itemId, sourceCount,
                "把散着的 " + itemId + " 并成整堆，腾出一格"));
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

    @Override
    public List<String> unconfirmedFacts() {
        return List.copyOf(unconfirmed);
    }

    @Override
    public void abandonStep() {
        PlayerContext player = contexts.get();
        if (player != null && player.localPlayer() != null && stage != Stage.IDLE) {
            // 界面可能开着、光标上可能还拿着东西：请游戏关一次，光标上的东西由关闭流程还回背包。
            try {
                player.menuActions().closeForTaskBoundary(player, STEP_TIMEOUT_TICKS, "腾地方收尾");
            } catch (RuntimeException busy) {
                // 界面入口此刻不接受关闭：留给下一步动作的"先关旧界面"处理，这里不硬抢。
            }
        }
        resetAttempt();
    }

    // 回到起点：下一次调用重新看背包、重新挑对。
    private void resetAttempt() {
        stage = Stage.IDLE;
        sourceBefore = ItemStack.EMPTY;
        sourceCount = 0;
        itemId = "";
        pending = null;
        failure = null;
        lastDrivenTick = Long.MIN_VALUE;
    }

    // 一次合并做不了的统一收场：原因说清，回到起点，下一次调用重新看现场。
    private SpaceStepResult giveUp(String why) {
        resetAttempt();
        return SpaceStepResult.cannotDo(why);
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
