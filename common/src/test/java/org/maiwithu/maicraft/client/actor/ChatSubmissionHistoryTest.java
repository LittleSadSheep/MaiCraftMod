package org.maiwithu.maicraft.client.actor;

import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.server.Bootstrap;
import org.maiwithu.maicraft.client.chat.ChatMessage;
import org.maiwithu.maicraft.core.task.base.NativeSubmissionJournal;
import org.maiwithu.maicraft.intent.persistence.StateIdentity;

/** 用真实提交标记和假的聊天框检查崩溃重放、保存等待及草稿取消；发送只计数，不连接服务器。 */
public final class ChatSubmissionHistoryTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        waitsWithoutSendingOrClaimingInput();
        cancelledDraftDoesNotReserve();
        recoveredOperationCannotSendAgain();
        System.out.println("ChatSubmissionHistoryTest: passed");
    }

    private static void waitsWithoutSendingOrClaimingInput() throws Exception {
        var h = new ActorControlTestHarness();
        var ready = new AtomicBoolean();
        var checks = new AtomicInteger();
        var view = new View();
        var session = new ChatSession(new ChatMessage("x", 50), view, () -> {
            checks.incrementAndGet();
            return ready.get();
        });
        session.tick(h.context, 0);
        h.nextTick(true);
        session.tick(h.context, 50_000_000);
        check(checks.get() == 0, "完整草稿显示之前不应预约发送");
        h.nextTick(true);
        session.tick(h.context, 300_000_000);
        check(view.sent == 0 && view.active && h.context.mutationAvailable(),
                "等待持久化期间保留草稿，不发消息也不占原生操作额度");
        ready.set(true);
        h.nextTick(true);
        session.tick(h.context, 350_000_000);
        check(view.sent == 1 && session.status() == ChatSession.Status.SUBMITTED,
                "取得持久化许可后才提交完整消息");
    }

    private static void cancelledDraftDoesNotReserve() throws Exception {
        var h = new ActorControlTestHarness();
        var identity = new StateIdentity("c".repeat(64), Files.createTempDirectory("chat-cancelled-"));
        var journal = new InlineJournal(identity, UUID.randomUUID());
        var view = new View();
        var session = new ChatSession(new ChatMessage("xy", 50), view, journal::prepare);
        session.tick(h.context, 0);
        h.nextTick(true);
        session.tick(h.context, 50_000_000);
        session.cancel("cancelled draft");
        check(!Files.exists(identity.directory().resolve("chat-submissions")) && view.sent == 0,
                "只打了一部分字就取消时，不应留下发送预约或真实消息");
    }

    private static void recoveredOperationCannotSendAgain() throws Exception {
        var h = new ActorControlTestHarness();
        var identity = new StateIdentity("c".repeat(64), Files.createTempDirectory("chat-recovery-"));
        UUID operation = UUID.randomUUID();
        var originalView = new View();
        var original = new ChatSession(new ChatMessage("x", 50), originalView,
                new InlineJournal(identity, operation)::prepare);
        finish(h, original);
        check(originalView.sent == 1, "原操作只提交一次");

        // 新会话只读到磁盘上的旧编号，不能借内存状态重置再发一次同一操作。
        var recoveredView = new View();
        var recovered = new ChatSession(new ChatMessage("x", 50), recoveredView,
                new InlineJournal(identity, operation)::prepare);
        finish(h, recovered);
        check(recoveredView.sent == 0 && recovered.status() == ChatSession.Status.UNCERTAIN,
                "恢复后的旧操作应停止并保留未知结果");
        var evidence = recovered.evidence();
        check(evidence.get("delivery_status").equals("unknown")
                && Boolean.TRUE.equals(evidence.get("outcome_uncertain"))
                && Boolean.FALSE.equals(evidence.get("mechanical_retry_allowed")), "已有标记既不证明成功，也不允许自动重发");
        check(!evidence.containsKey("effects_started") && !evidence.containsKey("submission_attempted"),
                "不能用新会话的零次发送冒充旧操作从未发生");
        recovered.cancel("cancel after recovery");
        h.nextTick(true);
        recovered.tick(h.context, 900_000_000);
        check(recoveredView.sent == 0 && recovered.status() == ChatSession.Status.UNCERTAIN,
                "后续取消和重复更新也不能放开重发");

        var nextView = new View();
        finish(h, new ChatSession(new ChatMessage("x", 50), nextView,
                new InlineJournal(identity, UUID.randomUUID())::prepare));
        check(nextView.sent == 1, "另一次明确操作仍可以发送同样文字，不能按内容全局去重");
    }

    private static void finish(ActorControlTestHarness h, ChatSession session) throws Exception {
        h.nextTick(true); session.tick(h.context, 0);
        h.nextTick(true); session.tick(h.context, 50_000_000);
        h.nextTick(true); session.tick(h.context, 300_000_000);
    }

    // 生产写盘使用后台线程；测试同步执行同一个文件写入器，使先落盘再发送的顺序可重复核验。
    private static final class InlineJournal extends NativeSubmissionJournal {
        InlineJournal(StateIdentity identity, UUID operation) { super(identity, operation, "chat", Runnable::run); }
    }

    private static final class View implements ChatSession.View {
        String text = "";
        boolean active;
        int sent;
        public boolean canOpen(Minecraft minecraft) { return !active; }
        public void open(Minecraft minecraft, String draft) { active = true; text = draft; }
        public boolean active() { return active; }
        public String text() { return text; }
        public void write(String draft) { text = draft; }
        public void submit() { sent++; }
        public void close() { active = false; }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
