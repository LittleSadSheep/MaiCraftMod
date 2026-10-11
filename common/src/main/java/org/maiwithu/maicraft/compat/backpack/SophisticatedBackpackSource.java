// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.backpack;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;

import org.maiwithu.maicraft.behavior.acquire.ContainerTakePlan;
import org.maiwithu.maicraft.behavior.acquire.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquireVia;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquisitionCost;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemSource;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.menu.ClientMenuChannel;
import org.maiwithu.maicraft.behavior.menu.MenuChannel;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuSession;
import org.maiwithu.maicraft.behavior.menu.MenuSlots;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.ClientBackpackView;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.ReportsUnconfirmed;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 随身背包的来源：包是身上的仓库，要的东西包里可能就有——开包取出来算拿到，并入"身上"这条途径。
 *
 * <p>问价按上次开包读到的存货（本次启动内，五分钟内算数）报明确数量；没读过报"包里可能有，
 * 不知道有多少"，排在数量明确的来源后面；读不到（联动停用、版本对不上）由登记表那一层如实回答。
 * 执行用模组自己的打开请求按格开包（对应玩家在背包里对着那只包按 B 键），身上几只包一只一只开，
 * 只看主存储（升级槽与幽灵槽不算货），
 * 按要的量搬进主背包，每笔以身上真的多了为准；带无限格升级的背包读数对不上，按"未证实"处理。
 * 主背包放不下就停，已取的照算；关界面前光标清空。往包里塞是腾地方的事，不在这里。
 */
public final class SophisticatedBackpackSource implements ItemSource {

    /** 开包、等同步、搬完、关上，大约几个动作：就在身上，不用走路。 */
    private static final int ACTIONS_PER_VISIT = 4;
    /** 一次开包取货的总期限（刻）；到点如实收场。 */
    private static final long GIVE_UP_TICKS = 20L * 30;
    /** 一笔搬运结清后，等两侧内容同步过来的宽限（刻）。 */
    private static final int SETTLE_TICKS = 5;
    /** 等背包界面打开的期限（刻）。 */
    private static final int OPEN_WAIT_TICKS = 40;

    private final BackpackCompat compat;
    private final PlayerServices services;
    private final BackpackMenus menus;
    private final SeenBackpackStock seen;
    private final Supplier<Instant> clock;

    public SophisticatedBackpackSource(BackpackCompat compat, PlayerServices services, BackpackMenus menus,
            SeenBackpackStock seen, Supplier<Instant> clock) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.services = Objects.requireNonNull(services, "services");
        this.menus = Objects.requireNonNull(menus, "menus");
        this.seen = Objects.requireNonNull(seen, "seen");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override public String describe() {
        return "随身背包";
    }

    @Override public AcquireVia via() {
        return AcquireVia.CARRIED;
    }

    @Override public SourceQuote quote(ItemRequest request, SourceContext context) {
        int carried = compat.carriedBackpacks(services.backpack()).size();
        if (carried == 0) {
            return new SourceQuote.Unavailable(describe(), "身上没有随身背包");
        }
        Instant now = clock.get();
        Optional<SeenBackpackStock.Sighting> stock = seen.fresh(now);
        if (stock.isEmpty()) {
            return new SourceQuote.Offer(describe(), SourceQuote.Offer.UNKNOWN_COUNT,
                    new AcquisitionCost(0, ACTIONS_PER_VISIT * carried),
                    "包里可能有，还没开过或读数过时，不知道有多少", "");
        }
        Predicate<String> wanted = itemId -> request.wanted().matches(itemId,
                services.itemTags().tagsOf(itemId));
        int count = stock.get().count(wanted);
        // 身上不止一只包、有的还没看过：看过的不够，没看过的那几只里可能还有。
        int unseen = Math.max(0, carried - seen.freshCount(now));
        if (count <= 0 && unseen == 0) {
            return new SourceQuote.Unavailable(describe(), "上次开包（" + ago(stock.get().seenAt(), now)
                    + "）时里面没有" + request.wanted().describe());
        }
        if (count < request.count() && unseen > 0) {
            return new SourceQuote.Offer(describe(), SourceQuote.Offer.UNKNOWN_COUNT,
                    new AcquisitionCost(0, ACTIONS_PER_VISIT * carried),
                    "看过的包里有 " + count + " 件，另有 " + unseen + " 只包还没开过，不知道有多少", "");
        }
        return new SourceQuote.Offer(describe(), Math.min(count, request.count()),
                new AcquisitionCost(0, ACTIONS_PER_VISIT),
                "记的是" + ago(stock.get().seenAt(), now) + "开包看到的数，开包取货时以现场为准", "");
    }

    @Override public Optional<Action> begin(ItemRequest request, SourceQuote.Offer offer, SourceContext context) {
        // 到动手时包可能已经不在身上（刚腾地方给了别人）：重新认一遍，没有就交回空让引擎换路。
        if (compat.carriedBackpacks(services.backpack()).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new TakeAction(request, context.permissions()));
    }

    /**
     * 开包取货的动作：打开 → 按量搬 → 记下存货 → 关上；身上不止一只包时一只一只按格开，
     * 这只没有或不够就关上开下一只，开错了、打不开也换下一只，不卡在一只包上。点出去没能确认的一句一条交回。
     */
    private final class TakeAction implements Action, ReportsUnconfirmed {

        private enum Stage { OPEN, TAKE, CLOSE }

        private final ItemRequest request;
        private final Permissions permissions;
        private Stage stage = Stage.OPEN;
        private MenuChannel menu;
        private MenuSession session;
        private List<Integer> storageSlots = List.of();
        /** 正在点的那一笔还剩哪几下没点；一笔的点击逐刻发。 */
        private final Deque<ContainerTakePlan.Click> pressing = new ArrayDeque<>();
        /** 正在点的那一笔动手时身上有几件；没有在点的为 -1。 */
        private int moveStartCarried = -1;
        private int waited;
        private int totalTicks;
        private int quietTicks;
        /** 还没开的几只包（原版背包格号）；按不了格（饰品栏里的）记 -1，由模组自己找第一只。 */
        private final Deque<Integer> toOpen = new ArrayDeque<>();
        private boolean slotsKnown;
        /** 正在开的那一只。 */
        private int openingSlot = -1;
        /** 这一只的打开请求发过没有：一只只发一次，等界面开，不每刻重发。 */
        private boolean openRequested;
        /** 每只包卡住的原因：都试完还不够时一起交代。 */
        private final List<String> deadEnds = new ArrayList<>();
        /** 点出去了却没能确认结果的交互，一句一笔，经交回接缝交给引擎。 */
        private final List<String> unconfirmed = new ArrayList<>();
        private int carriedAtStart = -1;

        TakeAction(ItemRequest request, Permissions permissions) {
            this.request = request;
            this.permissions = permissions;
        }

        @Override public ActionStatus tick(TickContext tick) {
            if (carriedAtStart < 0) carriedAtStart = carriedWanted();
            if (!slotsKnown) {
                slotsKnown = true;
                PlayerContext context = services.context().get();
                if (context != null && context.localPlayer() != null) {
                    toOpen.addAll(compat.carriedSlots(context.localPlayer().getInventory()));
                }
                // 背包里一只都认不出（多半背在饰品栏里）：退回模组自己找第一只。
                if (toOpen.isEmpty()) toOpen.add(-1);
                openingSlot = toOpen.poll();
            }
            if (enough() && stage != Stage.CLOSE) {
                // 已经够了：界面还开着就记一笔存货、关上；还没开包就直接完成。
                return stage == Stage.OPEN && menu == null ? ActionStatus.done() : toClose();
            }
            // 总期限按还要开几只包放宽：一只包一份期限。
            if (++totalTicks > GIVE_UP_TICKS * (toOpen.size() + 1)) {
                noteUnsettled("总期限到了");
                return ActionStatus.failed(Problem.of(Problem.Kind.STUCK, "开包取货超时", null));
            }
            return switch (stage) {
                case OPEN -> open();
                case TAKE -> take();
                case CLOSE -> closeMenu(tick);
            };
        }

        // 按格开这一只（对应玩家在背包里对着它按 B 键），请求只发一次，等背包界面真的打开并认领它。
        private ActionStatus open() {
            PlayerContext context = services.context().get();
            if (context == null || context.localPlayer() == null) {
                return ActionStatus.failed(Problem.of(Problem.Kind.WRONG_TIME, "这一刻掌握不到角色，开不了包", null));
            }
            if (!openRequested) {
                if (openingSlot >= 0) {
                    menus.open(openingSlot);
                } else {
                    menus.openCarried();
                }
                openRequested = true;
                waited = 0;
                return ActionStatus.progressed();
            }
            AbstractContainerMenu opened = context.localPlayer().containerMenu;
            if (opened != null && menus.isBackpackMenu(opened)) {
                MenuChannel channel = ClientMenuChannel.claimCurrent(services.context());
                if (channel == null) {
                    return nextBackpackOr(Problem.of(Problem.Kind.TARGET_GONE, "背包界面点开了又马上没了", null));
                }
                menu = channel;
                Optional<List<Integer>> slots = menus.mainStorageSlotIds(opened);
                if (slots.isEmpty()) {
                    // 带无限格升级的背包读数对不上：这只取不了货，按未证实处理，关上换下一只。
                    deadEnds.add(whichBackpack() + "读数对不上（多半带着无限格升级），取不了货");
                    stage = Stage.CLOSE;
                    waited = 0;
                    return ActionStatus.progressed();
                }
                storageSlots = slots.get();
                stage = Stage.TAKE;
                waited = 0;
                return ActionStatus.progressed();
            }
            if (++waited > OPEN_WAIT_TICKS) {
                // 这只一直打不开：不卡在它身上，换下一只。
                return nextBackpackOr(Problem.of(Problem.Kind.STUCK,
                        "背包的打开请求发出去了，界面一直没开", null));
            }
            return ActionStatus.running();
        }

        /** 这只包没开成：还有没开的就换下一只，都开过了按这个问题收场。 */
        private ActionStatus nextBackpackOr(Problem problem) {
            deadEnds.add(whichBackpack() + "：" + problem.message());
            if (toOpen.isEmpty()) {
                return ActionStatus.failed(Problem.of(problem.kind(), String.join("；", deadEnds), null));
            }
            startNextBackpack();
            return ActionStatus.progressed();
        }

        /** 换下一只包：界面与这一只的进度清掉，从发打开请求开始。 */
        private void startNextBackpack() {
            menu = null;
            session = null;
            storageSlots = List.of();
            pressing.clear();
            moveStartCarried = -1;
            quietTicks = 0;
            waited = 0;
            openRequested = false;
            openingSlot = toOpen.poll();
            stage = Stage.OPEN;
        }

        /** 关上一只之后：够了收工；不够还有没开的就开下一只；都开过了交给引擎清点。 */
        private ActionStatus afterClosed() {
            if (enough() || toOpen.isEmpty()) {
                return ActionStatus.done();
            }
            startNextBackpack();
            return ActionStatus.progressed();
        }

        private String whichBackpack() {
            return openingSlot >= 0 ? "第 " + openingSlot + " 格的背包" : "身上的背包";
        }

        private boolean enough() {
            return carriedWanted() - carriedAtStart >= request.count();
        }

        // 按量搬：整堆够用整堆拿，尾数按件拿，拿够就停；上一笔没结清就本刻不动手。
        private ActionStatus take() {
            if (menu == null || !menu.stillOpen()) {
                // 界面被合上了（打断、模组自己关的）：没等到结果的那几下交回，搬走多少由引擎清点。
                noteUnsettled("界面关上时");
                return toClose();
            }
            Optional<MenuContent.Reading> reading = reading();
            if (reading.isEmpty()) {
                if (++quietTicks > SETTLE_TICKS * 4) {
                    return ActionStatus.failed(Problem.of(Problem.Kind.STUCK, "背包界面的内容一直没同步完", null));
                }
                return ActionStatus.running();
            }
            quietTicks = 0;
            if (moveStartCarried < 0) {
                // 上一笔已结清：按还缺的件数盘下一笔要点的几下。
                int stillNeeded = request.count() - (carriedWanted() - carriedAtStart);
                Optional<ContainerTakePlan.Move> next = stillNeeded < 1 ? Optional.empty()
                        : ContainerTakePlan.next(reading.get(), this::matches, stillNeeded);
                if (next.isEmpty()) {
                    // 要的都拿到了、包里没有了，或主背包放不下尾数：收场，已取的照算。
                    return toClose();
                }
                moveStartCarried = carriedWanted();
                quietTicks = 0;
                pressing.addAll(next.get().clicks());
            }
            if (!pressing.isEmpty()) {
                // 本刻发不出去（上一下没结清、界面刚刷新）就下一刻再点：没发出去不是背包放不下。
                ContainerTakePlan.Click click = pressing.peek();
                boolean sent = click.quickMove()
                        ? menu.quickMove(click.slotId())
                        : menu.click(click.slotId(), click.button());
                if (!sent) return ActionStatus.running();
                pressing.poll();
                return ActionStatus.progressed();
            }
            // 一笔的几下都点完了：等身上真的多了再结算这一笔（以背包增长为准，包里少掉只当核对）。
            int grown = carriedWanted() - moveStartCarried;
            if ((grown <= 0 || !cursorEmpty()) && ++quietTicks <= SETTLE_TICKS) {
                return ActionStatus.running();
            }
            boolean stuck = grown <= 0;
            moveStartCarried = -1;
            quietTicks = 0;
            if (stuck) {
                unconfirmed.add("从随身背包拿" + request.wanted().describe()
                        + "：搬的那几下都点出去了，身上一直没多，没能确认结果");
                return toClose();
            }
            if (!cursorEmpty()) {
                unconfirmed.add("从随身背包拿" + request.wanted().describe()
                        + "：点完了光标上还拿着东西，这一笔搬走多少没能确认");
                return toClose();
            }
            return ActionStatus.progressed();
        }

        /** 关上之前记下这次开包看到的主存储存货：下次问价不用再开一遍。 */
        private ActionStatus toClose() {
            recordStock();
            stage = Stage.CLOSE;
            waited = 0;
            return ActionStatus.progressed();
        }

        private ActionStatus closeMenu(TickContext tick) {
            if (session == null && menu != null
                    && MenuSession.claim(menu) instanceof MenuSession.Claim.Owned owned) {
                session = owned.session();
            }
            if (session != null) {
                return switch (session.closeNow(menu, tick.player().clientTick())) {
                    case MenuSession.Closing.Closed closed -> afterClosed();
                    case MenuSession.Closing.Failed failed -> {
                        unconfirmed.add("关随身背包的界面没能确认，光标上的东西交给游戏的关闭流程还回");
                        yield ActionStatus.done();
                    }
                    default -> ActionStatus.running();
                };
            }
            // 认领不了会话：照样请游戏关一次，东西由原版的关闭流程还回背包。
            if (menu != null) menu.requestClose();
            if (menu == null || !menu.stillOpen()) return afterClosed();
            if (++waited > SETTLE_TICKS * 10) {
                unconfirmed.add("随身背包的界面到期限还没关上");
                return ActionStatus.done();
            }
            return ActionStatus.running();
        }

        // 读主存储与背包那一侧：升级槽与幽灵槽不在主存储清单里，天然不算货。
        private Optional<MenuContent.Reading> reading() {
            PlayerContext context = services.context().get();
            if (context == null || context.localPlayer() == null || menu == null || !menu.stillOpen()) {
                return Optional.empty();
            }
            AbstractContainerMenu opened = context.localPlayer().containerMenu;
            if (opened == null || !menus.isBackpackMenu(opened)) {
                return Optional.empty();
            }
            List<Integer> playerSlots = menus.playerSlotIds(opened);
            List<SlotSnapshot> storage = new ArrayList<>();
            List<SlotSnapshot> player = new ArrayList<>();
            for (int slot : storageSlots) storage.add(snapshot(opened, slot));
            for (int slot : playerSlots) player.add(snapshot(opened, slot));
            MenuSlots slots = new MenuSlots() {
                @Override public String menuTypeId() { return "sophisticatedbackpacks:backpack"; }
                @Override public int slotCount() { return opened.slots.size(); }
                @Override public boolean playerBacked(int slot) {
                    return slot >= 0 && slot < opened.slots.size()
                            && opened.slots.get(slot).container instanceof Inventory;
                }
            };
            return Optional.of(new MenuContent.Reading(menu, slots, List.copyOf(storageSlots), playerSlots,
                    List.copyOf(storage), List.copyOf(player)));
        }

        private static SlotSnapshot snapshot(AbstractContainerMenu menu, int slot) {
            if (slot < 0 || slot >= menu.slots.size()) return SlotSnapshot.empty();
            var stack = menu.slots.get(slot).getItem();
            return stack.isEmpty() ? SlotSnapshot.empty() : SlotSnapshot.of(stack);
        }

        /** 这一格是不是想要的东西：按物品 ID 与它挂着的标签匹配。 */
        private boolean matches(SlotSnapshot snapshot) {
            if (snapshot.isEmpty()) return false;
            BackpackStack stack = ClientBackpackView.snapshot(snapshot.stack());
            return request.wanted().matches(stack.itemId(), services.itemTags().tagsOf(stack.itemId()));
        }

        private boolean cursorEmpty() {
            return menu != null && !menu.cursorCarrying();
        }

        /** 身上（主背包与副手之外这层只看主背包）有多少件想要的：取没取到以它的增长为准。 */
        private int carriedWanted() {
            int total = 0;
            for (BackpackStack stack : services.backpack().stacks()) {
                if (request.wanted().matches(stack.itemId(), services.itemTags().tagsOf(stack.itemId()))) {
                    total += stack.count();
                }
            }
            return total;
        }

        private void recordStock() {
            Optional<MenuContent.Reading> reading = reading();
            if (reading.isEmpty()) {
                seen.forget(openingSlot);
                return;
            }
            Map<String, Integer> stock = new HashMap<>();
            for (SlotSnapshot snapshot : reading.get().containerSnapshots()) {
                if (snapshot.isEmpty()) continue;
                BackpackStack stack = ClientBackpackView.snapshot(snapshot.stack());
                stock.merge(stack.itemId(), snapshot.count(), Integer::sum);
            }
            seen.saw(openingSlot, stock, clock.get());
        }

        private void noteUnsettled(String what) {
            if (moveStartCarried >= 0 || !pressing.isEmpty()) {
                unconfirmed.add("从随身背包拿" + request.wanted().describe() + "：" + what + "，最后一笔没能确认");
            }
        }

        @Override public List<String> unconfirmedFacts() {
            return List.copyOf(unconfirmed);
        }

        @Override public void pause() {
        }

        @Override public void close() {
            if (menu != null && menu.stillOpen()) {
                menu.requestClose();
            }
            menu = null;
            session = null;
        }

        @Override public Interruptibility interruptibility() {
            return menu != null ? Interruptibility.UNSAFE_TO_STOP : Interruptibility.WORKING;
        }

        @Override public String describe() {
            return switch (stage) {
                case OPEN -> "打开随身背包";
                case TAKE -> "从随身背包搬" + request.wanted().describe() + "进主背包";
                case CLOSE -> "关上随身背包";
            };
        }
    }

    /** 多久以前：给 LLM 看的粗略说法。 */
    private static String ago(Instant seenAt, Instant now) {
        long seconds = Math.max(0, Duration.between(seenAt, now).getSeconds());
        return seconds < 60 ? " " + seconds + " 秒前" : " " + seconds / 60 + " 分钟前";
    }
}
