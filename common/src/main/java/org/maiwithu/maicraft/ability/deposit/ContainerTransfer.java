// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.deposit;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.registries.BuiltInRegistries;

import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.behavior.menu.TransferPlan;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 往一只开着的容器里搬：按存什么的顺序一堆堆搬，每一下等游戏确认，再按两侧同种东西的总数前后对照结算。
 *
 * <p>整堆用快速移动；只存一部分时按搬运计划拆成半堆，明确放进一格（快速移动会把整堆多搬走）。
 * 先看容器里放不放得下再动手，不把东西拿在光标上再找地方放。背包少了几件、容器多了几件，
 * 两边对上才记账；点了没动是这只放不下了，换下一样或下一只；对不上的记进未确认，不凑数。
 * 被打断时请游戏关上界面（光标上的东西由原版还回背包），恢复后由任务重新点开接着存；
 * 关界面前没来得及结算的那一笔交给重新点开后的这一趟，按同一只容器两侧的总数补结算。
 */
final class ContainerTransfer implements Action {

    /** 一下点击结清后，等两侧内容同步过来的宽限（刻）；过了还纹丝不动就是放不下。 */
    static final int SETTLE_TICKS = 5;

    /** 搬运的账：还要存几件、确认存进了几件、哪一下对不上。 */
    interface Ledger {
        /** 这种东西还要存几件。 */
        int left(String itemId);

        /** 确认存进了这么多。 */
        void stored(String itemId, int amount);

        /** 点了但对不上账的一下，原样记下。 */
        void unconfirmed(String fact);
    }

    /** 一下原版鼠标操作：在哪一格、快速移动还是左右键。 */
    private record Click(int slotId, boolean quickMove, int button, boolean takesToCursor) {}

    /** 正在结算的一笔：搬哪种东西、动手前两侧各有几件、还剩哪几下没点；carried 表示是上一趟留下来补结算的。 */
    private record Move(String itemId, int playerBefore, int containerBefore, Deque<Click> clicks, boolean carried) {}

    /** 界面被关掉时还没结算的一笔：重新点开同一只容器后按两侧总数补结算。 */
    record Unsettled(String itemId, int playerBefore, int containerBefore) {}

    private final OpenedMenu menu;
    private final List<String> order;
    private final Ledger ledger;
    /** 这只容器里放不下的东西：不再往这只里塞。 */
    private final Set<String> full = new HashSet<>();
    private Move inFlight;
    private int quietTicks;
    private boolean finished;
    /** 界面被关掉了（被打断、被别人关）：这一趟到此为止，没结算的那一笔交给下一趟。 */
    private boolean menuLost;
    private Map<String, Integer> contentsAfter;

    /**
     * @param menu      点开并同步完的那一份界面
     * @param order     要存的东西，按存什么的顺序
     * @param ledger    搬运的账
     * @param carryOver 上一趟界面被关掉时没结算的那一笔；没有为 null
     */
    ContainerTransfer(OpenedMenu menu, List<String> order, Ledger ledger, Unsettled carryOver) {
        this.menu = menu;
        this.order = List.copyOf(order);
        this.ledger = ledger;
        if (carryOver != null) {
            // 关界面时光标上的东西已由原版还回背包，没点完的几下不再点，只按两侧总数补结算。
            inFlight = new Move(carryOver.itemId(), carryOver.playerBefore(), carryOver.containerBefore(),
                    new ArrayDeque<>(), true);
        }
    }

    /** 界面被关掉时还没结算的那一笔；这一趟不是因为界面被关掉而停的为空。 */
    Optional<Unsettled> unsettled() {
        return menuLost && inFlight != null
                ? Optional.of(new Unsettled(inFlight.itemId(), inFlight.playerBefore(), inFlight.containerBefore()))
                : Optional.empty();
    }

    /** 搬完时容器里有什么（物品 ID 到件数）；还没搬完为空。 */
    Optional<Map<String, Integer>> contentsAfter() {
        return Optional.ofNullable(contentsAfter);
    }

    /** 这只容器里放不下的东西。 */
    Set<String> full() {
        return Set.copyOf(full);
    }

    @Override public ActionStatus tick(TickContext context) {
        Optional<MenuContent.Reading> reading = menu.reading();
        if (reading.isEmpty()) {
            menuLost = true;
            return ActionStatus.failed(Problem.of(Problem.Kind.TARGET_GONE, "容器界面被关上了", null));
        }
        if (menu.busy()) {
            return ActionStatus.running();
        }
        if (inFlight != null) {
            if (!inFlight.clicks().isEmpty()) {
                press(inFlight.clicks().poll());
                return ActionStatus.progressed();
            }
            return settle(reading.get());
        }
        return startNext(reading.get());
    }

    // 挑下一样还要存、这只还放得下的东西，冻结两侧总数后点第一下；都没有了就是这只存完了。
    private ActionStatus startNext(MenuContent.Reading reading) {
        for (String itemId : order) {
            int left = ledger.left(itemId);
            if (left <= 0 || full.contains(itemId)) continue;
            int sourceIndex = firstOf(reading.playerSnapshots(), itemId);
            if (sourceIndex < 0) continue;
            SlotSnapshot source = reading.playerSnapshots().get(sourceIndex);
            Optional<Deque<Click>> clicks = clicksFor(reading, reading.playerSlotIds().get(sourceIndex), source, left);
            if (clicks.isEmpty()) {
                full.add(itemId);
                continue;
            }
            inFlight = new Move(itemId, total(reading.playerSnapshots(), itemId),
                    total(reading.containerSnapshots(), itemId), clicks.get(), false);
            quietTicks = 0;
            press(inFlight.clicks().poll());
            return ActionStatus.progressed();
        }
        finished = true;
        contentsAfter = merged(reading.containerSnapshots());
        return ActionStatus.done();
    }

    // 这一堆怎么点：要的不少于整堆就快速移动；只要一部分就拆半堆放进一格；容器里没地方放给空。
    private Optional<Deque<Click>> clicksFor(MenuContent.Reading reading, int sourceSlot, SlotSnapshot source, int left) {
        Deque<Click> clicks = new ArrayDeque<>();
        int maxStack = source.stack().getMaxStackSize();
        if (left >= source.count()) {
            if (!hasRoom(reading.containerSnapshots(), source, 1)) return Optional.empty();
            clicks.add(new Click(sourceSlot, true, 0, false));
            return Optional.of(clicks);
        }
        int targetIndex = roomyTarget(reading.containerSnapshots(), source, left);
        if (targetIndex < 0) return Optional.empty();
        int targetSlot = reading.containerSlotIds().get(targetIndex);
        for (TransferPlan.Step step : TransferPlan.exactAmount(source.count(), left, maxStack).steps()) {
            boolean onSource = step.where() == TransferPlan.Where.SOURCE;
            int slot = onSource ? sourceSlot : targetSlot;
            switch (step.kind()) {
                case TAKE_HALF -> clicks.add(new Click(slot, false, 1, true));
                case TAKE_ALL -> clicks.add(new Click(slot, false, 0, true));
                case GIVE_ALL -> clicks.add(new Click(slot, false, 0, false));
                case GIVE_ONE, PUT_BACK_ONE -> clicks.add(new Click(slot, false, 1, false));
                case QUICK_MOVE -> clicks.add(new Click(slot, true, 0, false));
            }
        }
        return Optional.of(clicks);
    }

    private void press(Click click) {
        if (click.quickMove()) {
            menu.quickMove(click.slotId());
            return;
        }
        // 从源格拿起东西前先告诉界面会话：中途被打断关界面时，光标上的东西放回这一格。
        if (click.takesToCursor()) menu.noteCursorTakenFrom(click.slotId());
        menu.click(click.slotId(), click.button());
    }

    // 结算这一笔：背包少的和容器多的对上才记账；点了没动是放不下；对不上记进未确认。
    private ActionStatus settle(MenuContent.Reading reading) {
        String itemId = inFlight.itemId();
        int out = inFlight.playerBefore() - total(reading.playerSnapshots(), itemId);
        int in = total(reading.containerSnapshots(), itemId) - inFlight.containerBefore();
        boolean untouched = out == 0 && in == 0;
        if ((untouched || !menu.cursorEmpty()) && ++quietTicks <= SETTLE_TICKS) {
            return ActionStatus.running();
        }
        if (untouched) {
            // 上一趟留下的那一笔两边都没动：那一下没点出去，不算放不下。
            if (!inFlight.carried()) full.add(itemId);
        } else if (out == in && out > 0 && menu.cursorEmpty()) {
            ledger.stored(itemId, out);
        } else {
            ledger.unconfirmed("往容器里搬 " + itemId + "：背包少了 " + out + " 件，容器多了 " + in
                    + " 件" + (menu.cursorEmpty() ? "" : "，光标上还拿着东西") + "，对不上账");
        }
        inFlight = null;
        return ActionStatus.progressed();
    }

    // 容器里放不放得下这么多：有空格，或同种同组件的堆还没满。
    private static boolean hasRoom(List<SlotSnapshot> container, SlotSnapshot item, int amount) {
        int max = item.stack().getMaxStackSize();
        for (SlotSnapshot slot : container) {
            if (slot.isEmpty() || slot.sameIdentity(item) && slot.count() + amount <= max) return true;
        }
        return false;
    }

    // 只放一部分时挑一格放得下全部的：先并进同种的堆，再占空格。
    private static int roomyTarget(List<SlotSnapshot> container, SlotSnapshot item, int amount) {
        int max = item.stack().getMaxStackSize();
        int empty = -1;
        for (int i = 0; i < container.size(); i++) {
            SlotSnapshot slot = container.get(i);
            if (slot.isEmpty()) {
                if (empty < 0) empty = i;
            } else if (slot.sameIdentity(item) && slot.count() + amount <= max) {
                return i;
            }
        }
        return empty;
    }

    private static int firstOf(List<SlotSnapshot> snapshots, String itemId) {
        for (int i = 0; i < snapshots.size(); i++) {
            if (!snapshots.get(i).isEmpty() && idOf(snapshots.get(i)).equals(itemId)) return i;
        }
        return -1;
    }

    private static int total(List<SlotSnapshot> snapshots, String itemId) {
        int sum = 0;
        for (SlotSnapshot snapshot : snapshots) {
            if (!snapshot.isEmpty() && idOf(snapshot).equals(itemId)) sum += snapshot.count();
        }
        return sum;
    }

    private static Map<String, Integer> merged(List<SlotSnapshot> snapshots) {
        Map<String, Integer> merged = new LinkedHashMap<>();
        for (SlotSnapshot snapshot : snapshots) {
            if (!snapshot.isEmpty()) merged.merge(idOf(snapshot), snapshot.count(), Integer::sum);
        }
        return merged;
    }

    private static String idOf(SlotSnapshot snapshot) {
        return BuiltInRegistries.ITEM.getKey(snapshot.stack().getItem()).toString();
    }

    // 光标上拿着东西的那一下停不得：撒手会把东西掉在界面外的流程里。
    @Override public Interruptibility interruptibility() {
        return inFlight != null && !menu.cursorEmpty() ? Interruptibility.UNSAFE_TO_STOP : Interruptibility.WORKING;
    }

    // 被生存需求打断：先请游戏关上界面，恢复后由任务重新点开接着存。
    @Override public void pause() {
        menu.abandon();
    }

    // 没搬完就收尾（被取消、角色没了）：这一笔说不清，记进未确认；界面请游戏关上。
    // 界面被关掉而停的，没结算的那一笔留给重新点开后的下一趟。
    @Override public void close() {
        if (finished || menuLost) return;
        if (inFlight != null) {
            ledger.unconfirmed("往容器里搬 " + inFlight.itemId() + " 的那一下还没结算就停下了");
            inFlight = null;
        }
        menu.abandon();
    }

    @Override public String describe() {
        return inFlight == null ? "往容器里搬东西" : "往容器里搬 " + inFlight.itemId();
    }
}
