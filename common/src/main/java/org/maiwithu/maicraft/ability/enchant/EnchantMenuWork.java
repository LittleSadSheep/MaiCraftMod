// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.enchant;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.game.menu.MenuConfirmation;
import org.maiwithu.maicraft.game.menu.PendingMenuAction;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 附魔台界面里的一段活：认槽 → 装一件待附魔物品与恰好 tier 个青金石 → 等报价稳定冻结 →
 * 按第 tier 档按钮 → 以「成品与费用同刻吻合」确认 → 成品与剩余青金石搬回 → 核对 → 关上。
 *
 * <p>每刻最多一下点击或一次按钮；每笔搬运点完按两侧读数核对，对不上重点最多两次；
 * 按钮被游戏明确拒绝就全数归还、重新装料再看报价再试一次；结果不明的提交记进 unconfirmed 绝不重发。
 * 打开者负责关闭：没做完就被收尾时界面照规矩请游戏关上，装进去的东西随原版关闭流程回背包。
 */
final class EnchantMenuWork implements Action {

    /** 报价要连续稳定这么多刻才冻结：三档数字与线索随服务端分刻到达，等齐再抄。 */
    private static final int OFFER_STABLE_TICKS = 15;
    /** 一笔搬运点完后等两侧同步的宽限；过了角色侧还纹丝不动才算没搬动。 */
    private static final int SETTLE_TICKS = 5;
    /** 搬运或报价对不上时重点重读的次数：同步偶尔滞后，不无限重试。 */
    private static final int RETRIES = 2;
    /** 按钮按下后等成品与费用分包抵达的期限；等级与经验的变化可能比成品晚到。 */
    private static final int BUTTON_TIMEOUT_TICKS = 400;
    /** 青金石的注册 ID。 */
    private static final String LAPIS = "minecraft:lapis_lazuli";

    /** 界面里的次序。 */
    private enum Step { CLAIM, LOAD_ITEM, LOAD_LAPIS, FREEZE, SUBMIT, TAKE_BACK, RETRIEVE, CLOSING }

    private final OpenedMenu menu;
    private final EnchantMenuFacts reads;
    private final String itemWanted;
    private final int tier;
    private final TaskRecords records;

    private Step step = Step.CLAIM;
    /** 冻结的三档报价；按钮按下前后都按它核对现场。 */
    private EnchantQuote quote;
    /** 装进去时的原物快照与装料前的背包按物品计数：取回核对的基线。 */
    private ItemFacts original = ItemFacts.EMPTY;
    private ItemFacts result = ItemFacts.EMPTY;
    private Map<String, Integer> backpackBefore = Map.of();
    /** 正在点的一笔：要点哪几下、期望工作槽变成几件。 */
    private final Deque<WorkslotMoves.Click> pressing = new ArrayDeque<>();
    private int expectedCount;
    private int quietTicks;
    private int moveRetries;
    private int offerStableTicks;
    private int offerRefreshes;
    /** 按钮的等待记录；「游戏明确拒绝」归还重装后再试一次。 */
    private PendingMenuAction buttonPending;
    private boolean retriedAfterRefusal;
    private Action closing;
    private Problem failure;
    private int settledLapis;
    private int spentLevels;
    private int spentLapis;

    /** 结果：冻结的报价、实际附上的全部条目与等级、实扣的青金石。 */
    private List<String> enchantments = List.of();

    EnchantMenuWork(OpenedMenu menu, EnchantMenuFacts reads, String itemWanted, int tier,
                    TaskRecords records) {
        this.menu = Objects.requireNonNull(menu, "menu");
        this.reads = Objects.requireNonNull(reads, "reads");
        this.itemWanted = Objects.requireNonNull(itemWanted, "itemWanted");
        this.tier = tier;
        this.records = Objects.requireNonNull(records, "records");
    }

    /** 冻结的三档报价；没等到报价就结束的为 null。 */
    EnchantQuote quote() {
        return quote;
    }

    /** 实际附上的全部条目与等级；只有按钮确认后才不是空，报价线索不冒充结果。 */
    List<String> enchantments() {
        return enchantments;
    }

    int spentLevels() {
        return spentLevels;
    }

    int spentLapis() {
        return spentLapis;
    }

    @Override public ActionStatus tick(TickContext tick) {
        if (failure != null) return ActionStatus.failed(failure);
        return switch (step) {
            case CLAIM -> claim();
            case LOAD_ITEM -> loadOne(tick, 0, itemWanted);
            case LOAD_LAPIS -> loadLapis(tick);
            case FREEZE -> freeze();
            case SUBMIT -> submit(tick);
            case TAKE_BACK -> takeBack();
            case RETRIEVE -> retrieve();
            case CLOSING -> closeMenu(tick);
        };
    }

    // 认槽：两格工作槽与光标都必须是空的——不挪走不属于自己的东西。
    private ActionStatus claim() {
        var reading = menu.reading();
        if (reading.isEmpty()) return readingLost();
        MenuContent.Reading open = reading.get();
        if (open.containerSnapshots().size() < 2) {
            return fail(Problem.of(Problem.Kind.UNSUPPORTED, "点开的界面两侧对不上附魔台的样子，一格都不点"));
        }
        if (!open.containerSnapshots().get(0).isEmpty() || !open.containerSnapshots().get(1).isEmpty()
                || !menu.cursorEmpty()) {
            return fail(Problem.of(Problem.Kind.WRONG_TIME, "附魔台的工作槽或光标上已经有别人的东西，不认领、不挪走"));
        }
        backpackBefore = countsFrom(open.playerSnapshots());
        step = Step.LOAD_ITEM;
        return ActionStatus.progressed();
    }

    /** 装一件进工作槽：从角色侧找出装这种东西的格，拿起、右键放一件、余量放回，点完核对。 */
    private ActionStatus loadOne(TickContext tick, int workSlot, String itemId) {
        var reading = menu.reading();
        if (reading.isEmpty()) return readingLost();
        MenuContent.Reading open = reading.get();
        if (menu.busy()) return ActionStatus.running();
        if (!pressing.isEmpty()) {
            WorkslotMoves.Click click = pressing.peek();
            boolean sent = menu.click(click.slotId(), click.button());
            if (sent) pressing.poll();
            return sent ? ActionStatus.progressed() : ActionStatus.running();
        }
        int now = open.containerSnapshots().get(workSlot).count();
        if (expectedCount > 0) {
            // 一笔的几下点完了：等两侧同步，再核对工作槽精确到位。
            if ((now != expectedCount || !menu.cursorEmpty()) && ++quietTicks <= SETTLE_TICKS) {
                return ActionStatus.running();
            }
            if (now != expectedCount || !menu.cursorEmpty()) {
                if (++moveRetries > RETRIES) {
                    return fail(Problem.of(Problem.Kind.STUCK,
                            "往工作槽装东西没核对上（要 " + expectedCount + " 件、现在是 " + now + " 件）"));
                }
                quietTicks = 0;
                expectedCount = 0;
                return ActionStatus.running();
            }
            expectedCount = 0;
            quietTicks = 0;
            if (workSlot == 0) original = ItemFacts.of(open.containerSnapshots().get(0).stack());
            step = Step.LOAD_LAPIS;
            return ActionStatus.progressed();
        }
        int from = findCarryingSlot(open, itemId);
        if (from < 0) {
            return fail(Problem.of(Problem.Kind.NEED_ITEM,
                    workSlot == 0 ? "身上没有可附魔的「" + itemId + "」" : "身上没有足够的青金石"));
        }
        expectedCount = now + 1;
        int fromSlotId = open.playerSlotIds().get(from);
        int fromCount = open.playerSnapshots().get(from).count();
        pressing.addAll(WorkslotMoves.load(fromSlotId, workSlot, 1, fromCount));
        return ActionStatus.progressed();
    }

    // 青金石一件一件精确搬，绝不整堆超额：装够 tier 个进 FREEZE。
    private ActionStatus loadLapis(TickContext tick) {
        var reading = menu.reading();
        if (reading.isEmpty()) return readingLost();
        if (reading.get().containerSnapshots().get(1).count() >= tier) {
            step = Step.FREEZE;
            return ActionStatus.progressed();
        }
        return loadOne(tick, 1, LAPIS);
    }

    // 等报价稳定：连续多个刻读数一致才冻结，不拿还没到齐的数字按按钮。
    private ActionStatus freeze() {
        EnchantMenuFacts.Snapshot snapshot = reads.readMenu();
        if (snapshot == null) return menuChanged();
        if (quote != null && quote.stillMatches(snapshot)) {
            // 报价稳住了就去按：原先这里只报了进展、没换到提交那一步，一直停在等报价稳定上，
            // 实机 10-14 剑和青金石放在台子上十几分钟一动不动。
            if (++offerStableTicks >= OFFER_STABLE_TICKS) {
                step = Step.SUBMIT;
                return ActionStatus.progressed();
            }
            return ActionStatus.running();
        }
        quote = EnchantQuote.freeze(snapshot);
        offerStableTicks = 0;
        return ActionStatus.running();
    }

    // 提交前再核一遍报价没变（等级、随机种子、线索、工作槽都对得上），变了就重读再等稳定。
    private ActionStatus submit(TickContext tick) {
        EnchantMenuFacts.Snapshot snapshot = reads.readMenu();
        if (snapshot == null) return menuChanged();
        if (buttonPending == null) {
            if (!quote.stillMatches(snapshot)) {
                if (++offerRefreshes > RETRIES) {
                    return fail(Problem.of(Problem.Kind.STUCK, "报价一直在变，没法按确定的档提交"));
                }
                offerStableTicks = 0;
                step = Step.FREEZE;
                return ActionStatus.running();
            }
            TierOffer offer = quote.offer(tier);
            if (!offer.usable(snapshot.creative(), snapshot.playerLevel())) {
                return fail(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                        "第 " + tier + " 档选不了：门槛 " + offer.thresholdLevel() + " 级、身上 " + snapshot.playerLevel()
                                + " 级" + (offer.clue().isEmpty() ? "，这一档没有附魔线索" : "，线索是 " + offer.clue())));
            }
            spentLevels = offer.levelsSpent();
            spentLapis = offer.lapisCost();
            settledLapis = quote.lapisCount();
            buttonPending = tick.player().menuActions().pressButton(tick.player(), tier - 1,
                    buttonConfirmation(offer), BUTTON_TIMEOUT_TICKS);
            return ActionStatus.running();
        }
        buttonPending = tick.player().menuActions().poll(tick.player(), buttonPending);
        if (!buttonPending.terminal()) return ActionStatus.running();
        PendingMenuAction settled = buttonPending;
        buttonPending = null;
        switch (settled.status()) {
            case CONFIRMED_APPLIED -> {
                // 只有按钮确认后才列附魔条目：报价线索不冒充最终结果。
                enchantments = List.copyOf(resultFacts().enchantmentLines());
                result = resultFacts();
                step = Step.RETRIEVE;
                return ActionStatus.progressed();
            }
            case CONFIRMED_NOT_APPLIED -> {
                // 游戏明确说按钮没执行：原物与青金石全数归还，重新装料再看报价再试一次，再不行如实结束。
                if (retriedAfterRefusal) {
                    records.unconfirmed(new Change(Change.Kind.OTHER, "附魔按钮", 1,
                            "第二次按也没执行，装进去的东西随界面收尾回到背包"));
                    return fail(Problem.of(Problem.Kind.REFUSED_BY_GAME,
                            "游戏两次都拒绝了第 " + tier + " 档按钮"));
                }
                retriedAfterRefusal = true;
                step = Step.TAKE_BACK;
                return ActionStatus.progressed();
            }
            default -> {
                // 结果不明：记进 unconfirmed，界面照常收尾（东西回背包），绝不重发。
                records.unconfirmed(new Change(Change.Kind.OTHER, "附魔按钮", 1,
                        "按了但没能确认成品与费用：" + settled.detail()));
                return fail(Problem.of(Problem.Kind.STUCK, "附魔按钮的结果没能确认，不再重发"));
            }
        }
    }

    // 归还：两格工作槽整堆搬回背包，搬空后重新装料再走一遍报价。
    private ActionStatus takeBack() {
        var reading = menu.reading();
        if (reading.isEmpty()) return readingLost();
        if (quickMoveWorkslot(reading.get())) return ActionStatus.progressed();
        if (!reading.get().containerSnapshots().get(0).isEmpty()
                || !reading.get().containerSnapshots().get(1).isEmpty()) return ActionStatus.running();
        // 归还完重新来：装料、看报价（每次附魔后种子会变，报价本来就是新的）。
        original = ItemFacts.EMPTY;
        quote = null;
        offerStableTicks = 0;
        backpackBefore = countsFrom(reading.get().playerSnapshots());
        step = Step.LOAD_ITEM;
        return ActionStatus.progressed();
    }

    // 取回：成品与剩余青金石搬回背包，核对「原物减一、成品加一、青金石实扣」。
    private ActionStatus retrieve() {
        var reading = menu.reading();
        if (reading.isEmpty()) return readingLost();
        if (quickMoveWorkslot(reading.get())) return ActionStatus.progressed();
        if (!reading.get().containerSnapshots().get(0).isEmpty()
                || !reading.get().containerSnapshots().get(1).isEmpty()) return ActionStatus.running();
        // 工作槽空了再等两刻：背包侧的同步偶尔比工作槽晚到，别把没到的当丢账。
        if (++quietTicks < 2) return ActionStatus.running();
        Map<String, Integer> after = countsFrom(reading.get().playerSnapshots());
        int originalBefore = backpackBefore.getOrDefault(original.itemId(), 0);
        int lapisBefore = backpackBefore.getOrDefault(LAPIS, 0);
        boolean originalGone = after.getOrDefault(original.itemId(), 0) == originalBefore - 1;
        boolean lapisSpent = after.getOrDefault(LAPIS, 0) == lapisBefore - spentLapis;
        boolean resultIn = after.getOrDefault(result.itemId(), 0)
                == backpackBefore.getOrDefault(result.itemId(), 0) + 1;
        if (!originalGone || !lapisSpent || !resultIn) {
            return fail(Problem.of(Problem.Kind.STUCK,
                    "取回的对不上：原物减一=" + originalGone + "、青金石实扣=" + lapisSpent + "、成品进包=" + resultIn));
        }
        records.change(Change.of(Change.Kind.ITEM_CONSUMED, original.itemId(), 1));
        if (spentLapis > 0) records.change(Change.of(Change.Kind.ITEM_CONSUMED, LAPIS, spentLapis));
        records.change(new Change(Change.Kind.ITEM_GAINED, result.itemId(), 1,
                "附上了 " + String.join("、", enchantments)));
        step = Step.CLOSING;
        return ActionStatus.progressed();
    }

    private ActionStatus closeMenu(TickContext tick) {
        if (closing == null) closing = menu.closing();
        ActionStatus status = closing.tick(tick);
        if (status instanceof ActionStatus.Running) return status;
        return ActionStatus.done();
    }

    /** 还没搬空的第一个工作槽整堆快速移动一下；两个都空了返回假。 */
    private boolean quickMoveWorkslot(MenuContent.Reading reading) {
        if (menu.busy()) return true;
        for (int slot = 0; slot < 2; slot++) {
            if (!reading.containerSnapshots().get(slot).isEmpty()) {
                menu.quickMove(reading.containerSlotIds().get(slot));
                return true;
            }
        }
        return false;
    }

    /** 工作槽第 0 格此刻的事实（确认后的成品）。 */
    private ItemFacts resultFacts() {
        var reading = menu.reading();
        if (reading.isEmpty()) return ItemFacts.EMPTY;
        SlotSnapshot snapshot = reading.get().containerSnapshots().get(0);
        return snapshot.isEmpty() ? ItemFacts.EMPTY : ItemFacts.of(snapshot.stack());
    }

    /** 角色侧找装着这种东西的一格（返回 playerSnapshots 下标）；没有返回 -1。 */
    private int findCarryingSlot(MenuContent.Reading reading, String itemId) {
        for (int i = 0; i < reading.playerSnapshots().size(); i++) {
            SlotSnapshot snapshot = reading.playerSnapshots().get(i);
            if (snapshot.isEmpty()) continue;
            if (ItemFacts.of(snapshot.stack()).itemId().equals(itemId)) return i;
        }
        return -1;
    }

    private Map<String, Integer> countsFrom(List<SlotSnapshot> snapshots) {
        Map<String, Integer> counts = new HashMap<>();
        for (SlotSnapshot snapshot : snapshots) {
            if (snapshot.isEmpty()) continue;
            counts.merge(ItemFacts.of(snapshot.stack()).itemId(), snapshot.count(), Integer::sum);
        }
        return counts;
    }

    // 成品与费用同刻吻合的确认：判据来自成品判据，费用按冻结报价核对；分包抵达就继续等。
    private MenuConfirmation buttonConfirmation(TierOffer offer) {
        int lapisAfter = settledLapis - tier;
        int levelAfter = quote.playerLevel() - spentLevels;
        return (context, pending) -> {
            EnchantMenuFacts.Snapshot now = reads.readMenu();
            if (now == null) return MenuConfirmation.Verdict.DIVERGED;
            return switch (EnchantedResultCheck.judgeWithCost(now, original, offer, lapisAfter, levelAfter)) {
                case CONFIRMED -> MenuConfirmation.Verdict.APPLIED;
                case PENDING -> MenuConfirmation.Verdict.PENDING;
                case DIVERGED -> MenuConfirmation.Verdict.DIVERGED;
            };
        };
    }

    private ActionStatus readingLost() {
        return fail(Problem.of(Problem.Kind.TARGET_GONE, "附魔台界面被关掉或被别的界面顶掉了"));
    }

    private ActionStatus menuChanged() {
        return fail(Problem.of(Problem.Kind.TARGET_GONE, "附魔台界面被换掉了，不点不属于自己的界面"));
    }

    private ActionStatus fail(Problem problem) {
        failure = problem;
        return ActionStatus.failed(problem);
    }

    // 光标上拿着东西的那几下停不得；按钮等确认时同样不安全，其余是正常干活。
    @Override public Interruptibility interruptibility() {
        if (buttonPending != null && !buttonPending.terminal()) return Interruptibility.UNSAFE_TO_STOP;
        if (!menu.cursorEmpty()) return Interruptibility.UNSAFE_TO_STOP;
        return Interruptibility.WORKING;
    }

    @Override public void pause() {
        if (closing != null) closing.pause();
    }

    // 收尾：正在点的笔不再点（没点出去的不欠账），界面照 M11 请游戏关上，装进去的东西随原版关闭流程回背包。
    @Override public void close() {
        if (closing != null) closing.close();
        menu.abandon();
    }

    @Override public String describe() {
        return "在附魔台前：装料、看三档报价、按第 " + tier + " 档、取回成品";
    }
}
