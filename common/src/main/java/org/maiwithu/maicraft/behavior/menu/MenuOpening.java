// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.menu;

import java.util.Optional;

import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 打开一只容器的动作：走到跟前、点开、认领点开的那一份界面、等内容同步完。
 * 做完后由 {@link #opened()} 交出这份界面，之后的读写与关闭归调用方；
 * 没做完就被收尾时，已经点开的界面由它自己请游戏关上，不留给玩家收拾。
 */
public interface MenuOpening extends Action {

    /** 点开并同步完的那一份界面；动作还没做完或失败时为空。 */
    Optional<OpenedMenu> opened();
}
