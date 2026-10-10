// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.menu;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

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
 */
final class ClientOpenedMenu implements OpenedMenu {

    /** 一下快速移动等确认的期限（刻）；到点由菜单入口按没能确认收场，调用方按两侧内容核对。 */
    private static final int QUICK_MOVE_TIMEOUT_TICKS = 40;

    private final ClientMenuChannel channel;
    private final MenuContent content;
    private final Supplier<PlayerContext> contexts;
    private MenuSession session;

    ClientOpenedMenu(ClientMenuChannel channel, MenuContent content, Supplier<PlayerContext> contexts) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.content = Objects.requireNonNull(content, "content");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
    }

    // 界面已经不是认领的那一份（被关掉、被顶掉）就读不到，免得读到别的界面的格子。
    @Override public Optional<MenuContent.Reading> reading() {
        return channel.stillOpen() ? content.current() : Optional.empty();
    }

    @Override public boolean busy() {
        PlayerContext context = contexts.get();
        return context == null || context.menuActions().hasPendingTransaction();
    }

    @Override public boolean cursorEmpty() {
        return !channel.cursorCarrying();
    }

    // 快速移动：本刻能动手才点（同一条规矩，见通道的 readyForAction）；搬没搬成由调用方按两侧内容核对。
    @Override public boolean quickMove(int slotId) {
        PlayerContext context = contexts.get();
        if (!channel.readyForAction(context)) return false;
        context.menuActions().click(context, slotId, 0, ClickType.QUICK_MOVE,
                MenuConfirmation.stateChanged(), QUICK_MOVE_TIMEOUT_TICKS);
        return true;
    }

    // 模组协议的一下：界面还是这一份、上一下结清、界面画过且本刻有交互机会才发；否则本刻不动，下一刻再试。
    @Override public Optional<PendingMenuAction> submitModAction(String what, Runnable send,
            MenuConfirmation confirmation, int timeoutTicks) {
        PlayerContext context = contexts.get();
        // 和点格子同一条规矩：本刻没有交互机会、上一下没结清、界面没画好都不发，按"下一刻再试"返回空。
        if (!channel.readyForAction(context)) return Optional.empty();
        return Optional.of(context.menuActions().submitModAction(context, what, send, confirmation, timeoutTicks));
    }

    @Override public boolean click(int slotId, int button) {
        return channel.click(slotId, button);
    }

    @Override public void noteCursorTakenFrom(int slotId) {
        session().ifPresent(owned -> owned.noteCursorTakenFrom(slotId));
    }

    @Override public Action closing() {
        return new Closing();
    }

    @Override public void abandon() {
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
