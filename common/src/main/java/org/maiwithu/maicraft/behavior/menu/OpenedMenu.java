// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.menu;

import java.util.Optional;

import org.maiwithu.maicraft.game.menu.MenuConfirmation;
import org.maiwithu.maicraft.game.menu.PendingMenuAction;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 点开后的一份容器界面：读两侧内容、点格子、关上。认领时绑定的就是点开的那一份，
 * 被关掉或被别的界面顶掉后读不到内容，也不会去点不属于自己的界面。
 *
 * <p>每刻最多点一下：上一下还在等游戏确认时 {@link #busy()} 为真，调用方等它结清再点下一下，
 * 两次没确认的点击分不清哪次搬走了什么。搬没搬成由调用方按两侧内容的前后对照核对。
 */
public interface OpenedMenu {

    /** 这份界面此刻的分侧读数；关上了、被顶掉或内容没同步完时为空。 */
    Optional<MenuContent.Reading> reading();

    /** 上一下点击还在等游戏确认。 */
    boolean busy();

    /** 光标上是空的。 */
    boolean cursorEmpty();

    /**
     * 快速移动（潜行点击）：把这一格整堆送到另一侧，落在哪一格由游戏挑。
     * 本刻发出去了返回真；本刻发不了（没有交互机会、上一下没结清、界面刚刷新还没画好）返回假，
     * 什么都没做——调用方下一刻再试，不能把没发出去当成"放不下"或"对面没动静"。
     */
    boolean quickMove(int slotId);

    /** 普通点击：左键 button=0 拿起或放下整份，右键 button=1 拿半堆或放一个。发没发出去同 {@link #quickMove}。 */
    boolean click(int slotId, int button);

    /**
     * 经模组自己的协议对这份界面做一次操作（例如 AE2 终端里取一件）：这份界面还开着、上一下已经结清、
     * 界面画过、本刻还有交互机会才发，发了返回这一下的挂起记录，调用方按它的状态等结果；本刻发不了时为空，下一刻再试。
     *
     * @param what 这一下在做什么，写进日志
     * @param send 真正发模组的包的那一下
     */
    default Optional<PendingMenuAction> submitModAction(String what, Runnable send, MenuConfirmation confirmation,
                                                        int timeoutTicks) {
        throw new UnsupportedOperationException("这份界面不支持经模组协议操作");
    }

    /** 光标上的东西是从这一格拿起的：关界面前先放回这里。 */
    void noteCursorTakenFrom(int slotId);

    /** 关上这份界面的动作：光标上自己拿起的先放回原格，再等界面真的消失；打开者负责关闭。 */
    Action closing();

    /** 中途撒手时请游戏关一次（被打断、被取消）：光标上的东西由原版的关闭流程还回背包。 */
    void abandon();
}
