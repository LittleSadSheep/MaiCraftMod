// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.menu;

import java.util.Optional;

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

    /** 快速移动（潜行点击）：把这一格整堆送到另一侧，落在哪一格由游戏挑。 */
    void quickMove(int slotId);

    /** 普通点击：左键 button=0 拿起或放下整份，右键 button=1 拿半堆或放一个。 */
    void click(int slotId, int button);

    /** 光标上的东西是从这一格拿起的：关界面前先放回这里。 */
    void noteCursorTakenFrom(int slotId);

    /** 关上这份界面的动作：光标上自己拿起的先放回原格，再等界面真的消失；打开者负责关闭。 */
    Action closing();

    /** 中途撒手时请游戏关一次（被打断、被取消）：光标上的东西由原版的关闭流程还回背包。 */
    void abandon();
}
