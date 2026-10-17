// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.interaction;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;

/**
 * 聊天草稿的原版实现：开的是原版聊天界面，字写进它自己的输入框，关的也只是自己开出的那份。
 *
 * <p>打字中的界面换成一个只多出「改字」一件事的子类：每多打一个字就把框里的字换成当前草稿，
 * 光标留在末尾，玩家看到的和真人在框里打字一样。发送不经这里，由发话任务走聊天通道。
 */
public final class ClientChatDraftScreen implements ChatDraftScreen {

    /** 自己开出的那一份；没开过、或已经被换成别的界面时它不再是当前界面。 */
    private DraftScreen ours;

    @Override
    public boolean show(String draft) {
        Minecraft minecraft = Minecraft.getInstance();
        if (ours != null && minecraft.screen == ours) {
            ours.redraft(draft);
            return true;
        }
        if (minecraft.screen != null) {
            // 别的界面开着（玩家开的、别的任务还没让出来的）：不抢，交回去由打字动作等它让出来。
            return false;
        }
        ours = new DraftScreen(draft);
        minecraft.setScreen(ours);
        return true;
    }

    @Override
    public boolean showing() {
        return ours != null && Minecraft.getInstance().screen == ours;
    }

    @Override
    public void close() {
        Minecraft minecraft = Minecraft.getInstance();
        if (ours != null && minecraft.screen == ours) {
            minecraft.setScreen(null);
        }
        ours = null;
    }

    /** 打字用的原版聊天界面：只多出「把框里的字换成当前草稿」一件事。 */
    private static final class DraftScreen extends ChatScreen {

        DraftScreen(String initial) {
            super(initial);
        }

        void redraft(String draft) {
            input.setValue(draft);
            input.moveCursorToEnd(false);
        }
    }
}
