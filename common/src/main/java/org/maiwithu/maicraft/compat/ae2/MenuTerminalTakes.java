// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.behavior.acquire.ItemRequest;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.behavior.menu.ClientMenuOpening;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MoveConfirmation;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.behavior.menu.TransferSettlement;
import org.maiwithu.maicraft.game.menu.MenuConfirmation;
import org.maiwithu.maicraft.game.menu.PendingMenuAction;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 从终端取货的生产实现：走到 ME 终端跟前、点在面板上打开（不点线缆芯）→ 核对是这台终端的界面、
 * 等网络连接状态与网络存货同步过来 → 按"缺一组以上整组取、不足一组一件一件取再放下"逐笔取 → 关上。
 *
 * <p>每一笔都等游戏确认：整组取看背包里多了几件，取一件看光标上多了一件，放下看光标空了、那一格多了。
 * 只记确认过的量（搬运结算），没动静就不再硬点，对不上的如实记下不凑数。
 * 拿走多少最终以引擎在动作结束后按身上的重新清点为准。关之前把这次看到的网络存货记下，下次报价用。
 * 收场时光标上还有东西，先放回背包（背包放不下就放回网络）再关界面，不留给关界面的流程去还——背包满了它会把东西丢在地上。
 */
public final class MenuTerminalTakes implements TerminalTakes {

    /** 一次取货的总期限（刻）：走过去加上最多六十几下单件取，一分钟足够；到点如实收场，不在一台终端上耗死。 */
    private static final long GIVE_UP_TICKS = 20L * 60;
    /** 点开后等网络存货表与连接状态同步过来的期限（刻）；等不到不把没同步的网络当成空的。 */
    private static final int STOCK_WAIT_TICKS = 40;
    /** 存货表出现后再等几刻（刻）：网络是空的或没连上时，同步到的就是空表，等过这一小会儿才下结论。 */
    private static final int STOCK_SETTLE_TICKS = 10;
    /** 一下取货等游戏确认的期限（刻）。 */
    private static final int TAKE_TIMEOUT_TICKS = 40;
    /** 放下光标那一下点完后，等两侧内容同步过来的宽限（刻）。 */
    private static final int PUT_DOWN_SETTLE_TICKS = 5;
    /** 收场前放好光标的期限（刻）：到点还没放好就照常关界面，光标上的东西交给游戏的关闭流程。 */
    private static final int CURSOR_TICKS = 40;

    private final Ae2Compat compat;
    private final PlayerServices services;
    private final SeenNetworkStock seen;
    private final Supplier<Instant> clock;

    public MenuTerminalTakes(Ae2Compat compat, PlayerServices services, SeenNetworkStock seen,
            Supplier<Instant> clock) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.services = Objects.requireNonNull(services, "services");
        this.seen = Objects.requireNonNull(seen, "seen");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override public Optional<Action> take(Ae2Terminals.Terminal terminal, String dimension, ItemRequest request,
            Permissions permissions) {
        return Optional.of(new TakeAction(terminal, dimension, request, permissions));
    }

    /** 从终端取货的先后顺序。 */
    private enum Stage { OPEN, SYNC, TAKE, CURSOR, CLOSE, DONE }

    /** 正在等确认的那一下是哪种取法。 */
    private enum Pending { STACK, ONE }

    /** 从终端取货的动作：点开 → 等同步 → 逐笔取 → 关上，跨刻推进。 */
    private final class TakeAction implements Action {

        private final Ae2Terminals.Terminal terminal;
        private final String dimension;
        private final ItemRequest request;
        private final ClientMenuOpening opening;
        private final TransferSettlement settlement = new TransferSettlement();
        private Stage stage = Stage.OPEN;
        private OpenedMenu menu;
        private Action closing;
        private long waited;
        private int syncWaited;
        private int settleWaited;
        /** 收场的原因：一件都没取到时按它失败；取到了就算做完。null 是正常收场（拿够了）。 */
        private Problem stopReason;

        // 正在等确认的一下：取法、取的那种东西、点之前角色那一侧与光标的样子。
        private PendingMenuAction pending;
        private Pending pendingKind;
        private SlotSnapshot pendingSample;
        private List<Integer> pendingSlotIds;
        private List<SlotSnapshot> pendingBefore;

        // 正在等结算的放下光标：放到哪一格、放之前光标与那一格的样子。
        private int putDownSlot = -1;
        private SlotSnapshot putDownCursorBefore;
        private SlotSnapshot putDownSlotBefore;
        private int putDownQuiet;

        // 收场前放好光标：已经等了几刻、正在等确认的"放回网络"那一下。
        private int cursorWaited;
        private PendingMenuAction putBackPending;

        TakeAction(Ae2Terminals.Terminal terminal, String dimension, ItemRequest request, Permissions permissions) {
            this.terminal = terminal;
            this.dimension = dimension;
            this.request = request;
            // 走过去能动多少地形按这次任务的许可来；靠近与右键都对准面板，不点线缆芯。
            this.opening = new ClientMenuOpening(terminal.block(), terminal.panel(), permissions,
                    services.menuLayouts(), services.approach(), services.interactions(), services.context());
        }

        @Override public ActionStatus tick(TickContext tick) {
            if (++waited > GIVE_UP_TICKS && stage.ordinal() < Stage.CURSOR.ordinal()) {
                stop(Problem.of(Problem.Kind.STUCK, "从 " + terminal.describe() + " 取货超时", null));
            }
            return switch (stage) {
                case OPEN -> open(tick);
                case SYNC -> sync(tick);
                case TAKE -> take(tick);
                case CURSOR -> clearCursor(tick);
                case CLOSE -> closeMenu(tick);
                case DONE -> result();
            };
        }

        // 走过去点开：走不到、点不开、界面认不出，都把打开动作的原因原样交回，引擎换路。
        private ActionStatus open(TickContext tick) {
            ActionStatus status = opening.tick(tick);
            if (!(status instanceof ActionStatus.Done)) return status;
            menu = opening.opened().orElseThrow();
            stage = Stage.SYNC;
            return ActionStatus.progressed();
        }

        // 核对是这台终端的界面，再等连接状态与网络存货同步过来；没连上网络就如实收场。
        private ActionStatus sync(TickContext tick) {
            PlayerContext player = tick.player();
            AbstractContainerMenu raw = player.localPlayer().containerMenu;
            ClientLevel level = player.level();
            if (menu.reading().isEmpty()) {
                return finishWithout(Problem.of(Problem.Kind.TARGET_GONE, "终端界面刚打开就被关上了", null));
            }
            if (!compat.menuBelongsTo(raw, level, terminal.block(), terminal.side())) {
                return stopNow(Problem.of(Problem.Kind.TARGET_GONE,
                        "点开的不是 " + terminal.describe() + " 的界面", null));
            }
            Optional<Boolean> linked = compat.networkLinked(raw);
            Optional<List<Ae2TerminalMenu.StockEntry>> stock = compat.networkStock(raw);
            if (linked.isEmpty() || stock.isEmpty()) {
                if (++syncWaited > STOCK_WAIT_TICKS) {
                    return stopNow(Problem.of(Problem.Kind.STUCK, "终端界面开了，网络存货一直没同步过来", null));
                }
                return ActionStatus.running();
            }
            // 连上了、也有货就不用再等；空表或没连上时多等一小会儿，免得把还没到的同步当成空网络。
            boolean clearlyReady = linked.get() && !stock.get().isEmpty();
            if (!clearlyReady && ++settleWaited <= STOCK_SETTLE_TICKS) return ActionStatus.running();
            seen.saw(dimension, terminal, linked.get(), stock.get(), clock.get());
            if (!linked.get()) {
                return stopNow(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                        terminal.describe() + " 没连上 ME 网络（没电或没频道），取不了", null));
            }
            stage = Stage.TAKE;
            return ActionStatus.progressed();
        }

        // 逐笔取：上一下还在等确认就本刻不动手；拿够、网络里没了、背包放不下就去关界面。
        private ActionStatus take(TickContext tick) {
            PlayerContext player = tick.player();
            AbstractContainerMenu raw = player.localPlayer().containerMenu;
            Optional<MenuContent.Reading> reading = menu.reading();
            if (reading.isEmpty()) {
                // 界面被合上了（打断或服务端关的）：取到多少由引擎清点，这里照常收场。
                return finishWithout(null);
            }
            if (pending != null) return awaitTake(player, raw, reading.get());
            if (putDownSlot >= 0) return awaitPutDown(raw);
            if (menu.busy()) return ActionStatus.running();

            List<Ae2TerminalMenu.StockEntry> stock = compat.networkStock(raw).orElse(List.of());
            SlotSnapshot cursor = SlotSnapshot.of(raw.getCarried());
            // 光标上挂着不是这次要取的东西：多半是有人动过光标，不是程序出错。不在上面叠，收场时由关界面放回背包。
            if (!cursor.isEmpty() && !wanted(cursor.stack())) {
                return stopNow(Problem.of(Problem.Kind.STUCK, "终端界面的光标上挂着 " + cursor.count() + " 个"
                        + itemId(cursor.stack()) + "，不是这次要取的，先停手", null));
            }
            Ae2TerminalMenu.StockEntry entry = cursor.isEmpty() ? firstWanted(stock) : entryFor(cursor, stock);
            if (entry == null) {
                return stopNow(nothingLeft());
            }
            int remaining = request.count() - confirmedTotal();
            NetworkTakePlan.Move move = NetworkTakePlan.next(remaining, cursor, entry,
                    reading.get().playerSlotIds(), reading.get().playerSnapshots());
            return switch (move) {
                case NetworkTakePlan.TakeStack takeStack -> submit(Pending.STACK, entry, reading.get(), raw,
                        () -> compat.takeStack(raw, takeStack.serial()));
                case NetworkTakePlan.TakeOne takeOne -> submit(Pending.ONE, entry, reading.get(), raw,
                        () -> compat.takeOne(raw, takeOne.serial()));
                case NetworkTakePlan.PutDown putDown -> putDown(putDown.slotId(), cursor, raw);
                case NetworkTakePlan.NoRoom noRoom -> stopNow(Problem.of(Problem.Kind.INVENTORY_FULL,
                        "背包放不下从 " + terminal.describe() + " 取的" + request.wanted().describe(), null));
                case NetworkTakePlan.Done done -> stopNow(remaining > 0 ? nothingLeft() : null);
            };
        }

        // 发一下取货：本刻发不出去（上一下没结清、界面没画好、没有交互机会）就下一刻再试。
        private ActionStatus submit(Pending kind, Ae2TerminalMenu.StockEntry entry, MenuContent.Reading reading,
                AbstractContainerMenu raw, Runnable send) {
            SlotSnapshot sample = SlotSnapshot.of(entry.sample());
            List<Integer> slotIds = reading.playerSlotIds();
            List<SlotSnapshot> before = reading.playerSnapshots();
            SlotSnapshot cursorBefore = SlotSnapshot.of(raw.getCarried());
            MenuConfirmation confirmation = (context, ignored) -> {
                AbstractContainerMenu now = context.localPlayer().containerMenu;
                NetworkTakeConfirmation.Result result = kind == Pending.STACK
                        ? NetworkTakeConfirmation.stackTaken(sample, before, snapshots(now, slotIds),
                                SlotSnapshot.of(now.getCarried()))
                        : NetworkTakeConfirmation.oneTaken(sample, cursorBefore, SlotSnapshot.of(now.getCarried()));
                return menuVerdict(result.verdict());
            };
            String what = (kind == Pending.STACK ? "从 ME 终端整组取 " : "从 ME 终端取一件 ") + itemId(entry.sample());
            Optional<PendingMenuAction> sent = menu.submitModAction(what, send, confirmation, TAKE_TIMEOUT_TICKS);
            if (sent.isEmpty()) return ActionStatus.running();
            pending = sent.get();
            pendingKind = kind;
            pendingSample = sample;
            pendingSlotIds = slotIds;
            pendingBefore = before;
            return ActionStatus.progressed();
        }

        // 等那一下取货的结论：整组取确认了就记账；取一件确认了东西在光标上，放下时再记；没动静或对不上就收场。
        private ActionStatus awaitTake(PlayerContext player, AbstractContainerMenu raw, MenuContent.Reading reading) {
            pending = player.menuActions().poll(player, pending);
            if (!pending.terminal()) return ActionStatus.running();
            PendingMenuAction settled = pending;
            pending = null;
            String item = itemId(pendingSample.stack());
            switch (settled.status()) {
                case CONFIRMED_APPLIED -> {
                    if (pendingKind == Pending.STACK) {
                        NetworkTakeConfirmation.Result result = NetworkTakeConfirmation.stackTaken(pendingSample,
                                pendingBefore, snapshots(raw, pendingSlotIds), SlotSnapshot.of(raw.getCarried()));
                        if (result.amount() > 0) settlement.confirm(item, result.amount());
                    }
                    return ActionStatus.progressed();
                }
                case CONFIRMED_NOT_APPLIED -> {
                    // 点了没动静：背包放不下、网络刚断、或这种东西刚被别人取走。不再硬点。
                    return stopNow(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                            "在 " + terminal.describe() + " 取" + item + "没取到（背包放不下、网络断了或刚被别人取走）",
                            null));
                }
                default -> {
                    settlement.unconfirmed("从 " + terminal.describe() + " 取" + item + "的一下没能确认："
                            + settled.detail());
                    return stopNow(Problem.of(Problem.Kind.STUCK,
                            "从 " + terminal.describe() + " 取" + item + "的一下没能确认", null));
                }
            }
        }

        // 把光标上的东西放进那一格：普通点格子；之后按光标空了、那一格多了核对。
        // 本刻点不出去（刚取过货、界面还没重新画好）就下一刻再点，不算点过。
        private ActionStatus putDown(int slotId, SlotSnapshot cursor, AbstractContainerMenu raw) {
            SlotSnapshot slotBefore = SlotSnapshot.of(raw.getSlot(slotId).getItem());
            if (!menu.click(slotId, 0)) return ActionStatus.running();
            putDownSlot = slotId;
            putDownCursorBefore = cursor;
            putDownSlotBefore = slotBefore;
            putDownQuiet = 0;
            return ActionStatus.progressed();
        }

        // 等放下的结论：确认了就记账；纹丝不动多等几刻，还不动就回到计划重新点；对不上如实记下收场。
        private ActionStatus awaitPutDown(AbstractContainerMenu raw) {
            if (menu.busy()) return ActionStatus.running();
            NetworkTakeConfirmation.Result result = NetworkTakeConfirmation.putDown(putDownCursorBefore,
                    putDownSlotBefore, SlotSnapshot.of(raw.getCarried()), SlotSnapshot.of(raw.getSlot(putDownSlot).getItem()));
            String item = itemId(putDownCursorBefore.stack());
            switch (result.verdict()) {
                case CONFIRMED -> {
                    settlement.confirm(item, result.amount());
                    putDownSlot = -1;
                    return ActionStatus.progressed();
                }
                case WAITING -> {
                    if (++putDownQuiet <= PUT_DOWN_SETTLE_TICKS) return ActionStatus.running();
                    // 点出去了却一直没动静：回到计划，下一刻按现场重新决定放哪一格。
                    putDownSlot = -1;
                    return ActionStatus.running();
                }
                default -> {
                    putDownSlot = -1;
                    settlement.unconfirmed("把光标上的" + item + "放进背包时两边对不上");
                    // 已经在收场放光标时对不上：不再折腾，直接去关界面。
                    if (stage == Stage.CURSOR) {
                        stage = Stage.CLOSE;
                        return ActionStatus.progressed();
                    }
                    return stopNow(Problem.of(Problem.Kind.STUCK, "把光标上的" + item + "放进背包时两边对不上", null));
                }
            }
        }

        // 收场前先把光标上的东西放回背包，再关界面：放进背包里能整个装下的一格，背包放不下就放回网络。
        // 到期限还没放好（例如一直点不出去）就照常关界面，光标上的东西交给游戏的关闭流程。
        private ActionStatus clearCursor(TickContext tick) {
            AbstractContainerMenu raw = tick.player().localPlayer().containerMenu;
            Optional<MenuContent.Reading> reading = menu.reading();
            if (reading.isEmpty()) return finishWithout(null);
            if (putBackPending != null) return awaitPutBack(tick.player());
            if (putDownSlot >= 0) return awaitPutDown(raw);
            SlotSnapshot cursor = SlotSnapshot.of(raw.getCarried());
            if (cursor.isEmpty() || ++cursorWaited > CURSOR_TICKS) {
                stage = Stage.CLOSE;
                return ActionStatus.progressed();
            }
            if (menu.busy()) return ActionStatus.running();
            Optional<Integer> slot = NetworkTakePlan.slotFor(cursor.stack(), cursor.count(),
                    reading.get().playerSlotIds(), reading.get().playerSnapshots());
            if (slot.isPresent()) return putDown(slot.get(), cursor, raw);
            return putBack(cursor, raw);
        }

        // 背包里没有一格装得下：在终端的网络格子空白处点一下，把光标上的东西放回网络；本刻发不出去就下一刻再试。
        private ActionStatus putBack(SlotSnapshot cursor, AbstractContainerMenu raw) {
            MenuConfirmation confirmation = (context, ignored) -> menuVerdict(NetworkTakeConfirmation.putBack(cursor,
                    SlotSnapshot.of(context.localPlayer().containerMenu.getCarried())).verdict());
            Optional<PendingMenuAction> sent = menu.submitModAction("把光标上的东西放回 ME 网络",
                    () -> compat.putBack(raw), confirmation, TAKE_TIMEOUT_TICKS);
            if (sent.isEmpty()) return ActionStatus.running();
            putBackPending = sent.get();
            return ActionStatus.progressed();
        }

        // 等放回网络那一下的结论：没能确认就记下，去关界面；放回了一部分（网络满了）下一刻再看光标。
        private ActionStatus awaitPutBack(PlayerContext player) {
            putBackPending = player.menuActions().poll(player, putBackPending);
            if (!putBackPending.terminal()) return ActionStatus.running();
            PendingMenuAction settled = putBackPending;
            putBackPending = null;
            if (settled.status() != PendingMenuAction.Status.CONFIRMED_APPLIED) {
                settlement.unconfirmed("把光标上的东西放回 " + terminal.describe() + " 的网络没能确认："
                        + settled.detail());
                stage = Stage.CLOSE;
            }
            return ActionStatus.progressed();
        }

        // 关界面：打开者负责关闭。关不上不冒充没开过，照常收场让引擎清点。
        private ActionStatus closeMenu(TickContext tick) {
            if (closing == null) closing = menu.closing();
            ActionStatus status = closing.tick(tick);
            if (status instanceof ActionStatus.Running) return status;
            stage = Stage.DONE;
            return result();
        }

        // 收场：取到了就算做完（数量由引擎清点）；一件都没取到就按收场原因失败，让引擎换别的路。
        private ActionStatus result() {
            if (confirmedTotal() > 0 || stopReason == null) return ActionStatus.done();
            String unconfirmed = settlement.unconfirmedFacts().isEmpty() ? ""
                    : "；没能确认的：" + String.join("；", settlement.unconfirmedFacts());
            return ActionStatus.failed(Problem.of(stopReason.kind(), stopReason.message() + unconfirmed,
                    stopReason.suggestion()));
        }

        // 去关界面之前把这台终端此刻的网络存货记下：下次报价用，过五分钟当没看过。
        private ActionStatus stopNow(Problem reason) {
            stop(reason);
            return ActionStatus.progressed();
        }

        // 收场：先记下网络存货，再放好光标、关界面。还没打开界面就收场（例如走过去超时）时没有界面要关，直接结束。
        private void stop(Problem reason) {
            if (stopReason == null) stopReason = reason;
            if (menu == null) {
                stage = Stage.DONE;
                return;
            }
            rememberStockNow();
            stage = Stage.CURSOR;
        }

        // 界面已经不在了：不用关，直接收场。
        private ActionStatus finishWithout(Problem reason) {
            if (stopReason == null) stopReason = reason;
            stage = Stage.DONE;
            return result();
        }

        private void rememberStockNow() {
            PlayerContext player = services.context().get();
            if (player == null || menu == null || menu.reading().isEmpty()) return;
            AbstractContainerMenu raw = player.localPlayer().containerMenu;
            Optional<Boolean> linked = compat.networkLinked(raw);
            Optional<List<Ae2TerminalMenu.StockEntry>> stock = compat.networkStock(raw);
            if (linked.isPresent() && stock.isPresent()) {
                seen.saw(dimension, terminal, linked.get(), stock.get(), clock.get());
            }
        }

        // 网络里已经没有想要的了：这一笔之前一件都没取到就是"网络里没有"，取到过就是"取完了"。
        private Problem nothingLeft() {
            return Problem.of(Problem.Kind.NEED_ITEM,
                    terminal.describe() + " 的网络里没有" + request.wanted().describe() + "了", null);
        }

        private int confirmedTotal() {
            int total = 0;
            for (Map.Entry<String, Integer> entry : settlement.confirmedByItem().entrySet()) {
                total += entry.getValue();
            }
            return total;
        }

        private Ae2TerminalMenu.StockEntry firstWanted(List<Ae2TerminalMenu.StockEntry> stock) {
            List<Ae2TerminalMenu.StockEntry> wanted = NetworkTakePlan.matching(stock, this::wanted);
            return wanted.isEmpty() ? null : wanted.getFirst();
        }

        // 光标上已经有一些：接着取同一条；网络里那条没了也要能把光标上的放下，就按光标的样子补一条存量为零的。
        private Ae2TerminalMenu.StockEntry entryFor(SlotSnapshot cursor, List<Ae2TerminalMenu.StockEntry> stock) {
            for (Ae2TerminalMenu.StockEntry entry : stock) {
                if (SlotSnapshot.of(entry.sample()).sameIdentity(SlotSnapshot.of(cursor.stack().copyWithCount(1)))) {
                    return entry;
                }
            }
            return new Ae2TerminalMenu.StockEntry(-1, cursor.stack(), 0, false);
        }

        private boolean wanted(ItemStack stack) {
            String itemId = itemId(stack);
            return request.wanted().matches(itemId, services.itemTags().tagsOf(itemId));
        }

        @Override public void pause() {
            opening.pause();
            if (menu != null) menu.abandon();
        }

        // 收尾：打开动作一并收尾；界面还开着就请游戏关上，光标上的东西由原版的关闭流程还回背包。
        @Override public void close() {
            opening.close();
            if (closing != null) closing.close();
            if (menu != null && stage != Stage.DONE) menu.abandon();
        }

        @Override public String describe() {
            return "从 " + terminal.describe() + " 取" + request.wanted().describe();
        }
    }

    // 界面里这些槽位此刻的样子，按给的顺序。
    private static List<SlotSnapshot> snapshots(AbstractContainerMenu menu, List<Integer> slotIds) {
        List<SlotSnapshot> snapshots = new ArrayList<>(slotIds.size());
        for (int slotId : slotIds) {
            snapshots.add(slotId >= 0 && slotId < menu.slots.size()
                    ? SlotSnapshot.of(menu.getSlot(slotId).getItem()) : SlotSnapshot.empty());
        }
        return snapshots;
    }

    private static MenuConfirmation.Verdict menuVerdict(MoveConfirmation.Verdict verdict) {
        return switch (verdict) {
            case CONFIRMED -> MenuConfirmation.Verdict.APPLIED;
            case WAITING -> MenuConfirmation.Verdict.PENDING;
            case DIVERGED -> MenuConfirmation.Verdict.DIVERGED;
        };
    }

    private static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }
}
