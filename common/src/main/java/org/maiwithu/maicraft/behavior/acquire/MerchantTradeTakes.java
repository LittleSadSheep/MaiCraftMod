// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.maiwithu.maicraft.behavior.approach.ApproachTarget;
import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.menu.ClientMenuChannel;
import org.maiwithu.maicraft.behavior.menu.MenuChannel;
import org.maiwithu.maicraft.behavior.menu.MenuSession;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import net.minecraft.world.entity.Entity;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.ReportsUnconfirmed;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 交易的生产实现：逐个走到村民跟前右键搭话，按界面上的报价一连买，买够或都试完为止。
 *
 * <p>每笔成交是三步：点选报价列表里那条（游戏照报价把付款从背包摆进付款格）、等结果格亮出货、
 * 把结果整堆快速移动进背包。身上真的多了东西才算这笔成交；点出去了却没能确认结果的不重付，
 * 一句一条交回给引擎，这单就此收场（引擎换别的来源，不再回到交易上重复买）。
 * 付款不够就关上界面、按同一套拿东西的办法先去凑，凑齐了回到同一个村民接着买。
 * 付款格或结果格里已经有东西的界面不碰（那可能不是留给我们的格子），换下一个村民。
 */
public final class MerchantTradeTakes implements TradesWithMerchants {

    /** 在一个村民身上从头到尾的期限（刻）；到点如实收场换下一个，不在一个人身上耗死。 */
    private static final long GIVE_UP_TICKS = 20L * 60;
    /** 等报价同步、等付款摆上、等结果进背包的宽限（刻）。 */
    private static final int SETTLE_TICKS = 10;
    /** 点选报价后至少等几刻再核对付款：本地预测是即时的，服务器的确认要过一次往返。 */
    private static final int SELECT_SYNC_TICKS = 3;

    private final Supplier<PlayerContext> contexts;
    private final BringsPlayerClose close;
    private final Interactions interactions;
    private final ReadsItemTags tags;
    private final ReadsTradeMenu menus;
    private final BackpackView backpack;
    private final OffhandContents offhand;
    private final ItemNeeds needs;

    public MerchantTradeTakes(Supplier<PlayerContext> contexts, BringsPlayerClose close, Interactions interactions,
            ReadsItemTags tags, ReadsTradeMenu menus, BackpackView backpack, OffhandContents offhand, ItemNeeds needs) {
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.close = Objects.requireNonNull(close, "close");
        this.interactions = Objects.requireNonNull(interactions, "interactions");
        this.tags = Objects.requireNonNull(tags, "tags");
        this.menus = Objects.requireNonNull(menus, "menus");
        this.backpack = Objects.requireNonNull(backpack, "backpack");
        this.offhand = Objects.requireNonNull(offhand, "offhand");
        this.needs = Objects.requireNonNull(needs, "needs");
    }

    @Override public Optional<Action> trade(Iterable<ScansMerchants.MerchantSpot> candidates, ItemRequest request,
            Permissions permissions) {
        return Optional.of(new TradeAction(candidates.iterator(), request, permissions));
    }

    /**
     * 一次买卖的动作：走近 → 点开 → 一连买 →（缺付款时去凑再回来）→ 关上；换村民从头来。
     * 状态机的每一步只做一件事，点出去的每一下都等游戏确认过才走下一步。
     */
    private final class TradeAction implements Action, ReportsUnconfirmed {

        private enum Stage { NEXT_MERCHANT, APPROACH, CLICK, WAIT_SYNC, BUY, VERIFY_SELECT, VERIFY_TAKE, CLOSE,
                FETCH_PAYMENT }

        private final Iterator<ScansMerchants.MerchantSpot> candidates;
        private final ItemRequest request;
        private final Permissions permissions;
        private Stage stage = Stage.NEXT_MERCHANT;
        private ScansMerchants.MerchantSpot merchant;
        private Action step;
        private MenuChannel menu;
        private MenuSession session;
        /** 正在按哪条报价买：点选、付款与取货的核对都对着它。 */
        private TradeOffers.View currentOffer;
        /** 动作开始时身上有几件：请求的 count 是"这次再多几件"，买够以它为基准。 */
        private int carriedAtStart = -1;
        /** 正在点的这一笔动手前身上有几件：结果进没进背包按它的差算。 */
        private int carriedAtTakeStart;
        private int waitTicks;
        private long merchantTicks;
        /** 有没能确认的取货：出了这种事这单就此收场，不回到交易上重复买。 */
        private boolean uncertain;
        /** 缺的付款：界面关上后按它去凑。 */
        private TradeOffers.PaymentGap fetchingFor;
        private Action fetching;
        /** 点出去了却没能确认结果的交互，一句一笔，经交回接缝交给引擎。 */
        private final List<String> unconfirmed = new ArrayList<>();
        /** 每个村民身上卡住的原因：都试完后汇总成失败说明。 */
        private final List<String> deadEnds = new ArrayList<>();

        TradeAction(Iterator<ScansMerchants.MerchantSpot> candidates, ItemRequest request, Permissions permissions) {
            this.candidates = candidates;
            this.request = request;
            this.permissions = permissions;
        }

        @Override public ActionStatus tick(TickContext tick) {
            if (carriedAtStart < 0) carriedAtStart = carriedWanted();
            if (stage != Stage.NEXT_MERCHANT && ++merchantTicks > GIVE_UP_TICKS) {
                deadEnds.add("在" + (merchant == null ? "村民" : merchant.describe()) + "身上超时");
                leaveMenu();
                stage = Stage.NEXT_MERCHANT;
            }
            return switch (stage) {
                case NEXT_MERCHANT -> nextMerchant();
                case APPROACH -> approach(tick);
                case CLICK -> click(tick);
                case WAIT_SYNC -> waitSync();
                case BUY -> buy();
                case VERIFY_SELECT -> verifySelect();
                case VERIFY_TAKE -> verifyTake();
                case CLOSE -> closeMenu(tick);
                case FETCH_PAYMENT -> fetchPayment(tick);
            };
        }

        private ActionStatus nextMerchant() {
            merchantTicks = 0;
            if (!candidates.hasNext()) {
                return ActionStatus.failed(Problem.of(Problem.Kind.NEED_ITEM, describeSummary("一个都没买成"), null));
            }
            merchant = candidates.next();
            stage = Stage.APPROACH;
            return ActionStatus.progressed();
        }

        // 走到这只村民跟前：它走动就朝它现在的包围盒走，不朝扫描时记下的旧位置走。
        private ActionStatus approach(TickContext tick) {
            if (step == null) {
                var entity = entityOf(tick);
                if (entity == null) {
                    return merchantGone();
                }
                step = close.toward(ApproachTarget.ofEntity(entity.getBoundingBox()), permissions);
            }
            ActionStatus status = step.tick(tick);
            if (status instanceof ActionStatus.Failed failed) {
                step.close();
                step = null;
                deadEnds.add("走不到" + merchant.describe() + "跟前（" + failed.problem().message() + "）");
                stage = Stage.NEXT_MERCHANT;
                return ActionStatus.progressed();
            }
            if (status instanceof ActionStatus.Done) {
                step.close();
                step = null;
                stage = Stage.CLICK;
            }
            return status;
        }

        // 右键搭话：界面编号变了才算点开；游戏没让开（被挡住、被服务器拒绝）就换下一个。
        private ActionStatus click(TickContext tick) {
            if (step == null) {
                var entity = entityOf(tick);
                if (entity == null) {
                    return merchantGone();
                }
                int before = tick.player().localPlayer().containerMenu.containerId;
                step = interactions.useEntity(entity, InteractionConfirmation.menuChanged(before));
            }
            ActionStatus status = step.tick(tick);
            if (status instanceof ActionStatus.Failed failed) {
                step.close();
                step = null;
                deadEnds.add(merchant.describe() + "点不开界面（" + failed.problem().message() + "）");
                stage = Stage.NEXT_MERCHANT;
                return ActionStatus.progressed();
            }
            if (status instanceof ActionStatus.Done) {
                step.close();
                step = null;
                menu = ClientMenuChannel.claimCurrent(contexts);
                if (menu == null) {
                    deadEnds.add(merchant.describe() + "的界面点开了又马上没了");
                    stage = Stage.NEXT_MERCHANT;
                    return ActionStatus.progressed();
                }
                waitTicks = 0;
                stage = Stage.WAIT_SYNC;
            }
            return status;
        }

        // 等报价同步：同步完了先看三格干净不干净——有别人东西的界面不碰，换下一个。
        private ActionStatus waitSync() {
            Optional<List<TradeOffers.View>> offers = menus.offers();
            if (offers.isEmpty()) {
                if (!menu.stillOpen() || ++waitTicks > SETTLE_TICKS * 4) {
                    deadEnds.add(merchant.describe() + "的界面一直没同步出报价");
                    leaveMenu();
                    stage = Stage.NEXT_MERCHANT;
                    return ActionStatus.progressed();
                }
                return ActionStatus.running();
            }
            for (int slot = 0; slot < 3; slot++) {
                Optional<TradeOffers.SlotContent> content = menus.slot(slot);
                if (content.isPresent() && !content.get().isEmpty()) {
                    deadEnds.add(merchant.describe() + "的界面第 " + (slot + 1) + " 格已经有东西，不是留给我们的，不碰");
                    leaveMenu();
                    stage = Stage.NEXT_MERCHANT;
                    return ActionStatus.progressed();
                }
            }
            currentOffer = null;
            stage = Stage.BUY;
            return ActionStatus.progressed();
        }

        // 买的主循环：买够了收场；每笔等确认过了才盘下一笔。
        private ActionStatus buy() {
            if (!menu.stillOpen()) {
                // 界面被合上了（打断、走远、村民没了）：已成交的几笔由引擎清点，这里照常换下一个。
                deadEnds.add(merchant.describe() + "的界面中途关上了");
                leaveMenu();
                return boughtEnough() ? ActionStatus.done() : toNextMerchantOrEnd();
            }
            if (boughtEnough()) return toClose();
            if (currentOffer == null) return startNextTake();
            return ActionStatus.running();
        }

        /** 盘下一笔：挑报价、核对付款；缺了先关界面去凑，点选发出去就等确认。 */
        private ActionStatus startNextTake() {
            Optional<List<TradeOffers.View>> offers = menus.offers();
            if (offers.isEmpty()) return toClose();
            Optional<TradeOffers.View> next = TradeOffers.nextBuy(TradeOffers.eligible(
                    offers.get(), request.wanted(), tags, permissions.useRareItems()));
            if (next.isEmpty()) {
                deadEnds.add(merchant.describe() + "这里卖" + request.wanted().describe()
                        + "的报价都买不了（没货或付款超出许可）");
                return toNextMerchantOrEnd();
            }
            currentOffer = next.get();
            List<TradeOffers.PaymentGap> gaps = TradeOffers.paymentGaps(currentOffer,
                    Math.max(1, request.count() - bought()), this::carriedCount);
            if (!gaps.isEmpty()) {
                fetchingFor = gaps.get(0);
                return toClose();
            }
            waitTicks = 0;
            if (!menu.selectTradeOffer(currentOffer.listIndex())) return ActionStatus.running();
            stage = Stage.VERIFY_SELECT;
            return ActionStatus.progressed();
        }

        // 点选报价之后：等付款摆进付款格、结果格亮出货，才去取货；等不到说明这笔没成，交回一句。
        private ActionStatus verifySelect() {
            if (!menu.stillOpen()) {
                return closedMidTake();
            }
            if (selectConfirmed()) {
                carriedAtTakeStart = carriedWanted();
                waitTicks = 0;
                if (!menu.quickMove(2)) return ActionStatus.running();
                stage = Stage.VERIFY_TAKE;
                return ActionStatus.progressed();
            }
            if (++waitTicks > SETTLE_TICKS) {
                unconfirmed.add("向" + merchant.describe() + "买" + request.wanted().describe()
                        + "：点选了第 " + (currentOffer.listIndex() + 1) + " 条报价，付款一直没摆上，没能确认这笔成没成");
                uncertain = true;
                return toClose();
            }
            return ActionStatus.running();
        }

        /** 付款格摆上了报价要的东西、结果格亮出货：点选确认生效。 */
        private boolean selectConfirmed() {
            if (waitTicks < SELECT_SYNC_TICKS) return false;
            Optional<TradeOffers.SlotContent> costA = menus.slot(0);
            if (costA.isEmpty() || !matches(costA.get(), currentOffer.costA())) return false;
            if (currentOffer.costB().isPresent()) {
                Optional<TradeOffers.SlotContent> costB = menus.slot(1);
                if (costB.isEmpty() || !matches(costB.get(), currentOffer.costB().get())) return false;
            }
            return menus.resultMatches(request, tags).orElse(false);
        }

        private boolean matches(TradeOffers.SlotContent content, TradeOffers.Payment payment) {
            return !content.isEmpty() && content.itemId().equals(payment.itemId())
                    && content.count() >= payment.count();
        }

        // 取货之后：结果格空了、光标空了、身上真的多了，才算这笔成交；等不到不重付。
        private ActionStatus verifyTake() {
            if (!menu.stillOpen()) {
                return closedMidTake();
            }
            Optional<Boolean> resultGone = menus.resultMatches(request, tags);
            if (resultGone.isPresent() && !resultGone.get() && !menu.cursorCarrying()) {
                if (carriedWanted() > carriedAtTakeStart) {
                    currentOffer = null;
                    stage = Stage.BUY;
                    return ActionStatus.progressed();
                }
                // 结果格空了身上却没多：货去了说不清的地方，这一笔按没能确认交代，不再接着买。
                unconfirmed.add("向" + merchant.describe() + "买" + request.wanted().describe()
                        + "：结果从界面上取走了，背包里却没见到多出来，没能确认这笔去了哪");
                uncertain = true;
                return toClose();
            }
            if (++waitTicks > SETTLE_TICKS) {
                unconfirmed.add("向" + merchant.describe() + "买" + request.wanted().describe()
                        + "：把结果点向背包后一直没等到界面清空，没能确认这笔成交了没有，不再接着买");
                uncertain = true;
                return toClose();
            }
            return ActionStatus.running();
        }

        /** 界面在交易中途被合上：这一笔没能确认，照实交回后收场。 */
        private ActionStatus closedMidTake() {
            unconfirmed.add("向" + merchant.describe() + "买" + request.wanted().describe()
                    + "：界面在成交中途关上了，最后一笔没能确认");
            uncertain = true;
            leaveMenu();
            return boughtEnough() ? ActionStatus.done() : toNextMerchantOrEnd();
        }

        /** 关界面：会话从头用到尾；关上后按接下来该做什么分流（凑付款、换村民、收场）。 */
        private ActionStatus closeMenu(TickContext tick) {
            if (session == null && MenuSession.claim(menu) instanceof MenuSession.Claim.Owned owned) {
                session = owned.session();
            }
            if (session != null) {
                return switch (session.closeNow(menu, tick.player().clientTick())) {
                    case MenuSession.Closing.Closed closed -> afterClosed();
                    case MenuSession.Closing.Failed failed -> {
                        unconfirmed.add("关" + merchant.describe() + "的交易界面没能确认，付款格余货交给游戏还回背包");
                        yield afterClosed();
                    }
                    default -> ActionStatus.running();
                };
            }
            // 认领不了会话（光标上挂着不是自己拿的东西）：照样请游戏关一次，东西由原版的关闭流程还回背包。
            menu.requestClose();
            if (!menu.stillOpen()) return afterClosed();
            if (++waitTicks > SETTLE_TICKS * 10) {
                unconfirmed.add(merchant.describe() + "的界面到期限还没关上");
                leaveMenu();
                return boughtEnough() ? ActionStatus.done() : toNextMerchantOrEnd();
            }
            return ActionStatus.running();
        }

        /** 界面关上之后：缺付款去凑（凑完回到同一个村民接着买），有没能确认的收场，否则换村民。 */
        private ActionStatus afterClosed() {
            session = null;
            menu = null;
            if (fetchingFor != null) {
                fetching = needs.actionFor(new ItemRequest(WantedItem.ofItem(fetchingFor.itemId()),
                        fetchingFor.missingCount(), "买" + request.wanted().describe() + "的付款"), permissions);
                fetchingFor = null;
                stage = Stage.FETCH_PAYMENT;
                return ActionStatus.progressed();
            }
            if (uncertain) {
                return ActionStatus.failed(Problem.of(Problem.Kind.NEED_ITEM,
                        describeSummary("有一笔没能确认成交，不再接着买"), null));
            }
            if (boughtEnough()) return ActionStatus.done();
            return toNextMerchantOrEnd();
        }

        // 推进凑付款的动作：做成回到同一个村民接着买，做不成这单如实结束，引擎换别的来源。
        private ActionStatus fetchPayment(TickContext tick) {
            ActionStatus status = fetching.tick(tick);
            if (status instanceof ActionStatus.Running) return status;
            if (status instanceof ActionStatus.Failed failed) {
                fetching.close();
                fetching = null;
                return ActionStatus.failed(Problem.of(Problem.Kind.NEED_ITEM,
                        "买" + request.wanted().describe() + "的付款没凑齐（缺 "
                                + fetchingGapText() + "）：" + failed.problem().message(), null));
            }
            fetching.close();
            fetching = null;
            merchantTicks = 0;
            stage = Stage.APPROACH;
            return ActionStatus.progressed();
        }

        private String fetchingGapText() {
            return currentOffer == null ? "付款" : currentOffer.costA().itemId();
        }

        private int bought() {
            return carriedWanted() - carriedAtStart;
        }

        private boolean boughtEnough() {
            return bought() >= request.count();
        }

        private int carriedWanted() {
            return CarriedItems.matching(backpack, offhand, request, tags);
        }

        private int carriedCount(String itemId) {
            return CarriedItems.matching(backpack, offhand, WantedItem.ofItem(itemId), tags);
        }

        private ActionStatus merchantGone() {
            if (step != null) {
                step.close();
                step = null;
            }
            deadEnds.add(merchant.describe() + "已经不在了");
            stage = Stage.NEXT_MERCHANT;
            return ActionStatus.progressed();
        }

        private Entity entityOf(TickContext tick) {
            var level = tick.player().level();
            var entity = level.getEntity(merchant.entityId());
            return entity == null || entity.isRemoved() || !entity.isAlive() ? null : entity;
        }

        private ActionStatus toNextMerchantOrEnd() {
            if (boughtEnough()) return ActionStatus.done();
            if (!candidates.hasNext()) {
                return ActionStatus.failed(Problem.of(Problem.Kind.NEED_ITEM,
                        describeSummary("试过的村民都买不够"), null));
            }
            leaveMenu();
            stage = Stage.NEXT_MERCHANT;
            return ActionStatus.progressed();
        }

        private ActionStatus toClose() {
            waitTicks = 0;
            stage = Stage.CLOSE;
            return ActionStatus.progressed();
        }

        /** 丢下界面换下一个：已经点开的界面请游戏关上，不留给别人收拾。 */
        private void leaveMenu() {
            if (menu != null && menu.stillOpen()) {
                menu.requestClose();
            }
            menu = null;
            session = null;
            currentOffer = null;
        }

        private String describeSummary(String why) {
            StringBuilder message = new StringBuilder("想买 ").append(request.count()).append(" 个")
                    .append(request.wanted().describe()).append("，").append(why);
            for (String deadEnd : deadEnds) {
                message.append("；").append(deadEnd);
            }
            return message.toString();
        }

        @Override public List<String> unconfirmedFacts() {
            return List.copyOf(unconfirmed);
        }

        @Override public void pause() {
            if (step != null) step.pause();
            if (fetching != null) fetching.pause();
        }

        @Override public void close() {
            if (step != null) {
                step.close();
                step = null;
            }
            if (fetching != null) {
                fetching.close();
                fetching = null;
            }
            leaveMenu();
        }

        @Override public Interruptibility interruptibility() {
            return menu != null ? Interruptibility.UNSAFE_TO_STOP : Interruptibility.WORKING;
        }

        @Override public String describe() {
            return switch (stage) {
                case NEXT_MERCHANT -> "在找下一个能做买卖的村民";
                case APPROACH -> "走向" + (merchant == null ? "村民" : merchant.describe());
                case CLICK -> "和" + merchant.describe() + "搭话，点开交易界面";
                case WAIT_SYNC -> "等" + merchant.describe() + "的报价同步过来";
                case BUY -> "在看" + merchant.describe() + "的报价";
                case VERIFY_SELECT -> "按" + merchant.describe() + "的第 "
                        + (currentOffer == null ? "?" : currentOffer.listIndex() + 1) + " 条报价付款";
                case VERIFY_TAKE -> "把买到的东西收进背包";
                case CLOSE -> "关上" + merchant.describe() + "的交易界面";
                case FETCH_PAYMENT -> "先去凑买" + request.wanted().describe() + "的付款";
            };
        }
    }
}
