// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.ChatScreen;
import org.maiwithu.maicraft.client.chat.ChatMessage;
import static org.maiwithu.maicraft.client.actor.ActorControlTestHarness.*;

// 用假的输入框检查占用操作机会、暂停恢复、人的接管和提交结果未知时不重发。
// 这里的发送只增加测试计数，不向游戏服务器发消息。
public final class ChatSessionTest {
    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        var h = new ActorControlTestHarness();
        var invisiblePause = h.allocate(PauseScreen.class);
        check(ChatScreenView.mayOpen(invisiblePause, false), "background automatic pause can resume for chat");
        check(!ChatScreenView.mayOpen(invisiblePause, true), "foreground pause is retained");
        field(PauseScreen.class, "showPauseMenu").setBoolean(invisiblePause, true);
        check(!ChatScreenView.mayOpen(invisiblePause, false), "manual pause menu is retained even in background");
        check(!ChatScreenView.mayOpen(h.allocate(ChatScreen.class), false), "never overwrite a human draft");
        View view = new View();
        ChatSession session = new ChatSession(new ChatMessage("你好", 100), view);
        session.tick(h.context, 0);
        check(!h.context.mutationAvailable() && view.text.isEmpty(), "opening claims one mutation and displays empty draft");
        h.nextTick(true); session.tick(h.context, 100_000_000L);
        check(view.text.equals("你") && view.sent == 0, "typing is local and cannot send a partial message");
        session.suspend();
        check(!view.active, "pause closes the automation screen");
        h.nextTick(true); session.tick(h.context, 1_000_000_000L);
        check(view.text.equals("你"), "resume restores the same draft");
        h.nextTick(true); session.tick(h.context, 1_100_000_000L);
        h.nextTick(true); session.tick(h.context, 1_350_000_000L);
        check(session.status() == ChatSession.Status.SUBMITTED && view.sent == 1, "whole message submits exactly once");
        session.cancel("late cancellation"); session.tick(h.context, 9_000_000_000L);
        check(view.sent == 1 && session.evidence().get("delivery_status").equals("submitted_to_client"), "no duplicate or false server acknowledgement");

        for (boolean throwing : new boolean[]{false, true}) {
            view = new View(); view.throwOnSend = throwing;
            session = new ChatSession(new ChatMessage("x", 50), view);
            h.nextTick(true); session.tick(h.context, 0);
            h.nextTick(true); session.tick(h.context, 50_000_000L);
            h.nextTick(true);
            if (!throwing) view.active = false; // Esc, manual input or replacement by another screen.
            try { session.tick(h.context, 300_000_000L); }
            catch (IllegalStateException expected) { check(throwing, "only native send can throw here"); }
            session.tick(h.context, 900_000_000L);
            check(view.sent == (throwing ? 1 : 0), "lost screen and uncertain dispatch never resend");
            check(session.status() == (throwing ? ChatSession.Status.UNCERTAIN : ChatSession.Status.CANCELLED), "honest terminal evidence");
        }
        view = new View(); session = new ChatSession(new ChatMessage("x", 50), view);
        h.nextTick(false);
        try { session.tick(h.context, 0); throw new AssertionError("human controls accepted"); }
        catch (IllegalStateException expected) { check(!view.active, "no screen opened without authority"); }
        var stale = h.context; h.nextTick(true);
        try { session.tick(stale, 0); throw new AssertionError("stale context accepted"); }
        catch (IllegalStateException expected) { check(!view.active, "no screen opened from stale context"); }
        System.out.println("ChatSessionTest: passed");
    }

    private static final class View implements ChatSession.View {
        String text = "";
        boolean active, throwOnSend;
        int sent;
        public boolean canOpen(Minecraft minecraft) { return !active; }
        public void open(Minecraft minecraft, String draft) { active = true; text = draft; }
        public boolean active() { return active; }
        public String text() { return text; }
        public void write(String draft) { text = draft; }
        public void submit() { sent++; if (throwOnSend) throw new IllegalStateException("native send failed"); }
        public void close() { active = false; }
    }
}
