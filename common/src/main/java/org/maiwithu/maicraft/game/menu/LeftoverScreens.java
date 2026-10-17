// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.menu;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 换一件事做之前关掉上一件留下的界面：工作台、箱子、过滤器、背包还开着时交给原版关上（光标上的东西照原版还回背包），
 * 像人先按 Esc 再干别的。原先做东西卡住、改过滤器没认出界面时界面一直开着，后面走路、丢东西、吃饭全被挡住。
 */
public final class LeftoverScreens {

    private static final Logger LOG = LoggerFactory.getLogger(LeftoverScreens.class);
    /** 等原版关界面的期限（刻）。 */
    private static final int CLOSE_TICKS = 20;

    private LeftoverScreens() {}

    /** 有容器界面或背包界面开着就请原版关上；关的过程由菜单入口每刻接着推进，新的那件事等它关完再点界面。 */
    public static void close(PlayerContext player) {
        LocalPlayer self = player == null ? null : player.localPlayer();
        if (self == null) return;
        boolean open = self.containerMenu != self.inventoryMenu
                || Minecraft.getInstance().screen instanceof AbstractContainerScreen<?>;
        if (!open) return;
        try {
            player.menuActions().closeForTaskBoundary(player, CLOSE_TICKS, "换一件事做之前关掉上一件留下的界面");
            LOG.info("换一件事做之前关掉上一件留下的界面");
        } catch (RuntimeException notNow) {
            // 这一刻交不出关界面（交互机会已用掉）：要用世界的动作自己会先关界面再做。
            LOG.debug("换事时没能关上留下的界面", notNow);
        }
    }
}
