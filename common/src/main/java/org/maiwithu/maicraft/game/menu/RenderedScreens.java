// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.menu;

import net.minecraft.client.gui.screens.Screen;

/**
 * 最近一次真正画出来的界面与帧号。界面渲染从 Mixin 进来记在这里（经 ClientHooks 登记的这一份），
 * 菜单可见性按它判断界面改变后"真正画过一帧"，只创建了菜单对象、玩家还没看到时不点。
 */
public final class RenderedScreens {

    /** 一次绘制：哪个界面、第几帧；整体换，不出现半更新的可见状态。 */
    private record Rendered(Screen screen, long frame) {}

    private volatile Rendered last = new Rendered(null, 0);

    /** 界面这一帧画完了；帧号单调前进。 */
    public void rendered(Screen screen) {
        last = new Rendered(screen, last.frame() + 1);
    }

    long frame() {
        return last.frame();
    }

    Screen screen() {
        return last.screen();
    }

    void reset() {
        last = new Rendered(null, 0);
    }
}
