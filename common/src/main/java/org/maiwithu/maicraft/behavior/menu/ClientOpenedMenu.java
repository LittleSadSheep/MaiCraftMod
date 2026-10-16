// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.menu;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;

import org.maiwithu.maicraft.game.menu.MenuConfirmation;
import org.maiwithu.maicraft.game.menu.PendingMenuAction;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 点开后的一份容器界面的生产实现：读数经点开时新建的读端，点击与关闭经认领时绑定的通道，
 * 关闭走一份界面会话从头用到尾（只请求游戏关一次，期限按这份会话算）。
 *
 * <p>打开者声明过服务端换页的换法（{@link PageSwaps}）时，认领的那一页被服务端换成声明认的一页，
 * 每次用之前先把通道与读端换绑过去，会话也跟着接管；请游戏关过以后不再跟，免得关的路上又认领一页。
 */
final class ClientOpenedMenu implements OpenedMenu {

    /** 一下快速移动等确认的期限（刻）；到点由菜单入口按没能确认收场，调用方按两侧内容核对。 */
    private static final int QUICK_MOVE_TIMEOUT_TICKS = 40;

    private ClientMenuChannel channel;
    private ClientMenuContent content;
    private final Supplier<PlayerContext> contexts;
    private MenuSession session;
    /** 打开者声明的哪些服务端换页算接着用；没声明就一页都不认。 */
    private PageSwaps swaps = PageSwaps.NONE;
    /** 已经请游戏关过：之后服务端再换上来什么都不跟。 */
    private boolean closeAsked;

    ClientOpenedMenu(ClientMenuChannel channel, ClientMenuContent content, Supplier<PlayerContext> contexts) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.content = Objects.requireNonNull(content, "content");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
    }

    @Override public void followPageSwaps(PageSwaps swaps) {
        this.swaps = Objects.requireNonNull(swaps, "swaps");
    }

    // 服务端在这次交互里换了页、换上来的是打开者声明接着用的那种：通道与读端换绑到新的一页，会话跟着接管。
    // 换上来的是别的界面、界面被关上了（回到背包界面）、或已经请游戏关过，都不跟，照旧当成这份界面不在了。
    private void followSwap() {
        if (swaps == PageSwaps.NONE || closeAsked || channel.stillOpen()) return;
        PlayerContext context = contexts.get();
        if (context == null) return;
        AbstractContainerMenu now = context.localPlayer().containerMenu;
        if (!followable(channel.claimedMenu(), now, context.localPlayer().inventoryMenu, swaps)) return;
        ClientMenuChannel next = ClientMenuChannel.claimCurrent(contexts);
        if (next == null) return;
        channel = next;
        content = content.forNextPage();
        if (session != null) session.pageSwapped();
    }

    /**
     * 认领的那一页不在了以后，此刻开着的这一页要不要接着用：开着的不是背包界面（界面没被关上）、
     * 不是原来那一份、打开者声明的换法认它。纯判断，离线测试用。
     */
    static boolean followable(AbstractContainerMenu claimed, AbstractContainerMenu now, AbstractContainerMenu inventory,
            PageSwaps swaps) {
        return now != null && now != inventory && now != claimed && swaps.follows(claimed, now);
    }

    // 界面已经不是认领的那一份（被关掉、被顶掉）就读不到，免得读到别的界面的格子；服务端换上来的接着用的那一页照常读。
    @Override public Optional<MenuContent.Reading> reading() {
        followSwap();
        return channel.stillOpen() ? content.current() : Optional.empty();
    }

    @Override public boolean busy() {
        PlayerContext context = contexts.get();
        return context == null || context.menuActions().hasPendingTransaction();
    }

    @Override public boolean cursorEmpty() {
        followSwap();
        return !channel.cursorCarrying();
    }

    // 快速移动：本刻能动手才点（同一条规矩，见通道的 readyForAction）；搬没搬成由调用方按两侧内容核对。
    @Override public boolean quickMove(int slotId) {
        followSwap();
        PlayerContext context = contexts.get();
        if (!channel.readyForAction(context)) return false;
        context.menuActions().click(context, slotId, 0, ClickType.QUICK_MOVE,
                ClientMenuChannel.clickedChanged(context, slotId), QUICK_MOVE_TIMEOUT_TICKS);
        return true;
    }

    // 模组协议的一下：界面还是这一份、上一下结清、界面画过且本刻有交互机会才发；否则本刻不动，下一刻再试。
    @Override public Optional<PendingMenuAction> submitModAction(String what, Runnable send,
            MenuConfirmation confirmation, int timeoutTicks) {
        followSwap();
        PlayerContext context = contexts.get();
        // 和点格子同一条规矩：本刻没有交互机会、上一下没结清、界面没画好都不发，按"下一刻再试"返回空。
        if (!channel.readyForAction(context)) return Optional.empty();
        return Optional.of(context.menuActions().submitModAction(context, what, send, confirmation, timeoutTicks));
    }

    // 点界面上的按钮（交易界面里点选一条报价）：与模组协议的一下同一条就绪判断，发的是选择报价的包。
    @Override public Optional<PendingMenuAction> pressMenuButton(int button, MenuConfirmation confirmation,
            int timeoutTicks) {
        followSwap();
        PlayerContext context = contexts.get();
        if (!channel.readyForAction(context)) return Optional.empty();
        return Optional.of(context.menuActions().selectTradeOffer(context, button, confirmation, timeoutTicks));
    }

    @Override public boolean click(int slotId, int button) {
        followSwap();
        return channel.click(slotId, button);
    }

    @Override public void noteCursorTakenFrom(int slotId) {
        session().ifPresent(owned -> owned.noteCursorTakenFrom(slotId));
    }

    @Override public Action closing() {
        return new Closing();
    }

    // 中途撒手：先跟上服务端换上来的那一页再请游戏关，关的是此刻这次交互开着的那一页，不是早就没了的第一页。
    @Override public void abandon() {
        followSwap();
        closeAsked = true;
        if (channel.stillOpen()) channel.requestClose();
    }

    // 会话在第一次需要时认领：认领要求光标为空，点开时光标是空的，搬运中途拿起的东西由会话记着放回去。
    private Optional<MenuSession> session() {
        if (session == null && MenuSession.claim(channel) instanceof MenuSession.Claim.Owned owned) {
            session = owned.session();
        }
        return Optional.ofNullable(session);
    }

    /** 关上这份界面：一份会话从头用到尾；界面先一步没了（玩家关掉、被顶掉）也算关上了。 */
    private final class Closing implements Action {

        /** 认领不了会话时自己数的期限，与会话的关闭期限同量级。 */
        private static final int UNOWNED_CLOSE_TICKS = 100;
        private boolean askedWithoutSession;
        private int waitedWithoutSession;

        @Override public ActionStatus tick(TickContext tick) {
            // 关之前先跟上服务端换上来的那一页；开始关以后不再跟。
            followSwap();
            closeAsked = true;
            if (!channel.stillOpen()) {
                return ActionStatus.done();
            }
            Optional<MenuSession> owned = session();
            if (owned.isEmpty()) {
                // 光标上挂着不是自己拿的东西，会话认领不了：照样请游戏关一次，东西由原版的关闭流程还回背包。
                if (!askedWithoutSession) {
                    askedWithoutSession = true;
                    channel.requestClose();
                }
                return ++waitedWithoutSession > UNOWNED_CLOSE_TICKS
                        ? ActionStatus.failed(Problem.of(Problem.Kind.STUCK, "界面到期限还没有关上", null))
                        : ActionStatus.running();
            }
            return switch (owned.get().closeNow(channel, tick.player().clientTick())) {
                case MenuSession.Closing.Closed closed -> ActionStatus.done();
                case MenuSession.Closing.Failed failed -> ActionStatus.failed(failed.problem());
                default -> ActionStatus.running();
            };
        }

        @Override public void close() {
            abandon();
        }

        @Override public String describe() {
            return "关上容器界面";
        }
    }
}
