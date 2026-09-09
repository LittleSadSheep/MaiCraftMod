// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;

/** The real vanilla chat UI. Native submission retains loader chat hooks and command dispatch. */
final class ChatScreenView implements ChatSession.View {
    private Minecraft minecraft;
    private TypingScreen screen;

    static boolean mayOpen(Screen current, boolean windowActive) {
        // pauseGame(true) creates this invisible singleplayer pause on loss of window focus.
        return current == null || !windowActive && current instanceof PauseScreen pause && !pause.showsPauseMenu();
    }

    @Override public boolean canOpen(Minecraft minecraft) { return mayOpen(minecraft.screen, minecraft.isWindowActive()); }
    @Override public void open(Minecraft minecraft, String draft) {
        this.minecraft = minecraft;
        screen = new TypingScreen(draft);
        minecraft.setScreen(screen);
    }
    @Override public boolean active() { return screen != null && minecraft.screen == screen; }
    @Override public String text() { return screen.draft(); }
    @Override public void write(String draft) { screen.write(draft); }
    @Override public void submit() { screen.handleChatInput(screen.draft(), true); }
    @Override public void close() {
        if (active()) minecraft.setScreen(null);
        screen = null;
    }

    private static final class TypingScreen extends ChatScreen {
        TypingScreen(String initial) { super(initial); }
        String draft() { return input.getValue(); }
        void write(String value) { input.setValue(value); input.moveCursorToEnd(false); }

        private ChatScreen handoff() {
            ChatScreen manual = new ChatScreen(draft());
            minecraft.setScreen(manual);
            return manual;
        }

        // Real user input hands the draft to an ordinary ChatScreen; the task cannot reclaim it.
        @Override public boolean keyPressed(int key, int scanCode, int modifiers) {
            if (key == GLFW.GLFW_KEY_ESCAPE) { minecraft.setScreen(null); return true; }
            return handoff().keyPressed(key, scanCode, modifiers);
        }
        @Override public boolean charTyped(char character, int modifiers) {
            return handoff().charTyped(character, modifiers);
        }
        @Override public boolean mouseClicked(double x, double y, int button) {
            return handoff().mouseClicked(x, y, button);
        }
    }
}
